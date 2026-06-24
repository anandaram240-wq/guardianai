package com.guardianai.agent.services

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.guardianai.agent.GuardianApp
import com.guardianai.agent.R
import com.guardianai.agent.config.Config
import com.guardianai.agent.receivers.GuardianAdminReceiver
import com.guardianai.agent.receivers.RestartAlarmReceiver
import com.guardianai.agent.utils.DeviceInfo
import com.guardianai.agent.workers.CommandSyncWorker
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.ByteBuffer
import java.time.Instant
import java.util.Collections

/**
 * GuardianService — the core invisible background agent.
 *
 * Runs as a foreground service with a SILENT, minimized notification.
 * Responsibilities:
 *  - Heartbeat: updates is_online + battery every 30 seconds
 *  - Location: uploads GPS coordinates every 60 seconds
 *  - Command polling: checks device_commands table every 10 seconds
 *  - App monitoring: uploads usage stats every 5 minutes
 *  - Self-restart: schedules AlarmManager restart if killed
 */
class GuardianService : LifecycleService() {

    companion object {
        private const val TAG = "GuardianService"
        private const val CAMERA_THREAD_NAME = "CameraThread"

        var isRunning = false
            private set
    }

    // ===== HELPER SERVICES =====
    private lateinit var locationService: LocationService
    private lateinit var appMonitorService: AppMonitorService

    // ===== COROUTINE JOBS =====
    private var heartbeatJob: Job? = null
    private var locationJob: Job? = null
    private var commandPollJob: Job? = null
    private var appMonitorJob: Job? = null

    // ===== DEVICE ADMIN =====
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    // ===== AUDIO RECORDING =====
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false

    // ===== CHILD ID =====
    private var childId: String? = null

