package com.norwedish.twitcherchat

/**
 * Loads and caches badge images (global and per-channel) used in chat message rendering.
 */

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

// --- Data Classes for Twitch Badge API ---

@Serializable
data class BadgeApiResponse(
    val data: List<BadgeSet>
)

@Serializable
data class BadgeSet(
    val set_id: String,
    val versions: List<BadgeVersion>
)

@Serializable
data class BadgeVersion(
    val id: String,
    val image_url_1x: String,
    val image_url_2x: String,
    val image_url_4x: String
)

object BadgeManager {

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    // Cache for global badge URLs: Map<badge_name, badge_url>
    private val globalBadgeCache = ConcurrentHashMap<String, String>()

    // Cache for channel badge URLs: Map<broadcasterId, Map<badge_name, badge_url>>
    private val channelBadgeCache = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    private var globalLoaded = false

    suspend fun loadGlobalBadges(token: String, clientId: String) {
        if (globalLoaded) return
        try {
            val response: BadgeApiResponse = client.get("https://api.twitch.tv/helix/chat/badges/global") {
                headers {
                    append("Authorization", "Bearer $token")
                    append("Client-Id", clientId)
                }
            }.body()

            for (badgeSet in response.data) {
                val version = badgeSet.versions.firstOrNull()
                if (version != null) {
                    globalBadgeCache[badgeSet.set_id] = version.image_url_2x
                }
            }
            globalLoaded = true
        } catch (e: Exception) {
            // Swallow; caller can retry later
        }
    }

    suspend fun loadChannelBadges(broadcasterId: String, token: String, clientId: String) {
        // Avoid duplicate fetches for the same broadcaster
        if (channelBadgeCache.containsKey(broadcasterId)) return

        try {
            val response: BadgeApiResponse = client.get("https://api.twitch.tv/helix/chat/badges") {
                parameter("broadcaster_id", broadcasterId)
                headers {
                    append("Authorization", "Bearer $token")
                    append("Client-Id", clientId)
                }
            }.body()

            val map = ConcurrentHashMap<String, String>()
            for (badgeSet in response.data) {
                val version = badgeSet.versions.firstOrNull()
                if (version != null) {
                    map[badgeSet.set_id] = version.image_url_2x
                }
            }
            channelBadgeCache[broadcasterId] = map
        } catch (e: Exception) {
            // Swallow errors; UI will fallback to global badges
        }
    }

    fun getBadgeUrl(badgeName: String, broadcasterId: String? = null): String? {
        // Prefer channel-specific badge if available
        if (broadcasterId != null) {
            channelBadgeCache[broadcasterId]?.let { map ->
                map[badgeName]?.let { return it }
            }
        }
        // Fallback to global
        return globalBadgeCache[badgeName]
    }

    // Backwards-compatible method used elsewhere
    fun getBadgeUrl(badgeName: String): String? {
        return getBadgeUrl(badgeName, null)
    }
}
