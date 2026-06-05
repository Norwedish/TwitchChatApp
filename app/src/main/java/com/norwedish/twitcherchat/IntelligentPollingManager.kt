package com.norwedish.twitcherchat

/**
 * Intelligent Polling Manager for streamers beyond EventSub subscription limits.
 *
 * Strategy:
 * - Uses adaptive polling intervals based on streamer activity
 * - Batch checks multiple streamers to reduce API calls
 * - Prioritizes recently active streamers
 * - Exponential backoff for consistently offline streamers
 */

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class StreamerPollingState(
    val userId: String,
    val broadcasterLogin: String,
    val broadcasterName: String,
    var isLive: Boolean = false,
    var lastChecked: Long = System.currentTimeMillis(),
    var consecutiveOfflineChecks: Int = 0,
    var pollIntervalMs: Long = POLL_INTERVAL_NORMAL,
    var lastNotificationTime: Long = 0
) {
    companion object {
        const val POLL_INTERVAL_RAPID = 5_000L // 5 seconds - actively viewing
        const val POLL_INTERVAL_NORMAL = 30_000L // 30 seconds - monitoring
        const val POLL_INTERVAL_IDLE = 120_000L // 2 minutes - low priority
        const val POLL_INTERVAL_BACKOFF = 300_000L // 5 minutes - many consecutive offline
    }
}

object IntelligentPollingManager {
    private const val TAG = "IntelligentPollingManager"

    // Polling configuration
    private const val BATCH_SIZE = 10 // Check 10 streamers per API call
    private const val MAX_CONSECUTIVE_OFFLINE = 10 // After 10 offline checks, increase interval

    // State tracking
    private val pollingState = ConcurrentHashMap<String, StreamerPollingState>()
    private val lastBatchCheck = AtomicLong(0)

    // Callbacks
    private var onStreamOnline: ((userId: String, broadcasterName: String) -> Unit)? = null
    private var onStreamOffline: ((userId: String) -> Unit)? = null

    fun initialize(
        onOnline: (userId: String, broadcasterName: String) -> Unit,
        onOffline: (userId: String) -> Unit
    ) {
        onStreamOnline = onOnline
        onStreamOffline = onOffline
        Log.d(TAG, "IntelligentPollingManager initialized")
    }

    /**
     * Add a streamer to the polling queue
     */
    fun addStreamerForPolling(
        userId: String,
        broadcasterLogin: String,
        broadcasterName: String,
        isHighPriority: Boolean = false
    ) {
        val state = StreamerPollingState(
            userId = userId,
            broadcasterLogin = broadcasterLogin,
            broadcasterName = broadcasterName,
            pollIntervalMs = if (isHighPriority) StreamerPollingState.POLL_INTERVAL_RAPID else StreamerPollingState.POLL_INTERVAL_NORMAL
        )
        pollingState[userId] = state
        Log.d(TAG, "Added $broadcasterName to polling queue (priority: $isHighPriority, interval: ${state.pollIntervalMs}ms)")
    }

    /**
     * Remove a streamer from polling
     */
    fun removeStreamerFromPolling(userId: String) {
        pollingState.remove(userId)
        Log.d(TAG, "Removed $userId from polling queue")
    }

    /**
     * Set polling interval for a specific streamer (for priority adjustment)
     */
    fun setPollingInterval(userId: String, intervalMs: Long) {
        pollingState[userId]?.apply {
            pollIntervalMs = intervalMs
            Log.d(TAG, "Updated polling interval for $userId to ${intervalMs}ms")
        }
    }

