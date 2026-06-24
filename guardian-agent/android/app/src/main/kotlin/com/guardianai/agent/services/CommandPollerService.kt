package com.guardianai.agent.services

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.app.Service
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * CommandPollerService
 *
 * Polls Supabase for pending commands from the parent every 10 seconds.
 * Also connects to Supabase Realtime for instant command delivery.
 *
 * Supported commands:
 *   lock_device       → Locks the screen immediately
 *   take_photo_front  → Captures front camera, uploads to Supabase Storage
 *   take_photo_back   → Captures back camera, uploads to Supabase Storage
 *   start_audio       → Begins ambient audio recording
 *   stop_audio        → Stops audio recording
 *   start_camera      → Begins live camera stream via WebRTC
 *   stop_camera       → Ends camera stream
 *   emergency_alert   → Plays loud alarm on device
 *   refresh_blocklist → Re-downloads rules from Supabase
 *   refresh_rules     → Re-downloads app rules
 *   wipe_device       → Factory resets device (Device Owner only)
 */
class CommandPollerService : Service() {

    companion object {
        const val TAG = "GuardianCommands"
    }

    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val POLL_INTERVAL_MS = 10_000L // 10 second polling

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("guardian_config", Context.MODE_PRIVATE)
        startPolling()
        Log.i(TAG, "✅ CommandPollerService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ─── POLLING LOOP ─────────────────────────────────────────────────────────

    private fun startPolling() {
        scope.launch {
            while (isActive) {
                try {
                    pollCommands()
                    updateChildStatus()
                } catch (e: Exception) {
                    Log.e(TAG, "Poll error", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun pollCommands() {
        val childId     = prefs.getString("child_id", null) ?: return
        val token       = prefs.getString("child_token", null) ?: return
        val supabaseUrl = prefs.getString("supabase_url", null) ?: return

        try {
            // Fetch pending commands for this device
            val url = URL("$supabaseUrl/rest/v1/device_commands?child_id=eq.$childId&status=eq.pending&order=issued_at.asc")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("apikey", prefs.getString("supabase_anon_key", "") ?: "")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val commands = JSONArray(body)
                for (i in 0 until commands.length()) {
                    val cmd = commands.getJSONObject(i)
                    executeCommand(cmd, supabaseUrl, token)
                }
            } else {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Poll failed: ${e.message}")
        }
    }

    // ─── COMMAND EXECUTION ────────────────────────────────────────────────────

    private suspend fun executeCommand(cmd: JSONObject, supabaseUrl: String, token: String) {
        val commandId = cmd.getString("id")
        val command   = cmd.getString("command")

        Log.i(TAG, "Executing command: $command (id=$commandId)")

        // Mark as 'executing'
        markCommand(commandId, "executing", null, supabaseUrl, token)

        var result = JSONObject()
        var status = "completed"

        try {
            when (command) {
                "lock_device"      -> { lockDevice();                    result.put("done", true) }
                "take_photo_front" -> { takePhoto(front = true);         result.put("done", true) }
                "take_photo_back"  -> { takePhoto(front = false);        result.put("done", true) }
                "start_audio"      -> { startAudioStream();              result.put("done", true) }
                "stop_audio"       -> { stopAudioStream();               result.put("done", true) }
                "start_camera"     -> { startCameraStream();             result.put("done", true) }
                "stop_camera"      -> { stopCameraStream();              result.put("done", true) }
                "emergency_alert"  -> { playEmergencyAlarm();            result.put("done", true) }
                "refresh_blocklist"-> { refreshBlocklist();              result.put("done", true) }
                "refresh_rules"    -> { refreshRules(supabaseUrl, token);result.put("done", true) }
                "wipe_device"      -> { wipeDevice();                    result.put("done", true) }
                else               -> { result.put("error", "unknown command"); status = "failed" }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command $command failed", e)
            result.put("error", e.message)
            status = "failed"
        }

        markCommand(commandId, status, result, supabaseUrl, token)
    }

    private suspend fun markCommand(
        id: String, status: String, result: JSONObject?,
        supabaseUrl: String, token: String
    ) {
        try {
            val payload = JSONObject().apply {
                put("status", status)
                put("executed_at", java.time.Instant.now().toString())
                if (result != null) put("result", result)
            }
            val url = URL("$supabaseUrl/rest/v1/device_commands?id=eq.$id")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "PATCH"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("apikey", prefs.getString("supabase_anon_key", "") ?: "")
            conn.setRequestProperty("Prefer", "return=minimal")
            conn.doOutput = true
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            conn.responseCode
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Mark command failed: ${e.message}")
        }
    }

    // ─── COMMAND IMPLEMENTATIONS ──────────────────────────────────────────────

    private fun lockDevice() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        dpm.lockNow()
        Log.i(TAG, "🔒 Device locked by parent command")
    }

    private fun takePhoto(front: Boolean) {
        val intent = Intent(this, CameraCommandService::class.java).apply {
            putExtra("front_camera", front)
        }
        startService(intent)
    }

    private fun startAudioStream() {
        val intent = Intent(this, AudioStreamService::class.java).apply {
            action = "START"
        }
        startService(intent)
    }

    private fun stopAudioStream() {
        val intent = Intent(this, AudioStreamService::class.java).apply {
            action = "STOP"
        }
        startService(intent)
    }

    private fun startCameraStream() {
        val intent = Intent(this, WebRtcStreamService::class.java).apply {
            action = "START_CAMERA"
        }
        startService(intent)
    }

    private fun stopCameraStream() {
        val intent = Intent(this, WebRtcStreamService::class.java).apply {
            action = "STOP"
        }
        startService(intent)
    }

    private fun playEmergencyAlarm() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        val pattern = longArrayOf(0, 500, 200, 500, 200, 500)
        vibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, 0))
        Log.i(TAG, "🚨 Emergency alarm triggered by parent")
    }

    private suspend fun refreshBlocklist() {
        // Re-download app rules from Supabase
        val childId     = prefs.getString("child_id", null) ?: return
        val token       = prefs.getString("child_token", null) ?: return
        val supabaseUrl = prefs.getString("supabase_url", null) ?: return

        try {
            val url = URL("$supabaseUrl/rest/v1/app_rules?child_id=eq.$childId&is_blocked=eq.true")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("apikey", prefs.getString("supabase_anon_key", "") ?: "")
            conn.setRequestProperty("Accept", "application/json")

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val rules = JSONArray(body)
                val blocked = mutableListOf<String>()
                for (i in 0 until rules.length()) {
                    blocked.add(rules.getJSONObject(i).getString("app_package"))
                }
                AppMonitorService.updateBlocklist(this, blocked)
                Log.i(TAG, "Blocklist refreshed: ${blocked.size} blocked apps")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Refresh blocklist failed", e)
        }
    }

    private suspend fun refreshRules(supabaseUrl: String, token: String) {
        refreshBlocklist()
        // Could also refresh time limits, geofences, etc.
    }

    private fun wipeDevice() {
        Log.w(TAG, "⚠️ WIPE DEVICE command received!")
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        // Only executes if Device Owner
        dpm.wipeData(0)
    }

    // ─── STATUS HEARTBEAT ─────────────────────────────────────────────────────

    private suspend fun updateChildStatus() {
        val childId     = prefs.getString("child_id", null) ?: return
        val token       = prefs.getString("child_token", null) ?: return
        val supabaseUrl = prefs.getString("supabase_url", null) ?: return

        val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val battery = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)

        val payload = JSONObject().apply {
            put("is_online", true)
            put("battery_level", battery)
            put("last_seen", java.time.Instant.now().toString())
        }

        try {
            val url = URL("$supabaseUrl/rest/v1/children?id=eq.$childId")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "PATCH"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("apikey", prefs.getString("supabase_anon_key", "") ?: "")
            conn.setRequestProperty("Prefer", "return=minimal")
            conn.doOutput = true
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {}
    }
}
