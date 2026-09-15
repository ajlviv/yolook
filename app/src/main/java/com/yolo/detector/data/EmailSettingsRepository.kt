package com.yolo.detector.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

private val Context.emailDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "email_settings"
)

private const val SECURE_PREFS_NAME = "email_secure_prefs"
private const val API_KEY = "api_key"

/**
 * Persists email-alert configuration.
 *
 * - Non-secret fields (enabled, recipient, sender, cooldown, class filter) live in
 *   Jetpack DataStore, consistent with [SettingsRepository].
 * - The Brevo API key is stored via [EncryptedSharedPreferences] (AndroidX Security),
 *   never in plain SharedPreferences.
 */
class EmailSettingsRepository(context: Context) {

    private val appContext = context.applicationContext
    private val dataStore = appContext.emailDataStore

    // Lazy so the (slower) crypto initialisation only runs the first time the API key
    // is actually read or written.
    private val securePrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val RECIPIENT = stringPreferencesKey("recipient")
        val SENDER_EMAIL = stringPreferencesKey("sender_email")
        val COOLDOWN_MS = longPreferencesKey("cooldown_ms")
        val TRIGGER_CLASS_IDS = stringPreferencesKey("trigger_class_ids")
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /** Emits the current [EmailSettings], falling back to defaults on IO error. */
    val settingsFlow: Flow<EmailSettings> = dataStore.data
        .catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { prefs -> prefs.toEmailSettings() }

    private fun Preferences.toEmailSettings(): EmailSettings {
        val triggerIds = this[Keys.TRIGGER_CLASS_IDS]
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.toSet()
            ?: emptySet()
        return EmailSettings(
            enabled = this[Keys.ENABLED] ?: false,
            recipient = this[Keys.RECIPIENT] ?: "",
            senderEmail = this[Keys.SENDER_EMAIL] ?: "",
            cooldownMs = (this[Keys.COOLDOWN_MS] ?: 60_000L).coerceIn(0L, 3_600_000L),
            triggerClassIds = triggerIds,
        )
    }

    /** Synchronous, encrypted read of the stored API key ("" when unset). */
    fun apiKey(): String = securePrefs.getString(API_KEY, "").orEmpty()

    // ── Write ─────────────────────────────────────────────────────────────────

    suspend fun setEnabled(value: Boolean) {
        dataStore.edit { it[Keys.ENABLED] = value }
    }

    suspend fun setRecipient(value: String) {
        dataStore.edit { it[Keys.RECIPIENT] = value.trim() }
    }

    suspend fun setSenderEmail(value: String) {
        dataStore.edit { it[Keys.SENDER_EMAIL] = value.trim() }
    }

    suspend fun setCooldownMs(value: Long) {
        dataStore.edit { it[Keys.COOLDOWN_MS] = value.coerceIn(0L, 3_600_000L) }
    }

    suspend fun setTriggerClassIds(ids: Set<Int>) {
        dataStore.edit { it[Keys.TRIGGER_CLASS_IDS] = ids.joinToString(",") }
    }

    /** Writes (or clears, when [value] is blank) the encrypted API key off the main thread. */
    suspend fun setApiKey(value: String) {
        val trimmed = value.trim()
        withContext(Dispatchers.IO) {
            securePrefs.edit().putString(API_KEY, trimmed).apply()
        }
    }

    /** Resets non-secret email settings to defaults. The API key is intentionally kept. */
    suspend fun resetSettings() {
        dataStore.edit { it.clear() }
    }
}