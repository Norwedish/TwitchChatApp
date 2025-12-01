package com.norwedish.twitcherchat

/**
 * ViewModel that manages chat state for a single channel.
 *
 * Responsibilities include:
 * - Maintaining the list of messages and UI state (input, emote menu, selected user, etc.)
 * - Managing background collection jobs that synchronize with ChatService
 * - Exposing flows and state for the UI to observe
 */

import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
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

class ChatViewModel : ViewModel() {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _inputMessage = MutableStateFlow(TextFieldValue(""))
    val inputMessage: StateFlow<TextFieldValue> = _inputMessage.asStateFlow()

    private val _isEmoteMenuVisible = MutableStateFlow(false)
    val isEmoteMenuVisible: StateFlow<Boolean> = _isEmoteMenuVisible.asStateFlow()

    private val _availableEmotes = MutableStateFlow<List<Emote>>(emptyList())
    val availableEmotes: StateFlow<List<Emote>> = _availableEmotes.asStateFlow()

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

    // Expose a hint message the UI can show when the chatter list is limited
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

    private var currentChannelId: String = ""
    private var currentChannel: String = ""
    private var pollingJob: Job? = null
    private var chatServiceRef: java.lang.ref.WeakReference<ChatService>? = null
    private var messageCollectionJob: Job? = null
    private var messageProcessingJob: Job? = null
    private var stateCollectionJob: Job? = null
    private var pollCollectionJob: Job? = null
    private var modStatusCollectionJob: Job? = null
    private var deletedMessagesJob: Job? = null
    private var deletedUserMessagesJob: Job? = null
    private var roomStateCollectionJob: Job? = null
    private val messageBuffer = Collections.synchronizedList(mutableListOf<ChatMessage>())
    private var isUserAtBottom = true

    // Simple in-memory cache/debounce for fetchChatters to avoid repeated calls
    private var lastChattersFetchMillis: Long = 0
    private var cachedChattersForChannel: Map<String, List<String>>? = null
    private var cachedChattersChannelId: String? = null
    private val chatterCacheTtlMs: Long = 5_000 // 5 seconds

    override fun onCleared() {
        super.onCleared()
        stopPolling()
        messageProcessingJob?.cancel()
    }

    fun onScrollStateChanged(isAtBottom: Boolean) {
        isUserAtBottom = isAtBottom
        if (isUserAtBottom) {
            _unreadMessageCount.value = 0
        }
    }

    fun jumpToBottom() {
        viewModelScope.launch {
            _scrollToBottom.emit(Unit)
        }
        _unreadMessageCount.value = 0
        isUserAtBottom = true
        // Jumping to bottom should not alter ban state by itself; keep banned state until server clears it.
    }

