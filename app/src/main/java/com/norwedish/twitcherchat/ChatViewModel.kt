package com.norwedish.twitcherchat

import android.util.Log
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.UUID

/**
 * Clean, compact ChatViewModel that provides the state and operations the UI expects.
 * This replaces a corrupted implementation and aims to be behaviorally compatible.
 */
class ChatViewModel : ViewModel() {
    companion object { private const val TAG = "ChatViewModel" }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _inputMessage = MutableStateFlow(TextFieldValue(""))
    val inputMessage: StateFlow<TextFieldValue> = _inputMessage.asStateFlow()

    private val _isEmoteMenuVisible = MutableStateFlow(false)
    val isEmoteMenuVisible: StateFlow<Boolean> = _isEmoteMenuVisible.asStateFlow()

    enum class EmoteTab(val label: String, val provider: EmoteProvider?) {
        TWITCH("Twitch", EmoteProvider.TWITCH),
        BTTV("BTTV", EmoteProvider.BTTV),
        SEVENTV("7TV", EmoteProvider.SEVENTV),
        FFZ("FFZ", EmoteProvider.FRANKENFACEZ)
    }

    private val _selectedEmoteTab = MutableStateFlow(EmoteTab.TWITCH)
    val selectedEmoteTab: StateFlow<EmoteTab> = _selectedEmoteTab.asStateFlow()

    private val _availableEmotes = MutableStateFlow<List<Emote>>(emptyList())
    val availableEmotes: StateFlow<List<Emote>> = _availableEmotes.asStateFlow()

    // Filtered list the UI should render in the grid
    private val _visibleEmotes = MutableStateFlow<List<Emote>>(emptyList())
    val visibleEmotes: StateFlow<List<Emote>> = _visibleEmotes.asStateFlow()

    // Tabs should be dynamic: only show providers that actually have emotes loaded.
    private val _availableEmoteTabs = MutableStateFlow<List<EmoteTab>>(listOf(EmoteTab.TWITCH))
    val availableEmoteTabs: StateFlow<List<EmoteTab>> = _availableEmoteTabs.asStateFlow()

    private fun recomputeAvailableTabs(all: List<Emote>) {
        val providersPresent = all.map { it.provider }.toSet()
        Log.d(TAG, "Recomputing tabs. Providers present: $providersPresent")
        val tabs = EmoteTab.entries.filter { it.provider in providersPresent }
        Log.d(TAG, "Computed tabs: ${tabs.map { it.label }}")
        _availableEmoteTabs.value = tabs

        // Ensure current selection is still valid.
        val current = _selectedEmoteTab.value
        if (current !in tabs) {
            _selectedEmoteTab.value = tabs.firstOrNull() ?: EmoteTab.TWITCH
            Log.d(TAG, "Current tab ${current.label} not available, switched to ${_selectedEmoteTab.value.label}")
        }
    }

    private fun refreshVisibleEmotes() {
        val tab = _selectedEmoteTab.value
        val all = _availableEmotes.value
        Log.d(TAG, "Refreshing visible emotes for tab ${tab.label}")
        recomputeAvailableTabs(all)
        val filtered = all.filter { it.provider == tab.provider }
        Log.d(TAG, "Filtered emotes for ${tab.label}: ${filtered.size} (from total ${all.size})")
        _visibleEmotes.value = filtered
    }

    fun onEmoteTabSelected(tab: EmoteTab) {
        _selectedEmoteTab.value = tab
        refreshVisibleEmotes()
    }

    private val _isCurrentUserModerator = MutableStateFlow(false)
    val isCurrentUserModerator: StateFlow<Boolean> = _isCurrentUserModerator.asStateFlow()

    private val _selectedUserForProfile = MutableStateFlow<ChatMessage?>(null)
    val selectedUserForProfile: StateFlow<ChatMessage?> = _selectedUserForProfile.asStateFlow()

