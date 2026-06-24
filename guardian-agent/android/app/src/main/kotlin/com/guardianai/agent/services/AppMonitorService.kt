package com.guardianai.agent.services

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.guardianai.agent.GuardianApp
import com.guardianai.agent.config.Config
import com.guardianai.agent.utils.DeviceInfo
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

/**
 * AppMonitorService handles app usage tracking, rule enforcement, and new app detection.
 */
class AppMonitorService(private val context: Context) {

    companion object {
        private const val TAG = "AppMonitorService"
    }

    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    private val packageManager: PackageManager = context.packageManager

    /**
     * Retrieves app usage statistics for today (since midnight).
     * Returns a map of packageName -> foreground time in milliseconds.
     */
    fun getTodayUsageStats(): Map<String, Long> {
        return try {
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val startTime = calendar.timeInMillis
            val endTime = System.currentTimeMillis()

            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )

            stats
                .filter { it.totalTimeInForeground > 0 }
                .associate { it.packageName to it.totalTimeInForeground }
                .also { Log.d(TAG, "Retrieved usage stats for ${it.size} apps") }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get usage stats", e)
            emptyMap()
        }
    }

    /**
     * Gets the currently foreground (active) app package name.
     */
    fun getForegroundApp(): String? {
        return try {
            val endTime = System.currentTimeMillis()
            val startTime = endTime - 1000 * 60 * 5 // last 5 minutes

            val events = usageStatsManager.queryEvents(startTime, endTime)
            val event = android.app.usage.UsageEvents.Event()
            var lastForegroundPackage: String? = null

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    lastForegroundPackage = event.packageName
                }
            }
            lastForegroundPackage
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get foreground app", e)
            null
        }
    }

    /**
     * Uploads app usage statistics to Supabase app_usage_logs table.
     */
    suspend fun uploadUsageStats(childId: String) {
        try {
            val usageMap = getTodayUsageStats()
            if (usageMap.isEmpty()) {
                Log.d(TAG, "No usage stats to upload")
                return
            }

            val deviceId = DeviceInfo.getDeviceId(context)
            val today = LocalDate.now(ZoneId.systemDefault()).toString()

            // Batch upsert all usage records
            val records = usageMap.map { (packageName, timeMs) ->
                val appName = getAppName(packageName)
                buildJsonObject {
                    put("child_id", childId)
                    put("device_id", deviceId)
                    put("package_name", packageName)
                    put("app_name", appName)
                    put("usage_time_ms", timeMs)
                    put("usage_date", today)
                    put("last_updated", Instant.now().toString())
                }
            }

            // Insert in chunks of 50 to avoid request size limits
            records.chunked(50).forEach { chunk ->
                GuardianApp.supabase.postgrest[Config.TABLE_APP_USAGE_LOGS].upsert(
                    chunk,
                    onConflict = "child_id,package_name,usage_date"
                )
            }

            Log.d(TAG, "Uploaded usage stats for ${usageMap.size} apps")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload usage stats", e)
        }
    }

    /**
     * Fetches app rules from Supabase and checks if any currently-running apps should be blocked.
     * Uses DevicePolicyManager (if device owner) to enforce app restrictions.
     */
    suspend fun checkAppRules(childId: String) {
        try {
            val rules = GuardianApp.supabase.postgrest[Config.TABLE_APP_RULES]
                .select(Columns.list("package_name", "rule_type", "is_active")) {
                    filter {
                        eq("child_id", childId)
                        eq("is_active", true)
                    }
                }
                .decodeList<Map<String, String>>()

            if (rules.isEmpty()) return

            val blockedPackages = rules
                .filter { it["rule_type"] == "block" }
                .mapNotNull { it["package_name"] }
                .toSet()

            val foregroundApp = getForegroundApp()
            if (foregroundApp != null && foregroundApp in blockedPackages) {
                Log.w(TAG, "Blocked app detected in foreground: $foregroundApp")
                enforceAppBlock(foregroundApp, childId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check app rules", e)
        }
    }

    /**
     * Enforces app blocking by launching a lock screen or returning to home.
     * With device owner permissions, can use more aggressive measures.
     */
    private fun enforceAppBlock(packageName: String, childId: String) {
        try {
            // Return to home screen to forcefully exit the blocked app
            val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_HOME)
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(homeIntent)
            Log.i(TAG, "Forced home — blocked app: $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enforce app block for $packageName", e)
        }
    }

    /**
     * Detects newly installed apps by comparing against the known app list stored in prefs.
     * Alerts Supabase if a new app is found.
     */
    suspend fun detectNewApps(childId: String) {
        try {
            val installedPackages = packageManager.getInstalledPackages(0)
                .map { it.packageName }
                .toSet()

            val prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
            val knownAppsJson = prefs.getString(Config.PREF_KNOWN_APPS, null)

            if (knownAppsJson == null) {
                // First run — save current apps as baseline
                prefs.edit()
                    .putString(Config.PREF_KNOWN_APPS, installedPackages.joinToString(","))
                    .apply()
                Log.d(TAG, "Saved ${installedPackages.size} apps as baseline")
                return
            }

            val knownApps = knownAppsJson.split(",").toSet()
            val newApps = installedPackages - knownApps

            if (newApps.isNotEmpty()) {
                Log.i(TAG, "New apps detected: $newApps")

                // Alert each new app to Supabase
                newApps.forEach { packageName ->
                    val appName = getAppName(packageName)
                    GuardianApp.supabase.postgrest[Config.TABLE_DEVICE_EVENTS].insert(
                        buildJsonObject {
                            put("child_id", childId)
                            put("device_id", DeviceInfo.getDeviceId(context))
                            put("event_type", "new_app_installed")
                            put("message", "New app installed: $appName ($packageName)")
                            put("severity", "warning")
                            put("metadata", packageName)
                            put("timestamp", Instant.now().toString())
                        }
                    )
                }

                // Update known apps
                prefs.edit()
                    .putString(Config.PREF_KNOWN_APPS, installedPackages.joinToString(","))
                    .apply()

                Log.d(TAG, "Alerted ${newApps.size} new apps to Supabase")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to detect new apps", e)
        }
    }

    /**
     * Returns a human-readable app name for a package, or the package name itself if not found.
     */
    private fun getAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    /**
     * Returns total screen-on time in milliseconds for today.
     */
    fun getTotalScreenTime(): Long {
        return getTodayUsageStats().values.sum()
    }
}
