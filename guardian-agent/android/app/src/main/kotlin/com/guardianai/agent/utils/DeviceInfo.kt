package com.guardianai.agent.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.SharedPreferences
import com.guardianai.agent.config.Config
import com.guardianai.agent.receivers.GuardianAdminReceiver

/**
 * Utility object providing device-level information and shared preferences helpers.
 */
object DeviceInfo {

    /**
     * Returns a stable unique device identifier using ANDROID_ID.
     * Falls back to a generated UUID stored in prefs if unavailable.
     */
    fun getDeviceId(context: Context): String {
        return try {
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )
            if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
                androidId
            } else {
                getOrCreateFallbackDeviceId(context)
            }
        } catch (e: Exception) {
            getOrCreateFallbackDeviceId(context)
        }
    }

    private fun getOrCreateFallbackDeviceId(context: Context): String {
        val prefs = getPrefs(context)
        var id = prefs.getString(Config.PREF_DEVICE_ID, null)
        if (id == null) {
            id = java.util.UUID.randomUUID().toString()
            prefs.edit().putString(Config.PREF_DEVICE_ID, id).apply()
        }
        return id
    }

    /**
     * Returns manufacturer and model name (e.g. "Samsung Galaxy S21").
     */
    fun getDeviceModel(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }
    }

    /**
     * Returns Android OS version string (e.g. "13").
     */
    fun getAndroidVersion(): String {
        return Build.VERSION.RELEASE
    }

    /**
     * Returns Android SDK int.
     */
    fun getSdkVersion(): Int {
        return Build.VERSION.SDK_INT
    }

    /**
     * Returns battery level as a percentage (0-100), or -1 if unavailable.
     */
    fun getBatteryLevel(context: Context): Int {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            // Fallback using broadcast
            try {
                val intent = ContextCompat.registerReceiver(
                    context,
                    null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level == -1 || scale == -1) -1
                else (level * 100 / scale)
            } catch (e2: Exception) {
                -1
            }
        }
    }

    /**
     * Returns true if battery is currently charging.
     */
    fun isCharging(context: Context): Boolean {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            batteryManager.isCharging
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns true if this app is the device owner.
     */
    fun isDeviceOwner(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isDeviceOwnerApp(context.packageName)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns true if this app is an active device administrator.
     */
    fun isDeviceAdmin(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, GuardianAdminReceiver::class.java)
            dpm.isAdminActive(adminComponent)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns the child ID from SharedPreferences, or null if not set.
     */
    fun getChildIdFromPrefs(context: Context): String? {
        return getPrefs(context).getString(Config.PREF_CHILD_ID, null)
    }

    /**
     * Saves the child ID to SharedPreferences.
     */
    fun saveChildId(context: Context, id: String) {
        getPrefs(context).edit().putString(Config.PREF_CHILD_ID, id).apply()
    }

    /**
     * Returns the family ID from SharedPreferences, or null if not set.
     */
    fun getFamilyIdFromPrefs(context: Context): String? {
        return getPrefs(context).getString(Config.PREF_FAMILY_ID, null)
    }

    /**
     * Saves the family ID to SharedPreferences.
     */
    fun saveFamilyId(context: Context, id: String) {
        getPrefs(context).edit().putString(Config.PREF_FAMILY_ID, id).apply()
    }

    /**
     * Returns true if the device has been registered with Supabase.
     */
    fun isRegistered(context: Context): Boolean {
        return getPrefs(context).getBoolean(Config.PREF_REGISTERED, false)
    }

    /**
     * Marks the device as registered.
     */
    fun setRegistered(context: Context, registered: Boolean) {
        getPrefs(context).edit().putBoolean(Config.PREF_REGISTERED, registered).apply()
    }

    /**
     * Returns a map of basic device info for registration.
     */
    fun getDeviceInfoMap(context: Context): Map<String, String> {
        return mapOf(
            "device_id" to getDeviceId(context),
            "device_model" to getDeviceModel(),
            "android_version" to getAndroidVersion(),
            "sdk_version" to getSdkVersion().toString(),
            "battery_level" to getBatteryLevel(context).toString(),
            "app_version" to Config.VERSION
        )
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
    }
}
