package com.norwedish.twitcherchat

/**
 * Data models used by the whisper (direct message) subsystem.
 * Includes message, conversation and EventSub payload shapes.
 */

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.*

@Serializable
data class WhisperMessage(
    val id: String = UUID.randomUUID().toString(),
    val fromUserId: String,
    val fromUserLogin: String,
    val fromDisplayName: String,
    val fromProfileImageUrl: String,
    val toUserId: String,
    val toUserLogin: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isOutgoing: Boolean = false,
    val badges: List<String> = emptyList()
) {
    fun getFormattedTime(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}

@Serializable
data class WhisperConversation(
    val otherUserId: String,
    val otherUserLogin: String,
    val otherDisplayName: String,
    val otherProfileImageUrl: String,
    val messages: List<WhisperMessage> = emptyList(),
    val unreadCount: Int = 0,
    val lastMessageTime: Long = System.currentTimeMillis()
) {
    fun getLastMessagePreview(): String = messages.lastOrNull()?.message?.take(50) ?: "No messages yet"
}

@Serializable
data class WhisperUser(
    val userId: String,
    val userLogin: String,
    val displayName: String,
    val profileImageUrl: String
)

@Serializable
data class WhisperEventSubPayload(
    @SerialName("subscription")
    val subscription: WhisperSubscription,
    @SerialName("event")
    val event: WhisperEvent
)

@Serializable
data class WhisperSubscription(
    @SerialName("id")
    val id: String,
    @SerialName("status")
    val status: String,
    @SerialName("type")
    val type: String,
    @SerialName("version")
    val version: String,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("transport")
    val transport: WhisperTransport,
    @SerialName("cost")
    val cost: Int
)

@Serializable
data class WhisperTransport(
    @SerialName("method")
    val method: String,
    @SerialName("callback")
    val callback: String? = null
)

@Serializable
data class WhisperEvent(
    @SerialName("from_user_id")
    val fromUserId: String = "",
    @SerialName("from_user_login") val fromUserLogin: String = "",
    @SerialName("from_user_name") val fromUserName: String = "",
    @SerialName("to_user_id") val toUserId: String = "",
    @SerialName("to_user_login") val toUserLogin: String = "",
    @SerialName("to_user_name") val toUserName: String = "",
    @SerialName("whisper_id") val whisperId: String = "",
    @SerialName("text") val text: String = ""
)
