package com.yolo.detector.camera

import android.content.Context
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.yolo.detector.data.Detection
import com.yolo.detector.data.InferenceSettings
import com.yolo.detector.inference.toRgbBitmap
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
    private val frameIntervalMs: Long get() = 1000L / settings.inferenceRateFps.coerceAtLeast(1)
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
        if (now - lastInferenceMs < frameIntervalMs) {
            imageProxy.close()
            return
        }

        // If an inference pass is currently running, drop the frame to prevent queue buildup and concurrency issues
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
                val inferenceStart = System.currentTimeMillis()
                val rawDetections = detector.detect(bitmap)
                val inferenceEnd = System.currentTimeMillis()

                val tracked = tracker.update(rawDetections, inferenceEnd)
                val toEmit = if (tracked.isNotEmpty()) tracked else rawDetections
                _inferenceTimeMs.value = inferenceEnd - inferenceStart
                _detectionFlow.value = toEmit
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
    }
}