    fun setChatService(service: ChatService) {
        chatServiceRef = java.lang.ref.WeakReference(service)
        // Cancel all previous jobs
        messageCollectionJob?.cancel()
        stateCollectionJob?.cancel()
        pollCollectionJob?.cancel()
        modStatusCollectionJob?.cancel()
        deletedMessagesJob?.cancel()
        deletedUserMessagesJob?.cancel()
        messageProcessingJob?.cancel()
        roomStateCollectionJob?.cancel()
        messageBuffer.clear()

        messageCollectionJob = viewModelScope.launch {
            service.chatMessages.collect { chatMessage ->
                messageBuffer.add(chatMessage)
                if (chatMessage.author.equals(UserManager.currentUser?.login, ignoreCase = true)) {
                    if (chatMessage.authorColor != null && chatMessage.authorColor != UserManager.currentUser?.chatColor) {
                        UserManager.updateUserChatColor(chatMessage.authorColor)
                    }
                }
            }
        }

        messageProcessingJob = viewModelScope.launch {
            while (true) {
                delay(500) // Update every half second
                if (messageBuffer.isNotEmpty()) {
                    val messagesToAdd = synchronized(messageBuffer) {
                        val bufferCopy = ArrayList(messageBuffer)
                        messageBuffer.clear()
                        bufferCopy
                    }
                    if (!isUserAtBottom) {
                        _unreadMessageCount.value += messagesToAdd.size
                    }

                    _messages.update { currentList ->
                        val newList = (currentList + messagesToAdd).takeLast(200)
                        newList
                    }

                    // If the user is at the bottom, trigger a scroll-to-bottom event so the UI will auto-scroll
                    if (isUserAtBottom) {
                        // Delay slightly to give the UI time to recompose and measure the new list
                        viewModelScope.launch {
                            delay(150)
                            _scrollToBottom.emit(Unit)
                        }
                    }
                }
            }
        }

        stateCollectionJob = viewModelScope.launch {
            service.connectionState.collect { state ->
                _connectionState.value = state
            }
        }
        pollCollectionJob = viewModelScope.launch {
            service.poll.collect { poll ->
                _poll.value = poll
            }
        }
        modStatusCollectionJob = viewModelScope.launch {
            service.isCurrentUserModerator.collect { isMod ->
                _isCurrentUserModerator.value = isMod
            }
        }
        roomStateCollectionJob = viewModelScope.launch {
            service.roomState.collect { roomState ->
                _roomState.value = roomState
            }
        }

        deletedMessagesJob = viewModelScope.launch {
            service.deletedMessageIds.collect { deletedId ->
                _messages.update { currentMessages ->
                    val newList = currentMessages.map {
                        if (it.tags["id"] == deletedId) {
                            it.copy(type = MessageType.DELETED)
                        } else {
                            it
                        }
                    }
                    newList
                }
            }
        }

        deletedUserMessagesJob = viewModelScope.launch {
            service.deletedUserMessages.collect { deletedAuthor ->
                _messages.update { currentMessages ->
                    val newList = currentMessages.map {
                        if (it.authorLogin.equals(deletedAuthor, ignoreCase = true)) {
                            it.copy(type = MessageType.DELETED)
                        } else {
                            it
                        }
                    }
                    newList
                }
                // If the current user got banned/cleared, reflect it in the ViewModel
                val currentUserLogin = UserManager.currentUser?.login
                if (currentUserLogin != null && currentUserLogin.equals(deletedAuthor, ignoreCase = true)) {
                    _isBanned.value = true
                }
            }
        }

        viewModelScope.launch {
            service.chatters.collect {
                fetchChatters()
                // If the current user appears in the chatter list, we are not banned anymore.
                val currentUserLogin = UserManager.currentUser?.login
                if (currentUserLogin != null && it.contains(currentUserLogin)) {
                    _isBanned.value = false
                }
            }
        }
    }

    private fun fetchFollowRelationship() {
        viewModelScope.launch {
            val user = UserManager.currentUser
            val token = UserManager.accessToken
            if (user != null && token != null) {
                _followRelationship.value = TwitchApi.getFollowRelationship(user.id, currentChannelId, token, UserManager.CLIENT_ID)
            }
        }
    }

    fun onChatterListRequested() {
        _isChatterListVisible.value = true
        fetchChatters()
    }

    fun onChatterListDismissed() {
        _isChatterListVisible.value = false
        // Clear any hint message when the list is dismissed so it doesn't persist indefinitely
        _chatterListLimitedHint.value = null
    }

    // Allow UI to dismiss the inline chatter-list hint banner (e.g., when the user taps Close)
    fun dismissChatterListHint() {
        _chatterListLimitedHint.value = null
    }

