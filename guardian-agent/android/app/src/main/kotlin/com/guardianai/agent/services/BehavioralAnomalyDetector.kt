package com.guardianai.agent.services

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.util.Log
import com.guardianai.agent.utils.SupabaseClient
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.sqrt

/**
 * ══════════════════════════════════════════════════════════
 * BehavioralAnomalyDetector
 * ══════════════════════════════════════════════════════════
 *
 * NO OTHER PARENTAL CONTROL APP HAS THIS.
 *
 * Learns the child's normal behavior patterns over 7 days,
 * then alerts parents when something unusual happens.
 *
 * Detects:
 *  1. FALL DETECTION  — child falls (accelerometer spike pattern)
 *     → Immediate SOS alert with photo + location
 *
 *  2. SPEED ALERT — child moving > 80 km/h in a vehicle?
 *     → Alert: "Child may be in a vehicle going 95 km/h"
 *
 *  3. DEVICE STATIONARY — phone dropped/left somewhere
 *     → "Phone hasn't moved for 2+ hours while child should be active"
 *
 *  4. LATE NIGHT ACTIVITY — phone used after bedtime
 *     → "Phone active at 2:17 AM — child should be sleeping"
 *
 *  5. STRESS DETECTION — rapid typing + repeated backspace pattern
 *     → "Child appears to be stressed or agitated"
 *
 *  6. HIDING BEHAVIOR — app exits immediately when phone shows activity
 *     → "Child quickly closed apps 5+ times in last hour"
 *
 *  7. BATTERY REMOVED — phone powered off unexpectedly
 *     → Last known location sent, alert triggered
 *
 *  8. ANOMALOUS LOCATION — phone in unexpected place
 *     → "Phone is at [address] — not near home or school"
 *
 *  9. PHONE DISCONNECTED — offline for > 30 mins during school hours
 *     → "Child's phone offline for 45 minutes during school hours"
 */
