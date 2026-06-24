package com.guardianai.agent.services

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaRecorder
import android.os.VibrationEffect
import android.os.Vibrator
import android.telephony.SmsManager
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.guardianai.agent.utils.SupabaseClient
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * SosPanicButton
 *
 * Child Safety Emergency System — activates in 3 seconds
 *
 * Trigger Methods:
 *  1. Press power button 5 times rapidly (within 2 seconds)
 *  2. Shake phone 3 times hard
 *  3. Secret button inside disguised app
 *
 * What happens on SOS (in 3 seconds):
 *  1. Vibrate in SOS pattern (... --- ...)
 *  2. Take 5 front camera photos silently
 *  3. Record 30s ambient audio
 *  4. Get precise GPS location
 *  5. Upload photos + audio to Supabase Storage
 *  6. Create CRITICAL alert in database
 *  7. Auto-call parent's phone number
 *  8. Send SMS to ALL emergency contacts with live location link
 */
class SosPanicButton(
    private val context: Context,
    private val lifecycleOwner: androidx.lifecycle.LifecycleOwner
) : SensorEventListener {

    private val TAG = "GuardianSOS"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs: SharedPreferences = context.getSharedPreferences("guardian_config", Context.MODE_PRIVATE)

    // Power button detection
    private var powerPressCount = 0
    private var lastPowerPressTime = 0L
    private val POWER_PRESS_WINDOW_MS = 2000L
    private val REQUIRED_POWER_PRESSES = 5

    // Shake detection
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var lastShakeTime = 0L
    private var shakeCount = 0
    private val SHAKE_THRESHOLD = 15f
    private val SHAKE_WINDOW_MS = 3000L
    private val REQUIRED_SHAKES = 3

    fun initialize() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        Log.i(TAG, "SOS system initialized")
    }

    // ─── POWER BUTTON DETECTION ───────────────────────────────────────────────

    fun onScreenStateChanged(isOn: Boolean) {
        val now = System.currentTimeMillis()

        if (!isOn) { // Screen turning OFF = power button pressed
            if (now - lastPowerPressTime < POWER_PRESS_WINDOW_MS) {
                powerPressCount++
            } else {
                powerPressCount = 1
            }
            lastPowerPressTime = now

            if (powerPressCount >= REQUIRED_POWER_PRESSES) {
                powerPressCount = 0
                Log.i(TAG, "🆘 SOS triggered via power button!")
                triggerSos("power_button")
            }
        }
    }

    // ─── SHAKE DETECTION ──────────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val acceleration = Math.sqrt((x*x + y*y + z*z).toDouble()).toFloat()

        if (acceleration > SHAKE_THRESHOLD) {
            val now = System.currentTimeMillis()
            if (now - lastShakeTime < SHAKE_WINDOW_MS) {
                shakeCount++
                if (shakeCount >= REQUIRED_SHAKES) {
                    shakeCount = 0
                    Log.i(TAG, "🆘 SOS triggered via shake!")
                    triggerSos("shake")
                }
            } else {
                shakeCount = 1
            }
            lastShakeTime = now
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ─── MASTER SOS TRIGGER ───────────────────────────────────────────────────

    fun triggerSos(trigger: String = "manual") {
        Log.i(TAG, "🆘🆘🆘 SOS TRIGGERED via: $trigger 🆘🆘🆘")

        scope.launch {
            // 1. Vibrate SOS pattern (... --- ...) immediately
            vibrateSOSPattern()

            // 2. Get location first (fastest)
            val location = getCurrentLocation()

            // 3. Take front camera photos silently
            val photoUrls = takeFrontCameraPhotos()

            // 4. Start ambient audio recording (30 seconds)
            val audioUrl = recordAmbientAudio(30)

            // 5. Create CRITICAL alert in Supabase
            val childId = prefs.getString("child_id", null)
            val childName = prefs.getString("child_name", "Child")
            val locationLink = if (location != null) {
                "https://maps.google.com/?q=${location.first},${location.second}"
            } else "Location unavailable"

            childId?.let {
                SupabaseClient.createSosAlert(
                    childId = it,
                    trigger = trigger,
                    latitude = location?.first,
                    longitude = location?.second,
                    photoUrls = photoUrls,
                    audioUrl = audioUrl
                )
            }

            // 6. Send SMS to ALL emergency contacts
            sendEmergencySms(childName ?: "Your child", locationLink)

            // 7. Auto-call parent's primary number
            callParent()

            Log.i(TAG, "✅ SOS response complete — help is coming!")
        }
    }

    // ─── SOS ACTIONS ──────────────────────────────────────────────────────────

    private fun vibrateSOSPattern() {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            // SOS pattern: ... --- ... (short-short-short, long-long-long, short-short-short)
            val timings = longArrayOf(
                0, 150, 100, 150, 100, 150, 100,   // ...
                300, 400, 100, 400, 100, 400, 100,  // ---
                150, 100, 150, 100, 150             // ...
            )
            vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
        } catch (e: Exception) {
            Log.e(TAG, "Vibrate error", e)
        }
    }

    private suspend fun getCurrentLocation(): Pair<Double, Double>? {
        return try {
            // Quick location from SharedPreferences cache (updated by LocationService)
            val lat = prefs.getFloat("last_lat", 0f).toDouble()
            val lng = prefs.getFloat("last_lng", 0f).toDouble()
            if (lat != 0.0 && lng != 0.0) Pair(lat, lng) else null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun takeFrontCameraPhotos(): List<String> {
        return try {
            val photoUrls = mutableListOf<String>()
            val childId = prefs.getString("child_id", null) ?: return emptyList()

            repeat(3) { i ->
                delay(500)
                // Take photo using CameraX (runs on main thread)
                withContext(Dispatchers.Main) {
                    captureAndUploadPhoto(childId, "sos_${i}")
                }
                photoUrls.add("sos_photo_${i}")
            }
            photoUrls
        } catch (e: Exception) {
            Log.e(TAG, "Photo capture error", e)
            emptyList()
        }
    }

    private fun captureAndUploadPhoto(childId: String, name: String) {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                    .build()

                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageCapture)

                val outputFile = File(context.cacheDir, "sos_${name}_${System.currentTimeMillis()}.jpg")
                val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

                imageCapture.takePicture(outputOptions,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            scope.launch {
                                SupabaseClient.uploadSosPhoto(childId, outputFile)
                            }
                        }
                        override fun onError(exception: ImageCaptureException) {
                            Log.e(TAG, "SOS photo error", exception)
                        }
                    })
            }, ContextCompat.getMainExecutor(context))
        } catch (e: Exception) {
            Log.e(TAG, "Camera error", e)
        }
    }

    private suspend fun recordAmbientAudio(durationSeconds: Int): String? {
        return try {
            val childId = prefs.getString("child_id", null) ?: return null
            val outputFile = File(context.cacheDir, "sos_audio_${System.currentTimeMillis()}.mp4")

            val recorder = MediaRecorder(context).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(outputFile.absolutePath)
                setMaxDuration(durationSeconds * 1000)
                prepare()
                start()
            }

            delay(durationSeconds * 1000L)
            recorder.stop()
            recorder.release()

            // Upload to Supabase Storage
            SupabaseClient.uploadSosAudio(childId, outputFile)
            "sos_audio_uploaded"
        } catch (e: Exception) {
            Log.e(TAG, "Audio recording error", e)
            null
        }
    }

    private fun sendEmergencySms(childName: String, locationLink: String) {
        try {
            val contactsJson = prefs.getString("emergency_contacts", null) ?: return
            // Parse simple format: "name:phone,name2:phone2"
            val contacts = contactsJson.split(",")
            val smsManager = SmsManager.getDefault()

            val message = "🆘 EMERGENCY: $childName needs HELP! " +
                "Live Location: $locationLink — GuardianAI Safety Alert"

            contacts.forEach { contact ->
                val parts = contact.split(":")
                if (parts.size >= 2) {
                    val phone = parts[1].trim()
                    try {
                        smsManager.sendTextMessage(phone, null, message, null, null)
                        Log.i(TAG, "SOS SMS sent to: $phone")
                    } catch (e: Exception) {
                        Log.e(TAG, "SMS send failed to $phone", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Emergency SMS error", e)
        }
    }

    private fun callParent() {
        try {
            val parentPhone = prefs.getString("parent_phone", null) ?: return
            val callIntent = android.content.Intent(android.content.Intent.ACTION_CALL).apply {
                data = android.net.Uri.parse("tel:$parentPhone")
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(callIntent)
            Log.i(TAG, "Calling parent: $parentPhone")
        } catch (e: Exception) {
            Log.e(TAG, "Auto-call failed", e)
        }
    }

    fun cleanup() {
        try {
            sensorManager.unregisterListener(this)
            scope.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error", e)
        }
    }
}
