package com.norwedish.twitcherchat

/**
 * Data models for Twitch EventSub payloads used by services that subscribe to Twitch EventSub.
 * These classes mirror the JSON shapes received over EventSub WebSocket or HTTP notifications.
 */

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// --- WebSocket Message Models ---

@Serializable
data class EventSubMessage(
    val metadata: MessageMetadata,
    val payload: MessagePayload
)

@Serializable
data class MessageMetadata(
    @SerialName("message_id") val messageId: String,
    @SerialName("message_type") val messageType: String,
    @SerialName("message_timestamp") val timestamp: String,
    @SerialName("subscription_type") val subscriptionType: String? = null,
    @SerialName("subscription_version") val subscriptionVersion: String? = null
)

@Serializable
data class MessagePayload(
    val session: SessionData? = null,
    val subscription: EventSubSubscriptionData? = null,
    // Event can have different shapes depending on subscription type (e.g. whisper events vs stream events).
    // Use JsonElement to avoid failing deserialization when the concrete shape doesn't match EventData.
    val event: JsonElement? = null
)

@Serializable
data class SessionData(
    val id: String,
    val status: String,
    @SerialName("connected_at") val connectedAt: String,
    @SerialName("keepalive_timeout_seconds") val keepaliveTimeoutSeconds: Int? = null,
    @SerialName("reconnect_url") val reconnectUrl: String? = null
)

@Serializable
data class EventSubSubscriptionData(
    val id: String,
    val status: String,
    val type: String,
    val version: String,
    val cost: Int,
    val condition: Map<String, String>,
    val transport: TransportData,
    @SerialName("created_at") val createdAt: String
) : Any()

@Serializable
data class TransportData(
    val method: String,
    @SerialName("session_id") val sessionId: String
)

@Serializable
data class EventData(
    @SerialName("broadcaster_user_id") val broadcasterUserId: String,
    @SerialName("broadcaster_user_login") val broadcasterUserLogin: String,
    @SerialName("broadcaster_user_name") val broadcasterUserName: String,
    @SerialName("started_at") val startedAt: String
)


// --- API Subscription Models ---

@Serializable
data class EventSubSubscriptionRequest(
    val type: String,
    val version: String,
    val condition: Map<String, String>,
    val transport: SubscriptionTransport
)

@Serializable
data class SubscriptionTransport(
    val method: String,
    @SerialName("session_id") val sessionId: String
)
