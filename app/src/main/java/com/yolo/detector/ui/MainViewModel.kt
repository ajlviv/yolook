package com.yolo.detector.ui

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.lifecycle.*
import com.yolo.detector.R
import com.yolo.detector.camera.CameraManager
import com.yolo.detector.data.*
import com.yolo.detector.inference.TfliteDetector
import com.yolo.detector.tracking.ByteTracker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * ViewModel that owns and coordinates the entire detection pipeline:
 * [SettingsRepository] → [TfliteDetector] → [ByteTracker] → [CameraManager].
 *
 * Exposes three StateFlows for UI consumption:
 * - [detectionFlow] — current frame's tracked detections.
 * - [historyFlow]  — rolling in-memory list of the last [MAX_HISTORY] detections.
 * - [statsFlow]    — live performance metrics (FPS, latency, object count).
 *
 * When [InferenceSettings] change, the detector is torn down and recreated so the
 * new settings (confidence threshold, GPU toggle, etc.) take effect immediately.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val MAX_HISTORY = 500
        private const val FPS_ALPHA = 0.2f   // EMA smoothing factor
    }

    private val settingsRepo = SettingsRepository(application)

    // ── Pipeline components ────────────────────────────────────────────────────

    private var detector: TfliteDetector? = null
    private val tracker = ByteTracker()
    private var cameraManager: CameraManager? = null

    // ── Exposed flows ──────────────────────────────────────────────────────────

    private val _detectionFlow = MutableStateFlow<List<Detection>>(emptyList())
    val detectionFlow: StateFlow<List<Detection>> = _detectionFlow.asStateFlow()

    private val _historyFlow = MutableStateFlow<List<Detection>>(emptyList())
    val historyFlow: StateFlow<List<Detection>> = _historyFlow.asStateFlow()

    private val _statsFlow = MutableStateFlow(InferenceStats())
    val statsFlow: StateFlow<InferenceStats> = _statsFlow.asStateFlow()

    val settingsFlow: Flow<InferenceSettings> = settingsRepo.settingsFlow

    // ── Settings → pipeline reactivity ────────────────────────────────────────

    private var currentSettings: InferenceSettings = InferenceSettings()
    private var lastFrameMs: Long = 0L
    private var smoothedFps: Float = 0f

    init {
        viewModelScope.launch {
            settingsRepo.settingsFlow.collect { settings ->
                currentSettings = settings
                recreateDetector(settings)
            }
        }
    }

    private fun recreateDetector(settings: InferenceSettings) {
        val old = detector
        detector = TfliteDetector(getApplication(), settings)
        old?.close()

        // Recreate the camera manager with the new detector if already bound
        cameraManager?.let { mgr ->
            mgr.shutdown()
            cameraManager = CameraManager(getApplication(), settings, detector!!, tracker)
        }
    }

    // ── Camera binding ─────────────────────────────────────────────────────────

    /**
     * Connects the camera to [previewView] and starts the analysis pipeline.
     *
     * Must be called from the UI thread with a valid [LifecycleOwner].
     */
    fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val d = detector ?: TfliteDetector(getApplication(), currentSettings).also { detector = it }
        val mgr = CameraManager(getApplication(), currentSettings, d, tracker)
        cameraManager = mgr

        viewModelScope.launch {
            mgr.detectionFlow.collect { detections ->
                _detectionFlow.value = detections

                // Append to history (bounded)
                val updatedHistory = (_historyFlow.value + detections).takeLast(MAX_HISTORY)
                _historyFlow.value = updatedHistory

                // Update stats
                val now = System.currentTimeMillis()
                val elapsed = (now - lastFrameMs).coerceAtLeast(1L)
                val instantFps = 1000f / elapsed
                smoothedFps = FPS_ALPHA * instantFps + (1f - FPS_ALPHA) * smoothedFps
                lastFrameMs = now

                _statsFlow.value = InferenceStats(
                    fps = smoothedFps,
                    inferenceMs = mgr.inferenceTimeMs.value,
                    objectCount = detections.size,
                )
            }
        }

        mgr.bindCamera(lifecycleOwner, previewView)
    }

    // ── Actions ────────────────────────────────────────────────────────────────

    /** Clears the in-memory detection history. */
    fun clearHistory() {
        _historyFlow.value = emptyList()
    }

    /**
     * Saves [bitmap] as a JPEG to the public MediaStore (Pictures/YOLO).
     * Shows a Toast on completion (success or failure).
     */
    fun saveSnapshot(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            val filename = "YOLO_${System.currentTimeMillis()}.jpg"
            val resolver = getApplication<Application>().contentResolver

            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/YOLO")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            val success = uri != null && runCatching {
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
            }.isSuccess

            withContext(Dispatchers.Main) {
                val msg = if (success) R.string.snapshot_saved else R.string.snapshot_failed
                Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Settings delegates ─────────────────────────────────────────────────────

    fun setConfidenceThreshold(v: Float) = viewModelScope.launch { settingsRepo.setConfidenceThreshold(v) }
    fun setIouThreshold(v: Float)        = viewModelScope.launch { settingsRepo.setIouThreshold(v) }
    fun setMaxObjects(v: Int)            = viewModelScope.launch { settingsRepo.setMaxObjects(v) }
    fun setInferenceRateFps(v: Int)      = viewModelScope.launch { settingsRepo.setInferenceRateFps(v) }
    fun setGpuEnabled(v: Boolean)        = viewModelScope.launch { settingsRepo.setGpuEnabled(v) }
    fun setClassFilter(ids: Set<Int>)    = viewModelScope.launch { settingsRepo.setClassFilter(ids) }
    fun resetSettings()                  = viewModelScope.launch { settingsRepo.resetToDefaults() }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        cameraManager?.shutdown()
        detector?.close()
    }
}
