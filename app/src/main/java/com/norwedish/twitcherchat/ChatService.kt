package com.norwedish.twitcherchat

/**
 * Background service that manages the IRC/Twitch chat connection for a channel.
 * Responsibilities:
 *  - Connect to Twitch IRC for a channel and maintain reconnection logic
 *  - Parse incoming messages and emit them to bound ViewModels
 *  - Persist minimal state and handle NOTICE/room-state messages
 */

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
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.wss
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.*

// Connection lifecycle states observed by clients
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

// Message types used for rendering and handling special cases
enum class MessageType {
    STANDARD,
    SUBSCRIPTION,
    RAID,
    ANNOUNCEMENT,
    SYSTEM,
    DELETED
}

// Lightweight holder for raw emote index info parsed from IRC tags
data class TwitchEmoteInfo(
    val id: String,
    val startIndex: Int,
    val endIndex: Int,
)

// Chat message model emitted by the service for UI consumption
data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val author: String?,
    val authorLogin: String?,
    val message: String,
    val authorColor: String?,
    val emotes: List<ParsedEmote> = emptyList(),
    val badges: List<String> = emptyList(),
    val type: MessageType = MessageType.STANDARD,
    val timestamp: String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
    val tags: Map<String, String> = emptyMap(),
    val replyParentMsgId: String? = null,
    val replyParentUserLogin: String? = null,
    val replyParentDisplayName: String? = null,
    val replyParentMsgBody: String? = null
)

// Room state emitted by the service (emote-only, slow-mode, etc.)
data class RoomState(
    val emoteOnly: Boolean = false,
    val followersOnly: Int? = null, // Minutes
    val subsOnly: Boolean = false,
    val r9k: Boolean = false,
    val slowMode: Int? = null // Seconds
)


// Data classes for Polls
@Serializable
data class Poll(
    @SerialName("id") val id: String,
    val title: String,
    val choices: List<PollChoice>,
    val status: String, // "ACTIVE", "COMPLETED", "ARCHIVED"
    @SerialName("duration_seconds") val duration: Int,
    @SerialName("broadcaster_user_id") val broadcasterId: String
) {
    val totalVotes by lazy { choices.sumOf { it.votes } }
}

@Serializable
data class PollChoice(
    @SerialName("id") val id: String,
    val title: String,
    @SerialName("votes") val votes: Int = 0
)



class ChatService : Service() {


    private val binder = ChatBinder()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var isStarted = false

    // Preferences for configurable suppression of NOTICE msg-ids
    private val PREFS_NAME = "chat_service_prefs"
    private val PREF_KEY_SUPPRESSED = "suppressed_notice_msgids"

    // Default set of NOTICE msg-ids that duplicate ROOMSTATE events
    private val defaultSuppressedNotices = setOf(
        "slow_on", "slow_off",
        "emote_only_on", "emote_only_off",
        "subs_on", "subs_off", "subscribers_on", "subscribers_off",
        "r9k_on", "r9k_off"
    )

    // Backing synchronized set we consult at runtime. Mutable and persisted when changed.
    private val suppressedRoomStateNotices: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    override fun onCreate() {
        super.onCreate()
        loadSuppressedNoticesFromPrefs()
        initializeTokenRefreshListener()
    }

    private fun initializeTokenRefreshListener() {
        EmoteManager.setTokenRefreshListener(object : TokenRefreshListener {
            override suspend fun onTokenExpired(): String? {
                return try {
                    Log.d(TAG, "Token expired, attempting to refresh...")
                    null
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to refresh token", e)
                    null
                }
            }
        })
    }

