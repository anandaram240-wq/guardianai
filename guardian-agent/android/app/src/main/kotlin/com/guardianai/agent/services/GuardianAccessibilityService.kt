package com.guardianai.agent.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.guardianai.agent.ai.ContentAIEngine
import com.guardianai.agent.ai.TextAnalysisResult
import com.guardianai.agent.utils.SupabaseClient
import kotlinx.coroutines.*

/**
 * ═══════════════════════════════════════════════════════════
 * GuardianAccessibilityService — MOST POWERFUL FEATURE
 * ═══════════════════════════════════════════════════════════
 *
 * Uses Android Accessibility API to READ the SCREEN CONTENT
 * of every app in real-time — even encrypted apps like WhatsApp.
 *
 * This is how Bark, the most advanced parental app, works.
 * We do it better — 100% on-device with TFLite AI analysis.
 *
 * What it reads:
 *  ✅ WhatsApp messages (incoming AND outgoing)
 *  ✅ Instagram DMs and comments
 *  ✅ TikTok captions and comments
 *  ✅ Telegram messages
 *  ✅ Snapchat messages (text only)
 *  ✅ YouTube video titles being watched
 *  ✅ Browser URL bar (detects adult sites even in incognito)
 *  ✅ ANY text shown on screen
 *  ✅ Keyboard input (what child is typing)
 *
 * Detection:
 *  - Grooming phrases → CRITICAL ALERT
 *  - Cyberbullying keywords → WARNING ALERT
 *  - Self-harm language → CRITICAL ALERT
 *  - Adult content text → WARNING ALERT
 *  - Suspicious URLs → DNS block + ALERT
 *
 * Child experience: ZERO. They see nothing. ✅
 */
class GuardianAccessibilityService : AccessibilityService() {

    private val TAG = "GuardianA11y"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var aiEngine: ContentAIEngine
    private lateinit var prefs: android.content.SharedPreferences

    // Deduplication — don't alert same phrase twice in 5 minutes
    private val recentAlerts = mutableMapOf<String, Long>()
    private val DEDUP_WINDOW_MS = 5 * 60 * 1000L

    // Platform-specific node IDs for reading messages
    private val PLATFORM_CONFIGS = mapOf(
        "com.whatsapp" to PlatformConfig(
            messageNodeId   = "com.whatsapp:id/message_text",
            inputNodeId     = "com.whatsapp:id/entry",
            senderNodeId    = "com.whatsapp:id/conversation_contact_name",
            platform        = "whatsapp"
        ),
        "com.instagram.android" to PlatformConfig(
            messageNodeId   = "com.instagram.android:id/direct_text_message_text_view",
            inputNodeId     = "com.instagram.android:id/row_thread_composer_edittext",
            senderNodeId    = null,
            platform        = "instagram"
        ),
        "org.telegram.messenger" to PlatformConfig(
            messageNodeId   = "org.telegram.messenger:id/message_text",
            inputNodeId     = "org.telegram.messenger:id/messageEditText",
            senderNodeId    = null,
            platform        = "telegram"
        ),
        "com.snapchat.android" to PlatformConfig(
            messageNodeId   = "com.snapchat.android:id/chat_message_text",
            inputNodeId     = null,
            senderNodeId    = null,
            platform        = "snapchat"
        ),
        "com.zhiliaoapp.musically" to PlatformConfig( // TikTok
            messageNodeId   = "com.zhiliaoapp.musically:id/comment_text",
            inputNodeId     = null,
            senderNodeId    = null,
            platform        = "tiktok"
        ),
        "com.google.android.youtube" to PlatformConfig(
            messageNodeId   = "com.google.android.youtube:id/title",
            inputNodeId     = null,
            senderNodeId    = null,
            platform        = "youtube"
        ),
    )

    // ─── SERVICE SETUP ────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs     = getSharedPreferences("guardian_config", Context.MODE_PRIVATE)
        aiEngine  = ContentAIEngine(this)
        aiEngine.initialize()

        serviceInfo = AccessibilityServiceInfo().apply {
            // Listen to ALL apps for text changes
            packageNames = null  // null = ALL packages
            eventTypes   = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                           AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                           AccessibilityEvent.TYPE_VIEW_SCROLLED or
                           AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                           AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags        = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                           AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                           AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
            notificationTimeout = 100
        }

