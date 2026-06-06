package com.norwedish.twitcherchat

/**
 * Loads and caches badge images (global and per-channel) used in chat message rendering.
 * Handles multiple versions of badges (e.g., different subscriber tiers/months).
 */

import android.util.Log
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
    private const val TAG = "BadgeManager"

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    // Cache: broadcasterId (or "global") -> Map<"set_id/version", url>
    private val badgeCache = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    private var globalLoaded = false

    /**
     * Loads global badges from Twitch API.
     */
    suspend fun loadGlobalBadges(token: String, clientId: String) {
        if (globalLoaded) return
        try {
            Log.d(TAG, "Loading global badges...")
            val response: BadgeApiResponse = client.get("https://api.twitch.tv/helix/chat/badges/global") {
                headers {
                    append("Authorization", "Bearer $token")
                    append("Client-Id", clientId)
                }
            }.body()

            val map = ConcurrentHashMap<String, String>()
            for (badgeSet in response.data) {
                for (version in badgeSet.versions) {
                    // Store as "set_id/version" e.g. "subscriber/3"
                    map["${badgeSet.set_id}/${version.id}"] = version.image_url_2x
                }
            }
            badgeCache["global"] = map
            globalLoaded = true
            Log.d(TAG, "Global badges loaded: ${map.size} versions")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load global badges", e)
        }
    }

    /**
     * Loads channel-specific badges from Twitch API.
     */
    suspend fun loadChannelBadges(broadcasterId: String, token: String, clientId: String) {
        if (broadcasterId.isBlank()) return
        if (badgeCache.containsKey(broadcasterId)) return

        try {
            Log.d(TAG, "Loading channel badges for $broadcasterId...")
            val response: BadgeApiResponse = client.get("https://api.twitch.tv/helix/chat/badges") {
                parameter("broadcaster_id", broadcasterId)
                headers {
                    append("Authorization", "Bearer $token")
                    append("Client-Id", clientId)
                }
            }.body()

            val map = ConcurrentHashMap<String, String>()
            for (badgeSet in response.data) {
                for (version in badgeSet.versions) {
                    map["${badgeSet.set_id}/${version.id}"] = version.image_url_2x
                }
            }
            badgeCache[broadcasterId] = map
            Log.d(TAG, "Channel badges loaded for $broadcasterId: ${map.size} versions")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load channel badges for $broadcasterId", e)
        }
    }

    /**
     * Get badge URL. badgeString should be "set_id/version" (e.g. "subscriber/3") or just "set_id" (defaults to version 1)
     */
    fun getBadgeUrl(badgeString: String, broadcasterId: String? = null): String? {
        // If it's just "subscriber", try to find any version if possible, but standard is version/id
        val fullKey = if (badgeString.contains("/")) badgeString else "$badgeString/1"

        // 1. Try channel-specific badge
        if (broadcasterId != null) {
            badgeCache[broadcasterId]?.get(fullKey)?.let { return it }
            // If full key not found, try fallback to version 1 if we were looking for a specific version
            if (fullKey != "$badgeString/1") {
                badgeCache[broadcasterId]?.get("$badgeString/1")?.let { return it }
            }
        }

        // 2. Fallback to global badge
        badgeCache["global"]?.get(fullKey)?.let { return it }
        if (fullKey != "$badgeString/1") {
            badgeCache["global"]?.get("$badgeString/1")?.let { return it }
        }

        return null
    }

    /**
     * Check if a badge is channel-specific
     */
    fun isChannelBadge(badgeString: String, broadcasterId: String?): Boolean {
        if (broadcasterId == null) return false
        val fullKey = if (badgeString.contains("/")) badgeString else "$badgeString/1"
        return badgeCache[broadcasterId]?.containsKey(fullKey) ?: false
    }
}
