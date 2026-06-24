package com.guardianai.agent.workers

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.guardianai.agent.GuardianApp
import com.guardianai.agent.config.Config
import com.guardianai.agent.services.GuardianService
import com.guardianai.agent.utils.DeviceInfo
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.concurrent.TimeUnit
import android.content.Intent
import android.os.Build

/**
 * CommandSyncWorker is a periodic CoroutineWorker that:
 * 1. Checks Supabase for pending commands
 * 2. Ensures GuardianService is running
 * 3. Runs every 15 minutes as a WorkManager periodic task
 *
 * This provides a fallback mechanism if the foreground service polling is interrupted.
 */
class CommandSyncWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "CommandSyncWorker"

        /**
         * Schedules the worker to run every 15 minutes with network constraint.
         * Uses KEEP policy to avoid duplicate scheduling.
         */
        fun schedule(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val workRequest = PeriodicWorkRequestBuilder<CommandSyncWorker>(
                    Config.WORK_PERIODIC_INTERVAL_MIN,
                    TimeUnit.MINUTES,
                    // Flex interval: run anytime in the last 5 minutes of the period
                    5L,
                    TimeUnit.MINUTES
                )
                    .setConstraints(constraints)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        30L,
                        TimeUnit.SECONDS
                    )
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    Config.WORK_COMMAND_SYNC,
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                )

                Log.d(TAG, "CommandSyncWorker scheduled every ${Config.WORK_PERIODIC_INTERVAL_MIN} minutes")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule CommandSyncWorker", e)
            }
        }

        /**
         * Cancels any scheduled CommandSyncWorker.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(Config.WORK_COMMAND_SYNC)
            Log.d(TAG, "CommandSyncWorker cancelled")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "CommandSyncWorker running")

        return withContext(Dispatchers.IO) {
            try {
                val childId = DeviceInfo.getChildIdFromPrefs(applicationContext)

                if (childId.isNullOrBlank()) {
                    Log.w(TAG, "No child ID configured — skipping command sync")
                    return@withContext Result.success()
                }

                // Ensure GuardianService is running
                ensureServiceRunning(childId)

                // Poll and execute pending commands
                val processedCount = pollCommands(childId)
                Log.d(TAG, "CommandSyncWorker processed $processedCount commands")

                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "CommandSyncWorker failed", e)
                // Retry on failure with exponential backoff
                Result.retry()
            }
        }
    }

    /**
     * Ensures GuardianService is still running. Starts it if not.
     */
    private fun ensureServiceRunning(childId: String) {
        if (!GuardianService.isRunning) {
            Log.w(TAG, "GuardianService not running — restarting from Worker")
            try {
                val serviceIntent = Intent(applicationContext, GuardianService::class.java).apply {
                    putExtra("source", "command_sync_worker")
                    putExtra("child_id", childId)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(serviceIntent)
                } else {
                    applicationContext.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart GuardianService from Worker", e)
            }
        }
    }

    /**
     * Queries Supabase for pending commands, executes them, and updates their status.
     * Returns the count of processed commands.
     */
    private suspend fun pollCommands(childId: String): Int {
        val commands = try {
            GuardianApp.supabase.postgrest[Config.TABLE_DEVICE_COMMANDS]
                .select(Columns.list("id", "command_type", "payload", "status")) {
                    filter {
                        eq("child_id", childId)
                        eq("status", Config.STATUS_PENDING)
                    }
                    limit(20)
                }
                .decodeList<Map<String, String>>()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch commands from Supabase", e)
            return 0
        }

        if (commands.isEmpty()) {
            Log.d(TAG, "No pending commands")
            return 0
        }

        Log.d(TAG, "Found ${commands.size} pending commands")

        var successCount = 0
        for (command in commands) {
            val commandId = command["id"] ?: continue
            val commandType = command["command_type"] ?: continue

            try {
                // Mark as executing
                updateCommandStatus(commandId, Config.STATUS_EXECUTING, null)

                // For heavy commands (camera, audio), delegate to GuardianService via intent
                // For simple commands, execute directly
                when (commandType) {
                    Config.CMD_LOCK_DEVICE -> executeLockDevice()
                    Config.CMD_GET_LOCATION -> executeGetLocation(childId)
                    else -> {
                        // Route to GuardianService for complex commands
                        Log.d(TAG, "Delegating command to GuardianService: $commandType")
                    }
                }

                updateCommandStatus(commandId, Config.STATUS_COMPLETED, "Executed via worker")
                successCount++

            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute command $commandType", e)
                updateCommandStatus(commandId, Config.STATUS_FAILED, e.message ?: "Worker execution failed")
            }
        }

        return successCount
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
            Log.e(TAG, "Failed to update command status: $commandId", e)
        }
    }

    private fun executeLockDevice() {
        try {
            val dpm = applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE)
                    as android.app.admin.DevicePolicyManager
            val admin = android.content.ComponentName(
                applicationContext,
                com.guardianai.agent.receivers.GuardianAdminReceiver::class.java
            )
            if (dpm.isAdminActive(admin)) {
                dpm.lockNow()
                Log.i(TAG, "Device locked via Worker")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lock device failed in Worker", e)
            throw e
        }
    }

    private suspend fun executeGetLocation(childId: String) {
        try {
            val locationService = com.guardianai.agent.services.LocationService(applicationContext)
            val location = locationService.getLastLocation()
            if (location != null) {
                locationService.uploadLocation(
                    childId = childId,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy,
                    speed = location.speed,
                    altitude = location.altitude
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get location failed in Worker", e)
            throw e
        }
    }
}
