package com.guardianai.agent.services

import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.util.Log
import com.guardianai.agent.utils.SupabaseClient
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * ═══════════════════════════════════════════════════════════
 * WifiGuard — WiFi Security & Trust System
 * ═══════════════════════════════════════════════════════════
 *
 * NO OTHER PARENTAL APP HAS THIS LEVEL OF WIFI CONTROL.
 *
 * Features:
 *  1. TRUSTED NETWORK LIST
 *     Parent defines trusted WiFi (Home, School, Grandma's)
 *     If child connects to untrusted WiFi → ALERT
 *
 *  2. ROGUE HOTSPOT DETECTION
 *     Detects open WiFi with suspicious names:
 *     "Free WiFi", "FreeInternet", "Guest" etc.
 *     These are often used for man-in-middle attacks on children
 *
 *  3. VPN BYPASS DETECTION
 *     If child installs a VPN app to bypass DNS filtering,
 *     WifiGuard detects the VPN connection and alerts parent
 *     (Device Owner mode can also PREVENT VPN installation)
 *
 *  4. NETWORK CHANGE LOGGING
 *     Every WiFi change is logged with BSSID + location
 *     Builds a map of where child goes
 *
 *  5. AUTO-RECONNECT TO FILTERED DNS
 *     If DNS settings change, WifiGuard re-applies Private DNS
 *     pointing to your AdGuard Home server
 */
class WifiGuard(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val TAG = "WifiGuard"
    private val prefs: SharedPreferences =
        context.getSharedPreferences("guardian_config", Context.MODE_PRIVATE)

    private var lastBSSID: String = ""
    private var lastSSID: String  = ""

    // Suspicious open network patterns
    private val ROGUE_PATTERNS = listOf(
        "free wifi", "free internet", "free net", "open wifi",
        "guest", "public wifi", "hotel wifi", "cafe wifi",
        "airport", "starbucks", "mcdonald", "free hotspot",
        "connect free", "no password", "xfinity wifi"
    )

    fun start() {
        monitorWifiChanges()
        monitorVpnStatus()
        monitorDnsIntegrity()
        Log.i(TAG, "✅ WifiGuard started")
    }

    // ─── WIFI MONITORING ──────────────────────────────────────────────────────

    private fun monitorWifiChanges() {
        scope.launch {
            while (isActive) {
                delay(15_000) // Check every 15 seconds
                checkCurrentWifi()
            }
        }
    }

    private suspend fun checkCurrentWifi() {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wifiManager.connectionInfo ?: return

        @Suppress("DEPRECATION")
        val ssid  = info.ssid?.replace("\"", "") ?: return
        val bssid = info.bssid ?: return

        if (ssid == "<unknown ssid>" || ssid == lastSSID) return

        // New network detected
        Log.i(TAG, "📶 WiFi changed: $ssid (BSSID: $bssid)")
        lastSSID  = ssid
        lastBSSID = bssid

        val childId = prefs.getString("child_id", null) ?: return

        // Check if trusted
        val trustedNetworks = prefs.getStringSet("trusted_wifi", emptySet()) ?: emptySet()
        val isTrusted = trustedNetworks.any {
            it.equals(ssid, ignoreCase = true) || it == bssid
        }

        // Check if suspicious open network
        val isRogue = ROGUE_PATTERNS.any { ssid.lowercase().contains(it) }

        if (!isTrusted) {
            val severity = if (isRogue) "critical" else "warning"
            SupabaseClient.logAlert(
                childId  = childId,
                type     = "device_tampering",
                severity = severity,
                title    = if (isRogue) "🚨 Suspicious WiFi Connected!" else "📶 Unknown WiFi Network",
                body     = buildString {
                    append("Network: $ssid\n")
                    if (isRogue) append("⚠️ This looks like a rogue/open hotspot!\n")
                    append("This WiFi is not in the trusted list.\n")
                    append("BSSID: $bssid")
                },
                metadata = JSONObject().apply {
                    put("ssid", ssid)
                    put("bssid", bssid)
                    put("is_trusted", false)
                    put("is_rogue", isRogue)
                    put("lat", prefs.getFloat("last_lat", 0f))
                    put("lng", prefs.getFloat("last_lng", 0f))
                }
            )
        }
    }

    // ─── VPN BYPASS DETECTION ─────────────────────────────────────────────────

    private fun monitorVpnStatus() {
        scope.launch {
            while (isActive) {
                delay(30_000) // Check every 30 seconds
                if (isVpnActive()) {
                    val childId = prefs.getString("child_id", null) ?: continue
                    Log.w(TAG, "🔓 VPN detected! Child may be bypassing DNS filter!")

                    SupabaseClient.logAlert(
                        childId  = childId,
                        type     = "device_tampering",
                        severity = "critical",
                        title    = "🔓 VPN BYPASS DETECTED!",
                        body     = "A VPN connection was detected on child's device.\nThis may be an attempt to bypass content filtering.\nConsider enabling DISALLOW_CONFIG_VPN via Device Owner.",
                        metadata = JSONObject().apply {
                            put("vpn_active", true)
                            put("action", "bypass_attempt")
                        }
                    )

                    // In Device Owner mode, we can force-disconnect VPN
                    try {
                        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                        val component = GuardianAdminReceiver.getComponentName(context)
                        dpm.addUserRestriction(component, android.os.UserManager.DISALLOW_CONFIG_VPN)
                        Log.i(TAG, "VPN restriction applied via Device Owner")
                    } catch (e: Exception) {
                        Log.w(TAG, "Cannot restrict VPN (not Device Owner?)")
                    }

                    delay(600_000) // Don't re-alert for 10 minutes
                }
            }
        }
    }

    private fun isVpnActive(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val networks = cm.allNetworks
        for (network in networks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
                return true
            }
        }
        return false
    }

    // ─── DNS INTEGRITY MONITOR ────────────────────────────────────────────────

    private fun monitorDnsIntegrity() {
        scope.launch {
            while (isActive) {
                delay(60_000) // Check every minute
                verifyDnsSettings()
            }
        }
    }

    private suspend fun verifyDnsSettings() {
        try {
            // Check if Private DNS still points to our AdGuard server
            val expectedDns = prefs.getString("dns_server_hostname", null) ?: return

            // Try resolving a known-blocked domain
            // If it resolves → DNS filter is NOT working
            val testDomains = listOf("pornhub.com", "xvideos.com")
            for (domain in testDomains) {
                try {
                    val addresses = java.net.InetAddress.getAllByName(domain)
                    // If we get a real IP (not 0.0.0.0), DNS filter is broken
                    val hasRealIp = addresses.any {
                        val ip = it.hostAddress
                        ip != null && ip != "0.0.0.0" && ip != "::" && !ip.startsWith("127.")
                    }
                    if (hasRealIp) {
                        Log.w(TAG, "⚠️ DNS filter NOT working! $domain resolved to real IP")
                        reapplyDnsSettings(expectedDns)
                        break
                    }
                } catch (e: java.net.UnknownHostException) {
                    // Good — domain is blocked (can't resolve)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "DNS check error", e)
        }
    }

    private fun reapplyDnsSettings(dnsHostname: String) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val component = GuardianAdminReceiver.getComponentName(context)

            // Re-set Private DNS (Device Owner only)
            dpm.setGlobalPrivateDnsModeSpecifiedHost(component, dnsHostname)
            Log.i(TAG, "✅ DNS re-applied: $dnsHostname")

            val childId = prefs.getString("child_id", null) ?: return
            scope.launch {
                SupabaseClient.logAlert(
                    childId  = childId,
                    type     = "device_tampering",
                    severity = "warning",
                    title    = "🔧 DNS Filter Re-Applied",
                    body     = "DNS filter was bypassed or changed. GuardianAI automatically re-applied the protected DNS settings.",
                    metadata = JSONObject().apply {
                        put("dns_server", dnsHostname)
                        put("action", "auto_reapply")
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot re-apply DNS (not Device Owner?)", e)
        }
    }
}
