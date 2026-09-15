package com.yolo.detector.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.yolo.detector.alerts.AlertTrigger
import com.yolo.detector.alerts.DetectionThrottler
import com.yolo.detector.data.Detection
import com.yolo.detector.data.DetectionView
import com.yolo.detector.data.InferenceSettings
import com.yolo.detector.inference.toRgbBitmap
import com.yolo.detector.data.ViewMode
import com.yolo.detector.inference.TfliteDetector
import com.yolo.detector.tracking.ByteTracker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the CameraX pipeline: Preview + ImageAnalysis.
 *
 * The analysis pipeline:
 * 1. Receives frames via [ImageAnalysis] with STRATEGY_KEEP_ONLY_LATEST.
 * 2. Throttles frame processing to [InferenceSettings.inferenceRateFps].
 * 3. Converts each kept frame to a Bitmap.
 * 4. Runs [TfliteDetector.detect] sequentially on a dedicated background thread.
 * 5. Runs [ByteTracker.update] on the same thread.
 * 6. Posts the result to [detectionFlow] for the ViewModel to collect.
 *
 * The camera preview still runs at full device frame rate — only inference is throttled.
 */
class CameraManager(
    private val context: Context,
    @Volatile var settings: InferenceSettings,
    private val detector: TfliteDetector,
    private val tracker: ByteTracker,
) {

    private val _detectionFlow = MutableStateFlow<List<Detection>>(emptyList())
    val detectionFlow: StateFlow<List<Detection>> = _detectionFlow

    /**
     * Latest frame bitmap for filtered view modes, or null when viewing normally.
     *
     * Ownership transfers to the consumer: the UI recycles each emitted bitmap once
     * it has been replaced or cleared. Emitting only happens for non-NORMAL view modes
     * to avoid redundant copies of the always-running inference frames.
     */
    private val _frameFlow = MutableStateFlow<Bitmap?>(null)
    val frameFlow: StateFlow<Bitmap?> = _frameFlow

    private val _inferenceTimeMs = MutableStateFlow(0L)
    val inferenceTimeMs: StateFlow<Long> = _inferenceTimeMs

    private val _cameraError = MutableStateFlow<String?>(null)
    val cameraError: StateFlow<String?> = _cameraError

    // ── Email-alert throttling ───────────────────────────────────────────────
    // Global alert state machine, fed once per inference frame (see processFrame).
    private val alertThrottler = DetectionThrottler()

    /** Master switch for alert dispatch. Guarded by the analysis thread / [updateAlertSettings]. */
    @Volatile var alertEnabled: Boolean = false

    /** Empty = alert on any detection; otherwise restrict to these COCO class IDs. */
    @Volatile var alertTriggerClassIds: Set<Int> = emptySet()

    /**
     * Called on the analysis thread when a confirmed detection fires; ownership of the
     * passed [Bitmap] transfers to the receiver (it must be recycled there).
     */
    @Volatile var onAlert: ((AlertTrigger, Bitmap) -> Unit)? = null

    private val isAnalyzing = AtomicBoolean(false)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val analysisDispatcher = analysisExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(analysisDispatcher + SupervisorJob())

    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null

    // Throttle bookkeeping
    private val frameIntervalMs: Long get() = 1000L / settings.inferenceRateFps.coerceAtLeast(1)
    private var lastInferenceMs: Long = 0L

    companion object {
        /**
         * View-mode refresh cadence, decoupled from the inference throttle.
         * Filtered view modes (Matrix/Heatmap/Edge, and OBJECTS_ONLY masking)
         * re-render at this independent cap regardless of the "Inference FPS"
         * slider, so they stay smooth even when detection is slowed down for
         * battery/perf.
         */
        const val VIEW_FPS = 15
        const val VIEW_INTERVAL_MS: Long = 1000L / VIEW_FPS
    }
    private var lastViewEmitMs: Long = 0L

    /**
     * Binds the camera to [lifecycleOwner] and connects the preview to [previewView].
     *
     * Must be called from the main thread.
     */
    fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        _cameraError.value = null

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val resolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                .setResolutionStrategy(
                    androidx.camera.core.resolutionselector.ResolutionStrategy(
                        android.util.Size(1280, 720), 
                        androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        processFrame(imageProxy)
                    }
                }

            provider.unbindAll()
            val camera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis,
            )
            boundCamera = camera

            camera.cameraInfo.cameraState.observe(lifecycleOwner) { state ->
                val code = state.error?.code ?: return@observe
                if (code != CameraState.ERROR_CAMERA_IN_USE &&
                    code != CameraState.ERROR_MAX_CAMERAS_IN_USE &&
                    code != CameraState.ERROR_CAMERA_DISABLED &&
                    code != CameraState.ERROR_CAMERA_FATAL_ERROR &&
                    code != CameraState.ERROR_OTHER_RECOVERABLE_ERROR
                ) return@observe

                val msg = when (code) {
                    CameraState.ERROR_CAMERA_IN_USE -> "Camera in use by another app"
                    CameraState.ERROR_MAX_CAMERAS_IN_USE -> "Too many cameras open"
                    CameraState.ERROR_CAMERA_DISABLED -> "Camera disabled by policy"
                    CameraState.ERROR_CAMERA_FATAL_ERROR -> "Camera device fatal error"
                    CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> "Camera recoverable error"
                    else -> "Unknown camera error (code $code)"
                }
                _cameraError.value = msg
            }

        }, ContextCompat.getMainExecutor(context))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()

        // Filtered view modes render from this frame feed. Give them their own
        // refresh cadence (VIEW_FPS) decoupled from the inference throttle so a
        // low "Inference FPS" setting doesn't make the Matrix/Heatmap/Edge views
        // stutter. NORMAL mode without masking uses the smooth PreviewView.
        val needsFrame = settings.viewMode != ViewMode.NORMAL ||
                settings.detectionView == DetectionView.OBJECTS_ONLY
        val viewDue = needsFrame && now - lastViewEmitMs >= VIEW_INTERVAL_MS
        val inferenceDue = now - lastInferenceMs >= frameIntervalMs

        if (!viewDue && !inferenceDue) {
            imageProxy.close()
            return
        }

        // If a pass is currently running, drop the frame to prevent queue buildup
        // and concurrency issues (CameraX keeps only the latest frame anyway).
        if (!isAnalyzing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        if (inferenceDue) lastInferenceMs = now
        if (viewDue) lastViewEmitMs = now

        // Convert to bitmap on the analysis executor thread (shared by both the
        // view emit and the inference pass so a frame is never converted twice).
        val bitmap = try {
            imageProxy.toRgbBitmap()
        } catch (e: Exception) {
            android.util.Log.e("CameraManager", "Failed to convert frame to bitmap", e)
            imageProxy.close()
            isAnalyzing.set(false)
            return
        }
        imageProxy.close()  // Must close before launching work

        scope.launch {
            try {
                if (viewDue) {
                    // Hand a copy to the UI for display. Ownership of the copy
                    // moves to the consumer; the bitmap below is still recycled.
                    android.util.Log.i("ViewMode", "emit frame ${settings.viewMode}/${settings.detectionView}")
                    _frameFlow.value = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                } else if (!needsFrame && _frameFlow.value != null) {
                    _frameFlow.value = null
                }

                if (inferenceDue) {
                    val inferenceStart = System.currentTimeMillis()
                    val rawDetections = detector.detect(bitmap)
                    val inferenceEnd = System.currentTimeMillis()

                val tracked = tracker.update(rawDetections, inferenceEnd)
                val toEmit = if (tracked.isNotEmpty()) tracked else rawDetections
                _inferenceTimeMs.value = inferenceEnd - inferenceStart
                _detectionFlow.value = toEmit

                // Email-alert throttling: runs on this exact inference frame, converting
                // it to JPEG downstream. Only the confirmed frame is ever copied, and
                // the copy is handed to [onAlert] before `bitmap` is recycled below.
                if (alertEnabled) {
                    val candidates = if (alertTriggerClassIds.isEmpty()) toEmit
                    else toEmit.filter { it.classId in alertTriggerClassIds }
                    // Monotonic clock: wall-clock can jump (NTP/user changes) and would
                    // expire COOLDOWN early, causing exactly this kind of duplicate.
                    val trigger = alertThrottler.onDetections(candidates, SystemClock.elapsedRealtime())
                    if (trigger != null) {
                        val alertFrame = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                        onAlert?.invoke(trigger, alertFrame)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("CameraManager", "Analysis error", e)
            } finally {
                bitmap.recycle()
                isAnalyzing.set(false)
            }
        }
    }

    /**
     * Updates the email-alert throttling config. Safe to call from any thread.
     *
     * @param triggerClassIds empty set = alert on any detection; otherwise only when a
     *        detected class is in this set.
     */
    fun updateAlertSettings(enabled: Boolean, cooldownMs: Long, triggerClassIds: Set<Int>) {
        alertEnabled = enabled
        alertThrottler.cooldownMs = cooldownMs
        alertTriggerClassIds = triggerClassIds
        if (!enabled) alertThrottler.reset()
    }

    /** Shuts down the background executor and cancels the coroutine scope. */
    fun shutdown() {
        scope.cancel()
        cameraProvider?.unbindAll()
        cameraProvider = null
        boundCamera = null
        analysisExecutor.shutdown()
        try {
            analysisExecutor.awaitTermination(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {}
        tracker.reset()
        alertThrottler.reset()
        _cameraError.value = null
        _detectionFlow.value = emptyList()
        _frameFlow.value = null
    }
}
