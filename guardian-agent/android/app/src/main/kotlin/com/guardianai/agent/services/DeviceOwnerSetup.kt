package com.guardianai.agent.services

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.UserManager
import android.util.Log
import com.guardianai.agent.receivers.GuardianAdminReceiver

/**
 * DeviceOwnerSetup
 *
 * Handles all Device Owner (MDM) level operations.
 * Once set as Device Owner via ADB, this class can:
 *  - Set Always-On VPN without any user consent popup
 *  - Configure Private DNS silently (no VPN key icon in status bar)
 *  - Lock ALL bypass attempts (VPN, safe boot, factory reset, ADB)
 *  - Hide the app icon from the launcher
 *  - Prevent uninstallation
 *
 * One-time ADB setup command (parent runs once):
 *   adb shell dpm set-device-owner com.guardianai.agent/.receivers.GuardianAdminReceiver
 */
object DeviceOwnerSetup {

    private const val TAG = "GuardianDeviceOwner"

    /**
     * Check if GuardianAI is the Device Owner of this device.
     * Returns true if full MDM control is available.
     */
    fun isDeviceOwner(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, GuardianAdminReceiver::class.java)
            dpm.isDeviceOwnerApp(context.packageName)
        } catch (e: Exception) {
            Log.e(TAG, "isDeviceOwner check failed", e)
            false
        }
    }

    /**
     * Set Private DNS to GuardianAI's AdGuard Home server.
     *
     * CRITICAL: This is how Ghost Shield works with ZERO notification.
     * - Private DNS routes ALL DNS queries through AdGuard Home
     * - AdGuard blocks 3M+ adult/harmful domains silently
     * - Returns 0.0.0.0 for blocked domains → browser silently fails
     * - Child sees: nothing / blank / Google (if we redirect)
     * - NO VPN key icon. NO notification. ZERO disruption. ✅
     */
    fun setPrivateDns(context: Context, dnsHostname: String): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, GuardianAdminReceiver::class.java)

            if (!dpm.isDeviceOwnerApp(context.packageName)) {
                Log.w(TAG, "Not device owner — cannot set Private DNS silently")
                return false
            }

            // Set mode to 'hostname' (use custom DNS-over-TLS server)
            dpm.setGlobalSetting(adminComponent, "private_dns_mode", "hostname")
            // Point to our AdGuard Home DoT server (Oracle free VM)
            dpm.setGlobalSetting(adminComponent, "private_dns_specifier", dnsHostname)

            Log.i(TAG, "✅ Private DNS set to: $dnsHostname")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set Private DNS", e)
            false
        }
    }

    /**
     * Apply ALL UserManager restrictions.
     * This locks down every possible bypass method the child could use.
     */
    fun setupAllRestrictions(context: Context) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, GuardianAdminReceiver::class.java)

            if (!dpm.isDeviceOwnerApp(context.packageName)) {
                Log.w(TAG, "Not device owner — restrictions limited")
                return
            }

            val restrictions = listOf(
                // DNS & VPN bypass prevention
                UserManager.DISALLOW_CONFIG_VPN,
                UserManager.DISALLOW_CONFIG_PRIVATE_DNS,

                // System bypass prevention
                UserManager.DISALLOW_SAFE_BOOT,
                UserManager.DISALLOW_FACTORY_RESET,
                UserManager.DISALLOW_ADD_USER,
                UserManager.DISALLOW_REMOVE_USER,

                // ADB/developer bypass prevention
                UserManager.DISALLOW_DEBUGGING_FEATURES,
                UserManager.DISALLOW_USB_FILE_TRANSFER,

                // Network bypass prevention
                UserManager.DISALLOW_AIRPLANE_MODE,
                UserManager.DISALLOW_CONFIG_WIFI,
                UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS,
                UserManager.DISALLOW_CONFIG_TETHERING,

                // App bypass prevention
                UserManager.DISALLOW_INSTALL_APPS,
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                UserManager.DISALLOW_UNINSTALL_APPS,

                // Account bypass prevention
                UserManager.DISALLOW_MODIFY_ACCOUNTS,
                UserManager.DISALLOW_ADD_MANAGED_PROFILE,

                // Settings bypass prevention
                UserManager.DISALLOW_CONFIG_DATE_TIME,
            )

            for (restriction in restrictions) {
                try {
                    dpm.addUserRestriction(adminComponent, restriction)
                    Log.d(TAG, "Applied restriction: $restriction")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not apply restriction $restriction: ${e.message}")
                }
            }

            Log.i(TAG, "✅ All ${restrictions.size} bypass restrictions applied")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply restrictions", e)
        }
    }

    /**
     * Set Always-On VPN programmatically (NO consent dialog shown to child).
     * Requires Device Owner mode.
     * Uses lockdown=true so child cannot disable or bypass.
     */
    fun setAlwaysOnVpn(context: Context, vpnPackageName: String): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, GuardianAdminReceiver::class.java)

            if (!dpm.isDeviceOwnerApp(context.packageName)) {
                Log.w(TAG, "Not device owner — cannot set Always-On VPN silently")
                return false
            }

            // lockdown=true: if VPN drops, all network access is blocked
            // This prevents bypass by killing the VPN connection
            dpm.setAlwaysOnVpnPackage(adminComponent, vpnPackageName, true)
            Log.i(TAG, "✅ Always-On VPN set for: $vpnPackageName (lockdown enabled)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set Always-On VPN", e)
            false
        }
    }

    /**
     * Hide app icon from the launcher.
     * GuardianAI becomes completely invisible to the child.
     * Parent can still access via secret gesture (5x power button or shake).
     */
    fun hideAppIcon(context: Context) {
        try {
            val pm = context.packageManager
            // Disable the launcher activity — removes from app drawer
            pm.setComponentEnabledSetting(
                ComponentName(context, "com.guardianai.agent.ui.MainActivity"),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.i(TAG, "✅ App icon hidden from launcher")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide app icon", e)
        }
    }

    /**
     * Show app icon (for parent to re-access settings).
     * Called when parent unlocks with PIN.
     */
    fun showAppIcon(context: Context) {
        try {
            val pm = context.packageManager
            pm.setComponentEnabledSetting(
                ComponentName(context, "com.guardianai.agent.ui.MainActivity"),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.i(TAG, "App icon shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show app icon", e)
        }
    }

    /**
     * Master setup function. Call this during initial app setup.
     * Parent must have run the ADB command first.
     *
     * @param dnsHostname  Your AdGuard Home DoT server (e.g. "1.2.3.4" or "filter.guardianai.app")
     */
    fun setupAll(context: Context, dnsHostname: String) {
        Log.i(TAG, "=== Starting GuardianAI Device Owner Setup ===")

        val isOwner = isDeviceOwner(context)
        Log.i(TAG, "Device Owner status: $isOwner")

        if (isOwner) {
            // Step 1: Set Private DNS (Ghost Shield - no VPN notification!)
            val dnsSet = setPrivateDns(context, dnsHostname)
            Log.i(TAG, "Private DNS configured: $dnsSet")

            // Step 2: Lock all bypass methods
            setupAllRestrictions(context)

            // Step 3: Hide app icon
            hideAppIcon(context)

            Log.i(TAG, "=== GuardianAI Setup Complete (Device Owner Mode) ===")
        } else {
            Log.w(TAG, "=== Device Owner NOT set. Run ADB command: ===")
            Log.w(TAG, "adb shell dpm set-device-owner com.guardianai.agent/.receivers.GuardianAdminReceiver")
            Log.w(TAG, "Fallback: Some features will require manual permission")
        }
    }
}