    fun fetchChatters() {
        viewModelScope.launch {
            _isChattersLoading.value = true
            val currentUser = UserManager.currentUser
            val token = UserManager.accessToken

            // Serve from cache if recent
            val now = System.currentTimeMillis()
            if (cachedChattersChannelId == currentChannelId && cachedChattersForChannel != null && (now - lastChattersFetchMillis) < chatterCacheTtlMs) {
                Log.d(TAG, "Returning cached chatters for channel $currentChannelId")
                _chatters.value = cachedChattersForChannel!!
                _isChattersLoading.value = false
                return@launch
            }

            if (currentUser != null && token != null) {
                // Check if the current user has privileges (moderator or broadcaster) before calling Helix
                val isPrivileged = _isCurrentUserModerator.value || currentUser.login.equals(currentChannel, ignoreCase = true)

                if (isPrivileged) {
                    Log.i(TAG, "User is privileged; calling Helix chatters for $currentChannelId")
                    val helixChatters = TwitchApi.getHelixChatters(currentChannelId, currentUser.id, token, UserManager.CLIENT_ID)

                    if (helixChatters != null) {
                        Log.i(TAG, "Fetched Helix chatters for $currentChannelId (count=${helixChatters.size})")
                        val grouped = mutableMapOf<String, MutableList<String>>(
                            "Viewers" to mutableListOf()
                        )

                        helixChatters.forEach { chatter ->
                            grouped["Viewers"]?.add(chatter.userName)
                        }
                        grouped.forEach { (_, list) -> list.sortWith(String.CASE_INSENSITIVE_ORDER) }
                        _chatters.value = grouped

                        // update cache
                        cachedChattersForChannel = grouped
                        cachedChattersChannelId = currentChannelId
                        lastChattersFetchMillis = System.currentTimeMillis()

                        // clear hint when we successfully used Helix
                        _chatterListLimitedHint.value = null
                    } else {
                        Log.w(TAG, "Helix chatters returned null for $currentChannelId; falling back to local chat service")
                        // Helix returned null (maybe API unavailable) — fall back to local service chatters
                        chatServiceRef?.get()?.let { service ->
                            val viewers = service.chatters.value
                            val grouped = mutableMapOf<String, MutableList<String>>(
                                "Viewers" to mutableListOf()
                            )

                            viewers.forEach { viewer ->
                                grouped["Viewers"]?.add(viewer)
                            }
                            grouped.forEach { (_, list) -> list.sortWith(String.CASE_INSENSITIVE_ORDER) }
                            _chatters.value = grouped

                            // update cache and hint (limit due to API)
                            cachedChattersForChannel = grouped
                            cachedChattersChannelId = currentChannelId
                            lastChattersFetchMillis = System.currentTimeMillis()
                            _chatterListLimitedHint.value = "Full chatter list unavailable; showing local view only."

                            Log.i(TAG, "Displayed local chatters for $currentChannelId (Helix unavailable)")
                        }
                    }
                } else {
                    // Not privileged — avoid calling Helix and use best-effort local chatters
                    Log.i(TAG, "Skipping Helix chatters for $currentChannelId: user not privileged")
                    // Do not show a hint to non-privileged users; they don't need to see it.

                    chatServiceRef?.get()?.let { service ->
                        val viewers = service.chatters.value
                        val grouped = mutableMapOf<String, MutableList<String>>(
                            "Viewers" to mutableListOf()
                        )

                        viewers.forEach { viewer ->
                            grouped["Viewers"]?.add(viewer)
                        }
                        grouped.forEach { (_, list) -> list.sortWith(String.CASE_INSENSITIVE_ORDER) }
                        _chatters.value = grouped

                        // update cache
                        cachedChattersForChannel = grouped
                        cachedChattersChannelId = currentChannelId
                        lastChattersFetchMillis = System.currentTimeMillis()
                    }
                }
            }
            _isChattersLoading.value = false
        }
    }