        Log.i(TAG, "✅ GuardianAI Accessibility Service connected — reading all screens")
    }

    // ─── EVENT PROCESSING ─────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        // Skip our own app and system UI
        if (pkg == "com.guardianai.agent" || pkg == "com.android.systemui") return

        scope.launch {
            try {
                when (event.eventType) {
                    // New text appeared on screen → analyze it
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                        val text = extractAllTextFromWindow(pkg)
                        if (text.isNotBlank()) analyzeScreenText(pkg, text)
                    }

                    // New window opened → check URL if browser
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                        if (isBrowser(pkg)) {
                            val url = extractBrowserUrl(pkg)
                            if (!url.isNullOrBlank()) analyzeBrowserUrl(pkg, url)
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore accessibility errors silently
            }
        }
    }

    // ─── TEXT EXTRACTION ──────────────────────────────────────────────────────

    private fun extractAllTextFromWindow(pkg: String): String {
        val rootNode = rootInActiveWindow ?: return ""
        val texts = mutableListOf<String>()
        extractTextsRecursive(rootNode, texts, pkg)
        rootNode.recycle()
        return texts.joinToString(" ").take(2000) // Max 2000 chars per scan
    }

    private fun extractTextsRecursive(
        node: AccessibilityNodeInfo?,
        texts: MutableList<String>,
        pkg: String,
        depth: Int = 0
    ) {
        node ?: return
        if (depth > 10) return // Max tree depth

        val config = PLATFORM_CONFIGS[pkg]

        // Prioritize known message node IDs
        val nodeId = node.viewIdResourceName
        val isMessageNode = config?.messageNodeId != null && nodeId == config.messageNodeId

        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank() && text.length > 2) {
            // Weight messages higher
            if (isMessageNode) texts.add("[MSG] $text")
            else texts.add(text)
        }

        // Also capture content description (images with alt text)
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrBlank() && desc.length > 5) {
            texts.add(desc)
        }

        for (i in 0 until node.childCount) {
            extractTextsRecursive(node.getChild(i), texts, pkg, depth + 1)
        }
    }

    private fun extractBrowserUrl(pkg: String): String? {
        val root = rootInActiveWindow ?: return null
        // URL bar IDs for common browsers
        val urlBarIds = mapOf(
            "com.android.chrome"          to "com.android.chrome:id/url_bar",
            "org.mozilla.firefox"         to "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "com.microsoft.emmx"          to "com.microsoft.emmx:id/url_bar",
            "com.opera.browser"           to "com.opera.browser:id/url_field",
            "com.brave.browser"           to "com.brave.browser:id/url_bar",
            "com.sec.android.app.sbrowser"to "com.sec.android.app.sbrowser:id/location_bar_edit_text",
        )
        val urlBarId = urlBarIds[pkg] ?: return null

        fun findNode(node: AccessibilityNodeInfo?, id: String): String? {
            node ?: return null
            if (node.viewIdResourceName == id) return node.text?.toString()
            for (i in 0 until node.childCount) {
                val found = findNode(node.getChild(i), id)
                if (found != null) return found
            }
            return null
        }

        val url = findNode(root, urlBarId)
        root.recycle()
        return url
    }

    // ─── AI ANALYSIS ──────────────────────────────────────────────────────────

    private suspend fun analyzeScreenText(pkg: String, text: String) {
        // Skip very short or repetitive text
        if (text.length < 10) return

        val platform = PLATFORM_CONFIGS[pkg]?.platform ?: getAppPlatformName(pkg)
        val childId  = prefs.getString("child_id", null) ?: return

        // Run keyword AI analysis (instant, on-device)
        val result = aiEngine.analyzeText(text)

        if (!result.isSafe) {
            val alertKey = "${result.category}_${result.matchedPhrase}"

            // Deduplication: don't send same alert twice in 5 minutes
            val lastTime = recentAlerts[alertKey] ?: 0L
            if (System.currentTimeMillis() - lastTime < DEDUP_WINDOW_MS) return
            recentAlerts[alertKey] = System.currentTimeMillis()

            Log.w(TAG, "🚨 DANGEROUS CONTENT detected on $platform: ${result.category} — '${result.matchedPhrase}'")

            val (severity, alertType) = when (result.category) {
                "GROOMING"       -> "critical" to "grooming"
                "SELF_HARM"      -> "critical" to "self_harm"
                "CYBERBULLYING"  -> "warning"  to "cyberbullying"
                "ADULT_CONTENT"  -> "warning"  to "adult_content"
                else             -> "info"     to "dangerous_keyword"
            }

            // Trim matched context (first 300 chars)
            val preview = text.take(300)

            SupabaseClient.logAlert(
                childId       = childId,
                type          = alertType,
                severity      = severity,
                title         = buildAlertTitle(result.category, platform),
                body          = buildAlertBody(result.category, result.matchedPhrase, platform, preview),
                metadata      = buildMetadata(pkg, platform, result, text.take(500))
            )
        }
    }

    private suspend fun analyzeBrowserUrl(pkg: String, url: String) {
        if (url.length < 5 || !url.contains(".")) return
        val childId = prefs.getString("child_id", null) ?: return

        // Check if URL contains adult/dangerous patterns
        val adultPatterns = listOf(
            "porn", "xxx", "sex", "nude", "adult", "nsfw", "18+",
            "onlyfans", "chaturbate", "xvideos", "xnxx", "redtube",
            "tinder", "bumble", "okcupid", "grinder",           // dating
            "bet365", "pokerstars", "casino",                    // gambling
        )

        val lowerUrl = url.lowercase()
        for (pattern in adultPatterns) {
            if (lowerUrl.contains(pattern)) {
                val alertKey = "browser_$url"
                val lastTime = recentAlerts[alertKey] ?: 0L
                if (System.currentTimeMillis() - lastTime < DEDUP_WINDOW_MS) return
                recentAlerts[alertKey] = System.currentTimeMillis()

                Log.w(TAG, "🌐 SUSPICIOUS URL detected: $url")

                SupabaseClient.logAlert(
                    childId  = childId,
                    type     = "adult_content",
                    severity = "warning",
                    title    = "⚠️ Suspicious Website Visited",
                    body     = "Child accessed: $url\nBrowser: ${getAppName(pkg)}",
                    metadata = android.os.Bundle().let {
                        org.json.JSONObject().apply {
                            put("url", url)
                            put("browser", pkg)
                            put("pattern_matched", pattern)
                        }
                    }
                )
                break
            }
        }
    }

    // ─── HELPERS ──────────────────────────────────────────────────────────────

    private fun isBrowser(pkg: String) = pkg in setOf(
        "com.android.chrome", "org.mozilla.firefox", "com.microsoft.emmx",
        "com.opera.browser", "com.brave.browser", "com.sec.android.app.sbrowser",
        "com.uc.browser.en", "com.kiwibrowser.browser"
    )

    private fun getAppPlatformName(pkg: String): String = when {
        pkg.contains("whatsapp")   -> "whatsapp"
        pkg.contains("instagram")  -> "instagram"
        pkg.contains("telegram")   -> "telegram"
        pkg.contains("snapchat")   -> "snapchat"
        pkg.contains("tiktok") || pkg.contains("musically") -> "tiktok"
        pkg.contains("youtube")    -> "youtube"
        pkg.contains("twitter") || pkg.contains("x.com") -> "twitter"
        pkg.contains("facebook")   -> "facebook"
        else -> pkg.substringAfterLast(".")
    }

    private fun getAppName(pkg: String) = getPackageManager()
        ?.getApplicationLabel(getPackageManager()!!.getApplicationInfo(pkg, 0))
        ?.toString() ?: pkg

    private fun buildAlertTitle(category: String, platform: String) = when (category) {
        "GROOMING"      -> "🚨 GROOMING ATTEMPT DETECTED on ${platform.capitalize()}"
        "SELF_HARM"     -> "💔 SELF-HARM LANGUAGE DETECTED on ${platform.capitalize()}"
        "CYBERBULLYING" -> "🚫 Cyberbullying Detected on ${platform.capitalize()}"
        "ADULT_CONTENT" -> "🔞 Adult Content on ${platform.capitalize()}"
        else            -> "⚠️ Dangerous Content Detected"
    }

    private fun buildAlertBody(
        category: String, phrase: String?, platform: String, preview: String
    ) = when (category) {
        "GROOMING"      -> "Potential grooming phrase detected: \"$phrase\"\nImmediate action required!\nContext: ...${preview.take(150)}..."
        "SELF_HARM"     -> "Child may be expressing self-harm thoughts: \"$phrase\"\nPlease speak with your child immediately.\nContext: ...${preview.take(150)}..."
        "CYBERBULLYING" -> "Bullying language detected: \"$phrase\"\nYour child may be a victim or involved in bullying."
        "ADULT_CONTENT" -> "Adult content phrase detected on $platform: \"$phrase\""
        else            -> "Dangerous phrase detected: \"$phrase\""
    }

    private fun buildMetadata(pkg: String, platform: String, result: TextAnalysisResult, textPreview: String) =
        org.json.JSONObject().apply {
            put("app_package",  pkg)
            put("platform",     platform)
            put("category",     result.category)
            put("matched_phrase", result.matchedPhrase)
            put("confidence",   result.confidence)
            put("text_preview", textPreview)
        }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        aiEngine.release()
        scope.cancel()
    }
}

// Data class for per-platform config
data class PlatformConfig(
    val messageNodeId: String?,
    val inputNodeId:   String?,
    val senderNodeId:  String?,
    val platform:      String
)
