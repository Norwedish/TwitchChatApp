package com.norwedish.twitcherchat

/**
 * Background service that handles whisper (private message) transport, persistence and delivery.
 * Exposes flows for incoming messages and implements sending via Twitch endpoints.
 */

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.client.call.body
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import java.util.concurrent.TimeUnit

class WhisperService : Service() {

    private val binder = WhisperBinder()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private val _incomingWhispers = MutableSharedFlow<WhisperMessage>(replay = 1)
    val incomingWhispers = _incomingWhispers.asSharedFlow()

    private var preferenceManager: WhisperPreferenceManager? = null
    // EventSub (WebSocket) support
    private var eventSubWebSocket: WebSocket? = null
    // Use server keepalives (EventSub session_keepalive) and disable OkHttp client pings to avoid
    // write-ping timeout errors on some networks. We rely on our keepalive monitor (lastKeepaliveMillis)
    // to detect missed server keepalives and reconnect cleanly.
    private val okHttpClient = OkHttpClient.Builder()
        // Disable client-initiated pings — Twitch's EventSub sends session_keepalive messages which we use.
        // Enabling a small client-initiated ping interval helps keep NAT mappings and idle
        // connections alive on networks that drop idle TCP sockets. Twitch will still send
        // session_keepalive messages, but client pings are a useful complementary keepalive.
        // 20s is a reasonable default that avoids aggressive write-ping behavior.
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        // No read timeout for long-lived socket
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var eventSubSessionId: String? = null
    private var eventSubReconnectAttempts = 0
    // Keepalive monitoring: timestamp of last session_welcome/session_keepalive received (ms)
    @Volatile
    private var lastKeepaliveMillis: Long = 0L
    // Keepalive timeout seconds reported by the session (fallback to 90 seconds)
    @Volatile
    private var keepaliveTimeoutSeconds: Int = 90
    private val eventSubJson = Json { ignoreUnknownKeys = true }
    // NOTE: Twitch EventSub subscription type for receiving whisper messages
    // "channel.whisper" is deprecated; use "user.whisper.message" instead
    private val WHISPER_EVENTSUB_TYPE = "user.whisper.message" // Current valid EventSub whisper event type

    // Auth BroadcastReceiver
    private var authBroadcastReceiver: android.content.BroadcastReceiver? = null

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        preferenceManager = WhisperPreferenceManager(this)
        startEventSubIfPossible()

