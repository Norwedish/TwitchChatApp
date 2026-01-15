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
import io.ktor.client.request.url
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

// Data classes for GQL Voting
@Serializable
data class GqlRequest(val operationName: String, val variables: GqlVariables, val extensions: GqlExtensions)

@Serializable
data class GqlVariables(val input: GqlInput)

@Serializable
data class GqlInput(val pollID: String, val choiceID: String)

@Serializable
data class GqlExtensions(@SerialName("persistedQuery") val persistedQuery: GqlPersistedQuery)

@Serializable
data class GqlPersistedQuery(val version: Int, val sha256Hash: String)


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
            Log.d(TAG, "Loaded suppressed NOTICE msg-ids: ${suppressedRoomStateNotices}")
        } catch (t: Throwable) {
            suppressedRoomStateNotices.clear()
            suppressedRoomStateNotices.addAll(defaultSuppressedNotices)
        }
    }

    private fun saveSuppressedNoticesToPrefs() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(PREF_KEY_SUPPRESSED, suppressedRoomStateNotices.joinToString(",")).apply()
            Log.d(TAG, "Saved suppressed NOTICE msg-ids: ${suppressedRoomStateNotices}")
        } catch (_: Throwable) { /* best-effort */ }
    }

    fun isNoticeSuppressed(msgId: String): Boolean = suppressedRoomStateNotices.contains(msgId)

    fun getSuppressedNotices(): Set<String> = HashSet(suppressedRoomStateNotices)

    fun setSuppressedNotices(newSet: Set<String>) {
        suppressedRoomStateNotices.clear()
        suppressedRoomStateNotices.addAll(newSet)
        saveSuppressedNoticesToPrefs()
    }

    fun toggleSuppressedNotice(msgId: String, enabled: Boolean) {
        if (enabled) suppressedRoomStateNotices.add(msgId) else suppressedRoomStateNotices.remove(msgId)
        saveSuppressedNoticesToPrefs()
    }

    private val client = HttpClient(CIO) {
        install(WebSockets)
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val _chatMessages = MutableSharedFlow<ChatMessage>()
    val chatMessages = _chatMessages.asSharedFlow()

    private val _deletedMessageIds = MutableSharedFlow<String>()
    val deletedMessageIds = _deletedMessageIds.asSharedFlow()

    private val _deletedUserMessages = MutableSharedFlow<String>()
    val deletedUserMessages = _deletedUserMessages.asSharedFlow()

    private val _isCurrentUserModerator = MutableStateFlow(false)
    val isCurrentUserModerator = _isCurrentUserModerator.asSharedFlow()

    private var session: DefaultClientWebSocketSession? = null
    private var connectionJob: Job? = null
    var currentChannel: String = ""

    // Track whether we have successfully JOINed the current channel.
    // Without join, Twitch will not accept PRIVMSG to that channel.
    @Volatile
    private var hasJoinedChannel: Boolean = false

    private val _poll = MutableStateFlow<Poll?>(null)
    val poll = _poll.asStateFlow()

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

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Twitch Chat")
            .setContentText("Connected to #$channelName")
            .setSmallIcon(R.drawable.transparentlogo)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        connectAndJoin(channelName)

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        disconnect()
    }

    private fun createNotificationChannel() {
        val name = "Twitch Chat Service"
        val descriptionText = "Notification channel for the chat service"
        val importance = NotificationManager.IMPORTANCE_DEFAULT
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
            // Disable sound and vibration for chat notifications (user requested)
            setSound(null, null)
            enableVibration(false)
        }
        val notificationManager:
                NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    fun connectAndJoin(channelName: String) {
        if (connectionState.value == ConnectionState.CONNECTED || connectionState.value == ConnectionState.CONNECTING) {
            return
        }

        val token = UserManager.accessToken
        val nick = UserManager.currentUser?.login

        if (token == null || nick == null) {
            // Surface a user-visible reason.
            serviceScope.launch {
                _chatMessages.emit(
                    ChatMessage(
                        author = null,
                        authorLogin = null,
                        message = "Not logged in — can't connect to chat.",
                        authorColor = null,
                        type = MessageType.SYSTEM,
                        tags = mapOf("client" to "auth_missing")
                    )
                )
            }
            return
        }

        // Kick off emote loading for the emote menu (best-effort; independent from IRC).
        // NOTE: This requires the *channel/broadcaster* user id, not the currently logged-in user id.
        // We resolve it using the Helix users endpoint.
        serviceScope.launch {
            try {
                val broadcasterId = resolveBroadcasterIdByLogin(normalizeChannelName(channelName), token)
                if (!broadcasterId.isNullOrBlank()) {
                    EmoteManager.loadEmotesForChannel(broadcasterId, token, UserManager.CLIENT_ID)
                } else {
                    Log.w(TAG, "Couldn't resolve broadcaster id for #${normalizeChannelName(channelName)}; emote menu may be empty.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Emote preload failed: ${e.message}")
            }
        }

        currentChannel = channelName
        hasJoinedChannel = false

        connectionJob?.cancel()
        currentChatters.clear() // Clear chatters for the new channel
        _chatters.value = emptyList()
        _roomState.value = null

        connectionJob = serviceScope.launch {
            _connectionState.value = ConnectionState.CONNECTING
            try {
                client.wss(
                    method = HttpMethod.Get,
                    host = "irc-ws.chat.twitch.tv",
                    port = 443,
                    path = ""
                ) {
                    session = this

                    // Twitch IRC expects CRLF-terminated commands.
                    // Also normalize token to avoid accidentally sending oauth:oauth:...
                    val normalizedToken = token.removePrefix("oauth:")

                    sendIrc("PASS oauth:$normalizedToken")
                    sendIrc("NICK $nick")
                    sendIrc("CAP REQ :twitch.tv/membership")
                    sendIrc("CAP REQ :twitch.tv/tags")
                    sendIrc("CAP REQ :twitch.tv/commands")

                    listenToMessages(channelName)
                }
            } catch (_: CancellationException) {
                // This is expected when the job is cancelled
            } catch (_: Exception) {
                _connectionState.value = ConnectionState.ERROR
            } finally {
                session?.close()
                session = null
                hasJoinedChannel = false
                if (_connectionState.value != ConnectionState.ERROR) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
            }
        }
    }

    private suspend fun resolveBroadcasterIdByLogin(login: String, token: String): String? {
        return try {
            // TwitchApi doesn't currently expose a getUserByLogin helper; use the raw Helix users endpoint.
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
        _isCurrentUserModerator.value = false // Reset mod status on disconnect
        hasJoinedChannel = false
        connectionJob?.cancel()
        _poll.value = null // Clear the poll when disconnecting
        _roomState.value = null
    }

    fun sendMessage(channelName: String, message: String, replyToMessageId: String? = null) {
        val activeSession = session
        val normalizedChannel = normalizeChannelName(channelName)
        if (message.isBlank()) return

        // If we aren't actually joined yet, don't pretend we sent.
        if (!hasJoinedChannel || _connectionState.value != ConnectionState.CONNECTED || activeSession?.isActive != true) {
            serviceScope.launch {
                _chatMessages.emit(
                    ChatMessage(
                        author = null,
                        authorLogin = null,
                        message = "Not connected to #$normalizedChannel yet — message wasn't sent.",
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
                val ircMessage = if (replyToMessageId != null) {
                    "@reply-parent-msg-id=$replyToMessageId PRIVMSG #$normalizedChannel :$message"
                } else {
                    "PRIVMSG #$normalizedChannel :$message"
                }

                // Always send CRLF terminated IRC command.
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
                messageText.split("\r\n").forEach { rawMessage ->
                    if (rawMessage.startsWith("PING")) {
                        sendIrc("PONG :tmi.twitch.tv")
                        return@forEach
                    }

                    val joinMatch = Regex(":([^!]+)!.* JOIN #").find(rawMessage)
                    val partMatch = Regex(":([^!]+)!.* PART #").find(rawMessage)

                    if (rawMessage.contains(" 001 ")) { // Welcome message: authentication is successful
                        Log.d(TAG, "Authentication successful. Sending JOIN command.")
                        hasJoinedChannel = false
                        sendIrc("JOIN #$normalizedChannel")
                        // We'll mark CONNECTED when we actually see that we joined / got room state.
                    } else if (rawMessage.contains("ROOMSTATE") && rawMessage.contains("#$normalizedChannel")) {
                        // ROOMSTATE is a strong signal that we've successfully joined the channel.
                        hasJoinedChannel = true
                        _connectionState.value = ConnectionState.CONNECTED
                        handleRoomState(rawMessage)
                    } else if (rawMessage.contains("USERSTATE") && rawMessage.contains("#$normalizedChannel")) {
                        // USERSTATE also indicates we are in the channel.
                        hasJoinedChannel = true
                        _connectionState.value = ConnectionState.CONNECTED
                        handleUserState(rawMessage)
                    } else if (rawMessage.contains(" 353 ")) { // NAMES list
                        val usersPart = rawMessage.substringAfterLast(':')
                        val users = usersPart.trim().split(' ').filter { it.isNotBlank() }
                        currentChatters.addAll(users)
                        _chatters.value = currentChatters.toList().sorted()
                        Log.d(TAG, "Parsed ${users.size} chatters from NAMES. Total: ${currentChatters.size}")
                    } else if (rawMessage.contains(" 366 ")) { // End of NAMES list
                        _chatters.value = currentChatters.toList().sorted()
                        Log.d(TAG, "End of NAMES list. Current chatter count: ${currentChatters.size}")
                        // DO NOT CLEAR THE LIST
                    }
                    else if (joinMatch != null) {
                        val username = joinMatch.groupValues[1]
                        if (currentChatters.add(username)) {
                            _chatters.value = currentChatters.toList().sorted()
                            Log.d(TAG, "User JOINED: $username. Total: ${currentChatters.size}")
                        }

                        // If the JOIN is for the currently logged-in user, we're joined.
                        val currentLogin = UserManager.currentUser?.login
                        if (!currentLogin.isNullOrBlank() && username.equals(currentLogin, ignoreCase = true)) {
                            hasJoinedChannel = true
                            _connectionState.value = ConnectionState.CONNECTED
                        }
                    } else if (partMatch != null) {
                        val username = partMatch.groupValues[1]
                        if (currentChatters.remove(username)) {
                            _chatters.value = currentChatters.toList().sorted()
                            Log.d(TAG, "User PARTED: $username. Total: ${currentChatters.size}")
                        }
                    } else if (rawMessage.contains("USERNOTICE") && rawMessage.contains("msg-id=channel.poll.")) {
                        handlePollNotice(rawMessage)
                    } else if (rawMessage.contains("USERNOTICE")) {
                        // USERNOTICE lines (subs, raids, announcements) - parse and emit, and log debug info to help troubleshoot
                        try {
                            val parsed = parseMessage(rawMessage)
                            Log.d(TAG, "USERNOTICE raw=${rawMessage.replace("\n", "\\n")} | parseInfo=${ChatMessageParser.debugParseInfo(rawMessage)} | parsed=$parsed")
                            parsed?.let {
                                serviceScope.launch { _chatMessages.emit(it) }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse USERNOTICE", e)
                        }
                    } else if (rawMessage.contains("CLEARMSG")) {
                        handleClearMessage(rawMessage)
                    } else if (rawMessage.contains("CLEARCHAT")) {
                        handleClearChat(rawMessage)
                    } else if (rawMessage.contains("NOTICE")) {
                        handleNotice(rawMessage)
                    }
                    else {
                        val parsed = parseMessage(rawMessage)
                        if (parsed == null || parsed.message.isBlank()) {
                            // Log the raw message and parser debug info to help troubleshooting
                            Log.d(TAG, "Failed to parse chat message. raw=${rawMessage.replace("\n", "\\n")}")
                            try {
                                Log.d(TAG, "parseInfo=${ChatMessageParser.debugParseInfo(rawMessage)}")
                            } catch (_: Exception) { /* ignore debug helper failures */ }

                            if (rawMessage.contains("PRIVMSG")) {
                                try {
                                    val simpleMessage = rawMessage.substringAfter("PRIVMSG ").substringAfter(":", "").trim()
                                    val authorMatch = Regex("^:([^!]+)!").find(rawMessage)
                                    val author = authorMatch?.groupValues?.get(1)
                                    if (simpleMessage.isNotBlank()) {
                                        Log.d(TAG, "Parser fallback used for PRIVMSG; author=$author message=${simpleMessage}")
                                        val fallback = ChatMessage(
                                            author = author,
                                            authorLogin = author,
                                            message = simpleMessage,
                                            authorColor = null
                                        )
                                        serviceScope.launch { _chatMessages.emit(fallback) }
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Fallback parse failed: ${e.message}")
                                }
                            }
                        } else {
                            serviceScope.launch { _chatMessages.emit(parsed) }
                        }
                    }
                }
            }
        }
    }

    // --- IRC helpers ---
    private fun normalizeChannelName(channelName: String): String = channelName.trim().removePrefix("#")

    private suspend fun DefaultClientWebSocketSession.sendIrc(line: String) {
        // Twitch IRC expects \r\n and rejects NUL. Avoid double terminators.
        val sanitized = line.replace("\u0000", "").trimEnd('\r', '\n')
        send(Frame.Text("$sanitized\r\n"))
    }

    private fun handleNotice(rawMessage: String) {
        val tagsPart = rawMessage.substringAfter('@').substringBefore(" :")
        val messagePart = rawMessage.substringAfter(":")
        val tags = tagsPart.split(';').associate {
            val parts = it.split('=', limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
        }
        val noticeMsgId = tags["msg-id"] ?: ""
        if (isNoticeSuppressed(noticeMsgId)) {
            Log.d(TAG, "Suppressing NOTICE msg-id=$noticeMsgId (configured suppression)")
            return
        }
        val systemMessage = messagePart
        serviceScope.launch {
            _chatMessages.emit(
                ChatMessage(
                author = null,
                authorLogin = null,
                message = systemMessage,
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

    private fun handleClearChat(rawMessage: String) {
        val targetUser = rawMessage.substringAfterLast(":", "").trim()
        if (targetUser.isNotEmpty()) {
            serviceScope.launch {
                _deletedUserMessages.emit(targetUser)
            }
        }
    }

    private fun handleUserState(rawMessage: String) {
        val tagsPart = rawMessage.substringAfter('@').substringBefore(" :")
        val tags = tagsPart.split(';').associate {
            val parts = it.split('=', limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
        }
        val badges = tags["badges"]?.split(',') ?: emptyList()
        val isMod = badges.any { it.startsWith("moderator") || it.startsWith("broadcaster") }
        if (isMod != _isCurrentUserModerator.value) {
            _isCurrentUserModerator.value = isMod
        }
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
                    Log.e(TAG, "Failed to parse poll data from tags", e)
                }
            }
            "channel.poll.end" -> {
                _poll.value = null
            }
        }
    }

    private fun parsePollFromTags(tags: Map<String, String>): Poll {
        val choices = mutableListOf<PollChoice>()
        for (i in 1..5) { // Twitch polls have a max of 5 choices
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
        // Use the centralized parser implementation for consistency and better tag handling
        return ChatMessageParser.parse(rawMessage)
    }


    fun voteOnPoll(pollId: String, choiceId: String) {
        serviceScope.launch {
            val token = UserManager.accessToken ?: return@launch
            try {
                val request = GqlRequest(
                    operationName = "PollVote",
                    variables = GqlVariables(input = GqlInput(pollID = pollId, choiceID = choiceId)),
                    extensions = GqlExtensions(
                        persistedQuery = GqlPersistedQuery(
                            version = 1,
                            sha256Hash = "968dba31919a2d89369931b753472097951c3a64b9795029ba26a9096734e064"
                        )
                    )
                )

                client.post("https://gql.twitch.tv/gql") {
                    header("Authorization", "OAuth $token")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(request))
                }
            } catch (e: Exception) {
                // Handle GQL error
            }
        }
    }
}
