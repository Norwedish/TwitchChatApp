package com.norwedish.twitcherchat

/**
 * EventSub Manager handles Twitch EventSub WebSocket connections with intelligent subscription management.
 *
 * Problem: EventSub has a limit on concurrent subscriptions (~10,000 for app subscriptions).
 * Solution:
 * - Maintain EventSub WebSocket for hot-priority streamers (those being watched)
 * - Use smart polling for remaining followed streamers with exponential backoff
 * - Dynamically add/remove subscriptions based on activity
 * - Fall back to rapid polling (5 sec) when viewing a stream
 */

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.wss
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object EventSubManager {
    private const val TAG = "EventSubManager"

    // EventSub WebSocket endpoint
    private const val EVENTSUB_WS_URL = "wss://eventsub-beta.wss.twitch.tv"

    // Subscription limits and tuning
    private const val MAX_EVENTSUB_SUBSCRIPTIONS = 100 // Conservative limit for stability
    private const val RAPID_POLL_INTERVAL = 5_000L // 5 seconds when actively watching
    private const val NORMAL_POLL_INTERVAL = 30_000L // 30 seconds for idle monitoring
    private const val IDLE_POLL_INTERVAL = 120_000L // 2 minutes for non-priority streamers

    // Feature flag: disable EventSub if network issues occur
    private var eventSubEnabled = true
    private var eventSubFailureCount = 0
    private const val MAX_FAILURES_BEFORE_DISABLE = 3

    // State management
    private var webSocketScope: CoroutineScope? = null
    private var webSocketClient: HttpClient? = null
    private val activeSubscriptions = ConcurrentHashMap<String, Long>() // broadcasterId -> subscriptionTime
    private val polledStreamers = ConcurrentHashMap<String, Long>() // userId -> lastPollTime
    private val lastStatusCheck = AtomicLong(0)

    // Callbacks
    private var onStreamOnline: ((userId: String, broadcasterName: String) -> Unit)? = null
    private var onStreamOffline: ((userId: String) -> Unit)? = null

    /**
     * Initialize EventSub manager with callbacks
     */
    fun initialize(
        context: Context,
        onOnline: (userId: String, broadcasterName: String) -> Unit,
        onOffline: (userId: String) -> Unit
    ) {
        onStreamOnline = onOnline
        onStreamOffline = onOffline
        Log.d(TAG, "EventSubManager initialized with callbacks")
    }

    /**
     * Start EventSub WebSocket connection
     */
    fun start(userId: String, token: String, clientId: String) {
        if (!eventSubEnabled) {
            Log.d(TAG, "EventSub is disabled. Skipping connection.")
            return
        }

        Log.d(TAG, "Starting EventSub connection for user $userId")

        webSocketScope = CoroutineScope(Dispatchers.IO)
        webSocketClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
            install(WebSockets)
        }

        webSocketScope?.launch {
            var reconnectAttempts = 0
            val maxReconnectAttempts = 2 // Reduced from 3

            while (reconnectAttempts < maxReconnectAttempts && eventSubEnabled) {
                try {
                    Log.d(TAG, "Attempting EventSub connection (attempt ${reconnectAttempts + 1}/$maxReconnectAttempts)")
                    connectAndListenToEventSub(userId, token, clientId)
                    reconnectAttempts = 0 // Reset on successful connection
                    eventSubFailureCount = 0
                } catch (e: CancellationException) {
                    Log.d(TAG, "EventSub connection cancelled")
                    break
                } catch (e: Exception) {
                    reconnectAttempts++
                    eventSubFailureCount++
                    Log.e(TAG, "EventSub connection error (attempt $reconnectAttempts/$maxReconnectAttempts, failures: $eventSubFailureCount): ${e.message}")

                    // Disable EventSub if too many failures
                    if (eventSubFailureCount >= MAX_FAILURES_BEFORE_DISABLE) {
                        Log.e(TAG, "EventSub disabled after $eventSubFailureCount failures. Using polling instead.")
                        eventSubEnabled = false
                        break
                    }

                    if (reconnectAttempts >= maxReconnectAttempts) {
                        Log.e(TAG, "EventSub connection failed after $maxReconnectAttempts attempts. Will retry on next app start.")
                        break
                    }

                    // Short backoff: 3s, 6s
                    val backoffDelay = 3000L * (1 shl (reconnectAttempts - 1))
                    Log.d(TAG, "Retrying EventSub connection in ${backoffDelay}ms...")
                    delay(backoffDelay)
                }
            }
        }
    }

    /**
     * Stop EventSub connection
     */
    fun stop() {
        Log.d(TAG, "Stopping EventSub connection")
        webSocketScope?.launch {
            try {
                webSocketClient?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing WebSocket: ${e.message}")
            }
        }
        webSocketScope = null
        webSocketClient = null
        activeSubscriptions.clear()
    }

    /**
     * Subscribe to stream.online events for specific broadcasters
     * Handles subscription limits by maintaining a priority queue
     */
    fun subscribeToStreamer(broadcasterId: String, isHighPriority: Boolean = false) {
        Log.d(TAG, "Subscribe request for streamer $broadcasterId (priority: $isHighPriority)")

        // If we have capacity, add to active subscriptions
        if (activeSubscriptions.size < MAX_EVENTSUB_SUBSCRIPTIONS) {
            activeSubscriptions[broadcasterId] = System.currentTimeMillis()
            Log.d(TAG, "Added to EventSub subscriptions (${activeSubscriptions.size}/$MAX_EVENTSUB_SUBSCRIPTIONS)")
        } else {
            // If at capacity and this is low priority, add to polling instead
            if (!isHighPriority) {
                polledStreamers[broadcasterId] = System.currentTimeMillis()
                Log.d(TAG, "Added to polling queue due to EventSub limit (polling: ${polledStreamers.size})")
            } else {
                // For high priority, evict oldest low-priority subscription
                val oldestEntry = activeSubscriptions.minByOrNull { it.value }
                if (oldestEntry != null && !isHighPriority) {
                    activeSubscriptions.remove(oldestEntry.key)
                    polledStreamers[oldestEntry.key] = System.currentTimeMillis()
                    activeSubscriptions[broadcasterId] = System.currentTimeMillis()
                    Log.d(TAG, "Evicted ${oldestEntry.key} from EventSub, added to polling")
                }
            }
        }
    }

    /**
     * Unsubscribe from a streamer
     */
    fun unsubscribeFromStreamer(broadcasterId: String) {
        Log.d(TAG, "Unsubscribe request for streamer $broadcasterId")
        activeSubscriptions.remove(broadcasterId)
        polledStreamers.remove(broadcasterId)
    }

    /**
     * Get subscription status
     */
    fun getSubscriptionStatus(): Map<String, Any> {
        return mapOf(
            "activeSubscriptions" to activeSubscriptions.size,
            "polledStreamers" to polledStreamers.size,
            "maxCapacity" to MAX_EVENTSUB_SUBSCRIPTIONS,
            "lastStatusCheck" to lastStatusCheck.get()
        )
    }

    /**
     * Main WebSocket connection and listening loop
     */
    private suspend fun connectAndListenToEventSub(
        userId: String,
        token: String,
        clientId: String
    ) {
        val client = webSocketClient ?: return

        try {
            Log.d(TAG, "Attempting to connect to EventSub WebSocket at $EVENTSUB_WS_URL")

            client.wss(EVENTSUB_WS_URL) {
                Log.d(TAG, "Connected to EventSub WebSocket")

                // Listen for welcome message to get session ID
                var sessionId: String? = null

                // Listen to incoming messages
                try {
                    for (message in incoming) {
                        if (message is Frame.Text) {
                            val text = message.readText()
                            handleEventSubMessage(text, userId, token, clientId) { newSessionId ->
                                sessionId = newSessionId
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading WebSocket messages: ${e.message}", e)
                    throw e
                }
            }
        } catch (e: java.nio.channels.UnresolvedAddressException) {
            Log.e(TAG, "DNS resolution failed for EventSub WebSocket: ${e.message}. Network may be unavailable.", e)
            throw Exception("Network unavailable - cannot reach EventSub endpoint", e)
        } catch (e: Exception) {
            Log.e(TAG, "WebSocket connection error: ${e.message}", e)
            throw e
        }
    }

    /**
     * Handle incoming EventSub messages
     */
    private fun handleEventSubMessage(
        messageText: String,
        userId: String,
        token: String,
        clientId: String,
        onSessionId: (String) -> Unit
    ) {
        try {
            val json = Json.parseToJsonElement(messageText)
            val jsonObj = json.jsonObject

            val metadata = jsonObj["metadata"]?.jsonObject
            val messageType = metadata?.get("message_type")?.jsonPrimitive?.content

            Log.d(TAG, "Received EventSub message type: $messageType")

            when (messageType) {
                "session_welcome" -> {
                    val sessionId = metadata?.get("session_id")?.jsonPrimitive?.content
                    if (sessionId != null) {
                        onSessionId(sessionId)
                        Log.d(TAG, "Session established: $sessionId")
                    }
                }

                "notification" -> {
                    val subscriptionType = metadata?.get("subscription_type")?.jsonPrimitive?.content
                    val payload = jsonObj["payload"]?.jsonObject
                    val event = payload?.get("event")?.jsonObject

                    when (subscriptionType) {
                        "stream.online" -> {
                            val broadcasterId = event?.get("broadcaster_user_id")?.jsonPrimitive?.content
                            val broadcasterName = event?.get("broadcaster_user_name")?.jsonPrimitive?.content

                            if (broadcasterId != null && broadcasterName != null) {
                                Log.d(TAG, "Stream online: $broadcasterName ($broadcasterId)")
                                onStreamOnline?.invoke(broadcasterId, broadcasterName)
                            }
                        }

                        "stream.offline" -> {
                            val broadcasterId = event?.get("broadcaster_user_id")?.jsonPrimitive?.content
                            if (broadcasterId != null) {
                                Log.d(TAG, "Stream offline: $broadcasterId")
                                onStreamOffline?.invoke(broadcasterId)
                            }
                        }
                    }
                }

                "session_keepalive" -> {
                    Log.d(TAG, "Keepalive received")
                }

                "session_reconnect" -> {
                    Log.d(TAG, "Reconnect requested")
                    throw Exception("Session reconnect requested")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing EventSub message: ${e.message}")
        }
    }
}