    // ===================================
    // LIFECYCLE
    // ===================================

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "GuardianService onCreate")

        isRunning = true

        locationService = LocationService(this)
        appMonitorService = AppMonitorService(this)

        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, GuardianAdminReceiver::class.java)

        childId = DeviceInfo.getChildIdFromPrefs(this)

        startForegroundWithSilentNotification()
        startAllCoroutines()
        CommandSyncWorker.schedule(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d(TAG, "onStartCommand — source: ${intent?.getStringExtra("source")}")

        // Refresh child ID in case it was just set via SetupActivity
        childId = DeviceInfo.getChildIdFromPrefs(this)

        // If service was killed and restarted, ensure coroutines are running
        if (heartbeatJob?.isActive != true) {
            startAllCoroutines()
        }

        // Make the service sticky — restart if killed
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "GuardianService onDestroy — scheduling restart")
        isRunning = false

        // Cancel all coroutines
        heartbeatJob?.cancel()
        locationJob?.cancel()
        commandPollJob?.cancel()
        appMonitorJob?.cancel()

        // Stop audio if recording
        stopAudioRecording()

        // Schedule an AlarmManager restart in 5 seconds
        scheduleRestart()
    }

    // ===================================
    // FOREGROUND NOTIFICATION (SILENT)
    // ===================================

    /**
     * Starts the service in foreground with a completely silent, collapsed notification.
     * Uses IMPORTANCE_MIN channel + no icon text to be invisible to the child.
     */
    private fun startForegroundWithSilentNotification() {
        val notification = buildSilentNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Config.NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Config.NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(Config.NOTIFICATION_ID, notification)
        }

        Log.d(TAG, "Foreground service started with silent notification")
    }

    private fun buildSilentNotification(): Notification {
        return NotificationCompat.Builder(this, Config.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setAutoCancel(false)
            .build()
    }

    // ===================================
    // COROUTINE STARTERS
    // ===================================

    private fun startAllCoroutines() {
        startHeartbeat()
        startLocationTracking()
        startCommandPolling()
        startAppMonitoring()
    }

    // ===================================
    // HEARTBEAT (every 30 seconds)
    // ===================================

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = lifecycleScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Heartbeat coroutine started")
            while (isActive) {
                try {
                    sendHeartbeat()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat error", e)
                }
                delay(Config.HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private suspend fun sendHeartbeat() {
        val cId = childId ?: return
        val battery = DeviceInfo.getBatteryLevel(this)
        val deviceId = DeviceInfo.getDeviceId(this)

        GuardianApp.supabase.postgrest[Config.TABLE_CHILDREN].update(
            {
                set("is_online", true)
                set("battery_level", battery)
                set("last_seen", Instant.now().toString())
                set("device_id", deviceId)
                set("app_version", Config.VERSION)
            }
        ) {
            filter { eq("id", cId) }
        }

        Log.d(TAG, "Heartbeat sent — battery: $battery%")
    }

    // ===================================
    // LOCATION TRACKING (every 60 seconds)
    // ===================================

    private fun startLocationTracking() {
        locationJob?.cancel()
        locationJob = lifecycleScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Location tracking coroutine started")
            while (isActive) {
                try {
                    trackLocation()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Location tracking error", e)
                }
                delay(Config.LOCATION_INTERVAL_MS)
            }
        }
    }

    private suspend fun trackLocation() {
        val cId = childId ?: return
        val location = locationService.getLastLocation() ?: return

        locationService.uploadLocation(
            childId = cId,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            speed = location.speed,
            altitude = location.altitude
        )
    }

    // ===================================
    // COMMAND POLLING (every 10 seconds)
    // ===================================

    private fun startCommandPolling() {
        commandPollJob?.cancel()
        commandPollJob = lifecycleScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Command polling coroutine started")
            while (isActive) {
                try {
                    pollAndExecuteCommands()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Command poll error", e)
                }
                delay(Config.COMMAND_POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun pollAndExecuteCommands() {
        val cId = childId ?: return

        val commands = GuardianApp.supabase.postgrest[Config.TABLE_DEVICE_COMMANDS]
            .select(Columns.list("id", "command_type", "payload", "status")) {
                filter {
                    eq("child_id", cId)
                    eq("status", Config.STATUS_PENDING)
                }
                limit(10)
            }
            .decodeList<Map<String, String>>()

        for (command in commands) {
            val commandId = command["id"] ?: continue
            val commandType = command["command_type"] ?: continue
            val payload = command["payload"]

            Log.i(TAG, "Executing command: $commandType (id: $commandId)")

            // Mark as executing
            updateCommandStatus(commandId, Config.STATUS_EXECUTING, null)

            try {
                executeCommand(commandType, payload, cId)
                updateCommandStatus(commandId, Config.STATUS_COMPLETED, "Success")
            } catch (e: Exception) {
                Log.e(TAG, "Command execution failed: $commandType", e)
                updateCommandStatus(commandId, Config.STATUS_FAILED, e.message ?: "Unknown error")
            }
        }
    }

    private suspend fun updateCommandStatus(commandId: String, status: String, result: String?) {
        try {
            GuardianApp.supabase.postgrest[Config.TABLE_DEVICE_COMMANDS].update(
                {
                    set("status", status)
                    set("executed_at", Instant.now().toString())
                    if (result != null) set("result", result)
                }
            ) {
                filter { eq("id", commandId) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update command status", e)
        }
    }

    // ===================================
    // APP MONITORING (every 5 minutes)
    // ===================================

    private fun startAppMonitoring() {
        appMonitorJob?.cancel()
        appMonitorJob = lifecycleScope.launch(Dispatchers.IO) {
            Log.d(TAG, "App monitoring coroutine started")
            while (isActive) {
                try {
                    val cId = childId
                    if (cId != null) {
                        appMonitorService.uploadUsageStats(cId)
                        appMonitorService.checkAppRules(cId)
                        appMonitorService.detectNewApps(cId)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "App monitoring error", e)
                }
                delay(Config.APP_USAGE_INTERVAL_MS)
            }
        }
    }

    // ===================================
    // COMMAND EXECUTION
    // ===================================

    private suspend fun executeCommand(commandType: String, payload: String?, childId: String) {
        withContext(Dispatchers.Main) {
            when (commandType) {
                Config.CMD_LOCK_DEVICE -> lockDevice()
                Config.CMD_TAKE_PHOTO_FRONT -> takePhoto(useFrontCamera = true, childId = childId)
                Config.CMD_TAKE_PHOTO_BACK -> takePhoto(useFrontCamera = false, childId = childId)
                Config.CMD_START_AUDIO -> startAudioRecording(childId)
                Config.CMD_STOP_AUDIO -> stopAudioRecording()
                Config.CMD_EMERGENCY_ALERT -> handleEmergencyAlert(childId)
                Config.CMD_GET_LOCATION -> forceLocationUpdate(childId)
                Config.CMD_BLOCK_APP -> { /* Handled via app rules in AppMonitorService */ }
                Config.CMD_UNBLOCK_APP -> { /* Handled via app rules in AppMonitorService */ }
                Config.CMD_WIPE_DEVICE -> wipeDevice()
                else -> Log.w(TAG, "Unknown command type: $commandType")
            }
        }
    }

    // ===================================
    // LOCK DEVICE
    // ===================================

    private fun lockDevice() {
        try {
            if (DeviceInfo.isDeviceAdmin(this)) {
                devicePolicyManager.lockNow()
                Log.i(TAG, "Device locked via DevicePolicyManager")
            } else {
                Log.w(TAG, "Cannot lock — not device admin")
                throw IllegalStateException("Not a device admin")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lock device failed", e)
            throw e
        }
    }

    // ===================================
    // WIPE DEVICE
    // ===================================

    private fun wipeDevice() {
        try {
            if (DeviceInfo.isDeviceOwner(this)) {
                Log.w(TAG, "WIPE DEVICE command received — wiping!")
                devicePolicyManager.wipeData(0)
            } else {
                Log.w(TAG, "Cannot wipe — not device owner")
                throw IllegalStateException("Not a device owner")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Wipe device failed", e)
            throw e
        }
    }

    // ===================================
    // TAKE PHOTO (Camera2)
    // ===================================

    @SuppressLint("MissingPermission")
    private fun takePhoto(useFrontCamera: Boolean, childId: String) {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val cameraId = try {
            cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (useFrontCamera) {
                    facing == CameraCharacteristics.LENS_FACING_FRONT
                } else {
                    facing == CameraCharacteristics.LENS_FACING_BACK
                }
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to enumerate cameras", e)
            return
        }

        if (cameraId == null) {
            Log.w(TAG, "Requested camera not found")
            return
        }

        val handlerThread = HandlerThread(CAMERA_THREAD_NAME).also { it.start() }
        val handler = Handler(handlerThread.looper)

        val imageReader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 1)
        val outputFile = File(
            getExternalFilesDir(null),
            "photo_${System.currentTimeMillis()}.jpg"
        )

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            try {
                val buffer: ByteBuffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                outputFile.writeBytes(bytes)
                Log.i(TAG, "Photo captured: ${outputFile.absolutePath} (${bytes.size} bytes)")

                // Upload photo path to Supabase events
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        GuardianApp.supabase.postgrest[Config.TABLE_DEVICE_EVENTS].insert(
                            buildJsonObject {
                                put("child_id", childId)
                                put("device_id", DeviceInfo.getDeviceId(this@GuardianService))
                                put("event_type", "photo_captured")
                                put("message", "Photo captured: ${outputFile.name}")
                                put("severity", "info")
                                put("metadata", outputFile.absolutePath)
                                put("timestamp", Instant.now().toString())
                            }
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to log photo event", e)
                    }
                }
            } finally {
                image?.close()
                handlerThread.quitSafely()
            }
        }, handler)

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    val surfaces = Collections.singletonList(imageReader.surface)
                    camera.createCaptureSession(
                        surfaces,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                val captureBuilder = camera.createCaptureRequest(
                                    CameraDevice.TEMPLATE_STILL_CAPTURE
                                ).apply {
                                    addTarget(imageReader.surface)
                                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                    set(CaptureRequest.JPEG_QUALITY, 85)
                                }
                                session.capture(
                                    captureBuilder.build(),
                                    object : CameraCaptureSession.CaptureCallback() {},
                                    handler
                                )
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.e(TAG, "Camera session configuration failed")
                                camera.close()
                                handlerThread.quitSafely()
                            }
                        },
                        handler
                    )
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    handlerThread.quitSafely()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    handlerThread.quitSafely()
                }
            }, handler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to open camera", e)
            handlerThread.quitSafely()
        }
    }

    // ===================================
    // AUDIO RECORDING (MediaRecorder)
    // ===================================

    @Suppress("DEPRECATION")
    private fun startAudioRecording(childId: String) {
        if (isRecording) {
            Log.d(TAG, "Audio already recording")
            return
        }

        try {
            val outputFile = File(
                getExternalFilesDir(null),
                "audio_${System.currentTimeMillis()}.m4a"
            )

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            Log.i(TAG, "Audio recording started: ${outputFile.absolutePath}")

            // Auto-stop after 60 seconds
            lifecycleScope.launch {
                delay(60_000)
                if (isRecording) {
                    stopAudioRecording()
                    // Log to Supabase
                    withContext(Dispatchers.IO) {
                        try {
                            GuardianApp.supabase.postgrest[Config.TABLE_DEVICE_EVENTS].insert(
                                buildJsonObject {
                                    put("child_id", childId)
                                    put("device_id", DeviceInfo.getDeviceId(this@GuardianService))
                                    put("event_type", "audio_recorded")
                                    put("message", "60s audio clip recorded")
                                    put("severity", "info")
                                    put("metadata", outputFile.absolutePath)
                                    put("timestamp", Instant.now().toString())
                                }
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to log audio event", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio recording", e)
            isRecording = false
        }
    }

    private fun stopAudioRecording() {
        try {
            if (isRecording && mediaRecorder != null) {
                mediaRecorder?.stop()
                mediaRecorder?.release()
                mediaRecorder = null
                isRecording = false
                Log.i(TAG, "Audio recording stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio recording", e)
            mediaRecorder = null
            isRecording = false
        }
    }

    // ===================================
    // EMERGENCY ALERT
    // ===================================

    private suspend fun handleEmergencyAlert(childId: String) {
        try {
            // Force an immediate location update
            val location = locationService.getLastLocation()
            val lat = location?.latitude
            val lng = location?.longitude

            withContext(Dispatchers.IO) {
                GuardianApp.supabase.postgrest[Config.TABLE_DEVICE_EVENTS].insert(
                    buildJsonObject {
                        put("child_id", childId)
                        put("device_id", DeviceInfo.getDeviceId(this@GuardianService))
                        put("event_type", "emergency_alert")
                        put("message", "EMERGENCY: Parent requested emergency check-in")
                        put("severity", "critical")
                        put("metadata", if (lat != null && lng != null) "$lat,$lng" else "location_unavailable")
                        put("timestamp", Instant.now().toString())
                    }
                )
                Log.w(TAG, "Emergency alert logged — location: $lat, $lng")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Emergency alert failed", e)
        }
    }

    // ===================================
    // FORCE LOCATION UPDATE
    // ===================================

    private suspend fun forceLocationUpdate(childId: String) {
        val location = locationService.getLastLocation() ?: return
        locationService.uploadLocation(
            childId = childId,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            speed = location.speed,
            altitude = location.altitude
        )
        Log.d(TAG, "Forced location update: ${location.latitude}, ${location.longitude}")
    }

    // ===================================
    // SELF-RESTART via AlarmManager
    // ===================================

    /**
     * Schedules a one-shot AlarmManager alarm to restart the service after 5 seconds.
     * This ensures the agent persists even if the OS kills the service.
     */
    private fun scheduleRestart() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val restartIntent = Intent(this, RestartAlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                0,
                restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val triggerTime = SystemClock.elapsedRealtime() + Config.RESTART_DELAY_MS

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }

            Log.d(TAG, "Service restart scheduled in ${Config.RESTART_DELAY_MS}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule service restart", e)
        }
    }
}
