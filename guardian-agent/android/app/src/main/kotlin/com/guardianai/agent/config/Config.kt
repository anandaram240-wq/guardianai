package com.guardianai.agent.config

/**
 * Central configuration object for GuardianAgent.
 * All constants are defined here to allow easy modification.
 */
object Config {

    // ===== SUPABASE =====
    const val SUPABASE_URL = "https://fwomjmygsyjhpgqyevad.supabase.co"
    const val SUPABASE_ANON_KEY = "sb_publishable_1n-LflDaCRJXMCStGFKQhg_gHBOTD3V"

    // ===== BACKEND API =====
    // Will be updated to Oracle VPS IP when deployed
    const val API_URL = "http://localhost:3001"

    // ===== POLLING INTERVALS =====
    const val HEARTBEAT_INTERVAL_MS = 30_000L       // 30 seconds
    const val LOCATION_INTERVAL_MS = 60_000L        // 60 seconds
    const val COMMAND_POLL_INTERVAL_MS = 10_000L    // 10 seconds
    const val APP_USAGE_INTERVAL_MS = 300_000L      // 5 minutes
    const val RESTART_DELAY_MS = 5_000L             // 5 seconds restart delay

    // ===== NETWORK =====
    const val DNS_SERVER = "94.140.14.14"           // AdGuard public DNS
    const val DNS_SERVER_SECONDARY = "94.140.15.15" // AdGuard secondary DNS
    const val CONNECT_TIMEOUT_SEC = 30L
    const val READ_TIMEOUT_SEC = 30L

    // ===== NOTIFICATION =====
    const val NOTIFICATION_CHANNEL_ID = "guardian_service"
    const val NOTIFICATION_CHANNEL_NAME = "System Services"
    const val NOTIFICATION_ID = 1001
    const val RESTART_NOTIFICATION_ID = 1002

    // ===== APP META =====
    const val VERSION = "1.0.0"
    const val APP_PACKAGE = "com.guardianai.agent"

    // ===== SHARED PREFERENCES =====
    const val PREFS_NAME = "guardian_prefs"
    const val PREF_CHILD_ID = "child_id"
    const val PREF_FAMILY_ID = "family_id"
    const val PREF_DEVICE_ID = "device_id"
    const val PREF_REGISTERED = "is_registered"
    const val PREF_KNOWN_APPS = "known_apps_json"

    // ===== SUPABASE TABLE NAMES =====
    const val TABLE_CHILDREN = "children"
    const val TABLE_LOCATION_HISTORY = "location_history"
    const val TABLE_APP_USAGE_LOGS = "app_usage_logs"
    const val TABLE_DEVICE_COMMANDS = "device_commands"
    const val TABLE_DEVICE_EVENTS = "device_events"
    const val TABLE_APP_RULES = "app_rules"

    // ===== COMMAND TYPES =====
    const val CMD_LOCK_DEVICE = "lock_device"
    const val CMD_TAKE_PHOTO_FRONT = "take_photo_front"
    const val CMD_TAKE_PHOTO_BACK = "take_photo_back"
    const val CMD_START_AUDIO = "start_audio"
    const val CMD_STOP_AUDIO = "stop_audio"
    const val CMD_EMERGENCY_ALERT = "emergency_alert"
    const val CMD_WIPE_DEVICE = "wipe_device"
    const val CMD_BLOCK_APP = "block_app"
    const val CMD_UNBLOCK_APP = "unblock_app"
    const val CMD_GET_LOCATION = "get_location"

    // ===== COMMAND STATUS =====
    const val STATUS_PENDING = "pending"
    const val STATUS_COMPLETED = "completed"
    const val STATUS_FAILED = "failed"
    const val STATUS_EXECUTING = "executing"

    // ===== WORK MANAGER =====
    const val WORK_COMMAND_SYNC = "command_sync_work"
    const val WORK_PERIODIC_INTERVAL_MIN = 15L
}