    private fun startPolling(userId: String) {
        stopPolling()
        pollingJob = viewModelScope.launch {
            while (true) {
                UserManager.accessToken?.let { token ->
                    val stream = TwitchApi.getStream(userId, token, UserManager.CLIENT_ID)
                    _viewerCount.value = stream?.viewerCount
                    _streamTitle.value = stream?.title
                }
                delay(60_000)
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun onShowUserProfile(message: ChatMessage) {
        _selectedUserForProfile.value = message
    }

    fun onDismissUserProfile() {
        _selectedUserForProfile.value = null
    }

    fun onTimeout(username: String) {
        modAction("timeout", username)
    }

    fun onBan(username: String) {
        modAction("ban", username)
    }

    fun onReply(message: ChatMessage) {
        _replyToMessage.value = message
        _inputMessage.update { currentTFV ->
            val currentText = currentTFV.text
            val newMention = "@${message.authorLogin ?: message.author ?: ""} "
            val mentionRegex = Regex("""^@\w+\s""")
            val newText = if (mentionRegex.containsMatchIn(currentText)) {
                currentText.replaceFirst(mentionRegex, newMention)
            } else {
                currentText
            }
            TextFieldValue(newText, TextRange(newText.length))
        }
    }

    fun clearReply() {
        _replyToMessage.value = null
        _inputMessage.update { currentTFV ->
            val currentText = currentTFV.text
            val mentionRegex = Regex("""^@\w+\s""")
            val newText = if (mentionRegex.containsMatchIn(currentText)) {
                currentText.replaceFirst(mentionRegex, "")
            } else {
                currentText
            }
            TextFieldValue(newText, TextRange(newText.length))
        }
    }

    fun voteOnPoll(pollId: String, choiceId: String) {
        chatServiceRef?.get()?.voteOnPoll(pollId, choiceId)
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
        val cursorPosition = newValue.selection.start
        val lastAt = text.substring(0, cursorPosition).lastIndexOf('@')

        if (lastAt != -1) {
            val query = text.substring(lastAt + 1, cursorPosition)
            if (" " !in query) {
                val allChatters = _chatters.value.values.flatten().distinct()
                _userSuggestions.value = allChatters.filter { it.startsWith(query, ignoreCase = true) }
                _showUserSuggestions.value = _userSuggestions.value.isNotEmpty()
            } else {
                _showUserSuggestions.value = false
            }
        } else {
            _showUserSuggestions.value = false
        }

        // Add syntax highlighting for mentions
        val annotatedString = buildAnnotatedString {
            append(text)
            val userMentionRegex = Regex("""@\w+""")
            userMentionRegex.findAll(text).forEach { matchResult ->
                val user = matchResult.value.drop(1)
                val allChatters = _chatters.value.values.flatten().distinct()
                if (allChatters.any { it.equals(user, ignoreCase = true) }) {
                    addStyle(
                        style = SpanStyle(
                            color = Color.White,
                            background = Color.Black
                        ),
                        start = matchResult.range.first,
                        end = matchResult.range.last + 1
                    )
                }
            }
        }
        _inputMessage.value = TextFieldValue(annotatedString, newValue.selection)
    }

    fun onUserSuggestionSelected(username: String) {
        val currentTFV = _inputMessage.value
        val currentText = currentTFV.text
        val cursorPosition = currentTFV.selection.start

        val startOfMention = currentText.substring(0, cursorPosition).lastIndexOf('@')
        if (startOfMention == -1) {
            _showUserSuggestions.value = false
            return
        }

        val mentionQuery = currentText.substring(startOfMention + 1, cursorPosition)
        if (mentionQuery.contains(" ")) {
            _showUserSuggestions.value = false
            return
        }

        val prefix = currentText.substring(0, startOfMention)

        var endOfMention = cursorPosition
        while (endOfMention < currentText.length && currentText[endOfMention] != ' ') {
            endOfMention++
        }
        val suffix = currentText.substring(endOfMention)

        val newText = buildString {
            append(prefix)
            append('@')
            append(username)
            if (suffix.isEmpty() || !suffix.startsWith(" ")) {
                append(" ")
            }
            append(suffix)
        }

        val newCursorPosition = prefix.length + 1 + username.length + 1

        val annotatedString = buildAnnotatedString {
            append(newText)
            val userMentionRegex = Regex("""@\w+""")
            userMentionRegex.findAll(newText).forEach { matchResult ->
                val user = matchResult.value.drop(1)
                val allChatters = _chatters.value.values.flatten().distinct()
                if (allChatters.any { it.equals(user, ignoreCase = true) }) {
                    addStyle(
                        style = SpanStyle(
                            color = Color.White,
                            background = Color.Black
                        ),
                        start = matchResult.range.first,
                        end = matchResult.range.last + 1
                    )
                }
            }
        }

        _inputMessage.value = TextFieldValue(annotatedString, TextRange(newCursorPosition))
        _showUserSuggestions.value = false
    }

    fun onEmoteMenuToggled() {
        if (!_isEmoteMenuVisible.value) {
            _availableEmotes.value = EmoteManager.getAllEmotes()
        }
        _isEmoteMenuVisible.value = !_isEmoteMenuVisible.value
    }

    fun onEmoteSelected(emoteCode: String) {
        _inputMessage.update { currentFieldValue ->
            val currentText = currentFieldValue.text
            val newText = if (currentText.isBlank()) {
                emoteCode
            } else {
                "$currentText $emoteCode "
            }
            TextFieldValue(newText, TextRange(newText.length))
        }
        onEmoteMenuToggled()
    }

    fun sendMessage() {
        val messageText = _inputMessage.value.text
        if (messageText.isBlank()) return
        val currentUser = UserManager.currentUser ?: return

        val messageId = UUID.randomUUID().toString()

        // Ensure badge data is available (best-effort) so local message echo can show badge images immediately
        viewModelScope.launch {
            try {
                UserManager.accessToken?.let { token ->
                    BadgeManager.loadGlobalBadges(token, UserManager.CLIENT_ID)
                    // Attempt to load channel badges for the active channel
                    if (currentChannelId.isNotBlank()) {
                        BadgeManager.loadChannelBadges(currentChannelId, token, UserManager.CLIENT_ID)
                    }

                    // Force re-emit of messages so any newly-loaded badge urls are picked up by UI (local echo included)
                    _messages.update { currentList ->
                        currentList.toList()
                    }
                }
            } catch (_: Exception) {
                // ignore failures; fallback will still display without images
            }
        }

        // Build badges for the local user message so they are visible immediately in the UI
        val localBadges = mutableListOf<String>()
        try {
            // Broadcaster badge if the current channel belongs to the current user
            if (currentChannel.equals(currentUser.login, ignoreCase = true)) {
                localBadges.add("broadcaster")
            }
            // Moderator badge if the chat service has marked the local user as mod
            if (_isCurrentUserModerator.value) {
                localBadges.add("moderator")
            }
        } catch (_: Exception) {
            // Defensive: don't let badge assignment crash the send flow
        }

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
            replyParentMsgBody = _replyToMessage.value?.message
        )
        _messages.update { currentList ->
            val newList = (currentList + localMessage).takeLast(200)
            // Note: _currentMessageCount was removed earlier; don't reference it here.
            newList
        }

        // Send via the chat service reference so we don't reference a non-existent field
        chatServiceRef?.get()?.sendMessage(currentChannel, messageText, _replyToMessage.value?.tags?.get("id"))

        _inputMessage.value = TextFieldValue("")
        _replyToMessage.value = null // Clear reply after sending
    }

    fun prepareForChannel(channelName: String, twitchUserId: String) {
        this.currentChannel = channelName
        this.currentChannelId = twitchUserId
        _messages.value = emptyList()
        startPolling(twitchUserId)
        fetchFollowRelationship()
        // Also fetch subscriber status for the current user vs this broadcaster
        viewModelScope.launch {
            val user = UserManager.currentUser
            val token = UserManager.accessToken
            if (user != null && token != null) {
                _isSubscriber.value = TwitchApi.isUserSubscribed(user.id, twitchUserId, token, UserManager.CLIENT_ID)
            } else {
                _isSubscriber.value = null
            }
        }
        viewModelScope.launch {
            UserManager.accessToken?.let { token ->
                BadgeManager.loadGlobalBadges(token, UserManager.CLIENT_ID)
                EmoteManager.loadEmotesForChannel(twitchUserId, token, UserManager.CLIENT_ID)

                // Load channel-specific badges (channel/subscriber/bits badges)
                try {
                    BadgeManager.loadChannelBadges(twitchUserId, token, UserManager.CLIENT_ID)
                } catch (_: Exception) {
                    // Ignore: fallback to global badges
                }

                // Force re-emit of messages so any newly-loaded badge urls are picked up by UI
                _messages.update { currentList ->
                    currentList.toList()
                }
            }
        }
    }
}