        // Listen for login/logout broadcasts to start/stop EventSub lifecycle
        val filter = android.content.IntentFilter().apply {
            addAction(UserManager.ACTION_USER_LOGGED_IN)
            addAction(UserManager.ACTION_USER_LOGGED_OUT)
        }
        authBroadcastReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    UserManager.ACTION_USER_LOGGED_IN -> startEventSubIfPossible()
                    UserManager.ACTION_USER_LOGGED_OUT -> stopEventSub()
                }
            }
        }
        // Register receiver as not exported (required on Android 13+/Tiramisu).
        // Use the Context.RECEIVER_NOT_EXPORTED flag on API 33+; on older devices call the two-arg overload.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(authBroadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // Use the older 4-arg overload with null permission/handler to avoid lint complaints about export flags
            registerReceiver(authBroadcastReceiver, filter, null, null)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Twitcher Chat")
            .setContentText("Whisper service running")
            .setSmallIcon(R.drawable.transparentlogo)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        client.close()
        stopEventSub()
        try {
            authBroadcastReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) {}
        try {
            okHttpClient.dispatcher.executorService.shutdown()
        } catch (_: Exception) {}
    }

    suspend fun sendWhisper(targetUserLogin: String, message: String): Boolean {
        return try {
            val accessToken = UserManager.accessToken ?: return false

            val url = "https://api.twitch.tv/helix/whispers"

            val response = client.post(url) {
                bearerAuth(accessToken)
                header("Client-ID", UserManager.CLIENT_ID)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("to_user_login", targetUserLogin)
                    put("message", message)
                })
            }

            val statusValue = try { response.status.value } catch (_: Exception) { -1 }
            if (statusValue in 200..299) {
                true
            } else {
                if (statusValue == 401) {
                    serviceScope.launch { UserManager.logout() }
                    stopEventSub()
                }
                false
            }
        } catch (e: Exception) {
            Log.e("WhisperService", "Error sending whisper: ${e.message}", e)
            false
        }
    }

    suspend fun getUserByLogin(login: String): WhisperUser? {
        return try {
            val accessToken = UserManager.accessToken ?: return null
            val url = "https://api.twitch.tv/helix/users?login=${login}"

            val response = client.get(url) {
                bearerAuth(accessToken)
                header("Client-ID", UserManager.CLIENT_ID)
            }

            val statusValue = try { response.status.value } catch (_: Exception) { -1 }
            if (statusValue in 200..299) {
                val body = response.body<HelixUsersResponse>()
                val u = body.data.firstOrNull() ?: return null
                WhisperUser(
                    userId = u.id,
                    userLogin = u.login,
                    displayName = u.displayName,
                    profileImageUrl = u.profileImageUrl
                )
            } else {
                if (statusValue == 401) {
                    serviceScope.launch { UserManager.logout() }
                    stopEventSub()
                }
                null
            }
        } catch (e: Exception) {
            Log.e("WhisperService", "Error fetching user: ${e.message}", e)
            null
        }
    }

    fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Twitch Whispers",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            // Keep default sound/vibration for whispers so they notify audibly
            // No changes: intentionally do not call setSound(null, ...) here
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    fun simulateIncomingWhisper(message: WhisperMessage) {
        serviceScope.launch {
            _incomingWhispers.emit(message)
        }
    }

    inner class WhisperBinder : Binder() {
        fun getService(): WhisperService = this@WhisperService
    }

    companion object {
        private const val CHANNEL_ID = "whisper_channel"
        private const val NOTIFICATION_ID = 1002
    }

    private fun startEventSubIfPossible() {
        val token = UserManager.accessToken
        val user = UserManager.currentUser
        if (token == null || user == null) {
            // Will try later when service is bound again
            Log.d("WhisperService", "EventSub: no token/user available, skipping start")
            return
        }
        startEventSubWebSocket()
    }

    private fun startEventSubWebSocket() {
        // Avoid starting multiple sockets
        if (eventSubWebSocket != null) return

        val request = Request.Builder()
            .url("wss://eventsub.wss.twitch.tv/ws")
            .build()

        eventSubWebSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("WhisperService", "EventSub WS opened")
                eventSubReconnectAttempts = 0
                // initialize keepalive timestamp — we'll update when we receive session_welcome
                lastKeepaliveMillis = System.currentTimeMillis()
                // Start a monitor that checks for missing server keepalives and reconnects if necessary.
                serviceScope.launch {
                    val thisWebSocket = webSocket
                    while (eventSubWebSocket == thisWebSocket) {
                        try {
                            // check frequency: half the keepalive timeout, minimum 15s
                            val checkInterval = (keepaliveTimeoutSeconds * 1000L / 2).coerceAtLeast(15_000L)
                            kotlinx.coroutines.delay(checkInterval)
                            val now = System.currentTimeMillis()
                            val last = lastKeepaliveMillis
                            val allowed = keepaliveTimeoutSeconds * 1000L + 30_000L // add 30s grace
                            if (now - last > allowed) {
                                Log.w("WhisperService", "No EventSub session keepalive seen for ${now - last}ms (allowed=$allowed), reconnecting")
                                try { thisWebSocket.close(1001, "Keepalive missing, reconnect") } catch (_: Exception) {}
                                break
                            }
                        } catch (e: Exception) {
                            Log.w("WhisperService", "Keepalive monitor error: ${e.message}")
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleEventSubMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("WhisperService", "EventSub WS closed: $code / $reason")
                eventSubWebSocket = null
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Improve failure logging: detect common ping/pong timeout messages and handle them gracefully.
                if (t is java.net.SocketTimeoutException || (t.message?.contains("didn't receive pong") == true)) {
                    Log.w("WhisperService", "EventSub WS ping/pong timeout or SocketTimeoutException: ${t.message}")
                } else {
                    Log.e("WhisperService", "EventSub WS failure", t)
                }
                eventSubWebSocket = null
                scheduleReconnect()
            }
        })
    }

    private fun stopEventSub() {
        try {
            eventSubWebSocket?.close(1000, "Service stopping")
        } catch (_: Exception) {}
        eventSubWebSocket = null
    }

    // Note: don't shutdown the OkHttp dispatcher here — we reuse the client across reconnects.
    // The dispatcher is shut down when the service is destroyed below.

    private fun scheduleReconnect() {
        eventSubReconnectAttempts++
        val delayMs = (2000L * eventSubReconnectAttempts).coerceAtMost(60_000L)
        serviceScope.launch {
            kotlinx.coroutines.delay(delayMs)
            startEventSubWebSocket()
        }
    }

    private fun handleEventSubMessage(text: String) {
        try {
            // Any message from the server indicates the connection is alive — update the
            // last keepalive timestamp here so the monitor does not mistakenly treat the
            // socket as idle when we receive other messages (notifications, etc.).
            val now = System.currentTimeMillis()
            val elapsedSinceLast = if (lastKeepaliveMillis == 0L) -1L else (now - lastKeepaliveMillis)
            lastKeepaliveMillis = now

            val msg = eventSubJson.decodeFromString(EventSubMessage.serializer(), text)
            val messageType = msg.metadata.messageType
            Log.d("WhisperService", "EventSub message received type=$messageType elapsedSinceLast=$elapsedSinceLast ms keepaliveTimeoutSecs=$keepaliveTimeoutSeconds")

            when (messageType) {
                "session_welcome", "session_keepalive" -> {
                    val sessionId = msg.payload.session?.id
                    if (!sessionId.isNullOrBlank()) {
                        Log.d("WhisperService", "EventSub session id: $sessionId")
                        eventSubSessionId = sessionId
                        // Reset reconnect attempts when we successfully receive welcome/keepalive messages.
                        eventSubReconnectAttempts = 0
                        // Update keepalive timestamp and (optionally) timeout seconds reported by the server
                        // already set above for all messages; keep here to be explicit
                        lastKeepaliveMillis = System.currentTimeMillis()
                        msg.payload.session?.keepaliveTimeoutSeconds?.let {
                            keepaliveTimeoutSeconds = it
                        }
                        serviceScope.launch { createEventSubSubscription(sessionId) }
                      }
                }
                "notification" -> {
                    val subType = msg.payload.subscription?.type
                    if (subType != null && subType == WHISPER_EVENTSUB_TYPE) {
                        try {
                            // The incoming WebSocket wrapper has the shape EventSubMessage -> payload -> event.
                            // We already parsed the wrapper into `msg` above. The `payload.event` can be a
                            // different concrete JSON shape depending on the subscription type, so we
                            // decode the event JsonElement directly into our `WhisperEvent` model.
                            val eventElement = msg.payload.event
                            if (eventElement == null) throw IllegalStateException("Event element missing in payload")
                            try {
                                // Preferred path: decode directly to WhisperEvent
                                val ev = eventSubJson.decodeFromJsonElement(WhisperEvent.serializer(), eventElement)
                                val whisperMessage = WhisperMessage(
                                    id = ev.whisperId,
                                    fromUserId = ev.fromUserId,
                                    fromUserLogin = ev.fromUserLogin,
                                    fromDisplayName = ev.fromUserName,
                                    fromProfileImageUrl = "",
                                    toUserId = ev.toUserId,
                                    toUserLogin = ev.toUserLogin,
                                    message = ev.text,
                                    isOutgoing = false
                                )
                                serviceScope.launch { _incomingWhispers.emit(whisperMessage) }
                            } catch (primaryEx: Exception) {
                                // If structured deserialization fails (e.g., missing `text`), attempt a tolerant manual parse.
                                try {
                                    Log.w("WhisperService", "WhisperEvent deserialization failed: ${primaryEx.message}; attempting tolerant parse")
                                    val obj = eventElement.jsonObject

                                    // Helper: try a list of possible keys and return the first primitive content found
                                    fun v(vararg keys: String): String? {
                                        for (k in keys) {
                                            val el = obj[k]
                                            if (el is kotlinx.serialization.json.JsonPrimitive) {
                                                try { return el.content } catch (_: Exception) { /* fallthrough */ }
                                            }
                                        }
                                        return null
                                    }

                                    val fromUserId = v("from_user_id", "from_id", "fromId")
                                    val fromUserLogin = v("from_user_login", "from_login", "fromUserLogin")
                                    val fromUserName = v("from_user_name", "from_name", "display_name", "fromUserName")
                                    val toUserId = v("to_user_id", "to_id", "toUserId")
                                    val toUserLogin = v("to_user_login", "to_login", "toUserLogin")
                                    val whisperId = v("whisper_id", "id", "whisperId") ?: java.util.UUID.randomUUID().toString()

                                    // Try several likely keys for the message content, including nested message objects.
                                    var text: String? = v("text", "message", "message_text", "message_body")
                                    if (text.isNullOrBlank()) {
                                        val messageObjEl = obj["message"]
                                        val messageObj = if (messageObjEl is kotlinx.serialization.json.JsonObject) messageObjEl else null
                                        text = messageObj?.let { mo ->
                                            val t = mo["text"]
                                            if (t is kotlinx.serialization.json.JsonPrimitive) return@let try { t.content } catch (_: Exception) { null }
                                            val b = mo["body"]
                                            if (b is kotlinx.serialization.json.JsonPrimitive) return@let try { b.content } catch (_: Exception) { null }
                                            val m = mo["message"]
                                            if (m is kotlinx.serialization.json.JsonPrimitive) return@let try { m.content } catch (_: Exception) { null }
                                            null
                                        }
                                    }

                                    val whisperMessage = WhisperMessage(
                                        id = whisperId,
                                        fromUserId = fromUserId ?: "",
                                        fromUserLogin = fromUserLogin ?: "",
                                        fromDisplayName = fromUserName ?: fromUserLogin ?: "",
                                        fromProfileImageUrl = "",
                                        toUserId = toUserId ?: (UserManager.currentUser?.id ?: ""),
                                        toUserLogin = toUserLogin ?: (UserManager.currentUser?.login ?: ""),
                                        message = text ?: "",
                                        isOutgoing = false
                                    )
                                    Log.d("WhisperService", "Tolerant whisper parse succeeded: $whisperMessage")
                                    serviceScope.launch { _incomingWhispers.emit(whisperMessage) }
                                } catch (fallbackEx: Exception) {
                                    Log.e("WhisperService", "Failed to decode whisper payload (tolerant fallback also failed). Raw event: $eventElement", fallbackEx)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("WhisperService", "Failed to decode whisper payload", e)
                        }
                    }
                }
                else -> {
                    // ignore
                }
            }
        } catch (e: Exception) {
            Log.e("WhisperService", "Error handling EventSub message", e)
        }
    }

    // Make subscription creation more defensive and try multiple condition shapes when Twitch's validation
    // rejects the payload. This handles inconsistent validation rules across Twitch deployments.
    private suspend fun createEventSubSubscription(sessionId: String) {
        val accessToken = UserManager.accessToken ?: return
        val userId = UserManager.currentUser?.id ?: return

        try {
            // Try a small list of candidate condition shapes (most specific first).
            val candidateConditions = listOf(
                mapOf("user_id" to userId, "to_user_id" to userId),
                mapOf("to_user_id" to userId, "user_id" to userId), // just in case ordering matters
                mapOf("to_user_id" to userId),
                mapOf("user_id" to userId)
            )

            var lastErrorBody: String? = null
            for (condition in candidateConditions) {
                val req = EventSubSubscriptionRequest(
                    type = WHISPER_EVENTSUB_TYPE,
                    version = "1",
                    condition = condition,
                    transport = SubscriptionTransport(method = "websocket", sessionId = sessionId)
                )

                // Debug: serialize request to JSON and log it so we can confirm the field names sent to Twitch
                try {
                    val reqJson = eventSubJson.encodeToString(EventSubSubscriptionRequest.serializer(), req)
                    Log.d("WhisperService", "EventSub subscription request JSON: $reqJson")
                } catch (e: Exception) {
                    Log.w("WhisperService", "Failed to serialize EventSub request for logging: ${e.message}")
                }

                Log.d("WhisperService", "Creating EventSub subscription with: userId=$userId, sessionId=$sessionId, conditionKeys=${condition.keys}")

                val response: HttpResponse = client.post("https://api.twitch.tv/helix/eventsub/subscriptions") {
                    header("Client-Id", UserManager.CLIENT_ID)
                    bearerAuth(accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(req)
                }

                val statusValue = try { response.status.value } catch (_: Exception) { -1 }

                when {
                    statusValue in 200..299 -> {
                        Log.d("WhisperService", "✓ EventSub subscription created successfully for type=$WHISPER_EVENTSUB_TYPE with condition=${condition.keys}")
                        return
                    }
                    statusValue == 401 -> {
                        Log.e("WhisperService", "✗ Received 401 (Unauthorized) - token may have expired or lack required scopes")
                        Log.w("WhisperService", "Logging out user and stopping EventSub")
                        serviceScope.launch { UserManager.logout() }
                        stopEventSub()
                        return
                    }
                    statusValue == 409 -> {
                        Log.i("WhisperService", "✓ Subscription already exists (409 Conflict) - this is OK")
                        return
                    }
                    statusValue == 400 -> {
                        val errorBody = try { response.bodyAsText() } catch (_: Exception) { "Could not read body" }
                        lastErrorBody = errorBody
                        Log.w("WhisperService", "✗ Received 400 (Bad Request) - $errorBody")

                        // If Twitch validator complains about missing user_id, try the next candidate condition.
                        if (errorBody.contains("SubscriptionCondition.user_id", ignoreCase = true)
                            || errorBody.contains("user_id' failed", ignoreCase = true)
                            || errorBody.contains("user_id", ignoreCase = true) && errorBody.contains("required", ignoreCase = true)
                        ) {
                            Log.i("WhisperService", "Server rejected condition; will try a different condition shape and reconnect")
                            // Close WS to force a fresh session before the next attempt
                            eventSubWebSocket?.close(1000, "Retrying subscription with different condition")
                            eventSubWebSocket = null
                            // Continue loop to try next candidate
                            continue
                        } else {
                            // For other 400 errors, stop trying.
                            Log.w("WhisperService", "Unrecognized 400 response; will not retry: $errorBody")
                            eventSubWebSocket?.close(1000, "Subscription failed: 400")
                            eventSubWebSocket = null
                            return
                        }
                    }
                    statusValue == 403 -> {
                        val errorBody = try { response.bodyAsText() } catch (_: Exception) { "Could not read body" }
                        Log.w("WhisperService", "✗ Received 403 (Forbidden) - $errorBody")
                        // Token scopes/authorization problem — give up and let user re-auth.
                        eventSubWebSocket?.close(1000, "Subscription forbidden, stopping")
                        eventSubWebSocket = null
                        return
                    }
                    else -> {
                        val errorBody = try { response.bodyAsText() } catch (_: Exception) { "Could not read body" }
                        Log.w("WhisperService", "✗ Unexpected status $statusValue when creating subscription: $errorBody")
                        // Close and reconnect to try again later
                        eventSubWebSocket?.close(1000, "Subscription error, reconnecting")
                        eventSubWebSocket = null
                        return
                    }
                }
            }

            // If we exit the loop without success, log the last error for debugging
            Log.e("WhisperService", "Failed to create EventSub subscription after trying multiple condition shapes. Last error: $lastErrorBody")

        } catch (e: Exception) {
            Log.e("WhisperService", "✗ Error creating EventSub subscription: ${e.message}", e)
            // Close connection to trigger reconnect on error
            eventSubWebSocket?.close(1000, "Subscription error, reconnecting")
            eventSubWebSocket = null
        }
    }
}

@Serializable
private data class HelixUsersResponse(
    val data: List<HelixUser>
)

@Serializable
private data class HelixUser(
    @SerialName("id") val id: String,
    @SerialName("login") val login: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("profile_image_url") val profileImageUrl: String
)