    /**
     * Perform batch polling of streamers that need checking
     * Returns list of streamers to check
     */
    suspend fun performBatchPolling(token: String, clientId: String) {
        if (pollingState.isEmpty()) {
            return
        }

        val now = System.currentTimeMillis()

        // Find streamers that need to be polled
        val streamersToCheck = pollingState.values
            .filter { now - it.lastChecked >= it.pollIntervalMs }
            .sortedByDescending { it.lastChecked } // Check recently checked ones first
            .take(BATCH_SIZE)

        if (streamersToCheck.isEmpty()) {
            return
        }

        Log.d(TAG, "Batch polling ${streamersToCheck.size} streamers")

        try {
            // Fetch live status for all streamers
            val userIds = streamersToCheck.map { it.userId }
            val liveStreams = try {
                TwitchApi.getStreamsForUsers(userIds, token, clientId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch stream status: ${e.message}")
                emptyList()
            }

            val liveUserIds = liveStreams.map { it.userId }.toSet()

            // Update state and trigger callbacks
            for (streamer in streamersToCheck) {
                val wasLive = streamer.isLive
                val isNowLive = streamer.userId in liveUserIds

                streamer.apply {
                    lastChecked = now

                    when {
                        isNowLive && !wasLive -> {
                            // Stream just went online
                            isLive = true
                            consecutiveOfflineChecks = 0
                            pollIntervalMs = StreamerPollingState.POLL_INTERVAL_NORMAL
                            Log.d(TAG, "$broadcasterName went ONLINE")
                            onStreamOnline?.invoke(userId, broadcasterName)
                        }

                        !isNowLive && wasLive -> {
                            // Stream just went offline
                            isLive = false
                            consecutiveOfflineChecks = 0
                            pollIntervalMs = StreamerPollingState.POLL_INTERVAL_NORMAL
                            Log.d(TAG, "$broadcasterName went OFFLINE")
                            onStreamOffline?.invoke(userId)
                        }

                        !isNowLive && !wasLive -> {
                            // Still offline - increase check interval
                            consecutiveOfflineChecks++
                            if (consecutiveOfflineChecks >= MAX_CONSECUTIVE_OFFLINE) {
                                pollIntervalMs = StreamerPollingState.POLL_INTERVAL_BACKOFF
                                Log.d(TAG, "$broadcasterName: idle backoff activated (${consecutiveOfflineChecks} checks)")
                            } else {
                                pollIntervalMs = StreamerPollingState.POLL_INTERVAL_IDLE
                            }
                        }

                        isNowLive && wasLive -> {
                            // Still online - maintain normal interval
                            pollIntervalMs = StreamerPollingState.POLL_INTERVAL_NORMAL
                        }
                    }
                }
            }

            lastBatchCheck.set(now)
        } catch (e: Exception) {
            Log.e(TAG, "Error during batch polling: ${e.message}", e)
        }
    }

    /**
     * Get current polling statistics
     */
    fun getPollingStats(): Map<String, Any> {
        val onlineCount = pollingState.count { it.value.isLive }
        val offlineCount = pollingState.size - onlineCount
        val avgInterval = pollingState.values.map { it.pollIntervalMs }.average()

        return mapOf(
            "totalStreamers" to pollingState.size,
            "onlineCount" to onlineCount,
            "offlineCount" to offlineCount,
            "averageInterval" to avgInterval,
            "lastBatchCheck" to lastBatchCheck.get()
        )
    }

    /**
     * Clear all polling state
     */
    fun clear() {
        pollingState.clear()
        lastBatchCheck.set(0)
        Log.d(TAG, "Cleared all polling state")
    }
}

/**
 * Worker that performs batch polling checks
 */
class IntelligentPollingWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_NAME = "IntelligentPollingWorker"
        private const val TAG = "IntelligentPollingWorker"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Worker executing batch poll")

        val token = UserManager.accessToken
        val user = UserManager.currentUser

        if (token == null || user == null) {
            Log.d(TAG, "No user logged in")
            return Result.success()
        }

        return try {
            IntelligentPollingManager.performBatchPolling(token, UserManager.CLIENT_ID)
            Log.d(TAG, "Batch poll completed")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Batch poll failed: ${e.message}", e)
            Result.retry()
        }
    }
}

