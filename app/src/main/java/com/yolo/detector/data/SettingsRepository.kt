package com.yolo.detector.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
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
        )
    }

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

    /** Resets all settings to factory defaults by clearing the DataStore. */
    suspend fun resetToDefaults() {
        dataStore.edit { it.clear() }
    }
}
