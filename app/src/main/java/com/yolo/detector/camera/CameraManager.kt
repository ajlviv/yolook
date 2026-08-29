package com.yolo.detector.camera

import android.content.Context
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.yolo.detector.data.Detection
import com.yolo.detector.data.InferenceSettings
import com.yolo.detector.inference.toBitmap
import com.yolo.detector.inference.TfliteDetector
import com.yolo.detector.tracking.ByteTracker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Manages the CameraX pipeline: Preview + ImageAnalysis.
 *
 * The analysis pipeline:
 * 1. Receives frames via [ImageAnalysis] with STRATEGY_KEEP_ONLY_LATEST.
 * 2. Throttles frame processing to [InferenceSettings.inferenceRateFps].
 * 3. Converts each kept frame to a Bitmap.
 * 4. Runs [TfliteDetector.detect] on [Dispatchers.Default].
 * 5. Runs [ByteTracker.update] on the same coroutine.
 * 6. Posts the result to [detectionFlow] for the ViewModel to collect.
 *
 * The camera preview still runs at full device frame rate — only inference is throttled.
 */
class CameraManager(
    private val context: Context,
    private val settings: InferenceSettings,
    private val detector: TfliteDetector,
    private val tracker: ByteTracker,
) {

    private val _detectionFlow = MutableStateFlow<List<Detection>>(emptyList())
    val detectionFlow: StateFlow<List<Detection>> = _detectionFlow

    private val _inferenceTimeMs = MutableStateFlow(0L)
    val inferenceTimeMs: StateFlow<Long> = _inferenceTimeMs

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Throttle bookkeeping
    private val frameIntervalMs: Long get() = 1000L / settings.inferenceRateFps
    private var lastInferenceMs: Long = 0L

    /**
     * Binds the camera to [lifecycleOwner] and connects the preview to [previewView].
     *
     * Must be called from the main thread.
     */
    fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        processFrame(imageProxy)
                    }
                }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis,
            )

        }, ContextCompat.getMainExecutor(context))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastInferenceMs < frameIntervalMs) {
            imageProxy.close()
            return
        }
        lastInferenceMs = now

        // Convert to bitmap on the analysis executor thread
        val bitmap = try {
            imageProxy.toBitmap()
        } catch (e: Exception) {
            imageProxy.close()
            return
        }
        imageProxy.close()  // Must close before launching coroutine

        scope.launch {
            val inferenceStart = System.currentTimeMillis()
            val rawDetections = detector.detect(bitmap)
            val inferenceEnd = System.currentTimeMillis()

            bitmap.recycle()

            val tracked = tracker.update(rawDetections, inferenceEnd)
            _inferenceTimeMs.value = inferenceEnd - inferenceStart
            _detectionFlow.value = tracked
        }
    }

    /** Shuts down the background executor and cancels the coroutine scope. */
    fun shutdown() {
        scope.cancel()
        analysisExecutor.shutdown()
        tracker.reset()
    }
}