    private fun loadSuppressedNoticesFromPrefs() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val csv = prefs.getString(PREF_KEY_SUPPRESSED, null)
            suppressedRoomStateNotices.clear()
            if (csv.isNullOrBlank()) {
                suppressedRoomStateNotices.addAll(defaultSuppressedNotices)
            } else {
                suppressedRoomStateNotices.addAll(csv.split(',').map { it.trim() }.filter { it.isNotEmpty() })
            }
        } catch (t: Throwable) {
            suppressedRoomStateNotices.clear()
            suppressedRoomStateNotices.addAll(defaultSuppressedNotices)
        }
    }

    private fun saveSuppressedNoticesToPrefs() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(PREF_KEY_SUPPRESSED, suppressedRoomStateNotices.joinToString(",")).apply()
        } catch (_: Throwable) { }
    }

    fun isNoticeSuppressed(msgId: String): Boolean = suppressedRoomStateNotices.contains(msgId)

    private val client = HttpClient(CIO) {
        install(WebSockets)
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    // Buffered flows so emit() in the read loop never suspends and message order is preserved
    private val _chatMessages = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 64)
    val chatMessages = _chatMessages.asSharedFlow()

    private val _deletedMessageIds = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val deletedMessageIds = _deletedMessageIds.asSharedFlow()

    private val _deletedUserMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val deletedUserMessages = _deletedUserMessages.asSharedFlow()

    private val _isCurrentUserModerator = MutableStateFlow(false)
    val isCurrentUserModerator = _isCurrentUserModerator.asStateFlow()

    private var session: DefaultClientWebSocketSession? = null
    private var connectionJob: Job? = null
    var currentChannel: String = ""

    @Volatile
    private var hasJoinedChannel: Boolean = false

    // When false (logout / explicit disconnect), the reconnect loop stops
    @Volatile
    private var reconnectEnabled: Boolean = true

    private val _poll = MutableStateFlow<Poll?>(null)
    val poll = _poll.asStateFlow()

    // Pinned message (moderators/streamer can pin one message to the top of chat)
    private val _pinnedMessage = MutableStateFlow<PinnedMessage?>(null)
    val pinnedMessage = _pinnedMessage.asStateFlow()

    private val _roomState = MutableStateFlow<RoomState?>(null)
    val roomState = _roomState.asStateFlow()

    private val _chatters = MutableStateFlow<List<String>>(emptyList())
    val chatters = _chatters.asStateFlow()

    private val currentChatters = Collections.synchronizedSet(mutableSetOf<String>())

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "ChatServiceChannel"
        private const val TAG = "ChatService"
    }

    inner class ChatBinder : Binder() {
        fun getService(): ChatService = this@ChatService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isStarted) {
            Log.d(TAG, "Service already started, ignoring command.")
            return START_STICKY
        }
        isStarted = true

        val channelName = intent?.getStringExtra("channelName") ?: return START_NOT_STICKY
        val twitchUserId = intent.getStringExtra("twitchUserId")

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Twitch Chat")
            .setContentText("Connected to #$channelName")
            .setSmallIcon(R.drawable.transparentlogo)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        connectAndJoin(channelName, twitchUserId)

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        disconnect()
        isStarted = false
    }

    private fun createNotificationChannel() {
        val name = "Twitch Chat Service"
        val descriptionText = "Notification channel for the chat service"
        val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW).apply {
            description = descriptionText
            setSound(null, null)
            enableVibration(false)
        }
        val notificationManager:
                NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    fun connectAndJoin(channelName: String, twitchUserId: String? = null) {
        if (connectionState.value == ConnectionState.CONNECTED || connectionState.value == ConnectionState.CONNECTING) {
            return
        }

        val token = UserManager.accessToken
        val nick = UserManager.currentUser?.login

        if (token == null || nick == null) {
            serviceScope.launch {
                _chatMessages.emit(
                    ChatMessage(
                        author = null,
                        authorLogin = null,
                        message = "Not logged in \u2014 can't connect to chat.",
                        authorColor = null,
                        type = MessageType.SYSTEM,
                        tags = mapOf("client" to "auth_missing")
                    )
                )
            }
            return
        }

        // Kick off emote and badge loading
        serviceScope.launch {
            try {
                // Use passed ID if available, otherwise resolve it
                val broadcasterId = twitchUserId ?: resolveBroadcasterIdByLogin(normalizeChannelName(channelName), token)
                if (!broadcasterId.isNullOrBlank()) {
                    EmoteManager.loadEmotesForChannel(broadcasterId, token, UserManager.CLIENT_ID)
                    BadgeManager.loadGlobalBadges(token, UserManager.CLIENT_ID)
                    BadgeManager.loadChannelBadges(broadcasterId, token, UserManager.CLIENT_ID)
                } else {
                    Log.w(TAG, "Couldn't resolve broadcaster id for #${normalizeChannelName(channelName)}; badges might be missing.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Emote/Badge preload failed: ${e.message}")
            }
        }

        currentChannel = channelName
        hasJoinedChannel = false

        connectionJob?.cancel()
        currentChatters.clear()
        _chatters.value = emptyList()
        _roomState.value = null
        reconnectEnabled = true

        connectionJob = serviceScope.launch {
            var attempt = 0
            // Reconnect loop with exponential backoff so a dropped connection heals itself
            while (isActive && reconnectEnabled) {
                _connectionState.value = ConnectionState.CONNECTING
                try {
                    client.wss(
                        method = HttpMethod.Get,
                        host = "irc-ws.chat.twitch.tv",
                        port = 443,
                        path = ""
                    ) {
                        session = this
                        val normalizedToken = token.removePrefix("oauth:")

                        sendIrc("PASS oauth:$normalizedToken")
                        sendIrc("NICK $nick")
                        sendIrc("CAP REQ :twitch.tv/membership")
                        sendIrc("CAP REQ :twitch.tv/tags")
                        sendIrc("CAP REQ :twitch.tv/commands")

                        listenToMessages(channelName)
                    }
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "WebSocket error: ${e.message}")
                    if (isActive && reconnectEnabled) {
                        _connectionState.value = ConnectionState.ERROR
                    }
                } finally {
                    session?.close()
                    session = null
                    hasJoinedChannel = false
                    if (_connectionState.value != ConnectionState.ERROR) {
                        _connectionState.value = ConnectionState.DISCONNECTED
                    }
                }

                if (!isActive || !reconnectEnabled) break
                // Exponential backoff: 2s, 4s, 8s, 16s, then capped at 30s
                attempt++
                val backoffMs = (2_000L shl minOf(attempt - 1, 4)).coerceAtMost(30_000L)
                Log.d(TAG, "Reconnecting to chat in ${backoffMs}ms (attempt $attempt)")
                delay(backoffMs)
            }
        }
    }

    private suspend fun resolveBroadcasterIdByLogin(login: String, token: String): String? {
        return try {
            val response: GetUsersResponse = client.get("https://api.twitch.tv/helix/users") {
                url { parameters.append("login", login) }
                headers {
                    append("Authorization", "Bearer $token")
                    append("Client-Id", UserManager.CLIENT_ID)
                }
            }.body()
            response.data?.firstOrNull()?.id
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve user id for login=$login: ${e.message}")
            null
        }
    }

    fun disconnect() {
        reconnectEnabled = false
        _isCurrentUserModerator.value = false
        hasJoinedChannel = false
        connectionJob?.cancel()
        _poll.value = null
        _pinnedMessage.value = null
        _roomState.value = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun sendMessage(channelName: String, message: String, replyToMessageId: String? = null, nonce: String? = null) {
        val activeSession = session
        val normalizedChannel = normalizeChannelName(channelName)
        if (message.isBlank()) return

        if (!hasJoinedChannel || _connectionState.value != ConnectionState.CONNECTED || activeSession?.isActive != true) {
            serviceScope.launch {
                _chatMessages.emit(
                    ChatMessage(
                        author = null,
                        authorLogin = null,
                        message = "Not connected to #$normalizedChannel yet \u2014 message wasn't sent.",
                        authorColor = null,
                        type = MessageType.SYSTEM,
                        tags = mapOf("client" to "send_guard")
                    )
                )
            }
            return
        }

        serviceScope.launch {
            try {
                val tags = mutableListOf<String>()
                if (replyToMessageId != null) tags.add("reply-parent-msg-id=$replyToMessageId")
                if (nonce != null) tags.add("client-nonce=$nonce")

                val ircMessage = if (tags.isNotEmpty()) {
                    "@${tags.joinToString(";")} PRIVMSG #$normalizedChannel :$message"
                } else {
                    "PRIVMSG #$normalizedChannel :$message"
                }

                activeSession.sendIrc(ircMessage)
            } catch (e: Exception) {
                serviceScope.launch {
                    _chatMessages.emit(
                        ChatMessage(
                            author = null,
                            authorLogin = null,
                            message = "Failed to send message: ${e.message ?: "unknown error"}",
                            authorColor = null,
                            type = MessageType.SYSTEM,
                            tags = mapOf("client" to "send_error")
                        )
                    )
                }
            }
        }
    }

    private suspend fun DefaultClientWebSocketSession.listenToMessages(channelName: String) {
        val normalizedChannel = normalizeChannelName(channelName)
        for (frame in incoming) {
            if (frame is Frame.Text) {
                val messageText = frame.readText().trim()
                Log.d(TAG, "RAW IRC MESSAGE: $messageText")
                messageText.split("\r\n").forEach { rawMessage ->
                    if (rawMessage.startsWith("PING")) {
                        sendIrc("PONG :tmi.twitch.tv")
                        return@forEach
                    }

                    if (rawMessage.contains(" 421 ") ||
                        rawMessage.contains(" 671 ") ||
                        rawMessage.contains(" 902 ") ||
                        rawMessage.contains("ERR_PASSWDMISMATCH") ||
                        rawMessage.contains("ERR_UNKNOWNCOMMAND")) {
                        Log.e(TAG, "IRC authentication error detected")
                        reconnectEnabled = false // don't keep retrying with a bad token
                        serviceScope.launch { UserManager.logout() }
                        return@forEach
                    }

                    // Dispatch on the IRC command token instead of substring matching, so chat
                    // text containing e.g. "NOTICE" or "ROOMSTATE" is never misrouted.
                    when (ircCommand(rawMessage)) {
                        "001" -> {
                            hasJoinedChannel = false
                            sendIrc("JOIN #$normalizedChannel")
                        }
                        "ROOMSTATE" -> if (rawMessage.contains("#$normalizedChannel")) {
                            hasJoinedChannel = true
                            _connectionState.value = ConnectionState.CONNECTED
                            handleRoomState(rawMessage)
                        }
                        "USERSTATE" -> if (rawMessage.contains("#$normalizedChannel")) {
                            hasJoinedChannel = true
                            _connectionState.value = ConnectionState.CONNECTED
                            handleUserState(rawMessage)
                        }
                        "353" -> {
                            val usersPart = rawMessage.substringAfterLast(':')
                            val users = usersPart.trim().split(' ').filter { it.isNotBlank() }
                            currentChatters.addAll(users)
                            _chatters.value = currentChatters.toList().sorted()
                        }
                        "366" -> {
                            _chatters.value = currentChatters.toList().sorted()
                        }
                        "JOIN" -> {
                            val username = rawMessage.substringAfter(':').substringBefore('!')
                            if (currentChatters.add(username)) {
                                _chatters.value = currentChatters.toList().sorted()
                            }
                            val currentLogin = UserManager.currentUser?.login
                            if (!currentLogin.isNullOrBlank() && username.equals(currentLogin, ignoreCase = true)) {
                                hasJoinedChannel = true
                                _connectionState.value = ConnectionState.CONNECTED
                            }
                        }
                        "PART" -> {
                            val username = rawMessage.substringAfter(':').substringBefore('!')
                            if (currentChatters.remove(username)) {
                                _chatters.value = currentChatters.toList().sorted()
                            }
                        }
                        "USERNOTICE" -> {
                            when {
                                rawMessage.contains("msg-id=channel.poll.") -> handlePollNotice(rawMessage)
                                rawMessage.contains("msg-id=announcement") &&
                                    rawMessage.contains("msg-param-pin-type") -> handlePinNotice(rawMessage)
                                else -> {
                                    try {
                                        parseMessage(rawMessage)?.let {
                                            if (it.message.isNotBlank()) _chatMessages.emit(it)
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to parse USERNOTICE", e)
                                    }
                                }
                            }
                        }
                        "CLEARMSG" -> {
                            handleClearMessage(rawMessage)
                            // Unpinning also arrives as CLEARMSG with the pinned message's id
                            val tags = ircTags(rawMessage)
                            if (tags["target-msg-id"] != null &&
                                tags["target-msg-id"] == _pinnedMessage.value?.messageId
                            ) {
                                _pinnedMessage.value = null
                            }
                        }
                        "CLEARCHAT" -> handleClearChat(rawMessage)
                        "NOTICE" -> handleNotice(rawMessage)
                        else -> {
                            val parsed = parseMessage(rawMessage)
                            if (parsed != null && parsed.message.isNotBlank()) {
                                // Direct emit: the flow has a buffer, so this preserves order
                                _chatMessages.emit(parsed)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun normalizeChannelName(channelName: String): String = channelName.trim().removePrefix("#")

    /** Extracts the IRC command token (e.g. PRIVMSG, ROOMSTATE, 001) from a raw IRC line. */
    private fun ircCommand(raw: String): String {
        var s = raw
        if (s.startsWith("@")) s = s.substringAfter(" ")
        if (s.startsWith(":")) s = s.substringAfter(" ")
        return s.substringBefore(" ").uppercase()
    }

    private suspend fun DefaultClientWebSocketSession.sendIrc(line: String) {
        val sanitized = line.replace("\u0000", "").trimEnd('\r', '\n')
        send(Frame.Text("$sanitized\r\n"))
    }

    private fun handleNotice(rawMessage: String) {
        val tagsPart = rawMessage.substringAfter('@').substringBefore(" :")
        val messagePart = rawMessage.substringAfter(":")
        val tags = if (rawMessage.startsWith("@")) {
            tagsPart.split(';').associate {
                val parts = it.split('=', limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
            }
        } else emptyMap()
        
        val noticeMsgId = tags["msg-id"] ?: ""

        if (noticeMsgId == "login_authentication_failed") {
            reconnectEnabled = false // don't keep retrying with a bad token
            serviceScope.launch { UserManager.logout() }
            return
        }
        if (noticeMsgId == "msg_ratelimit") {
            return
        }

        if (isNoticeSuppressed(noticeMsgId)) return
        
        serviceScope.launch {
            _chatMessages.emit(
                ChatMessage(
                author = null,
                authorLogin = null,
                message = messagePart,
                authorColor = null,
                type = MessageType.SYSTEM,
                tags = tags
            )
            )
        }
    }

    private fun handleClearMessage(rawMessage: String) {
        val tags = rawMessage.substringAfter('@').substringBefore(" :").split(';').associate {
            val parts = it.split('=', limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
        }
        val targetMessageId = tags["target-msg-id"]
        if (targetMessageId != null) {
            serviceScope.launch {
                _deletedMessageIds.emit(targetMessageId)
            }
        }
    }

    // NOTE: emits for chatMessages use the buffered flow directly in the read loop (see listenToMessages).

    private fun handleClearChat(rawMessage: String) {
        val targetUser = rawMessage.substringAfterLast(":", "").trim()
        if (targetUser.isNotEmpty()) {
            serviceScope.launch {
                _deletedUserMessages.emit(targetUser)
            }
        }
    }

    // (deletedUserMessages/deletedMessageIds emits stay in launch{} here; they are rare events)

    private fun handleUserState(rawMessage: String) {
        val tagsPart = rawMessage.substringAfter('@').substringBefore(" :")
        val tags = tagsPart.split(';').associate {
            val parts = it.split('=', limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
        }

        // Extract badges for this user from USERSTATE event
        val badgesTag = tags["badges"] ?: ""
        if (badgesTag.isNotBlank()) {
            val badgeKeys = badgesTag.split(",").filter { it.isNotBlank() }.toList()
            // Key by login (not display-name) so ChatViewModel can look the badges up via currentUser.login.
            val senderLogin = tags["login"] ?: tags["user-id"] ?: ""
            BadgeManager.storeUserBadges(senderLogin, badgeKeys)
            Log.d(TAG, "Cached ${badgeKeys.size} badges for user $senderLogin from USERSTATE")
        }

        val badges = tags["badges"]?.split(',') ?: emptyList()
        val isMod = badges.any { it.startsWith("moderator") || it.startsWith("broadcaster") }
        if (isMod != _isCurrentUserModerator.value) {
            _isCurrentUserModerator.value = isMod
        }
    }

    /** Parses IRC tags ("@a=b;c=d ...") into a map. Returns an empty map when there are none. */
    private fun ircTags(rawMessage: String): Map<String, String> {
        if (!rawMessage.startsWith("@")) return emptyMap()
        return rawMessage.substringAfter('@').substringBefore(" :").split(';').associate {
            val parts = it.split('=', limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
        }
    }

    /**
     * Handles a pinned message. Twitch sends a USERNOTICE with msg-id=announcement and
     * msg-param-pin-type=pin (or unpin) when a moderator pins/unpins a message.
     */
    private fun handlePinNotice(rawMessage: String) {
        val tags = ircTags(rawMessage)
        val pinType = tags["msg-param-pin-type"]
        if (pinType == "unpin") {
            _pinnedMessage.value = null
            return
        }
        val messageId = tags["msg-param-message-id"] ?: tags["id"]
        val body = tags["system-msg"]?.takeIf { it.isNotBlank() }
            ?: rawMessage.substringAfter(" :", "")
        val author = tags["msg-param-sender-name"]
            ?: tags["display-name"]
            ?: tags["login"]
            ?: ""
        if (messageId.isNullOrBlank() || body.isBlank()) return
        _pinnedMessage.value = PinnedMessage(
            messageId = messageId,
            author = author,
            message = body,
            pinnedBy = tags["login"]
        )
        Log.d(TAG, "Pinned message: $author: ${body.take(60)}")
    }

    private fun handlePollNotice(rawMessage: String) {
        val tagsPart = rawMessage.substringAfter('@').substringBefore(" :")
        val tags = tagsPart.split(';').associate {
            val parts = it.split('=', limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
        }

        val msgId = tags["msg-id"] ?: return

        when (msgId) {
            "channel.poll.begin", "channel.poll.progress" -> {
                try {
                    _poll.value = parsePollFromTags(tags)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse poll data")
                }
            }
            "channel.poll.end" -> {
                _poll.value = null
            }
        }
    }

    private fun parsePollFromTags(tags: Map<String, String>): Poll {
        val choices = mutableListOf<PollChoice>()
        for (i in 1..5) {
            val choiceId = tags["poll-choice-${i}-id"] ?: break
            val choiceTitle = tags["poll-choice-${i}-title"] ?: ""
            val choiceVotes = tags["poll-choice-${i}-votes"]?.toIntOrNull() ?: 0
            choices.add(PollChoice(id = choiceId, title = choiceTitle, votes = choiceVotes))
        }

        return Poll(
            id = tags["poll-id"] ?: "",
            title = tags["poll-title"] ?: "",
            choices = choices,
            status = tags["poll-status"]?.uppercase() ?: "ACTIVE",
            duration = tags["poll-duration-seconds"]?.toIntOrNull() ?: 0,
            broadcasterId = tags["room-id"] ?: ""
        )
    }

    private fun handleRoomState(rawMessage: String) {
        val tagsPart = rawMessage.substringAfter('@').substringBefore(" :")
        val tags = tagsPart.split(';').associate {
            val parts = it.split('=', limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
        }

        _roomState.value = RoomState(
            emoteOnly = tags["emote-only"] == "1",
            followersOnly = tags["followers-only"]?.toIntOrNull(),
            subsOnly = tags["subs-only"] == "1" || tags["subs_only"] == "1",
            r9k = tags["r9k"] == "1",
            slowMode = tags["slow"]?.toIntOrNull()
        )
    }

    private fun parseMessage(rawMessage: String): ChatMessage? {
        Log.d(TAG, "PARSING IRC MESSAGE: $rawMessage")
        val result = ChatMessageParser.parse(rawMessage)
        if (result != null) {
            Log.d(TAG, "PARSED OK: author=${result.authorLogin} message='${result.message}' type=${result.type}")
        } else {
            Log.d(TAG, "PARSE FAILED or returned null")
        }
        return result
    }


    fun voteOnPoll(pollId: String, choiceId: String) {
        serviceScope.launch {
            val ok = try {
                ChannelToolsApi.voteOnPoll(client, pollId, choiceId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to vote on poll", e)
                false
            }
            if (!ok) {
                _chatMessages.emit(
                    ChatMessage(
                        author = null,
                        authorLogin = null,
                        message = "Could not register your vote. Try again.",
                        authorColor = null,
                        type = MessageType.SYSTEM,
                        tags = mapOf("client" to "poll_vote_error")
                    )
                )
            }
        }
    }
}
