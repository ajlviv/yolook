package com.yolo.detector.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.yolo.detector.util.snapToStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "inference_settings"
)

/**
 * Persists [InferenceSettings] to Jetpack DataStore Preferences.
 *
 * - All reads are exposed as cold [Flow]s for reactive ViewModel consumption.
 * - All writes are suspend functions safe to call from any coroutine dispatcher.
 * - IO errors during reads fall back silently to default values.
 *
 * Usage:
 * ```kotlin
 * val repo = SettingsRepository(applicationContext)
 * repo.settingsFlow.collect { settings -> /* react to changes */ }
 * repo.setConfidenceThreshold(0.5f)
 * ```
 */
class SettingsRepository(context: Context) {

    private val dataStore = context.applicationContext.dataStore

    // ── Keys ─────────────────────────────────────────────────────────────────

    private object Keys {
        val CONF_THRESHOLD = floatPreferencesKey("conf_threshold")
        val IOU_THRESHOLD = floatPreferencesKey("iou_threshold")
        val MAX_OBJECTS = intPreferencesKey("max_objects")
        val INFERENCE_FPS = intPreferencesKey("inference_fps")
        val GPU_ENABLED = booleanPreferencesKey("gpu_enabled")
        // Stored as a comma-separated string of integers, e.g. "2,3,5,7"
        val CLASS_FILTER_IDS = stringPreferencesKey("class_filter_ids")
        // Stored as the enum name, e.g. "HEATMAP"
        val VIEW_MODE = stringPreferencesKey("view_mode")
        // Stored as the enum name, e.g. "COUNT"
        val DETECTION_VIEW = stringPreferencesKey("detection_view")
        val EDGE_THRESHOLD = intPreferencesKey("edge_threshold")
        val EDGE_DETAIL = intPreferencesKey("edge_detail")
        val HEATMAP_DETAIL = intPreferencesKey("heatmap_detail")
        val MATRIX_DETAIL = intPreferencesKey("matrix_detail")
        val MATRIX_GAMMA = floatPreferencesKey("matrix_gamma")
        // Stored as the enum name, e.g. "VIDEO"
        val CAPTURE_MODE = stringPreferencesKey("capture_mode")
        // Stored as the enum name, e.g. "HD"
        val VIDEO_RESOLUTION = stringPreferencesKey("video_resolution")
        val VIDEO_FPS = intPreferencesKey("video_fps")
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    /** Emits the current [InferenceSettings], falling back to defaults on IO error. */
    val settingsFlow: Flow<InferenceSettings> = dataStore.data
        .catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { prefs -> prefs.toSettings() }

    private fun Preferences.toSettings(): InferenceSettings {
        val filterIds = this[Keys.CLASS_FILTER_IDS]
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.toSet()
            .takeIf { it?.isNotEmpty() == true }
            ?: COCO_LABELS.indices.toSet()

        return InferenceSettings(
            confidenceThreshold = this[Keys.CONF_THRESHOLD] ?: 0.35f,
            iouThreshold = this[Keys.IOU_THRESHOLD] ?: 0.45f,
            maxObjects = this[Keys.MAX_OBJECTS] ?: 50,
            inferenceRateFps = this[Keys.INFERENCE_FPS] ?: 10,
            enableGpuDelegate = this[Keys.GPU_ENABLED] ?: true,
            classFilter = filterIds,
            viewMode = this[Keys.VIEW_MODE]?.toViewMode() ?: ViewMode.NORMAL,
            detectionView = this[Keys.DETECTION_VIEW]?.toDetectionView() ?: DetectionView.LABELS,
            edgeThreshold = this[Keys.EDGE_THRESHOLD] ?: 100,
            edgeDetail = this[Keys.EDGE_DETAIL] ?: 3,
            heatmapDetail = this[Keys.HEATMAP_DETAIL] ?: 3,
            matrixDetail = this[Keys.MATRIX_DETAIL] ?: 8,
            matrixGamma = snapToStep(this[Keys.MATRIX_GAMMA] ?: 0.74f, 0.5f, 1f, 0.01f),
            captureMode = this[Keys.CAPTURE_MODE]?.toCaptureMode() ?: CaptureMode.PHOTO,
            videoResolution = this[Keys.VIDEO_RESOLUTION]?.toVideoResolution() ?: VideoResolution.HD,
            videoFps = (this[Keys.VIDEO_FPS] ?: 30).coerceIn(15, 30),
        )
    }

    private fun String.toCaptureMode(): CaptureMode =
        CaptureMode.entries.firstOrNull { it.name == this } ?: CaptureMode.PHOTO

    private fun String.toVideoResolution(): VideoResolution =
        VideoResolution.entries.firstOrNull { it.name == this } ?: VideoResolution.HD

    private fun String.toViewMode(): ViewMode =
        ViewMode.entries.firstOrNull { it.name == this } ?: ViewMode.NORMAL

    private fun String.toDetectionView(): DetectionView =
        DetectionView.entries.firstOrNull { it.name == this } ?: DetectionView.LABELS

    // ── Write ─────────────────────────────────────────────────────────────────

    suspend fun setConfidenceThreshold(value: Float) {
        dataStore.edit { it[Keys.CONF_THRESHOLD] = value.coerceIn(0.1f, 0.9f) }
    }

    suspend fun setIouThreshold(value: Float) {
        dataStore.edit { it[Keys.IOU_THRESHOLD] = value.coerceIn(0.1f, 0.9f) }
    }

    suspend fun setMaxObjects(value: Int) {
        dataStore.edit { it[Keys.MAX_OBJECTS] = value.coerceIn(1, 100) }
    }

    suspend fun setInferenceRateFps(value: Int) {
        dataStore.edit { it[Keys.INFERENCE_FPS] = value.coerceIn(1, 30) }
    }

    suspend fun setGpuEnabled(value: Boolean) {
        dataStore.edit { it[Keys.GPU_ENABLED] = value }
    }

    suspend fun setClassFilter(ids: Set<Int>) {
        dataStore.edit { it[Keys.CLASS_FILTER_IDS] = ids.joinToString(",") }
    }

    suspend fun setViewMode(mode: ViewMode) {
        dataStore.edit { it[Keys.VIEW_MODE] = mode.name }
    }

    suspend fun setDetectionView(view: DetectionView) {
        dataStore.edit { it[Keys.DETECTION_VIEW] = view.name }
    }

    suspend fun setEdgeThreshold(value: Int) {
        dataStore.edit { it[Keys.EDGE_THRESHOLD] = value.coerceIn(30, 200) }
    }

    suspend fun setEdgeDetail(value: Int) {
        dataStore.edit { it[Keys.EDGE_DETAIL] = value.coerceIn(1, 3) }
    }

    suspend fun setHeatmapDetail(value: Int) {
        dataStore.edit { it[Keys.HEATMAP_DETAIL] = value.coerceIn(1, 3) }
    }

    suspend fun setMatrixDetail(value: Int) {
        dataStore.edit { it[Keys.MATRIX_DETAIL] = value.coerceIn(1, 10) }
    }

    suspend fun setMatrixGamma(value: Float) {
        val snapped = snapToStep(value, 0.5f, 1f, 0.01f)
        dataStore.edit { it[Keys.MATRIX_GAMMA] = snapped }
    }

    suspend fun setCaptureMode(mode: CaptureMode) {
        dataStore.edit { it[Keys.CAPTURE_MODE] = mode.name }
    }

    suspend fun setVideoResolution(resolution: VideoResolution) {
        dataStore.edit { it[Keys.VIDEO_RESOLUTION] = resolution.name }
    }

    suspend fun setVideoFps(value: Int) {
        dataStore.edit { it[Keys.VIDEO_FPS] = value.coerceIn(15, 30) }
    }

    /** Resets all settings to factory defaults by clearing the DataStore. */
    suspend fun resetToDefaults() {
        dataStore.edit { it.clear() }
    }
}
