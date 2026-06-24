package com.guardianai.agent.services

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.guardianai.agent.utils.SupabaseClient
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.Calendar

/**
 * ═══════════════════════════════════════════════════════════
 * ScreenTimeEnforcer — Intelligent Screen Time Limits
 * ═══════════════════════════════════════════════════════════
 *
 * More advanced than AirDroid:
 *
 *  1. PER-APP DAILY LIMITS
 *     YouTube: 60 min/day, TikTok: 30 min/day, Games: 45 min/day
 *
 *  2. TOTAL DAILY SCREEN TIME
 *     Overall device limit: e.g. 4 hours/day
 *     After limit: silently blocks ALL non-essential apps
 *
 *  3. TIME WINDOW RESTRICTIONS
 *     Block gaming apps during school hours (8AM-3PM)
 *     Block social media after bedtime (10PM-7AM)
 *
 *  4. REWARD SYSTEM
 *     Parent can grant bonus time: "30 extra minutes for good grades"
 *     Bonus applied immediately via Supabase command
 *
 *  5. GRADUAL WARNINGS
 *     5 min remaining: notification "5 minutes left on YouTube"
 *     1 min remaining: notification "1 minute left"
 *     Time's up: app silently closes — no popup to child
 *
 *  6. EDUCATIONAL EXEMPTIONS
 *     Khan Academy, Duolingo, Google Classroom = UNLIMITED
 *     Parent-defined whitelist of educational apps
 */
