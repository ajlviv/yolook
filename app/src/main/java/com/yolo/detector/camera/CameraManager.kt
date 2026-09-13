package com.yolo.detector.camera

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.yolo.detector.data.Detection
import com.yolo.detector.data.DriverScene
import com.yolo.detector.data.InferenceSettings
import com.yolo.detector.inference.toRgbBitmap
import com.yolo.detector.data.ViewMode
import com.yolo.detector.inference.TfliteDetector
import com.yolo.detector.tracking.ByteTracker
import com.yolo.detector.ui.DriverSceneBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.yolo.detector.util.ThermalMonitor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the CameraX pipeline: Preview + ImageAnalysis.
 *
 * The analysis pipeline:
 * 1. Receives frames via [ImageAnalysis] with STRATEGY_KEEP_ONLY_LATEST.
 * 2. Runs fast-path Kalman tracking prediction at full 30-60 FPS camera rate for smooth UI.
 * 3. Throttles heavy ML inference to [InferenceSettings.inferenceRateFps], adaptively regulated by [ThermalMonitor].
 * 4. Runs [TfliteDetector.detect] on a background thread.
 * 5. Corrects tracks with [ByteTracker.update] upon detection completion.
 */
class CameraManager(
    private val context: Context,
    @Volatile var settings: InferenceSettings,
    private val detector: TfliteDetector,
    private val tracker: ByteTracker,
    private val driverSceneBuilder: DriverSceneBuilder,
    private val thermalMonitor: ThermalMonitor? = null,
) {

    private val _detectionFlow = MutableStateFlow<List<Detection>>(emptyList())
    val detectionFlow: StateFlow<List<Detection>> = _detectionFlow

    /** Driver-mode HUD scene, built on this manager's analysis thread from the live
     *  frame (before it is recycled) + the detections for that same frame. */
    private val _driverSceneFlow = MutableStateFlow(DriverScene())
    val driverSceneFlow: StateFlow<DriverScene> = _driverSceneFlow

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

    private val isAnalyzing = AtomicBoolean(false)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val analysisDispatcher = analysisExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(analysisDispatcher + SupervisorJob())

    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null

    // Throttle bookkeeping
    private var lastInferenceMs: Long = 0L

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
        val requestedFps = settings.inferenceRateFps.coerceAtLeast(1)
        val effectiveFps = thermalMonitor?.getAdaptiveFpsCap(requestedFps) ?: requestedFps
        val intervalMs = 1000L / effectiveFps

        // Fast-path: Update predicted positions for existing active tracks on every frame (30-60 FPS)
        val fastTracks = tracker.getActiveDetections(now)
        if (fastTracks.isNotEmpty() && isAnalyzing.get()) {
            _detectionFlow.value = fastTracks
        }

        if (now - lastInferenceMs < intervalMs) {
            imageProxy.close()
            return
        }

        // If an inference pass is currently running, drop the frame to prevent queue buildup
        if (!isAnalyzing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        lastInferenceMs = now

        // Convert to bitmap on the analysis executor thread
        val bitmap = try {
            imageProxy.toRgbBitmap()
        } catch (e: Exception) {
            android.util.Log.e("CameraManager", "Failed to convert frame to bitmap", e)
            imageProxy.close()
            isAnalyzing.set(false)
            return
        }
        imageProxy.close()  // Must close before launching inference

        scope.launch {
            try {
                val viewMode = settings.viewMode
                val needsFrameCopy =
                    viewMode == ViewMode.BLACK_AND_WHITE ||
                    viewMode == ViewMode.INVERT ||
                    viewMode == ViewMode.HEATMAP ||
                    viewMode == ViewMode.DRIVER
                if (needsFrameCopy) {
                    _frameFlow.value = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                } else if (_frameFlow.value != null) {
                    _frameFlow.value = null
                }

                val inferenceStart = System.currentTimeMillis()
                val rawDetections = detector.detect(bitmap)
                val inferenceEnd = System.currentTimeMillis()

                val tracked = tracker.update(rawDetections, inferenceEnd)
                val toEmit = if (tracked.isNotEmpty()) tracked else rawDetections
                _inferenceTimeMs.value = inferenceEnd - inferenceStart
                _detectionFlow.value = toEmit

                if (viewMode == ViewMode.DRIVER) {
                    _driverSceneFlow.value = driverSceneBuilder.build(toEmit, bitmap)
                }
            } catch (e: Exception) {
                android.util.Log.e("CameraManager", "Inference error", e)
            } finally {
                bitmap.recycle()
                isAnalyzing.set(false)
            }
        }
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
        _cameraError.value = null
        _detectionFlow.value = emptyList()
        _frameFlow.value = null
        _driverSceneFlow.value = DriverScene()
    }
}