    private val _viewerCount = MutableStateFlow<Int?>(null)
    val viewerCount: StateFlow<Int?> = _viewerCount.asStateFlow()

    private val _streamTitle = MutableStateFlow<String?>(null)
    val streamTitle: StateFlow<String?> = _streamTitle.asStateFlow()

    private val _poll = MutableStateFlow<Poll?>(null)
    val poll: StateFlow<Poll?> = _poll.asStateFlow()

    private val _isChatterListVisible = MutableStateFlow(false)
    val isChatterListVisible: StateFlow<Boolean> = _isChatterListVisible.asStateFlow()

    private val _isChattersLoading = MutableStateFlow(false)
    val isChattersLoading: StateFlow<Boolean> = _isChattersLoading.asStateFlow()

    private val _chatters = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val chatters: StateFlow<Map<String, List<String>>> = _chatters.asStateFlow()

    private val _chatterListLimitedHint = MutableStateFlow<String?>(null)
    val chatterListLimitedHint: StateFlow<String?> = _chatterListLimitedHint.asStateFlow()

    private val _userSuggestions = MutableStateFlow<List<String>>(emptyList())
    val userSuggestions: StateFlow<List<String>> = _userSuggestions.asStateFlow()

    private val _showUserSuggestions = MutableStateFlow(false)
    val showUserSuggestions: StateFlow<Boolean> = _showUserSuggestions.asStateFlow()

    private val _roomState = MutableStateFlow<RoomState?>(null)
    val roomState: StateFlow<RoomState?> = _roomState.asStateFlow()

    private val _replyToMessage = MutableStateFlow<ChatMessage?>(null)
    val replyToMessage: StateFlow<ChatMessage?> = _replyToMessage.asStateFlow()

    private val _followRelationship = MutableStateFlow<FollowedChannel?>(null)
    val followRelationship: StateFlow<FollowedChannel?> = _followRelationship.asStateFlow()

    private val _isSubscriber = MutableStateFlow<Boolean?>(null)
    val isSubscriber: StateFlow<Boolean?> = _isSubscriber.asStateFlow()

    private val _unreadMessageCount = MutableStateFlow(0)
    val unreadMessageCount: StateFlow<Int> = _unreadMessageCount.asStateFlow()

    private val _isBanned = MutableStateFlow(false)
    val isBanned: StateFlow<Boolean> = _isBanned.asStateFlow()

    private val _scrollToBottom = MutableSharedFlow<Unit>(replay = 0)
    val scrollToBottom = _scrollToBottom.asSharedFlow()

    // internal
    private var currentChannelId: String = ""
    private var currentChannel: String = ""
    private var pollingJob: Job? = null
    private var chatServiceRef: java.lang.ref.WeakReference<ChatService>? = null

    private val messageBuffer = Collections.synchronizedList(mutableListOf<ChatMessage>())
    private var messageProcessingJob: Job? = null
    private var isUserAtBottom = true

