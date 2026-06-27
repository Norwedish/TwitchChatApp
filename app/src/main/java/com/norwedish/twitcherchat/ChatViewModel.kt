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

    private val _visibleEmotes = MutableStateFlow<List<Emote>>(emptyList())
    val visibleEmotes: StateFlow<List<Emote>> = _visibleEmotes.asStateFlow()

    private val _availableEmoteTabs = MutableStateFlow<List<EmoteTab>>(listOf(EmoteTab.TWITCH))
    val availableEmoteTabs: StateFlow<List<EmoteTab>> = _availableEmoteTabs.asStateFlow()

    private fun recomputeAvailableTabs(all: List<Emote>) {
        val providersPresent = all.map { it.provider }.toSet()
        val tabs = EmoteTab.entries.filter { it.provider in providersPresent }
        _availableEmoteTabs.value = tabs
        val current = _selectedEmoteTab.value
        if (current !in tabs) {
            _selectedEmoteTab.value = tabs.firstOrNull() ?: EmoteTab.TWITCH
        }
    }

    private fun refreshVisibleEmotes() {
        val tab = _selectedEmoteTab.value
        val all = _availableEmotes.value
        recomputeAvailableTabs(all)
        val filtered = all.filter { it.provider == tab.provider }
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

    private var currentChannelId: String = ""
    private var currentChannel: String = ""
    private var pollingJob: Job? = null
    private var chatServiceRef: java.lang.ref.WeakReference<ChatService>? = null

    private val messageBuffer = Collections.synchronizedList(mutableListOf<ChatMessage>())
    private var messageProcessingJob: Job? = null
    private var isUserAtBottom = true

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
        messageProcessingJob?.cancel()

        messageProcessingJob = viewModelScope.launch {
            launch {
                service.chatMessages.collect { msg ->
                    // There is no such thing as waiting for an echo from Twitch in self-sent chats.
                    // To prevent duplicate messages (since we append our own sent message to the UI instantly),
                    // we completely ignore any incoming messages from our own username.
                    val currentUserLogin = UserManager.currentUser?.login
                    if (currentUserLogin != null && msg.authorLogin.equals(currentUserLogin, ignoreCase = true)) {
                        return@collect
                    }
                    
                    messageBuffer.add(msg)
                    try {
                        if (msg.authorLogin.equals(UserManager.currentUser?.login, ignoreCase = true)) {
                            msg.authorColor?.let { color -> if (color != UserManager.currentUser?.chatColor) UserManager.updateUserChatColor(color) }
                        }
                    } catch (_: Exception) {}
                }
            }

            launch {
                while (true) {
                    delay(400)
                    if (messageBuffer.isNotEmpty()) {
                        val toAdd = synchronized(messageBuffer) {
                            val copy = ArrayList(messageBuffer)
                            messageBuffer.clear()
                            copy
                        }
                        if (!isUserAtBottom) _unreadMessageCount.value += toAdd.size
                        _messages.update { current -> (current + toAdd).takeLast(250) }
                        if (isUserAtBottom) {
                            launch {
                                delay(100)
                                _scrollToBottom.emit(Unit)
                            }
                        }
                    }
                }
            }
        }

        viewModelScope.launch { service.connectionState.collect { _connectionState.value = it } }
        viewModelScope.launch { service.poll.collect { _poll.value = it } }
        viewModelScope.launch { service.isCurrentUserModerator.collect { _isCurrentUserModerator.value = it } }
        viewModelScope.launch { service.roomState.collect { _roomState.value = it } }
        viewModelScope.launch {
            service.deletedMessageIds.collect { id ->
                _messages.update { list -> list.map { if (it.tags["id"] == id) it.copy(type = MessageType.DELETED) else it } }
            }
        }
        viewModelScope.launch {
            service.deletedUserMessages.collect { author ->
                _messages.update { list -> list.map { if (it.authorLogin.equals(author, ignoreCase = true)) it.copy(type = MessageType.DELETED) else it } }
            }
        }
    }

    fun fetchChatters() {
        viewModelScope.launch {
            _isChattersLoading.value = true
            val currentUser = UserManager.currentUser
            val token = UserManager.accessToken
            try {
                if (currentUser != null && token != null) {
                    val helix = TwitchApi.getHelixChatters(currentChannelId, currentUser.id, token, UserManager.CLIENT_ID)
                    if (helix != null) {
                        val grouped = mutableMapOf("Broadcaster" to helix.broadcaster.map { it.userName }, "Moderators" to helix.moderators.map { it.userName }, "VIPs" to helix.vips.map { it.userName }, "Viewers" to helix.viewers.map { it.userName })
                        _chatters.value = grouped
                        _isChattersLoading.value = false
                        _chatterListLimitedHint.value = null
                        return@launch
                    }
                }
                _chatters.value = mapOf("Viewers" to (chatServiceRef?.get()?.chatters?.value ?: emptyList()))
                _chatterListLimitedHint.value = "Full chatter list unavailable; showing local view only."
            } catch (_: Exception) {}
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
        // Just set the reply to message. There is no need to prepend @username to the input box,
        // as sending it with the reply tags is sufficient and prevents duplicate mentions.
        _replyToMessage.value = message
    }

    fun clearReply() {
        _replyToMessage.value = null
    }

    fun onEmoteMenuToggled() {
        if (!_isEmoteMenuVisible.value) {
            _availableEmotes.value = EmoteManager.getAllEmotes()
            recomputeAvailableTabs(_availableEmotes.value)
            _selectedEmoteTab.value = _availableEmoteTabs.value.firstOrNull { it == EmoteTab.TWITCH } ?: _availableEmoteTabs.value.firstOrNull() ?: EmoteTab.TWITCH
            refreshVisibleEmotes()
        }
        _isEmoteMenuVisible.value = !_isEmoteMenuVisible.value
    }

    fun onEmoteSelected(emoteCode: String) {
        _inputMessage.update { current ->
            val newText = if (current.text.isBlank()) "$emoteCode " else "${current.text.trimEnd()} $emoteCode "
            TextFieldValue(newText, TextRange(newText.length))
        }
        _isEmoteMenuVisible.value = false
    }

    fun sendMessage() {
        val messageText = _inputMessage.value.text
        if (messageText.isBlank()) return
        val currentUser = UserManager.currentUser ?: return

        val isCommand = messageText.startsWith("/")

        if (!isCommand) {
            val messageId = UUID.randomUUID().toString()
            val localBadges = mutableListOf<String>()
            
            // Add self badges based on current local state
            if (currentChannel.equals(currentUser.login, ignoreCase = true)) {
                localBadges.add("broadcaster/1")
            }
            if (_isCurrentUserModerator.value) {
                localBadges.add("moderator/1")
            }
            if (_isSubscriber.value == true) {
                localBadges.add("subscriber/1")
            }

            val replyParent = _replyToMessage.value

            val localMessage = ChatMessage(
                id = messageId,
                author = currentUser.displayName,
                authorLogin = currentUser.login,
                message = messageText,
                authorColor = currentUser.chatColor ?: "#8A2BE2",
                emotes = EmoteManager.parseThirdPartyEmotes(messageText),
                badges = localBadges,
                type = MessageType.STANDARD,
                replyParentMsgId = replyParent?.tags?.get("id") ?: replyParent?.id,
                replyParentUserLogin = replyParent?.authorLogin ?: replyParent?.author,
                replyParentMsgBody = replyParent?.message,
                tags = mapOf("user-id" to currentUser.id) // Adding user-id is extremely safe and helps with badge/icon mapping!
            )

            _messages.update { current -> (current + listOf(localMessage)).takeLast(200) }
        }

        val replyId = _replyToMessage.value?.tags?.get("id")

        try {
            chatServiceRef?.get()?.sendMessage(currentChannel, messageText, replyId)
        } catch (_: Exception) {
            if (!isCommand) {
                _messages.update { current ->
                    current.map {
                        if (it.message == messageText && it.authorLogin == currentUser.login) {
                            it.copy(message = "Failed to send: $messageText", type = MessageType.SYSTEM, tags = mapOf("client" to "send_failed"))
                        } else it
                    }
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
        val command = if (action == "ban") "/ban $username" else "/timeout $username 600"
        chatServiceRef?.get()?.sendMessage(currentChannel, command)
    }

    fun onInputChanged(newValue: TextFieldValue) {
        _inputMessage.value = newValue
        val text = newValue.text
        val lastAt = text.substring(0, newValue.selection.start).lastIndexOf('@')
        if (lastAt != -1) {
            val query = text.substring(lastAt + 1, newValue.selection.start)
            if (" " !in query) {
                _userSuggestions.value = _chatters.value.values.flatten().filter { it.startsWith(query, ignoreCase = true) }
                _showUserSuggestions.value = _userSuggestions.value.isNotEmpty()
                return
            }
        }
        _showUserSuggestions.value = false
    }

    fun onUserSuggestionSelected(username: String) {
        val currentText = _inputMessage.value.text
        val lastAt = currentText.substring(0, _inputMessage.value.selection.start).lastIndexOf('@')
        val prefix = currentText.substring(0, lastAt)
        val suffix = currentText.substring(_inputMessage.value.selection.start)
        val newText = "$prefix@$username $suffix"
        _inputMessage.value = TextFieldValue(newText, TextRange(prefix.length + username.length + 2))
        _showUserSuggestions.value = false
    }

    fun setCurrentChannel(channelId: String, channelName: String) {
        currentChannelId = channelId
        currentChannel = channelName
        stopPolling()
        if (channelId.isNotBlank()) startPolling(channelId)
    }

    private fun startPolling(userId: String) {
        pollingJob = viewModelScope.launch {
            while (true) {
                try {
                    val stream = TwitchApi.getStream(userId, UserManager.accessToken ?: "", UserManager.CLIENT_ID)
                    _viewerCount.value = stream?.viewerCount
                    _streamTitle.value = stream?.title
                } catch (_: Exception) {}
                delay(60_000)
            }
        }
    }

    private fun stopPolling() { pollingJob?.cancel() }
}
