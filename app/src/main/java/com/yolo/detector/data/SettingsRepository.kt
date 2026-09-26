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
        val SLICED_INFERENCE = booleanPreferencesKey("sliced_inference")
        val MODEL_PROFILE_ID = stringPreferencesKey("model_profile_id")
        // Legacy single-model filter, migrated onto the default profile on first read.
        // Stored as a comma-separated string of integers, e.g. "2,3,5,7"
        val CLASS_FILTER_IDS = stringPreferencesKey("class_filter_ids")
        // Per-profile filters, one entry per model: "<profileId>=<id,id,id>".
        val CLASS_FILTERS = stringSetPreferencesKey("class_filters")
        // Stored as the enum name, e.g. "HEATMAP"
        val VIEW_MODE = stringPreferencesKey("view_mode")
        // Stored as the enum name, e.g. "COUNT"
        val DETECTION_VIEW = stringPreferencesKey("detection_view")
        val MONITORING_MODE = booleanPreferencesKey("monitoring_mode")
        val EDGE_THRESHOLD = intPreferencesKey("edge_threshold")
        val EDGE_DETAIL = intPreferencesKey("edge_detail")
        val HEATMAP_DETAIL = intPreferencesKey("heatmap_detail")
        val MATRIX_DETAIL = intPreferencesKey("matrix_detail")
        val MATRIX_GAMMA = floatPreferencesKey("matrix_gamma")
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    /** Emits the current [InferenceSettings], falling back to defaults on IO error. */
    val settingsFlow: Flow<InferenceSettings> = dataStore.data
        .catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { prefs -> prefs.toSettings() }

    private fun Preferences.toSettings(): InferenceSettings {
        val profileId = this[Keys.MODEL_PROFILE_ID] ?: DEFAULT_MODEL_PROFILE_ID

        val filters = HashMap<String, Set<Int>>()
        // Carries the pre-multi-model filter forward as the default profile's filter.
        this[Keys.CLASS_FILTER_IDS]?.parseClassIds()?.takeIf { it.isNotEmpty() }?.let {
            filters[profileId] = it
        }
        for (entry in this[Keys.CLASS_FILTERS].orEmpty()) {
            val separator = entry.indexOf('=')
            if (separator <= 0) continue
            val ids = entry.substring(separator + 1).parseClassIds()
            if (ids.isEmpty()) continue
            filters[entry.substring(0, separator)] = ids
        }

        return InferenceSettings(
            confidenceThreshold = this[Keys.CONF_THRESHOLD] ?: 0.35f,
            iouThreshold = this[Keys.IOU_THRESHOLD] ?: 0.45f,
            maxObjects = this[Keys.MAX_OBJECTS] ?: 50,
            inferenceRateFps = this[Keys.INFERENCE_FPS] ?: 10,
            enableGpuDelegate = this[Keys.GPU_ENABLED] ?: true,
            slicedInference = this[Keys.SLICED_INFERENCE] ?: false,
            modelProfileId = profileId,
            classFilters = filters,
            viewMode = this[Keys.VIEW_MODE]?.toViewMode() ?: ViewMode.NORMAL,
            detectionView = this[Keys.DETECTION_VIEW]?.toDetectionView() ?: DetectionView.LABELS,
            monitoringMode = this[Keys.MONITORING_MODE] ?: false,
            edgeThreshold = this[Keys.EDGE_THRESHOLD] ?: 100,
            edgeDetail = this[Keys.EDGE_DETAIL] ?: 3,
            heatmapDetail = this[Keys.HEATMAP_DETAIL] ?: 3,
            matrixDetail = this[Keys.MATRIX_DETAIL] ?: 8,
            matrixGamma = snapToStep(this[Keys.MATRIX_GAMMA] ?: 0.74f, 0.5f, 1f, 0.01f),
        )
    }

    private fun String.parseClassIds(): Set<Int> =
        split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()

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

    suspend fun setSlicedInference(value: Boolean) {
        dataStore.edit { it[Keys.SLICED_INFERENCE] = value }
    }

    suspend fun setModelProfileId(value: String) {
        dataStore.edit { it[Keys.MODEL_PROFILE_ID] = value }
    }

    /** Stores [ids] as the class filter for [profileId], leaving other profiles untouched. */
    suspend fun setClassFilter(profileId: String, ids: Set<Int>) {
        dataStore.edit { prefs ->
            val entry = "$profileId=${ids.sorted().joinToString(",")}"
            prefs[Keys.CLASS_FILTERS] = prefs[Keys.CLASS_FILTERS].orEmpty() + entry
        }
    }

    suspend fun setViewMode(mode: ViewMode) {
        dataStore.edit { it[Keys.VIEW_MODE] = mode.name }
    }

    suspend fun setDetectionView(view: DetectionView) {
        dataStore.edit { it[Keys.DETECTION_VIEW] = view.name }
    }

    suspend fun setMonitoringMode(value: Boolean) {
        dataStore.edit { it[Keys.MONITORING_MODE] = value }
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

    /** Resets all settings to factory defaults by clearing the DataStore. */
    suspend fun resetToDefaults() {
        dataStore.edit { it.clear() }
    }
}