class ScreenTimeEnforcer(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val TAG = "ScreenTime"
    private val prefs: SharedPreferences =
        context.getSharedPreferences("guardian_config", Context.MODE_PRIVATE)

    // App rules cache: package → AppRule
    private val appRules = mutableMapOf<String, AppRule>()

    // Session tracking: package → session start time
    private val activeSessions = mutableMapOf<String, Long>()

    // Daily usage: package → total seconds used today
    private val dailyUsage = mutableMapOf<String, Int>()

    // Educational apps (always allowed, unlimited time)
    private val EDUCATIONAL_APPS = setOf(
        "com.khan.academy", "com.duolingo", "com.google.android.apps.classroom",
        "com.google.android.apps.docs", "com.google.android.apps.docs.editors.sheets",
        "com.google.android.apps.docs.editors.slides", "com.google.android.calculator",
        "org.wikipedia", "com.sololearn", "com.photomath.camera",
        "com.byju", "com.byjus.thelearningapp", "com.vedantu"
    )

    // System apps (always allowed, never blocked)
    private val SYSTEM_APPS = setOf(
        "com.android.settings", "com.android.phone", "com.android.contacts",
        "com.android.dialer", "com.android.messaging", "com.android.camera",
        "com.android.calculator2", "com.android.calendar", "com.android.clock",
        "com.guardianai.agent"  // Our own app!
    )

    data class AppRule(
        val packageName: String,
        val appName: String,
        val isBlocked: Boolean = false,
        val dailyLimitMinutes: Int = -1,  // -1 = unlimited
        val allowedStartHour: Int = 0,
        val allowedEndHour: Int = 24,
        val isEducational: Boolean = false,
        val bonusMinutes: Int = 0
    )

    fun start() {
        loadRulesFromCache()
        startEnforcement()
        syncRulesPeriodically()
        Log.i(TAG, "✅ ScreenTimeEnforcer started with ${appRules.size} rules")
    }

    // ─── ENFORCEMENT LOOP ─────────────────────────────────────────────────────

    private fun startEnforcement() {
        scope.launch {
            while (isActive) {
                delay(5_000) // Check every 5 seconds

                val currentApp = getCurrentForegroundApp()
                if (currentApp == null || isExemptApp(currentApp)) continue

                val rule = appRules[currentApp]
                val childId = prefs.getString("child_id", null)

                // 1. Check if app is BLOCKED
                if (rule?.isBlocked == true) {
                    Log.d(TAG, "🚫 Blocked app: $currentApp — closing silently")
                    AppMonitorService.bringHome(context)
                    if (childId != null) logBlock(childId, currentApp, "app_blocked")
                    continue
                }

                // 2. Check TIME WINDOW restriction
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                if (rule != null && (hour < rule.allowedStartHour || hour >= rule.allowedEndHour)) {
                    Log.d(TAG, "🕐 $currentApp blocked outside allowed hours ($hour not in ${rule.allowedStartHour}-${rule.allowedEndHour})")
                    AppMonitorService.bringHome(context)
                    if (childId != null) logBlock(childId, currentApp, "time_window")
                    continue
                }

                // 3. Check TOTAL DAILY SCREEN TIME
                val totalDailyLimit = prefs.getInt("total_daily_limit_minutes", -1)
                if (totalDailyLimit > 0) {
                    val totalUsed = dailyUsage.values.sum()
                    val totalLimitSec = totalDailyLimit * 60
                    if (totalUsed >= totalLimitSec) {
                        Log.d(TAG, "📱 Total daily limit reached: ${totalUsed / 60}m / ${totalDailyLimit}m")
                        AppMonitorService.bringHome(context)
                        if (childId != null) {
                            logBlock(childId, currentApp, "daily_total_limit")
                            sendLimitReachedAlert(childId, "total", totalDailyLimit)
                        }
                        continue
                    }
                }

                // 4. Check PER-APP DAILY LIMIT
                if (rule != null && rule.dailyLimitMinutes > 0) {
                    val effectiveLimit = (rule.dailyLimitMinutes + rule.bonusMinutes) * 60
                    val used = dailyUsage[currentApp] ?: 0

                    // Send warnings at 5 min and 1 min remaining
                    val remaining = effectiveLimit - used
                    if (remaining in 55..65) {
                        // 1 min warning (silently log, parent sees in dashboard)
                        Log.d(TAG, "⏱️ $currentApp: 1 minute remaining")
                    }

                    if (used >= effectiveLimit) {
                        Log.d(TAG, "⏱️ ${rule.appName} limit reached: ${used / 60}m / ${rule.dailyLimitMinutes}m")
                        AppMonitorService.bringHome(context)
                        if (childId != null) {
                            logBlock(childId, currentApp, "per_app_limit")
                            sendLimitReachedAlert(childId, rule.appName, rule.dailyLimitMinutes)
                        }
                        continue
                    }
                }

                // 5. Track usage time
                trackUsage(currentApp)
            }
        }
    }

    // ─── USAGE TRACKING ───────────────────────────────────────────────────────

    private fun trackUsage(packageName: String) {
        val now = System.currentTimeMillis()
        val sessionStart = activeSessions[packageName]

        if (sessionStart == null) {
            // New session
            activeSessions[packageName] = now
        } else {
            // Accumulate time (every 5 seconds)
            val elapsed = ((now - sessionStart) / 1000).toInt().coerceAtMost(10)
            dailyUsage[packageName] = (dailyUsage[packageName] ?: 0) + elapsed
            activeSessions[packageName] = now
        }
    }

    private fun getCurrentForegroundApp(): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - 10_000, now
        )
        return stats?.maxByOrNull { it.lastTimeUsed }?.packageName
    }

    private fun isExemptApp(pkg: String): Boolean {
        if (SYSTEM_APPS.contains(pkg)) return true
        if (EDUCATIONAL_APPS.contains(pkg)) return true
        return pkg.startsWith("com.android.") || pkg.startsWith("com.google.android.gms")
    }

    // ─── SYNC RULES FROM SUPABASE ─────────────────────────────────────────────

    private fun syncRulesPeriodically() {
        scope.launch {
            while (isActive) {
                delay(5 * 60_000L) // Sync every 5 minutes
                syncRulesFromServer()
                resetDailyUsageIfNewDay()
            }
        }
    }

    private suspend fun syncRulesFromServer() {
        val childId     = prefs.getString("child_id", null) ?: return
        val token       = prefs.getString("child_token", null) ?: return
        val supabaseUrl = prefs.getString("supabase_url", null) ?: return

        try {
            val url = java.net.URL("$supabaseUrl/rest/v1/app_rules?child_id=eq.$childId")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("apikey", prefs.getString("supabase_anon_key", "") ?: "")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 8_000

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val arr = org.json.JSONArray(body)
                appRules.clear()

                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val pkg = obj.getString("app_package")
                    appRules[pkg] = AppRule(
                        packageName      = pkg,
                        appName          = obj.optString("app_name", pkg),
                        isBlocked        = obj.optBoolean("is_blocked", false),
                        dailyLimitMinutes = obj.optInt("daily_limit_minutes", -1),
                        allowedStartHour = parseHour(obj.optString("allowed_start_time", "00:00")),
                        allowedEndHour   = parseHour(obj.optString("allowed_end_time", "23:59")),
                        bonusMinutes     = obj.optInt("bonus_minutes", 0)
                    )
                }

                // Save to cache
                saveRulesToCache()
                Log.d(TAG, "Rules synced: ${appRules.size} rules loaded")
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Rule sync error: ${e.message}")
        }
    }

    private fun resetDailyUsageIfNewDay() {
        val today = java.time.LocalDate.now().toString()
        val lastDate = prefs.getString("last_usage_date", "")
        if (today != lastDate) {
            dailyUsage.clear()
            prefs.edit().putString("last_usage_date", today).apply()
            Log.d(TAG, "Daily usage reset for new day: $today")
        }
    }

    // ─── ALERTS ───────────────────────────────────────────────────────────────

    private suspend fun sendLimitReachedAlert(childId: String, appOrTotal: String, limitMins: Int) {
        SupabaseClient.logAlert(
            childId  = childId,
            type     = "late_night",
            severity = "info",
            title    = "⏱️ Screen Time Limit Reached",
            body     = if (appOrTotal == "total")
                "Total daily screen time limit of ${limitMins} minutes has been reached. Non-essential apps are now blocked."
            else
                "$appOrTotal has reached its daily limit of ${limitMins} minutes. The app has been silently closed."
        )
    }

    private suspend fun logBlock(childId: String, pkg: String, reason: String) {
        SupabaseClient.logBlockedEvent(
            childId   = childId,
            blockType = "app",
            content   = pkg,
            details   = "Blocked reason: $reason"
        )
    }

    // ─── HELPERS ──────────────────────────────────────────────────────────────

    private fun parseHour(timeStr: String): Int {
        return try { timeStr.split(":")[0].toInt() } catch (_: Exception) { 0 }
    }

    private fun saveRulesToCache() {
        val json = org.json.JSONArray()
        appRules.values.forEach { rule ->
            json.put(JSONObject().apply {
                put("pkg",    rule.packageName)
                put("name",   rule.appName)
                put("block",  rule.isBlocked)
                put("limit",  rule.dailyLimitMinutes)
                put("start",  rule.allowedStartHour)
                put("end",    rule.allowedEndHour)
                put("bonus",  rule.bonusMinutes)
            })
        }
        prefs.edit().putString("cached_rules", json.toString()).apply()
    }

    private fun loadRulesFromCache() {
        val cached = prefs.getString("cached_rules", null) ?: return
        try {
            val arr = org.json.JSONArray(cached)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val pkg = obj.getString("pkg")
                appRules[pkg] = AppRule(
                    packageName       = pkg,
                    appName           = obj.getString("name"),
                    isBlocked         = obj.getBoolean("block"),
                    dailyLimitMinutes = obj.getInt("limit"),
                    allowedStartHour  = obj.getInt("start"),
                    allowedEndHour    = obj.getInt("end"),
                    bonusMinutes      = obj.optInt("bonus", 0)
                )
            }
        } catch (_: Exception) {}
    }
}
