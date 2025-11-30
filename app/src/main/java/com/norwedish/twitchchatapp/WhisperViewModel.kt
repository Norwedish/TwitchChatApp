package com.norwedish.twitchchatapp

/**
 * ViewModel for the whisper (private message) subsystem.
 * Manages conversations, unread counts and integrates with the WhisperService.
 */

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.text.input.TextFieldValue

class WhisperViewModel : ViewModel() {

    private val _conversations = MutableStateFlow<List<WhisperConversation>>(emptyList())
    val conversations: StateFlow<List<WhisperConversation>> = _conversations.asStateFlow()

    private val _selectedConversation = MutableStateFlow<WhisperConversation?>(null)
    val selectedConversation: StateFlow<WhisperConversation?> = _selectedConversation.asStateFlow()

    private val _inputMessage = MutableStateFlow(TextFieldValue(""))
    val inputMessage: StateFlow<TextFieldValue> = _inputMessage.asStateFlow()

    private val _totalUnreadCount = MutableStateFlow(0)
    val totalUnreadCount: StateFlow<Int> = _totalUnreadCount.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _whisperServiceRef = MutableStateFlow<java.lang.ref.WeakReference<WhisperService>?>(null)

    private var preferenceManager: WhisperPreferenceManager? = null
    private var currentUserId: String = ""
    private var currentUserLogin: String = ""

    fun initialize(preferenceManager: WhisperPreferenceManager, userId: String, userLogin: String) {
        this.preferenceManager = preferenceManager
        this.currentUserId = userId
        this.currentUserLogin = userLogin
        loadConversations()
    }

    fun setWhisperService(service: WhisperService) {
        _whisperServiceRef.value = java.lang.ref.WeakReference(service)

        // Use onEach + launchIn so the collection is tied to the viewModelScope and won't leak
        service.incomingWhispers
            .onEach { message ->
                Log.d("WhisperViewModel", "Received incoming whisper from=${message.fromUserLogin} msg=${message.message}")
                handleIncomingWhisper(message)
            }
            .launchIn(viewModelScope)

        // Ensure we reload persisted conversations after binding the service (in case service persisted items while UI was not bound)
        loadConversations()
    }

    private fun loadConversations() {
        viewModelScope.launch {
            preferenceManager?.let { pm ->
                val conversations = pm.getAllConversations()
                _conversations.value = conversations
                updateTotalUnreadCount()
            }
        }
    }

    fun selectConversation(userLogin: String) {
        viewModelScope.launch {
            if (userLogin.isEmpty()) {
                _selectedConversation.value = null
            } else {
                val conversation = _conversations.value.find { it.otherUserLogin == userLogin }
                if (conversation != null) {
                    _selectedConversation.value = conversation
                    // Clear unread count for this conversation
                    preferenceManager?.clearUnreadCount(userLogin)
                    updateTotalUnreadCount()
                }
            }
        }
    }

    fun onInputChanged(newInput: TextFieldValue) {
        _inputMessage.value = newInput
    }

    fun sendWhisper() {
        val message = _inputMessage.value.text.trim()
        val selectedConv = _selectedConversation.value

        if (message.isEmpty() || selectedConv == null) return

        viewModelScope.launch {
            _isSending.value = true
            try {
                val service = _whisperServiceRef.value?.get()
                if (service != null) {
                    val success = service.sendWhisper(selectedConv.otherUserLogin, message)
                    if (success) {
                        _inputMessage.value = TextFieldValue("")

                        // Add sent message to conversation
                        val sentMessage = WhisperMessage(
                            fromUserId = currentUserId,
                            fromUserLogin = currentUserLogin,
                            fromDisplayName = UserManager.currentUser?.displayName ?: currentUserLogin,
                            fromProfileImageUrl = UserManager.currentUser?.profileImageUrl ?: "",
                            toUserId = selectedConv.otherUserId,
                            toUserLogin = selectedConv.otherUserLogin,
                            message = message,
                            isOutgoing = true
                        )

                        updateConversationWithMessage(sentMessage)
                    }
                }
            } finally {
                _isSending.value = false
            }
        }
    }

