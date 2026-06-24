package com.guardianai.agent.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.guardianai.agent.config.Config
import com.guardianai.agent.services.GuardianService
import com.guardianai.agent.utils.DeviceInfo

/**
 * BroadcastReceiver that starts GuardianService on device boot.
 * Also handles package replacement (app updates) and other boot events.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "Boot receiver triggered: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            "android.intent.action.MY_PACKAGE_REPLACED",
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                startGuardianService(context)
            }
        }
    }

    /**
     * Starts GuardianService as a foreground service.
     * Only starts if child_id has been configured (device has been set up).
     */
    private fun startGuardianService(context: Context) {
        try {
            // Only start if the device has been registered/configured
            val childId = DeviceInfo.getChildIdFromPrefs(context)
            if (childId.isNullOrBlank()) {
                Log.w(TAG, "No child_id found in prefs. Skipping service start (device not configured yet).")
                return
            }

            Log.i(TAG, "Starting GuardianService for child: $childId")

            val serviceIntent = Intent(context, GuardianService::class.java).apply {
                putExtra("source", "boot_receiver")
                putExtra("child_id", childId)
            }

            // Use startForegroundService for API 26+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            Log.i(TAG, "GuardianService start requested successfully")

            // Also schedule the periodic WorkManager task
            com.guardianai.agent.workers.CommandSyncWorker.schedule(context)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start GuardianService on boot", e)
        }
    }
}