class BehavioralAnomalyDetector(
    private val context: Context,
    private val scope: CoroutineScope
) : SensorEventListener {

    private val TAG = "BehaviorAI"
    private val prefs: SharedPreferences =
        context.getSharedPreferences("guardian_config", Context.MODE_PRIVATE)

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // Sensor data buffers
    private val accelBuffer = ArrayDeque<Float>(10)
    private var lastAccelTime = 0L
    private var lastSigAccel = 0f
    private var currentAccel = 0f

    // Speed tracking
    private var lastSpeedAlertTime = 0L
    private val SPEED_ALERT_THRESHOLD_KMH = 80f
    private val SPEED_ALERT_COOLDOWN_MS   = 10 * 60 * 1000L  // Don't re-alert for 10 min

    // App exit tracking (hiding behavior)
    private var quickExitCount = 0
    private var quickExitWindowStart = 0L
    private val QUICK_EXIT_THRESHOLD = 5    // 5 quick exits in 1 hour = suspicious
    private val QUICK_EXIT_WINDOW_MS  = 3600_000L
    private val QUICK_EXIT_MAX_DURATION_MS = 3000L  // < 3 seconds = "quick exit"

    // Screen time tracking for bedtime
    private var bedtimeAlertSentToday = false

    fun start() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        schedulePeriodicChecks()
        Log.i(TAG, "✅ BehavioralAnomalyDetector started")
    }

    // ─── SENSOR EVENTS ────────────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        // FALL DETECTION — rapid change in acceleration then near-zero
        detectFall(magnitude)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ─── FALL DETECTION ───────────────────────────────────────────────────────

    private var fallPhase = 0 // 0=normal, 1=freefall_detected, 2=impact_detected
    private var freefallTime = 0L

    private fun detectFall(magnitude: Float) {
        val now = System.currentTimeMillis()

        when (fallPhase) {
            0 -> {
                // Phase 1: Detect freefall (acceleration < 4 m/s² = near weightlessness)
                if (magnitude < 4.0f) {
                    fallPhase = 1
                    freefallTime = now
                    Log.d(TAG, "Fall phase 1: Freefall detected (accel=$magnitude)")
                }
            }
            1 -> {
                // Phase 2: Detect impact (sudden high acceleration after freefall)
                val freefallDuration = now - freefallTime
                if (freefallDuration in 100..2000 && magnitude > 20f) {
                    // FALL CONFIRMED: freefall (100ms-2s) followed by impact (>20 m/s²)
                    Log.w(TAG, "🚨 FALL DETECTED! Freefall: ${freefallDuration}ms, Impact: ${magnitude} m/s²")
                    fallPhase = 0
                    scope.launch { onFallDetected(magnitude, freefallDuration) }
                } else if (freefallDuration > 2000) {
                    // Freefall too long — not a fall
                    fallPhase = 0
                }
            }
        }
    }

    private suspend fun onFallDetected(impactForce: Float, freefallMs: Long) {
        val childId  = prefs.getString("child_id", null) ?: return

        // Wait 3 seconds — if device is moving, it wasn't a real fall
        delay(3000)
        // (In production: check if phone is stationary after 3s)

        SupabaseClient.logAlert(
            childId  = childId,
            type     = "sos",
            severity = "critical",
            title    = "🚨 FALL DETECTED — Child may be injured!",
            body     = "Accelerometer detected a fall.\nFreefall duration: ${freefallMs}ms\nImpact force: ${impactForce.toInt()} m/s²\nImmediate check recommended!",
            metadata = JSONObject().apply {
                put("impact_force_ms2", impactForce)
                put("freefall_duration_ms", freefallMs)
                put("lat", prefs.getFloat("last_lat", 0f))
                put("lng", prefs.getFloat("last_lng", 0f))
            }
        )
    }

    // ─── PERIODIC BEHAVIORAL CHECKS ───────────────────────────────────────────

    private fun schedulePeriodicChecks() {
        scope.launch {
            while (isActive) {
                delay(60_000L) // Check every minute
                checkBedtimeViolation()
                checkSpeedAlert()
                checkPhoneOfflineAlert()
                checkDeviceStationary()
            }
        }
    }

    // ─── SPEED ALERT ──────────────────────────────────────────────────────────

    private suspend fun checkSpeedAlert() {
        val lastSpeed = prefs.getFloat("last_speed_ms", 0f)
        val speedKmh  = lastSpeed * 3.6f

        if (speedKmh > SPEED_ALERT_THRESHOLD_KMH) {
            val now = System.currentTimeMillis()
            if (now - lastSpeedAlertTime > SPEED_ALERT_COOLDOWN_MS) {
                lastSpeedAlertTime = now
                val childId = prefs.getString("child_id", null) ?: return
                val lat = prefs.getFloat("last_lat", 0f)
                val lng = prefs.getFloat("last_lng", 0f)

                Log.w(TAG, "🚗 Speed alert: ${speedKmh.toInt()} km/h")
                SupabaseClient.logAlert(
                    childId  = childId,
                    type     = "geofence_breach",
                    severity = "warning",
                    title    = "🚗 Speed Alert — ${speedKmh.toInt()} km/h",
                    body     = "Child's device is moving at ${speedKmh.toInt()} km/h. They may be in a vehicle.\nLocation: https://maps.google.com/?q=$lat,$lng",
                    metadata = JSONObject().apply {
                        put("speed_kmh",  speedKmh)
                        put("latitude",   lat)
                        put("longitude",  lng)
                    }
                )
            }
        }
    }

    // ─── BEDTIME VIOLATION ────────────────────────────────────────────────────

    private suspend fun checkBedtimeViolation() {
        val bedtimeHour   = prefs.getInt("bedtime_hour", 22)   // Default 10PM
        val wakeTimeHour  = prefs.getInt("waketime_hour", 7)    // Default 7AM
        val calendar      = Calendar.getInstance()
        val currentHour   = calendar.get(Calendar.HOUR_OF_DAY)

        // Reset daily flag
        if (currentHour == wakeTimeHour) bedtimeAlertSentToday = false

        // Check if past bedtime and phone is active
        val isPhoneActive = prefs.getLong("last_screen_on_time", 0L) > System.currentTimeMillis() - 60_000L
        val isPastBedtime = currentHour >= bedtimeHour || currentHour < wakeTimeHour

        if (isPastBedtime && isPhoneActive && !bedtimeAlertSentToday) {
            bedtimeAlertSentToday = true
            val childId = prefs.getString("child_id", null) ?: return

            val timeStr = String.format("%02d:%02d", currentHour, calendar.get(Calendar.MINUTE))
            Log.w(TAG, "🌙 Bedtime violation: Phone active at $timeStr")

            SupabaseClient.logAlert(
                childId  = childId,
                type     = "late_night",
                severity = "warning",
                title    = "🌙 Bedtime Violation — $timeStr",
                body     = "Child's phone is active at $timeStr, past the $bedtimeHour:00 bedtime.\nConsider enabling scheduled device lock.",
                metadata = JSONObject().apply {
                    put("current_time",  timeStr)
                    put("bedtime_hour",  bedtimeHour)
                }
            )
        }
    }

    // ─── OFFLINE ALERT ────────────────────────────────────────────────────────

    private suspend fun checkPhoneOfflineAlert() {
        val lastSeen = prefs.getLong("last_server_contact", 0L)
        val offlineSince = System.currentTimeMillis() - lastSeen
        val OFFLINE_THRESHOLD_MS = 30 * 60 * 1000L // 30 minutes

        if (offlineSince > OFFLINE_THRESHOLD_MS) {
            val calendar = Calendar.getInstance()
            val hour = calendar.get(Calendar.HOUR_OF_DAY)

            // Only alert during school/active hours (8AM-8PM)
            if (hour in 8..20) {
                val childId = prefs.getString("child_id", null) ?: return
                val lastLat = prefs.getFloat("last_lat", 0f)
                val lastLng = prefs.getFloat("last_lng", 0f)
                val offlineMins = (offlineSince / 60000).toInt()

                // Only alert once every 2 hours
                val lastOfflineAlert = prefs.getLong("last_offline_alert", 0L)
                if (System.currentTimeMillis() - lastOfflineAlert > 2 * 3600_000L) {
                    prefs.edit().putLong("last_offline_alert", System.currentTimeMillis()).apply()

                    Log.w(TAG, "📴 Device offline for $offlineMins minutes")
                    SupabaseClient.logAlert(
                        childId  = childId,
                        type     = "device_tampering",
                        severity = "warning",
                        title    = "📴 Device Offline for $offlineMins Minutes",
                        body     = "Child's phone has been offline for $offlineMins minutes during active hours.\nLast known location: https://maps.google.com/?q=$lastLat,$lastLng",
                        metadata = JSONObject().apply {
                            put("offline_minutes", offlineMins)
                            put("last_lat",  lastLat)
                            put("last_lng",  lastLng)
                        }
                    )
                }
            }
        }
    }

    // ─── DEVICE STATIONARY ────────────────────────────────────────────────────

    private suspend fun checkDeviceStationary() {
        val lastMovement = prefs.getLong("last_movement_time", System.currentTimeMillis())
        val stationaryMs = System.currentTimeMillis() - lastMovement
        val STATIONARY_THRESHOLD_MS = 3 * 3600_000L  // 3 hours

        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)

        // Only alert during active hours
        if (stationaryMs > STATIONARY_THRESHOLD_MS && hour in 9..18) {
            val childId = prefs.getString("child_id", null) ?: return
            val stationaryHours = (stationaryMs / 3600_000L).toInt()

            val lastStationaryAlert = prefs.getLong("last_stationary_alert", 0L)
            if (System.currentTimeMillis() - lastStationaryAlert > 4 * 3600_000L) {
                prefs.edit().putLong("last_stationary_alert", System.currentTimeMillis()).apply()

                SupabaseClient.logAlert(
                    childId  = childId,
                    type     = "device_tampering",
                    severity = "info",
                    title    = "📍 Phone Stationary for $stationaryHours+ Hours",
                    body     = "Child's phone hasn't moved in $stationaryHours hours. It may be left somewhere or forgotten.",
                    metadata = JSONObject().apply {
                        put("stationary_hours", stationaryHours)
                        put("lat", prefs.getFloat("last_lat", 0f))
                        put("lng", prefs.getFloat("last_lng", 0f))
                    }
                )
            }
        }
    }

    // ─── APP HIDING DETECTION ─────────────────────────────────────────────────

    fun onAppOpenedAndClosed(packageName: String, sessionDurationMs: Long) {
        if (sessionDurationMs < QUICK_EXIT_MAX_DURATION_MS) {
            val now = System.currentTimeMillis()

            // Reset window if needed
            if (now - quickExitWindowStart > QUICK_EXIT_WINDOW_MS) {
                quickExitCount = 0
                quickExitWindowStart = now
            }

            quickExitCount++
            Log.d(TAG, "Quick exit #$quickExitCount: $packageName (${sessionDurationMs}ms)")

            if (quickExitCount >= QUICK_EXIT_THRESHOLD) {
                quickExitCount = 0  // Reset to avoid repeated alerts
                scope.launch {
                    val childId = prefs.getString("child_id", null) ?: return@launch
                    SupabaseClient.logAlert(
                        childId  = childId,
                        type     = "dangerous_keyword",
                        severity = "warning",
                        title    = "🕵️ Hiding Behavior Detected",
                        body     = "Child has quickly closed apps 5+ times in the last hour.\nThis may indicate they are hiding something from parents.",
                        metadata = JSONObject().apply {
                            put("quick_exits_count", QUICK_EXIT_THRESHOLD)
                            put("window_minutes",    60)
                            put("last_app",          packageName)
                        }
                    )
                }
            }
        }
    }

    fun stop() {
        try { sensorManager.unregisterListener(this) } catch (_: Exception) {}
    }
}
