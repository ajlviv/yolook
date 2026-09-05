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
import com.yolo.detector.inference.ModelAssets
import com.yolo.detector.inference.TfliteDetector
import com.yolo.detector.tracking.ByteTracker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.FileNotFoundException

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

    /** The manager currently bound to a camera lifecycle, or null if none. */
    private val activeManager = MutableStateFlow<CameraManager?>(null)
    private var cameraManager: CameraManager?
        get() = activeManager.value
        set(value) { activeManager.value = value }

    // ── Exposed flows ──────────────────────────────────────────────────────────

    private val _detectionFlow = MutableStateFlow<List<Detection>>(emptyList())
    val detectionFlow: StateFlow<List<Detection>> = _detectionFlow.asStateFlow()

    private val _historyFlow = MutableStateFlow<List<Detection>>(emptyList())
    val historyFlow: StateFlow<List<Detection>> = _historyFlow.asStateFlow()

    private val _statsFlow = MutableStateFlow(InferenceStats())
    val statsFlow: StateFlow<InferenceStats> = _statsFlow.asStateFlow()

    private val _pipelineError = MutableStateFlow<String?>(null)
    val pipelineError: StateFlow<String?> = _pipelineError.asStateFlow()

    private val _cameraError = MutableStateFlow<String?>(null)
    val cameraError: StateFlow<String?> = _cameraError.asStateFlow()

    val settingsFlow: Flow<InferenceSettings> = settingsRepo.settingsFlow

    // ── Settings → pipeline reactivity ────────────────────────────────────────

    private var currentSettings: InferenceSettings = InferenceSettings()
    private var lastFrameMs: Long = 0L
    private var smoothedFps: Float = 0f

    private var boundLifecycleOwner: LifecycleOwner? = null
    private var boundPreviewView: PreviewView? = null

    init {
        viewModelScope.launch {
            settingsRepo.settingsFlow.collect { newSettings ->
                val oldSettings = currentSettings
                currentSettings = newSettings

                if (detector == null) {
                    loadDetector(newSettings)
                } else if (oldSettings.enableGpuDelegate != newSettings.enableGpuDelegate) {
                    recreateDetector(newSettings)
                } else {
                    // Update settings in-place without rebuilding TFLite interpreter or tearing down camera
                    detector?.settings = newSettings
                    cameraManager?.settings = newSettings
                }
            }
        }

        // Follow the active camera manager. When a new manager replaces an old one
        // (e.g. after a settings change tears the pipeline down and rebinds), the
        // detection/stats/error streams stay wired to whatever manager is current.
        viewModelScope.launch {
            activeManager.collectLatest { mgr ->
                if (mgr == null) return@collectLatest
                coroutineScope {
                    launch { collectDetections(mgr) }
                    launch { mgr.cameraError.collect { msg -> _cameraError.value = msg } }
                }
            }
        }
    }

    private suspend fun collectDetections(mgr: CameraManager) {
        mgr.detectionFlow.collect { detections ->
            _detectionFlow.value = detections
            val updatedHistory = (_historyFlow.value + detections).takeLast(MAX_HISTORY)
            _historyFlow.value = updatedHistory

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

    private fun recreateDetector(settings: InferenceSettings) {
        // Stop in-flight inference against the current manager/detector first, so we
        // never close an interpreter (or reset a tracker) while a frame is running.
        val hadManager = cameraManager != null
        cameraManager?.shutdown()
        cameraManager = null

        val old = detector
        val loaded = loadDetector(settings)
        old?.close()

        if (!loaded) {
            return
        }

        // If a camera was bound to the old pipeline and lifecycle is active, rebind
        val owner = boundLifecycleOwner
        val view = boundPreviewView
        if (hadManager && owner != null && view != null &&
            owner.lifecycle.currentState.isAtLeast(Lifecycle.State.INITIALIZED)
        ) {
            val mgr = CameraManager(getApplication(), settings, detector!!, tracker)
            cameraManager = mgr
            mgr.bindCamera(owner, view)
        }
    }

    private fun loadDetector(settings: InferenceSettings): Boolean {
        val app = getApplication<Application>()
        if (!ModelAssets.isListed(app.assets.list(""))) {
            detector = null
            _pipelineError.value = app.getString(R.string.model_missing)
            return false
        }
        return try {
            detector = TfliteDetector(app, settings)
            _pipelineError.value = null
            true
        } catch (e: FileNotFoundException) {
            detector = null
            _pipelineError.value = app.getString(R.string.model_missing)
            false
        } catch (e: Exception) {
            detector = null
            _pipelineError.value = app.getString(R.string.model_load_failed, e.message ?: e.javaClass.simpleName)
            false
        }
    }

    // ── Camera binding ─────────────────────────────────────────────────────────

    /**
     * Connects the camera to [previewView] and starts the analysis pipeline.
     *
     * Must be called from the UI thread with a valid [LifecycleOwner].
     */
    fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        boundLifecycleOwner = lifecycleOwner
        boundPreviewView = previewView

        val d = detector ?: run {
            if (!loadDetector(currentSettings)) return
            detector!!
        }
        val mgr = CameraManager(getApplication(), currentSettings, d, tracker)
        cameraManager = mgr

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
