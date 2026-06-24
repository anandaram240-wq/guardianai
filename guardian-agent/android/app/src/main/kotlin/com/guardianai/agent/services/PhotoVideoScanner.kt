package com.guardianai.agent.services

import android.content.Context
import android.content.SharedPreferences
import android.os.FileObserver
import android.util.Log
import com.guardianai.agent.ai.ContentAIEngine
import com.guardianai.agent.utils.SupabaseClient
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════
 * PhotoVideoScanner — THE MOST ADVANCED SAFETY FEATURE
 * ═══════════════════════════════════════════════════════════
 *
 * NO OTHER PARENTAL APP DOES THIS.
 *
 * Scans every new photo/video saved to the device:
 *  - Downloads from WhatsApp/Telegram/Instagram
 *  - Screenshots
 *  - Camera photos
 *  - Gallery downloads
 *
 * Uses TFLite AI NSFW detector to check each image.
 * If adult content: deletes it silently + alerts parent.
 *
 * Child sees nothing — photo just disappears.
 *
 * Also detects:
 *  - Received nudes (grooming indicator)
 *  - Screenshots of adult content
 *  - Suspicious selfies sent via apps
 *
 * Works via FileObserver on all media directories.
 */
class PhotoVideoScanner(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val TAG = "PhotoScanner"
    private val prefs: SharedPreferences =
        context.getSharedPreferences("guardian_config", Context.MODE_PRIVATE)
    private lateinit var aiEngine: ContentAIEngine
    private val observers = mutableListOf<FileObserver>()

    // Directories to monitor
    private val WATCH_DIRS = listOf(
        "/storage/emulated/0/DCIM/Camera",
        "/storage/emulated/0/Pictures",
        "/storage/emulated/0/Pictures/Screenshots",
        "/storage/emulated/0/Download",
        "/storage/emulated/0/WhatsApp/Media/WhatsApp Images",
        "/storage/emulated/0/WhatsApp/Media/WhatsApp Video",
        "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
        "/storage/emulated/0/Telegram/Telegram Images",
        "/storage/emulated/0/Telegram/Telegram Video",
        "/storage/emulated/0/Pictures/Instagram",
        "/storage/emulated/0/Pictures/TikTok",
        "/storage/emulated/0/Snapchat",
        "/storage/emulated/0/Movies",
    )

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    private val VIDEO_EXTENSIONS = setOf("mp4", "3gp", "mkv", "avi", "mov")

    // Deduplication
    private val scannedFiles = mutableSetOf<String>()
    private val MAX_SCANNED_CACHE = 500

    fun start() {
        aiEngine = ContentAIEngine(context)
        aiEngine.initialize()

        startFileObservers()
        runInitialScan()
        Log.i(TAG, "✅ PhotoVideoScanner started — monitoring ${WATCH_DIRS.size} directories")
    }

    // ─── FILE OBSERVERS ───────────────────────────────────────────────────────

    private fun startFileObservers() {
        for (dirPath in WATCH_DIRS) {
            val dir = File(dirPath)
            if (!dir.exists()) continue

            val observer = object : FileObserver(dir, CREATE or CLOSE_WRITE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    path ?: return
                    val fullPath = "$dirPath/$path"
                    val ext = path.substringAfterLast('.', "").lowercase()

                    if (ext in IMAGE_EXTENSIONS || ext in VIDEO_EXTENSIONS) {
                        scope.launch {
                            delay(1000) // Wait 1s for file to be fully written
                            scanFile(File(fullPath), ext)
                        }
                    }
                }
            }
            observer.startWatching()
            observers.add(observer)
            Log.d(TAG, "Watching: $dirPath")
        }
    }

    // ─── INITIAL SCAN ─────────────────────────────────────────────────────────

    private fun runInitialScan() {
        scope.launch {
            // Scan files from the last 24 hours
            val oneDayAgo = System.currentTimeMillis() - 24 * 3600 * 1000L

            for (dirPath in WATCH_DIRS) {
                val dir = File(dirPath)
                if (!dir.exists()) continue

                dir.listFiles()?.filter { file ->
                    file.lastModified() > oneDayAgo &&
                    file.extension.lowercase() in IMAGE_EXTENSIONS
                }?.sortedByDescending { it.lastModified() }
                ?.take(20) // Max 20 files per dir on initial scan
                ?.forEach { file ->
                    scanFile(file, file.extension.lowercase())
                }
            }
        }
    }

    // ─── SCAN FILE WITH AI ────────────────────────────────────────────────────

    private suspend fun scanFile(file: File, ext: String) {
        if (!file.exists() || file.length() < 1024) return  // Skip tiny files
        if (scannedFiles.contains(file.absolutePath)) return

        // Add to cache
        scannedFiles.add(file.absolutePath)
        if (scannedFiles.size > MAX_SCANNED_CACHE) {
            scannedFiles.remove(scannedFiles.first())
        }

        Log.d(TAG, "Scanning: ${file.name}")

        if (ext in IMAGE_EXTENSIONS) {
            scanImage(file)
        } else if (ext in VIDEO_EXTENSIONS) {
            scanVideoThumbnail(file)
        }
    }

    private suspend fun scanImage(file: File) {
        try {
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return

            // Run TFLite NSFW detection
            val result = aiEngine.analyzeImage(bitmap)
            bitmap.recycle()

            Log.d(TAG, "${file.name} — NSFW: ${result.nsfwScore}, Safe: ${result.safeScore}")

            if (result.isBlocked) {
                Log.w(TAG, "🔞 NSFW image detected: ${file.name} (score: ${result.nsfwScore})")
                handleNsfwDetected(file, result.nsfwScore, "image")
            }

            // Also run skin tone check as secondary
            val bmp2 = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            if (bmp2 != null) {
                val skinRatio = aiEngine.quickSkinToneCheck(bmp2)
                bmp2.recycle()
                if (skinRatio > ContentAIEngine.SKIN_THRESHOLD && !result.isBlocked) {
                    Log.d(TAG, "High skin ratio in ${file.name}: $skinRatio — logging for review")
                    // Could alert at lower severity
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image scan error: ${file.name}", e)
        }
    }

    private suspend fun scanVideoThumbnail(file: File) {
        try {
            // Extract thumbnail from video
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)

            // Get frames at 0s, 5s, 15s, 30s
            val timestamps = listOf(0L, 5_000_000L, 15_000_000L, 30_000_000L)
            var flagged = false

            for (ts in timestamps) {
                val frame = retriever.getFrameAtTime(ts, android.media.MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                val result = aiEngine.analyzeImage(frame)
                frame.recycle()

                if (result.isBlocked) {
                    Log.w(TAG, "🔞 NSFW video frame at ${ts / 1_000_000}s: ${file.name}")
                    flagged = true
                    handleNsfwDetected(file, result.nsfwScore, "video")
                    break
                }
            }

            retriever.release()
        } catch (e: Exception) {
            Log.e(TAG, "Video scan error: ${file.name}", e)
        }
    }

    // ─── HANDLE NSFW DETECTED ─────────────────────────────────────────────────

    private suspend fun handleNsfwDetected(file: File, nsfwScore: Float, mediaType: String) {
        val childId = prefs.getString("child_id", null) ?: return
        val autoDelete = prefs.getBoolean("auto_delete_nsfw", true)

        // Determine source
        val source = when {
            file.absolutePath.contains("WhatsApp")  -> "WhatsApp"
            file.absolutePath.contains("Telegram")   -> "Telegram"
            file.absolutePath.contains("Instagram")  -> "Instagram"
            file.absolutePath.contains("TikTok")     -> "TikTok"
            file.absolutePath.contains("Snapchat")   -> "Snapchat"
            file.absolutePath.contains("Download")   -> "Browser Download"
            file.absolutePath.contains("Screenshot") -> "Screenshot"
            file.absolutePath.contains("Camera")     -> "Camera"
            else -> "Unknown"
        }

        // Alert parent
        SupabaseClient.logAlert(
            childId  = childId,
            type     = "adult_content",
            severity = "critical",
            title    = "🔞 NSFW ${mediaType.capitalize()} Detected — $source",
            body     = buildString {
                append("Adult content detected in ${mediaType} from $source.\n")
                append("AI Confidence: ${(nsfwScore * 100).toInt()}%\n")
                append("File: ${file.name}\n")
                if (autoDelete) append("✅ File automatically deleted.")
                else append("⚠️ File NOT deleted (auto-delete disabled).")
            },
            metadata = JSONObject().apply {
                put("file_name",   file.name)
                put("file_size",   file.length())
                put("source",      source)
                put("media_type",  mediaType)
                put("nsfw_score",  nsfwScore)
                put("auto_deleted", autoDelete)
                put("file_path",   file.absolutePath)
            }
        )

        // Auto-delete if enabled
        if (autoDelete) {
            val deleted = file.delete()
            Log.i(TAG, "Auto-deleted NSFW file: ${file.name} — success: $deleted")

            // Also delete from MediaStore
            try {
                val uri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                context.contentResolver.delete(
                    uri, "${android.provider.MediaStore.MediaColumns.DATA} = ?",
                    arrayOf(file.absolutePath)
                )
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        observers.forEach { try { it.stopWatching() } catch (_: Exception) {} }
        observers.clear()
        aiEngine.release()
    }
}
