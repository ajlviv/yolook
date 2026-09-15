package com.yolo.detector.alerts

import android.graphics.Bitmap
import com.yolo.detector.data.EmailSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Sends detection alerts through the Brevo (Sendinblue) transactional email API.
 *
 * Everything runs on [Dispatchers.IO] so a slow network call never blocks the YOLO
 * analysis loop. The JPEG is attached as a base64 array (`attachment` field) built
 * from [Bitmap.compress] at ~75% quality to keep the payload small.
 *
 * Retries once on failure, then gives up. The detection state machine has already
 * moved to COOLDOWN regardless, so a flaky network can never wedge it.
 */
object EmailAlertService {

    private const val BREVO_SMTP_URL = "https://api.brevo.com/v3/smtp/email"
    private const val JPEG_QUALITY = 75
    private const val TAG = "EmailAlertService"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Compresses [frame] to JPEG bytes. Does not recycle [frame]; the caller retains
     * ownership and is responsible for recycling it.
     */
    fun frameToJpeg(frame: Bitmap, quality: Int = JPEG_QUALITY): ByteArray {
        val out = ByteArrayOutputStream()
        check(frame.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
            "JPEG compression failed"
        }
        return out.toByteArray()
    }

    /**
     * Sends an alert email with [jpegBytes] attached. Retries once on failure.
     *
     * @return [Result.success] when the provider accepted the request (HTTP 2xx).
     */
    suspend fun send(
        settings: EmailSettings,
        apiKey: String,
        jpegBytes: ByteArray,
        detectedLabels: String,
        timestampMs: Long,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val request = try {
            buildRequest(settings, apiKey, jpegBytes, detectedLabels, timestampMs)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to build alert request", e)
            return@withContext Result.failure(e)
        }

        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        android.util.Log.i(TAG, "Alert email sent (attempt ${attempt + 1})")
                        return@withContext Result.success(Unit)
                    }
                    val body = response.body?.string().orEmpty()
                    lastError = IllegalStateException("HTTP ${response.code}: $body")
                    android.util.Log.w(TAG, "Alert email rejected HTTP ${response.code}", lastError)
                }
            } catch (e: Exception) {
                lastError = e
                android.util.Log.w(TAG, "Alert email send failed (attempt ${attempt + 1})", e)
            }
        }
        Result.failure(lastError ?: IllegalStateException("Unknown send failure"))
    }

    private fun buildRequest(
        settings: EmailSettings,
        apiKey: String,
        jpegBytes: ByteArray,
        detectedLabels: String,
        timestampMs: Long,
    ): Request {
        val senderEmail = settings.senderEmail.ifBlank { settings.recipient }
        val base64 = android.util.Base64.encodeToString(jpegBytes, android.util.Base64.NO_WRAP)
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))

        val html = StringBuilder()
            .append("<p><b>YOLO detector alert</b></p>")
            .append("<p>Detected: ").append(escapeHtml(detectedLabels.ifBlank { "object" })).append("</p>")
            .append("<p>Time: ").append(escapeHtml(time)).append("</p>")
            .toString()

        val body = JSONObject()
            .put("sender", JSONObject().put("name", "YOLO Detector").put("email", senderEmail))
            .put("to", JSONArray().put(JSONObject().put("email", settings.recipient)))
            .put("subject", "YOLO detection alert")
            .put("htmlContent", html)
            .put("attachment", JSONArray().put(
                JSONObject()
                    .put("name", "detection_${timestampMs}.jpg")
                    .put("content", base64)
            ))
            .toString()

        return Request.Builder()
            .url(BREVO_SMTP_URL)
            .header("api-key", apiKey)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    /**
     * Outcome taxonomy requested by the Settings diagnostic flow: maps provider HTTP
     * responses to a stable, user-explaining category.
     */
    sealed interface SendOutcome {

        /** The provider accepted the request (HTTP 2xx). */
        data object Sent : SendOutcome

        /**
         * The provider rejected the request (HTTP 4xx/5xx). [httpCode] is the raw code;
         * [body] is the truncated response body, if any.
         */
        data class ProviderRejected(val httpCode: Int, val body: String) : SendOutcome

        /** The request never reached the provider. [cause] is the last I/O exception. */
        data class NetworkError(val cause: Exception) : SendOutcome
    }

    /**
     * Same as [send], but returns a structured [SendOutcome] so the UI can explain
     * transport failures vs. provider rejections instead of guessing from a string.
     */
    suspend fun sendDetailed(
        settings: EmailSettings,
        apiKey: String,
        jpegBytes: ByteArray,
        detectedLabels: String,
        timestampMs: Long,
    ): SendOutcome = withContext(Dispatchers.IO) {
        val request = try {
            buildRequest(settings, apiKey, jpegBytes, detectedLabels, timestampMs)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to build alert request", e)
            return@withContext SendOutcome.NetworkError(e)
        }

        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        android.util.Log.i(TAG, "Alert email sent (attempt ${attempt + 1})")
                        return@withContext SendOutcome.Sent
                    }
                    val body = response.body?.string().orEmpty()
                    lastError = IllegalStateException("HTTP ${response.code}: $body")
                    android.util.Log.w(TAG, "Alert email rejected HTTP ${response.code}", lastError)
                }
            } catch (e: Exception) {
                lastError = e
                android.util.Log.w(TAG, "Alert email send failed (attempt ${attempt + 1})", e)
            }
        }
        val outcome = when (val error = lastError) {
            null -> SendOutcome.NetworkError(IllegalStateException("Unknown send failure"))
            is IllegalStateException -> error.message
                ?.let { Regex("HTTP (\\d{3}):").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
                ?.let { SendOutcome.ProviderRejected(it, error.message?.take(300).orEmpty()) }
                ?: SendOutcome.NetworkError(error)
            else -> SendOutcome.NetworkError(error)
        }
        outcome
    }


    private fun escapeHtml(input: String): String =
        input.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}