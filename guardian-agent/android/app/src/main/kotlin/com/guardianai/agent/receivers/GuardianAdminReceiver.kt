package com.guardianai.agent.receivers

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.guardianai.agent.GuardianApp
import com.guardianai.agent.config.Config
import com.guardianai.agent.utils.DeviceInfo
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * Device Admin Receiver for GuardianAgent.
 * Receives admin-related events and logs them to Supabase.
 */
class GuardianAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "GuardianAdminReceiver"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin ENABLED for ${context.packageName}")
        logEventToSupabase(
            context = context,
            eventType = "admin_enabled",
            message = "GuardianAgent device admin privileges granted",
            severity = "info"
        )
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "Device admin DISABLED for ${context.packageName}")
        logEventToSupabase(
            context = context,
            eventType = "admin_disabled",
            message = "WARNING: GuardianAgent device admin privileges were revoked",
            severity = "critical"
        )
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Log.w(TAG, "Device admin disable requested")
        logEventToSupabase(
            context = context,
            eventType = "admin_disable_requested",
            message = "Child attempted to remove device admin privileges",
            severity = "warning"
        )
        // Return a warning message shown to the user trying to remove admin
        return "This is a required system service. Removing it may cause device issues."
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)
        Log.w(TAG, "Password unlock failed")
        logEventToSupabase(
            context = context,
            eventType = "password_failed",
            message = "Failed device unlock attempt detected",
            severity = "warning"
        )
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        super.onPasswordSucceeded(context, intent)
        logEventToSupabase(
            context = context,
            eventType = "password_succeeded",
            message = "Device unlocked successfully",
            severity = "info"
        )
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        Log.d(TAG, "Lock task mode entering for: $pkg")
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        super.onLockTaskModeExiting(context, intent)
        Log.d(TAG, "Lock task mode exiting")
    }

    /**
     * Logs a device admin event to the Supabase device_events table.
     */
    private fun logEventToSupabase(
        context: Context,
        eventType: String,
        message: String,
        severity: String
    ) {
        scope.launch {
            try {
                val childId = DeviceInfo.getChildIdFromPrefs(context) ?: return@launch
                val deviceId = DeviceInfo.getDeviceId(context)

                GuardianApp.supabase.postgrest[Config.TABLE_DEVICE_EVENTS].insert(
                    buildJsonObject {
                        put("child_id", childId)
                        put("device_id", deviceId)
                        put("event_type", eventType)
                        put("message", message)
                        put("severity", severity)
                        put("timestamp", Instant.now().toString())
                    }
                )

                Log.d(TAG, "Event logged to Supabase: $eventType")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log event to Supabase: $eventType", e)
            }
        }
    }
}
