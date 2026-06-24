package com.guardianai.agent.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.guardianai.agent.services.GuardianService
import com.guardianai.agent.utils.DeviceInfo

/**
 * BroadcastReceiver that restarts GuardianService when triggered by AlarmManager.
 * This provides resilience if the service is killed by the OS.
 */
class RestartAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "RestartAlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Restart alarm triggered — restarting GuardianService")

        val childId = DeviceInfo.getChildIdFromPrefs(context)
        if (childId.isNullOrBlank()) {
            Log.w(TAG, "No child_id configured, skipping restart")
            return
        }

        try {
            val serviceIntent = Intent(context, GuardianService::class.java).apply {
                putExtra("source", "restart_alarm")
                putExtra("child_id", childId)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            Log.i(TAG, "GuardianService restart requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart GuardianService", e)
        }
    }
}