    // Simple in-memory cache for fetchChatters
    private var lastChattersFetchMillis: Long = 0
    private var cachedChattersForChannel: Map<String, List<String>>? = null
    private var cachedChattersChannelId: String? = null
    private val chatterCacheTtlMs: Long = 5_000

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
        messageProcessingJob?.cancel()
    }

    fun onScrollStateChanged(isAtBottom: Boolean) {
        isUserAtBottom = isAtBottom
        if (isUserAtBottom) _unreadMessageCount.value = 0
    }

    fun jumpToBottom() {
        viewModelScope.launch { _scrollToBottom.emit(Unit) }
        _unreadMessageCount.value = 0
        isUserAtBottom = true
    }

    fun setChatService(service: ChatService) {
        chatServiceRef = java.lang.ref.WeakReference(service)

        // cancel previous processing job
        messageProcessingJob?.cancel()

        // Collect messages from service into local buffer and process periodically
        messageProcessingJob = viewModelScope.launch {
            // message collector
            launch {
                service.chatMessages.collect { msg ->
                    messageBuffer.add(msg)
                    // update user color if matches
                    try {
                        if (msg.author.equals(UserManager.currentUser?.login, ignoreCase = true)) {
                            msg.authorColor?.let { color -> if (color != UserManager.currentUser?.chatColor) UserManager.updateUserChatColor(color) }
                        }
                    } catch (_: Exception) {}
                }
            }

            // periodic flusher
            launch {
                while (true) {
                    delay(500)
                    if (messageBuffer.isNotEmpty()) {
                        val toAdd = synchronized(messageBuffer) {
                            val copy = ArrayList(messageBuffer)
                            messageBuffer.clear()
                            copy
                        }

                        if (!isUserAtBottom) _unreadMessageCount.value += toAdd.size

                        _messages.update { current -> (current + toAdd).takeLast(200) }

                        if (isUserAtBottom) {
                            launch {
                                delay(150)
                                _scrollToBottom.emit(Unit)
                            }
                        }
                    }
                }
            }
        }

        // other collectors
        viewModelScope.launch {
            service.connectionState.collect { _connectionState.value = it }
        }
        viewModelScope.launch {
            service.poll.collect { _poll.value = it }
        }
        viewModelScope.launch {
            service.isCurrentUserModerator.collect { _isCurrentUserModerator.value = it }
        }
        viewModelScope.launch {
            service.roomState.collect { _roomState.value = it }
        }
        viewModelScope.launch {
            service.deletedMessageIds.collect { id ->
                _messages.update { list -> list.map { if (it.tags["id"] == id) it.copy(type = MessageType.DELETED) else it } }
            }
        }
        viewModelScope.launch {
            service.deletedUserMessages.collect { author ->
                _messages.update { list -> list.map { if (it.authorLogin.equals(author, ignoreCase = true)) it.copy(type = MessageType.DELETED) else it } }
                val cur = UserManager.currentUser?.login
                if (cur != null && cur.equals(author, ignoreCase = true)) _isBanned.value = true
            }
        }
        viewModelScope.launch {
            service.chatters.collect { viewers ->
                // When service emits, refresh the chatter list (best-effort)
                fetchChatters()
                val currentUserLogin = UserManager.currentUser?.login
                if (currentUserLogin != null && viewers.contains(currentUserLogin)) _isBanned.value = false
            }
        }
    }

    fun fetchChatters() {
        viewModelScope.launch {
            _isChattersLoading.value = true

            val now = System.currentTimeMillis()
            if (cachedChattersChannelId == currentChannelId && cachedChattersForChannel != null && (now - lastChattersFetchMillis) < chatterCacheTtlMs) {
                _chatters.value = cachedChattersForChannel!!
                _isChattersLoading.value = false
                return@launch
            }

            val currentUser = UserManager.currentUser
            val token = UserManager.accessToken

            try {
                if (currentUser != null && token != null) {
                    val isPrivileged = _isCurrentUserModerator.value || currentUser.login.equals(currentChannel, ignoreCase = true)
                    if (isPrivileged) {
                        val helix = TwitchApi.getHelixChatters(currentChannelId, currentUser.id, token, UserManager.CLIENT_ID)
                        if (helix != null) {
                            val grouped = mutableMapOf<String, MutableList<String>>("Broadcaster" to mutableListOf(), "Moderators" to mutableListOf(), "VIPs" to mutableListOf(), "Viewers" to mutableListOf())
                            helix.broadcaster.forEach { grouped["Broadcaster"]?.add(it.userName) }
                            helix.moderators.forEach { grouped["Moderators"]?.add(it.userName) }
                            helix.vips.forEach { grouped["VIPs"]?.add(it.userName) }
                            helix.viewers.forEach { grouped["Viewers"]?.add(it.userName) }
                            grouped.forEach { (_, list) -> list.sortWith(String.CASE_INSENSITIVE_ORDER) }
                            _chatters.value = grouped

                            cachedChattersForChannel = grouped
                            cachedChattersChannelId = currentChannelId
                            lastChattersFetchMillis = System.currentTimeMillis()
                            _chatterListLimitedHint.value = null
                            _isChattersLoading.value = false
                            return@launch
                        }
                    }
                }

                // Fallback to local service chatters
                chatServiceRef?.get()?.let { service ->
                    val viewers = service.chatters.value
                    val grouped = mutableMapOf<String, MutableList<String>>()
                    // If privileged, try to fetch mods/vips; otherwise just viewers
                    val isPrivileged = _isCurrentUserModerator.value || UserManager.currentUser?.login?.equals(currentChannel, ignoreCase = true) == true
                    if (isPrivileged) {
                        grouped.putAll(mapOf("Broadcaster" to mutableListOf(), "Moderators" to mutableListOf(), "VIPs" to mutableListOf(), "Viewers" to mutableListOf()))
                        // best-effort enrichment
                        try {
                            UserManager.accessToken?.let { _ ->
                                // Conservative fallback: without dedicated endpoints, mark viewers and optionally the broadcaster.
                                val localSet = viewers.map { it }.toMutableList()
                                // If the current user is the broadcaster, ensure they are listed under Broadcaster
                                try {
                                    val currentBroadcaster = if (currentChannel.equals(UserManager.currentUser?.login, ignoreCase = true)) {
                                        UserManager.currentUser?.displayName ?: UserManager.currentUser?.login
                                    } else null
                                    currentBroadcaster?.let { bname ->
                                        if (localSet.any { it.equals(bname, ignoreCase = true) }) {
                                            grouped["Broadcaster"]?.add(bname)
                                            localSet.removeAll { it.equals(bname, ignoreCase = true) }
                                        }
                                    }
                                } catch (_: Exception) {}
                                // Remaining names considered viewers
                                localSet.forEach { name -> grouped["Viewers"]?.add(name) }
                             } ?: run {
                                 // no token: fall back to viewers only
                                 grouped["Viewers"]?.addAll(viewers)
                             }
                         } catch (e: Exception) {
                             Log.w(TAG, "Failed to enrich local chatters: ${e.message}")
                             grouped.clear()
                             grouped["Viewers"] = viewers.toMutableList()
                         }
                    } else {
                        grouped["Viewers"] = viewers.toMutableList()
                    }

                    grouped.forEach { (_, list) -> list.sortWith(String.CASE_INSENSITIVE_ORDER) }
                    _chatters.value = grouped
                    cachedChattersForChannel = grouped
                    cachedChattersChannelId = currentChannelId
                    lastChattersFetchMillis = System.currentTimeMillis()
                    _chatterListLimitedHint.value = "Full chatter list unavailable; showing local view only."
                } ?: run {
                    // No service: empty viewers
                    _chatters.value = mapOf("Viewers" to emptyList())
                    _isChattersLoading.value = false
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetchChatters failed: ${e.message}")
                _isChattersLoading.value = false
            }

            _isChattersLoading.value = false
        }
    }

    fun onChatterListRequested() { _isChatterListVisible.value = true; fetchChatters() }
    fun onChatterListDismissed() { _isChatterListVisible.value = false; _chatterListLimitedHint.value = null }
    fun dismissChatterListHint() { _chatterListLimitedHint.value = null }

    fun onShowUserProfile(message: ChatMessage) { _selectedUserForProfile.value = message }
    fun onDismissUserProfile() { _selectedUserForProfile.value = null }

    fun onTimeout(username: String) { modAction("timeout", username) }
    fun onBan(username: String) { modAction("ban", username) }

    fun onReply(message: ChatMessage) {
        _replyToMessage.value = message
        _inputMessage.update { currentTFV ->
            val currentText = currentTFV.text
            val newMention = "@${message.authorLogin ?: message.author ?: ""} "
            val mentionRegex = Regex("^@\\w+\\s")
            val newText = if (mentionRegex.containsMatchIn(currentText)) currentText.replaceFirst(mentionRegex, newMention) else currentText
            TextFieldValue(newText, TextRange(newText.length))
        }
    }

    fun clearReply() {
        _replyToMessage.value = null
        _inputMessage.update { currentTFV ->
            val currentText = currentTFV.text
            val mentionRegex = Regex("^@\\w+\\s")
            val newText = if (mentionRegex.containsMatchIn(currentText)) currentText.replaceFirst(mentionRegex, "") else currentText
            TextFieldValue(newText, TextRange(newText.length))
        }
    }

    fun onEmoteMenuToggled() {
        if (!_isEmoteMenuVisible.value) {
            val allEmotes = EmoteManager.getAllEmotes()
            Log.d(TAG, "Opening emote menu. Total emotes available: ${allEmotes.size}")
            _availableEmotes.value = allEmotes
            // Pick the first available provider tab (prefer Twitch if present)
            recomputeAvailableTabs(_availableEmotes.value)
            Log.d(TAG, "Available tabs: ${_availableEmoteTabs.value.map { it.label }}")
            _selectedEmoteTab.value = _availableEmoteTabs.value.firstOrNull { it == EmoteTab.TWITCH }
                ?: _availableEmoteTabs.value.firstOrNull()
                ?: EmoteTab.TWITCH
            Log.d(TAG, "Selected tab: ${_selectedEmoteTab.value.label}")
            refreshVisibleEmotes()
            Log.d(TAG, "Visible emotes for ${_selectedEmoteTab.value.label}: ${_visibleEmotes.value.size}")
        }
        _isEmoteMenuVisible.value = !_isEmoteMenuVisible.value
    }

    fun onEmoteSelected(emoteCode: String) {
        _inputMessage.update { current ->
            val newText = if (current.text.isBlank()) emoteCode else "${current.text} $emoteCode "
            TextFieldValue(newText, TextRange(newText.length))
        }
        onEmoteMenuToggled()
    }

    fun sendMessage() {
        val messageText = _inputMessage.value.text
        if (messageText.isBlank()) return
        val currentUser = UserManager.currentUser ?: return

        val messageId = UUID.randomUUID().toString()
        val localBadges = mutableListOf<String>()
        try {
            if (currentChannel.equals(currentUser.login, ignoreCase = true)) localBadges.add("broadcaster")
            if (_isCurrentUserModerator.value) localBadges.add("moderator")
        } catch (_: Exception) {}

        val localMessage = ChatMessage(
            id = messageId,
            author = currentUser.displayName,
            authorLogin = currentUser.login,
            message = messageText,
            authorColor = currentUser.chatColor ?: "#8A2BE2",
            emotes = EmoteManager.parseThirdPartyEmotes(messageText),
            badges = localBadges,
            replyParentMsgId = _replyToMessage.value?.tags?.get("id"),
            replyParentUserLogin = _replyToMessage.value?.authorLogin ?: _replyToMessage.value?.author,
            replyParentMsgBody = _replyToMessage.value?.message,
            type = MessageType.SYSTEM,
            tags = mapOf("client" to "pending_send")
        )

        // Add a local pending item, but don't pretend it's a real sent/received message.
        _messages.update { current -> (current + listOf(localMessage)).takeLast(200) }

        val replyId = localMessage.replyParentMsgId

        try {
            chatServiceRef?.get()?.sendMessage(currentChannel, messageText, replyId)
        } catch (_: Exception) {
            // If we can't send at all, mark as failed.
            _messages.update { current ->
                current.map {
                    if (it.id == messageId) it.copy(message = "Failed to send (service unavailable).", type = MessageType.SYSTEM, tags = mapOf("client" to "send_failed")) else it
                }
            }
        }

        // Replace the pending item with a failure if we don't see our own echo back within a short window.
        viewModelScope.launch {
            delay(8_000)
            _messages.update { current ->
                val stillPending = current.any { it.id == messageId && it.tags["client"] == "pending_send" }
                if (!stillPending) return@update current
                current.map {
                    if (it.id == messageId) it.copy(message = "Message wasn't confirmed by Twitch (not sent?).", type = MessageType.SYSTEM, tags = mapOf("client" to "send_unconfirmed")) else it
                }
            }
        }

        _inputMessage.value = TextFieldValue("")
        _replyToMessage.value = null
    }

    fun voteOnPoll(pollId: String, choiceId: String) {
        try { chatServiceRef?.get()?.voteOnPoll(pollId, choiceId) } catch (_: Exception) {}
    }

    private fun modAction(action: String, username: String) {
        val command = when (action) {
            "ban" -> "/ban $username"
            "timeout" -> "/timeout $username 600"
            else -> return
        }
        chatServiceRef?.get()?.sendMessage(currentChannel, command)
    }

    fun onInputChanged(newValue: TextFieldValue) {
        _inputMessage.value = newValue
        val text = newValue.text
        val cursorPosition = newValue.selection.start.coerceIn(0, text.length)
        val lastAt = text.substring(0, cursorPosition).lastIndexOf('@')

        if (lastAt != -1) {
            val query = text.substring(lastAt + 1, cursorPosition)
            if (" " !in query) {
                val allChatters = _chatters.value.values.flatten().distinct()
                _userSuggestions.value = allChatters.filter { it.startsWith(query, ignoreCase = true) }
                _showUserSuggestions.value = _userSuggestions.value.isNotEmpty()
                return
            }
        }
        _showUserSuggestions.value = false
    }

    fun onUserSuggestionSelected(username: String) {
        val currentTFV = _inputMessage.value
        val currentText = currentTFV.text
        val cursorPosition = currentTFV.selection.start.coerceIn(0, currentText.length)
        val startOfMention = currentText.substring(0, cursorPosition).lastIndexOf('@')
        if (startOfMention == -1) { _showUserSuggestions.value = false; return }

        val prefix = currentText.substring(0, startOfMention)
        var endOfMention = cursorPosition
        while (endOfMention < currentText.length && currentText[endOfMention] != ' ') endOfMention++
        val suffix = currentText.substring(endOfMention)

        val newText = buildString {
            append(prefix)
            append('@')
            append(username)
            if (suffix.isEmpty() || !suffix.startsWith(' ')) append(' ')
            append(suffix)
        }
        val newCursorPosition = prefix.length + 1 + username.length + 1
        val annotated = buildAnnotatedString { append(newText) }
        _inputMessage.value = TextFieldValue(annotated, TextRange(newCursorPosition))
        _showUserSuggestions.value = false
    }

    // Expose setters for current channel/context
    fun setCurrentChannel(channelId: String, channelName: String) {
        currentChannelId = channelId
        currentChannel = channelName
        // Restart stream metadata polling for the active channel
        stopPolling()
        if (channelId.isNotBlank()) startPolling(channelId)
    }

    // Poll stream metadata (title, viewer count) periodically for the active channel
    private fun startPolling(userId: String) {
        stopPolling()
        pollingJob = viewModelScope.launch {
            while (true) {
                try {
                    UserManager.accessToken?.let { token ->
                        val stream = TwitchApi.getStream(userId, token, UserManager.CLIENT_ID)
                        _viewerCount.value = stream?.viewerCount
                        _streamTitle.value = stream?.title
                    } ?: run {
                        // If no token, try without auth (may return limited info)
                        val stream = TwitchApi.getStream(userId, "", UserManager.CLIENT_ID)
                        _viewerCount.value = stream?.viewerCount
                        _streamTitle.value = stream?.title
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to poll stream metadata: ${e.message}")
                }
                delay(60_000)
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }
}
