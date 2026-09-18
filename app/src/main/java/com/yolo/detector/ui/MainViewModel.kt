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
import com.yolo.detector.alerts.AlertTrigger
import com.yolo.detector.alerts.EmailAlertService
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
 * - [historyFlow]  — in-memory summary of the last [MAX_HISTORY] events: seen
 *   objects grouped by track plus dispatched alert notifications.
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

    /** Persists email-alert config; the API key is stored encrypted (AndroidX Security). */
    private val emailRepo = EmailSettingsRepository(application)

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

    /**
     * Latest frame bitmap for filtered view modes (null when viewing normally).
     * Presented by mirroring the active [CameraManager.frameFlow].
     */
    private val _frameFlow = MutableStateFlow<Bitmap?>(null)
    val frameFlow: StateFlow<Bitmap?> = _frameFlow.asStateFlow()

    /** Mirrors the active [CameraManager.frameAspectRatio] for the overlay crop math. */
    private val _frameAspectRatio = MutableStateFlow(0f)
    val frameAspectRatio: StateFlow<Float> = _frameAspectRatio.asStateFlow()

    /** Per-track aggregate of every object seen so far; key is the [HistoryEntry] track identity. */
    private val historyByTrack = mutableMapOf<Int, HistoryEntry>()

    /** Dispatched alert events, newest last. Capped at [MAX_HISTORY] entries. */
    private val alertLog = mutableListOf<HistoryItem.Alert>()

    /** Unified, newest-first history stream (objects + alerts). All mutators run on the main thread. */
    private val _historyFlow = MutableStateFlow<List<HistoryItem>>(emptyList())
    val historyFlow: StateFlow<List<HistoryItem>> = _historyFlow.asStateFlow()

    private val _statsFlow = MutableStateFlow(InferenceStats())
    val statsFlow: StateFlow<InferenceStats> = _statsFlow.asStateFlow()

    private val _pipelineError = MutableStateFlow<String?>(null)
    val pipelineError: StateFlow<String?> = _pipelineError.asStateFlow()

    private val _cameraError = MutableStateFlow<String?>(null)
    val cameraError: StateFlow<String?> = _cameraError.asStateFlow()

    val settingsFlow: Flow<InferenceSettings> = settingsRepo.settingsFlow

    // ── Email-alert state ─────────────────────────────────────────────────────

    /** Latest persisted email-alert settings (for the Settings UI). */
    private val _emailSettings = MutableStateFlow(EmailSettings())
    val emailSettings: StateFlow<EmailSettings> = _emailSettings.asStateFlow()

    /** True when an encrypted API key is currently stored. */
    private val _emailApiKeyPresent = MutableStateFlow(false)
    val emailApiKeyPresent: StateFlow<Boolean> = _emailApiKeyPresent.asStateFlow()

    /** Snapshot read by the alert dispatcher off the camera thread. */
    @Volatile private var cachedEmailSettings: EmailSettings = EmailSettings()

    /**
     * Last actually-dispatched alert instant on the throttler's clock
     * ([SystemClock.elapsedRealtime]), or 0 when none yet. Lives here — not in
     * [DetectionThrottler] — so it survives CameraManager recreation while staying
     * ViewModel-scoped (a fresh process starts at zero, like the pipeline).
     */
    @Volatile private var lastAlertStartedAtMs: Long = 0L

    /** Latest settings snapshot for synchronous reads (updated by the collector above). */
    val currentSettingsSnapshot: InferenceSettings get() = currentSettings

    /** Latest known frame aspect ratio for the overlay's fillCenter crop alignment. */
    val currentFrameAspectRatio: Float get() = _frameAspectRatio.value

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
                    launch { mgr.frameFlow.collect { _frameFlow.value = it } }
                    launch { mgr.frameAspectRatio.collect { _frameAspectRatio.value = it } }
                    launch { mgr.cameraError.collect { msg -> _cameraError.value = msg } }
                }
            }
        }

        // Email-alert config: cache it for the alert dispatcher and push the
        // cooldown/class-filter into the active CameraManager.
        viewModelScope.launch {
            emailRepo.settingsFlow.collect { s ->
                cachedEmailSettings = s
                _emailSettings.value = s
                applyAlertConfig()
            }
        }
        refreshEmailApiKeyStatus()
    }

    /** Pushes the latest email-alert config into the active camera manager (if any). */
    private fun applyAlertConfig() {
        cameraManager?.updateAlertSettings(
            enabled = cachedEmailSettings.enabled,
            cooldownMs = cachedEmailSettings.cooldownMs,
            triggerClassIds = cachedEmailSettings.triggerClassIds,
        )
    }

    /**
     * Builds a [CameraManager] wired to dispatch confirmed detections to the email
     * alert pipeline. Callers then assign it to [cameraManager] and bind the camera.
     */
    private fun configuredManager(settings: InferenceSettings): CameraManager {
        val mgr = CameraManager(getApplication(), settings, detector!!, tracker)
        mgr.onAlert = ::dispatchAlert
        mgr.updateAlertSettings(
            enabled = cachedEmailSettings.enabled,
            cooldownMs = cachedEmailSettings.cooldownMs,
            triggerClassIds = cachedEmailSettings.triggerClassIds,
        )
        return mgr
    }

    /**
     * Called on the analysis thread when the throttler confirms a detection. Hands the
     * frame to an IO coroutine that compresses it to JPEG and emails it — never blocks
     * the camera/YOLO loop. Always recycles [frame].
     */
    private fun dispatchAlert(trigger: AlertTrigger, frame: Bitmap) {
        // Double-check the cooldown at dispatch time: the in-manager throttler resets
        // whenever the camera pipeline is recreated, so without this guard a settings
        // change or rebind could re-fire an alert well inside the "don't spam me"
        // window. Drops (recycling [frame]) instead of queueing — matching the
        // non-blocking contract of this path.
        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastAlertStartedAtMs
        if (last != 0L && now - last < cachedEmailSettings.cooldownMs.coerceAtLeast(0L)) {
            frame.recycle()
            return
        }
        lastAlertStartedAtMs = now
        val dispatchedAtMs = System.currentTimeMillis()
        // Record the notification immediately (on the main thread) so the history
        // entry exists the moment the alert is confirmed — independent of the email
        // send, which can take many seconds (or fail) and must not delay the record.
        val alert = HistoryItem.Alert(dispatchedAtMs, trigger.detectedClassIds, emailSent = false)
        viewModelScope.launch { recordAlert(alert) }

        viewModelScope.launch(Dispatchers.IO) {
            var emailAttempted = false
            var emailSent = false
            try {
                val settings = cachedEmailSettings
                val key = emailRepo.apiKey()
                // Only send (and surface toast results) when properly configured.
                if (settings.enabled && key.isNotBlank() && settings.recipient.isNotBlank()) {
                    emailAttempted = true
                    val labels = trigger.detectedClassIds
                        .map { labelFor(it) }
                        .distinct()
                        .joinToString(", ")
                    val jpeg = EmailAlertService.frameToJpeg(frame)
                    emailSent = EmailAlertService
                        .send(settings, key, jpeg, labels, dispatchedAtMs)
                        .isSuccess
                }
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Alert dispatch failed", e)
            } finally {
                frame.recycle()
            }
            withContext(Dispatchers.Main) {
                alert.emailSent = emailSent
                rebuildHistory()
                if (emailAttempted) {
                    Toast.makeText(
                        getApplication(),
                        if (emailSent) R.string.email_alert_sent else R.string.email_alert_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    /** Refreshes [emailApiKeyPresent] from the encrypted store. */
    fun refreshEmailApiKeyStatus() {
        viewModelScope.launch {
            _emailApiKeyPresent.value = emailRepo.apiKey().isNotBlank()
        }
    }

    private suspend fun collectDetections(mgr: CameraManager) {
        mgr.detectionFlow.collect { detections ->
            _detectionFlow.value = detections
            for (detection in detections) {
                upsertHistory(detection)
            }
            trimHistory()
            rebuildHistory()

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

    /**
     * Folds a single frame detection into the per-track history.
     *
     * Both history updates and [collectDetections] run on the main thread, so mutating
     * [HistoryEntry] fields in place is safe. Objects with a real track ID are grouped
     * by that ID; untracked detections (trackId = -1) of the same class share one bucket.
     */
    private fun upsertHistory(detection: Detection) {
        val key = if (detection.trackId >= 0) detection.trackId else -(detection.classId + 1)
        val entry = historyByTrack[key]

        if (entry == null) {
            historyByTrack[key] = HistoryEntry(
                trackId = detection.trackId,
                classId = detection.classId,
                count = 1,
                firstSeenMs = detection.timestampMs,
                lastSeenMs = detection.timestampMs,
                bestConfidence = detection.confidence,
                lastBbox = detection.bbox,
            )
            return
        }

        entry.classId = detection.classId
        entry.count += 1
        entry.lastSeenMs = detection.timestampMs
        if (detection.confidence > entry.bestConfidence) {
            entry.bestConfidence = detection.confidence
        }
        entry.lastBbox = detection.bbox
    }

    /** Drops the oldest objects once history exceeds [MAX_HISTORY] entries. */
    private fun trimHistory() {
        if (historyByTrack.size <= MAX_HISTORY) return
        historyByTrack.entries
            .sortedBy { it.value.lastSeenMs }
            .take(historyByTrack.size - MAX_HISTORY)
            .forEach { historyByTrack.remove(it.key) }
    }

    /**
     * Merges object entries and alert events into the newest-first [historyFlow],
     * capped at [MAX_HISTORY] rows. Must run on the main thread (alongside
     * [collectDetections]).
     */
    private fun rebuildHistory() {
        _historyFlow.value = mergeHistoryItems(historyByTrack.values, alertLog, MAX_HISTORY)
    }

    /**
     * Appends a dispatched alert to the history. Must run on the main thread.
     *
     * The entry carries its newest-first timestamp; [HistoryItem.Alert.emailSent]
     * is updated in place once the email send settles (see [dispatchAlert]).
     */
    private fun recordAlert(alert: HistoryItem.Alert) {
        alertLog.add(alert)
        if (alertLog.size > MAX_HISTORY) {
            alertLog.subList(0, alertLog.size - MAX_HISTORY).clear()
        }
        rebuildHistory()
    }

    private fun recreateDetector(settings: InferenceSettings) {
        // Stop in-flight inference against the current manager/detector first, so we
        // never close an interpreter (or reset a tracker) while a frame is running.
        val hadManager = cameraManager != null
        cameraManager?.shutdown()
        cameraManager = null

        // Close old detector first to release GPU/EGL context before opening a new one
        val old = detector
        detector = null
        old?.close()

        val loaded = loadDetector(settings)
        if (!loaded) {
            return
        }

        // If a camera was bound to the old pipeline and lifecycle is active, rebind
        val owner = boundLifecycleOwner
        val view = boundPreviewView
        if (hadManager && owner != null && view != null &&
            owner.lifecycle.currentState.isAtLeast(Lifecycle.State.INITIALIZED)
        ) {
            val mgr = configuredManager(settings)
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
        val mgr = configuredManager(currentSettings)
        cameraManager = mgr

        mgr.bindCamera(lifecycleOwner, previewView)
    }

    // ── Actions ────────────────────────────────────────────────────────────────

    /** Clears the in-memory detection/alert history. */
    fun clearHistory() {
        historyByTrack.clear()
        alertLog.clear()
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
    fun setSlicedInference(v: Boolean)   = viewModelScope.launch { settingsRepo.setSlicedInference(v) }
    fun setClassFilter(ids: Set<Int>)    = viewModelScope.launch { settingsRepo.setClassFilter(ids) }
    fun setViewMode(mode: ViewMode)      = viewModelScope.launch { settingsRepo.setViewMode(mode) }
    fun setDetectionView(view: DetectionView) = viewModelScope.launch { settingsRepo.setDetectionView(view) }
    fun setEdgeThreshold(v: Int)      = viewModelScope.launch { settingsRepo.setEdgeThreshold(v) }
    fun setEdgeDetail(v: Int)         = viewModelScope.launch { settingsRepo.setEdgeDetail(v) }
    fun setHeatmapDetail(v: Int)      = viewModelScope.launch { settingsRepo.setHeatmapDetail(v) }
    fun setMatrixDetail(v: Int)       = viewModelScope.launch { settingsRepo.setMatrixDetail(v) }
    fun setMatrixGamma(v: Float)      = viewModelScope.launch { settingsRepo.setMatrixGamma(v) }
    fun resetSettings()                  = viewModelScope.launch { settingsRepo.resetToDefaults() }

    // ── Email-alert settings delegates ────────────────────────────────────────

    fun setEmailEnabled(v: Boolean)        = viewModelScope.launch { emailRepo.setEnabled(v) }
    fun setEmailRecipient(v: String)       = viewModelScope.launch { emailRepo.setRecipient(v) }
    fun setEmailSender(v: String)          = viewModelScope.launch { emailRepo.setSenderEmail(v) }
    fun setEmailCooldownSeconds(v: Int)    = viewModelScope.launch { emailRepo.setCooldownMs(v * 1000L) }
    fun setEmailTriggerClassIds(ids: Set<Int>) = viewModelScope.launch { emailRepo.setTriggerClassIds(ids) }

    /** Latest detailed verdict when [sendTestAlertEmail] cannot complete the send. */
    private val _emailTestFailure = MutableStateFlow<String?>(null)
    val emailTestFailure: StateFlow<String?> = _emailTestFailure.asStateFlow()

    /** True only while [sendTestAlertEmail] has a network send in flight. */
    private val _emailTestRunning = MutableStateFlow(false)
    val emailTestRunning: StateFlow<Boolean> = _emailTestRunning.asStateFlow()

    /** Persists the (encrypted) API key, then refreshes its presence flag. */
    fun setEmailApiKey(v: String) {
        viewModelScope.launch(Dispatchers.IO) {
            emailRepo.setApiKey(v)
            refreshEmailApiKeyStatus()
        }
    }

    /**
     * Sends a placeholder "test" email. This path intentionally ignores the live
     * email-alerts master switch, and only requires the API key and recipient.
     * It still mirrors [saveSnapshot]'s IO + toast pattern so a slow provider
     * call never touches the camera loop.
     */
    fun sendTestAlertEmail() {
        if (_emailTestRunning.value) return
        _emailTestFailure.value = null
        _emailTestRunning.value = true
        viewModelScope.launch(Dispatchers.IO) {
            // Read the fresh persisted value, not the cached snapshot: the master
            // switch write may still be propagating when the user taps "Send test".
            val settings = emailRepo.settingsFlow.first().also { cachedEmailSettings = it }
            val key = emailRepo.apiKey()
            val app = getApplication<Application>()
            val missing = listOfNotNull(
                app.getString(R.string.email_test_missing_api_key).takeIf { key.isBlank() },
                app.getString(R.string.email_test_missing_recipient).takeIf { settings.recipient.isBlank() },
            )
            val ok = if (missing.isNotEmpty()) {
                _emailTestFailure.value = app.getString(R.string.email_test_config_missing, missing.joinToString(", "))
                _emailTestRunning.value = false
                false
            } else {
                val sent = sendTestPlaceholder(settings, key)
                _emailTestRunning.value = false
                sent
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    getApplication(),
                    if (ok) R.string.email_test_sent else R.string.email_test_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private suspend fun sendTestPlaceholder(settings: EmailSettings, key: String): Boolean {
        val placeholder = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).let { bmp ->
            val c = android.graphics.Canvas(bmp)
            c.drawColor(android.graphics.Color.rgb(0, 230, 118))
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.WHITE
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 6f
            }
            c.drawRect(40f, 40f, 280f, 200f, paint)
            bmp
        }
        try {
            val jpeg = EmailAlertService.frameToJpeg(placeholder)
            val outcome = EmailAlertService.sendDetailed(settings, key, jpeg, "test", System.currentTimeMillis())
            return when (outcome) {
                EmailAlertService.SendOutcome.Sent -> true
                is EmailAlertService.SendOutcome.ProviderRejected -> {
                    _emailTestFailure.value = providerRejectedMessage(outcome.httpCode)
                    android.util.Log.w("MainViewModel", "Test email rejected HTTP ${outcome.httpCode}: ${outcome.body}")
                    false
                }
                is EmailAlertService.SendOutcome.NetworkError -> {
                    _emailTestFailure.value = networkErrorMessage(outcome.cause)
                    android.util.Log.w("MainViewModel", "Test email failed", outcome.cause)
                    false
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Test email failed", e)
            _emailTestFailure.value = networkErrorMessage(e)
            return false
        } finally {
            placeholder.recycle()
        }
    }

    private fun networkErrorMessage(cause: Throwable): String {
        val app = getApplication<Application>()
        fun walk(t: Throwable?): Sequence<Throwable> = generateSequence(t) { it.cause }
        if (walk(cause).any {
                it is java.net.UnknownHostException ||
                    it is java.net.NoRouteToHostException ||
                    it is java.io.InterruptedIOException
            }
        ) {
            return app.getString(R.string.email_test_network_error, app.getString(R.string.email_test_network_dns))
        }
        if (walk(cause).any { it is java.net.SocketTimeoutException || it is java.util.concurrent.TimeoutException }) {
            return app.getString(R.string.email_test_network_error, app.getString(R.string.email_test_network_timeout))
        }
        if (walk(cause).any {
                it is java.net.ConnectException ||
                    it is java.net.SocketException ||
                    it is javax.net.ssl.SSLException ||
                    it is java.security.cert.CertificateException
            }
        ) {
            val detail = if (walk(cause).any {
                    it is javax.net.ssl.SSLException || it is java.security.cert.CertificateException
                }
            ) {
                app.getString(R.string.email_test_network_tls)
            } else {
                app.getString(R.string.email_test_network_unreachable)
            }
            return app.getString(R.string.email_test_network_error, detail)
        }
        val short = cause.message?.take(140) ?: cause.javaClass.simpleName
        return app.getString(R.string.email_test_network_error, short)
    }

    private fun providerRejectedMessage(httpCode: Int): String {
        val app = getApplication<Application>()
        val res = when (httpCode) {
            400 -> R.string.email_test_provider_request
            401 -> R.string.email_test_provider_auth
            402 -> R.string.email_test_provider_auth
            403 -> R.string.email_test_provider_auth
            404 -> R.string.email_test_provider_request
            429 -> R.string.email_test_provider_rate_limited
            in 500..599 -> R.string.email_test_provider_server
            else -> R.string.email_test_provider_rejected
        }
        return app.getString(res, httpCode)
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        cameraManager?.shutdown()
        detector?.close()
    }
}
