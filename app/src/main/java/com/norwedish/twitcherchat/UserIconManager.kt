package com.norwedish.twitcherchat

/**
 * User Icon Manager - Displays Twitch-style icons next to usernames in chat
 * Uses Twitch badge URLs fetched via BadgeManager for authentic appearance
 */

import android.util.Log

data class UserIconUrl(
    val url: String,
    val contentDescription: String,
    val priority: Int, // Higher priority displayed first
    val badgeType: BadgeType = BadgeType.STANDARD_TWITCH // Type of badge for categorization
)

enum class BadgeType {
    STANDARD_TWITCH,  // Built-in Twitch badges like broadcaster, mod, etc.
    CHANNEL_CUSTOM,   // Channel-specific custom badges
    GLOBAL_CUSTOM     // Global custom badges
}

object UserIconManager {
    private const val TAG = "UserIconManager"

    /**
     * Get ALL icon URLs for a user based on their badges, roles, and channel-specific badges
     * Returns both Twitch official badges and custom channel/global badges in priority order
     */
    fun getUserIconUrls(
        badges: List<String>,
        messageType: MessageType,
        broadcasterId: String?,
        authorId: String?,
        currentBroadcasterId: String?
    ): List<UserIconUrl> {
        val icons = mutableListOf<UserIconUrl>()

        Log.d(TAG, "Getting icons for user. Badges: $badges, AuthorId: $authorId, BroadcasterId: $broadcasterId")

        // Process badges from Twitch IRC tags. 
        // Note: badges strings are in "set_id/version" format (e.g., "subscriber/3", "moderator/1")
        badges.forEach { badge ->
            val setId = badge.substringBefore('/')
            val badgeUrl = BadgeManager.getBadgeUrl(badge, broadcasterId)
            
            if (badgeUrl != null) {
                // Determine priority and type based on the set_id
                val (priority, type) = when {
                    setId == "broadcaster" -> 100 to BadgeType.STANDARD_TWITCH
                    setId == "admin" -> 95 to BadgeType.STANDARD_TWITCH
                    setId == "staff" -> 93 to BadgeType.STANDARD_TWITCH
                    setId == "partner" -> 92 to BadgeType.STANDARD_TWITCH
                    setId == "moderator" -> 90 to BadgeType.STANDARD_TWITCH
                    setId == "artist-badge" -> 88 to BadgeType.STANDARD_TWITCH
                    setId == "vip" -> 80 to BadgeType.STANDARD_TWITCH
                    setId == "founder" -> 75 to BadgeType.STANDARD_TWITCH
                    setId == "subscriber" -> 70 to BadgeType.STANDARD_TWITCH
                    setId == "bits" -> 65 to BadgeType.STANDARD_TWITCH
                    setId == "premium" -> 60 to BadgeType.STANDARD_TWITCH
                    // If it's a channel-specific badge not in the list above, give it high-mid priority
                    BadgeManager.isChannelBadge(badge, broadcasterId) -> 55 to BadgeType.CHANNEL_CUSTOM
                    else -> 50 to BadgeType.GLOBAL_CUSTOM
                }

                icons.add(UserIconUrl(
                    url = badgeUrl,
                    contentDescription = setId,
                    priority = priority,
                    badgeType = type
                ))
            } else {
                Log.w(TAG, "No URL found for badge: $badge")
            }
        }

        // Sort by priority (highest first)
        return icons.sortedByDescending { it.priority }
    }

    /**
     * Get a single primary icon for a user (for compact display)
     */
    fun getPrimaryUserIconUrl(
        badges: List<String>,
        messageType: MessageType,
        broadcasterId: String?,
        authorId: String?,
        currentBroadcasterId: String?
    ): UserIconUrl? {
        return getUserIconUrls(badges, messageType, broadcasterId, authorId, currentBroadcasterId).firstOrNull()
    }
}
