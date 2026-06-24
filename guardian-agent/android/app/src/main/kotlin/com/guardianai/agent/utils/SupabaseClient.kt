package com.guardianai.agent.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * SupabaseClient
 *
 * Lightweight Supabase REST API client for the child agent.
 * Uses pure HttpURLConnection — no SDK needed → smaller APK.
 */
object SupabaseClient {

    private const val TAG = "SupabaseClient"
    private var baseUrl:   String = ""
    private var anonKey:   String = ""
    private var childToken: String = ""
    private var childId:   String = ""

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("guardian_config", Context.MODE_PRIVATE)
        baseUrl    = prefs.getString("supabase_url", "") ?: ""
        anonKey    = prefs.getString("supabase_anon_key", "") ?: ""
        childToken = prefs.getString("child_token", "") ?: ""
        childId    = prefs.getString("child_id", "") ?: ""
    }

    // ─── ALERT LOGGING ────────────────────────────────────────────────────────

    suspend fun createSosAlert(
        childId: String,
        trigger: String,
        latitude: Double?,
        longitude: Double?,
        photoUrls: List<String>,
        audioUrl: String?
    ) = withContext(Dispatchers.IO) {
        val meta = JSONObject().apply {
            put("trigger", trigger)
            if (latitude != null)  put("latitude", latitude)
            if (longitude != null) put("longitude", longitude)
            put("photo_count", photoUrls.size)
            if (!audioUrl.isNullOrEmpty()) put("audio_url", audioUrl)
        }

        val payload = JSONObject().apply {
            put("child_id",  childId)
            put("type",      "sos")
            put("severity",  "critical")
            put("title",     "🆘 SOS EMERGENCY ALERT")
            put("body",      "Child triggered SOS via $trigger. Immediate attention required!")
            put("metadata",  meta)
        }
        post("alert", "/rest/v1/alerts", payload)
    }

    suspend fun logAlert(
        childId: String,
        type: String,
        severity: String,
        title: String,
        body: String,
        screenshotUrl: String? = null,
        metadata: JSONObject = JSONObject()
    ) = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("child_id",       childId)
            put("type",           type)
            put("severity",       severity)
            put("title",          title)
            put("body",           body)
            if (!screenshotUrl.isNullOrEmpty()) put("screenshot_url", screenshotUrl)
            put("metadata",       metadata)
        }
        post("alert", "/rest/v1/alerts", payload)
    }

    // ─── BLOCKED EVENTS ───────────────────────────────────────────────────────

    suspend fun logBlockedEvent(
        childId: String,
        blockType: String,
        content: String,
        details: String
    ) = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("child_id",   childId)
            put("block_type", blockType)
            put("content",    content)
            put("details",    details)
        }
        post("blocked_event", "/rest/v1/blocked_events", payload)
    }

    // ─── APP USAGE ────────────────────────────────────────────────────────────

    suspend fun upsertAppUsage(
        childId: String,
        appPackage: String,
        date: String,
        durationSeconds: Int,
        appName: String = appPackage
    ) = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("child_id",         childId)
            put("app_package",      appPackage)
            put("app_name",         appName)
            put("usage_date",       date)
            put("duration_seconds", durationSeconds)
        }

        // UPSERT (insert or update on conflict)
        val url = URL("$baseUrl/rest/v1/app_usage_logs")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $childToken")
        conn.setRequestProperty("apikey", anonKey)
        conn.setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal")
        conn.doOutput = true
        conn.connectTimeout = 8_000
        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    // ─── STORAGE UPLOAD ───────────────────────────────────────────────────────

    suspend fun uploadSosPhoto(childId: String, file: File): String? =
        withContext(Dispatchers.IO) {
            uploadFile("sos-photos", "sos/$childId/${file.name}", file, "image/jpeg")
        }

    suspend fun uploadSosAudio(childId: String, file: File): String? =
        withContext(Dispatchers.IO) {
            uploadFile("sos-audio", "sos/$childId/${file.name}", file, "audio/mp4")
        }

    suspend fun uploadScreenshot(childId: String, file: File): String? =
        withContext(Dispatchers.IO) {
            uploadFile("screenshots", "ai/$childId/${file.name}", file, "image/jpeg")
        }

    private fun uploadFile(bucket: String, path: String, file: File, mimeType: String): String? {
        return try {
            val url = URL("$baseUrl/storage/v1/object/$bucket/$path")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", mimeType)
            conn.setRequestProperty("Authorization", "Bearer $childToken")
            conn.setRequestProperty("apikey", anonKey)
            conn.setRequestProperty("x-upsert", "true")
            conn.doOutput = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000

            conn.outputStream.use { out ->
                file.inputStream().use { inp -> inp.copyTo(out) }
            }

            val code = conn.responseCode
            conn.disconnect()

            if (code in 200..201) {
                "$baseUrl/storage/v1/object/public/$bucket/$path"
            } else {
                Log.w(TAG, "Upload failed HTTP $code for $path")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload error for $path", e)
            null
        }
    }

    // ─── HELPERS ──────────────────────────────────────────────────────────────

    private fun post(label: String, endpoint: String, payload: JSONObject) {
        try {
            val url = URL("$baseUrl$endpoint")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $childToken")
            conn.setRequestProperty("apikey", anonKey)
            conn.setRequestProperty("Prefer", "return=minimal")
            conn.doOutput = true
            conn.connectTimeout = 8_000
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            if (code !in 200..201) Log.w(TAG, "POST $label returned HTTP $code")
        } catch (e: Exception) {
            Log.e(TAG, "POST $label failed: ${e.message}")
        }
    }
}

/**
 * SupabaseLogger — static alias for convenience in services
 */
object SupabaseLogger {
    fun logAlert(childId: String, type: String, severity: String, title: String, body: String) {
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            SupabaseClient.logAlert(childId, type, severity, title, body)
        }
    }
}
