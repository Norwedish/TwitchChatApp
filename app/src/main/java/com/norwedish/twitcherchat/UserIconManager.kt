package com.norwedish.twitcherchat

/**
 * User Icon Manager - Displays Twitch-style icons next to usernames in chat
 * Uses Twitch badge URLs and official icon styling for authentic appearance
 */

import android.util.Log

data class UserIconUrl(
    val url: String,
    val contentDescription: String,
    val priority: Int // Higher priority displayed first
)

object UserIconManager {
    private const val TAG = "UserIconManager"

    // Twitch badge URLs - fetched from Twitch API
    private const val TWITCH_BADGE_BASE = "https://static-cdn.jtvnw.net/badges/v1/"

    /**
     * Get icon URLs for a user based on their badges and role
     * Returns Twitch official badge images
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

        // Check for broadcaster (is the stream owner)
        if (authorId == broadcasterId || authorId == currentBroadcasterId) {
            icons.add(UserIconUrl(
                url = "$TWITCH_BADGE_BASE/broadcaster/1/light/static",
                contentDescription = "Broadcaster",
                priority = 100
            ))
            Log.d(TAG, "Added broadcaster badge")
        }

        // Process badges from Twitch IRC tags
        badges.forEach { badge ->
            when {
                badge.startsWith("moderator") -> {
                    icons.add(UserIconUrl(
                        url = "$TWITCH_BADGE_BASE/moderator/1/light/static",
                        contentDescription = "Moderator",
                        priority = 90
                    ))
                    Log.d(TAG, "Added moderator badge")
                }

                badge.startsWith("vip") -> {
                    icons.add(UserIconUrl(
                        url = "$TWITCH_BADGE_BASE/vip/1/light/static",
                        contentDescription = "VIP",
                        priority = 80
                    ))
                    Log.d(TAG, "Added VIP badge")
                }

                badge.startsWith("subscriber") -> {
                    icons.add(UserIconUrl(
                        url = "$TWITCH_BADGE_BASE/subscriber/1/light/static",
                        contentDescription = "Subscriber",
                        priority = 70
                    ))
                    Log.d(TAG, "Added subscriber badge")
                }

                badge.startsWith("founder") -> {
                    icons.add(UserIconUrl(
                        url = "$TWITCH_BADGE_BASE/founder/1/light/static",
                        contentDescription = "Founder",
                        priority = 75
                    ))
                    Log.d(TAG, "Added founder badge")
                }

                badge.startsWith("bits") -> {
                    // Bits badges have different tiers (1, 100, 1000, etc.)
                    icons.add(UserIconUrl(
                        url = "$TWITCH_BADGE_BASE/bits/1/light/static",
                        contentDescription = "Bits Contributor",
                        priority = 65
                    ))
                    Log.d(TAG, "Added bits badge")
                }

                badge.startsWith("premium") -> {
                    icons.add(UserIconUrl(
                        url = "$TWITCH_BADGE_BASE/premium/1/light/static",
                        contentDescription = "Twitch Prime",
                        priority = 60
                    ))
                    Log.d(TAG, "Added premium badge")
                }

                badge.startsWith("admin") -> {
                    icons.add(UserIconUrl(
                        url = "$TWITCH_BADGE_BASE/admin/1/light/static",
                        contentDescription = "Admin",
                        priority = 95
                    ))
                    Log.d(TAG, "Added admin badge")
                }

                badge.startsWith("staff") -> {
                    icons.add(UserIconUrl(
                        url = "$TWITCH_BADGE_BASE/staff/1/light/static",
                        contentDescription = "Staff",
                        priority = 93
                    ))
                    Log.d(TAG, "Added staff badge")
                }

                badge.startsWith("partner") -> {
                    icons.add(UserIconUrl(
                        url = "$TWITCH_BADGE_BASE/partner/1/light/static",
                        contentDescription = "Twitch Partner",
                        priority = 92
                    ))
                    Log.d(TAG, "Added partner badge")
                }

                badge.startsWith("artist-badge") -> {
                    icons.add(UserIconUrl(
                        url = "$TWITCH_BADGE_BASE/artist-badge/1/light/static",
                        contentDescription = "Artist",
                        priority = 88
                    ))
                    Log.d(TAG, "Added artist badge")
                }

                badge.startsWith("moments") -> {
                    icons.add(UserIconUrl(
                        url = "$TWITCH_BADGE_BASE/moments/1/light/static",
                        contentDescription = "Moments Creator",
                        priority = 85
                    ))
                    Log.d(TAG, "Added moments badge")
                }
            }
        }

        // Sort by priority (highest first) and limit to reasonable number
        val result = icons.sortedByDescending { it.priority }.take(4)
        Log.d(TAG, "Returning ${result.size} icons with priorities: ${result.map { it.priority }}")
        return result
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