    private fun handleIncomingWhisper(message: WhisperMessage) {
        viewModelScope.launch {
            updateConversationWithMessage(message)

            // Update unread count if not viewing this conversation
            val currentSelected = _selectedConversation.value?.otherUserLogin
            if (currentSelected != message.fromUserLogin) {
                preferenceManager?.let { pm ->
                    val currentCount = pm.getUnreadCount(message.fromUserLogin)
                    pm.updateUnreadCount(message.fromUserLogin, currentCount + 1)
                }
            }

            updateTotalUnreadCount()
        }
    }

    private fun updateConversationWithMessage(message: WhisperMessage) {
        viewModelScope.launch {
            preferenceManager?.let { pm ->
                val otherUserLogin = if (message.isOutgoing) message.toUserLogin else message.fromUserLogin
                var conversation = pm.getConversation(otherUserLogin)

                if (conversation == null) {
                    // Create new conversation
                    conversation = WhisperConversation(
                        otherUserId = if (message.isOutgoing) message.toUserId else message.fromUserId,
                        otherUserLogin = otherUserLogin,
                        otherDisplayName = if (message.isOutgoing) message.toUserLogin else message.fromDisplayName,
                        otherProfileImageUrl = if (message.isOutgoing) "" else message.fromProfileImageUrl,
                        messages = listOf(message)
                    )
                } else {
                    // Add message to existing conversation
                    conversation = conversation.copy(
                        messages = (conversation.messages + message).takeLast(100) // Keep last 100 messages
                    )
                }

                conversation = conversation.copy(lastMessageTime = System.currentTimeMillis())
                pm.saveConversation(conversation)

                // Update UI
                loadConversations()
                if (_selectedConversation.value?.otherUserLogin == otherUserLogin) {
                    _selectedConversation.value = conversation
                }
            }
        }
    }

    private fun updateTotalUnreadCount() {
        viewModelScope.launch {
            preferenceManager?.let { pm ->
                _totalUnreadCount.value = pm.getTotalUnreadCount()
            }
        }
    }

    fun deleteConversation(userLogin: String) {
        viewModelScope.launch {
            preferenceManager?.deleteConversation(userLogin)
            loadConversations()
            if (_selectedConversation.value?.otherUserLogin == userLogin) {
                _selectedConversation.value = null
            }
        }
    }

    fun startNewConversation(userLogin: String, displayName: String = userLogin, profileImageUrl: String = "") {
        viewModelScope.launch {
            preferenceManager?.let { pm ->
                // Check if conversation already exists
                var conversation = pm.getConversation(userLogin)

                if (conversation == null) {
                    // Create new empty conversation
                    conversation = WhisperConversation(
                        otherUserId = "",
                        otherUserLogin = userLogin,
                        otherDisplayName = displayName,
                        otherProfileImageUrl = profileImageUrl,
                        messages = emptyList()
                    )
                    pm.saveConversation(conversation)
                    loadConversations()
                }

                // Select the conversation
                _selectedConversation.value = conversation
            }
        }
    }

    fun clearAllConversations() {
        viewModelScope.launch {
            preferenceManager?.clearAllConversations()
            _conversations.value = emptyList()
            _selectedConversation.value = null
            _totalUnreadCount.value = 0
        }
    }

    // Lookup a Twitch user by login using the WhisperService. Returns null if not found or on error.
    fun lookupUser(login: String, onResult: (WhisperUser?) -> Unit) {
        viewModelScope.launch {
            val service = _whisperServiceRef.value?.get()
            if (service == null) {
                onResult(null)
                return@launch
            }

            val user = try {
                service.getUserByLogin(login)
            } catch (e: Exception) {
                null
            }

            onResult(user)
        }
    }

    // Start a new conversation with a looked-up user and select it
    fun startConversationWithUser(user: WhisperUser) {
        viewModelScope.launch {
            preferenceManager?.let { pm ->
                val existing = pm.getConversation(user.userLogin)
                val conversation = existing ?: WhisperConversation(
                    otherUserId = user.userId,
                    otherUserLogin = user.userLogin,
                    otherDisplayName = user.displayName,
                    otherProfileImageUrl = user.profileImageUrl,
                    messages = emptyList()
                )

                pm.saveConversation(conversation)
                loadConversations()
                _selectedConversation.value = conversation
            }
        }
    }
}
