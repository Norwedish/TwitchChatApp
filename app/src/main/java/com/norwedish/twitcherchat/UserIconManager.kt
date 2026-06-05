package com.norwedish.twitcherchat

/**
 * User Icon Manager - Displays Twitch-style icons next to usernames in chat
 * Uses Twitch badge URLs and official icon styling for authentic appearance
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

    // Twitch badge URLs - fetched from Twitch API
    private const val TWITCH_BADGE_BASE = "https://static-cdn.jtvnw.net/badges/v1/"

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

        // Check for broadcaster (is the stream owner)
        if (authorId == broadcasterId || authorId == currentBroadcasterId) {
            icons.add(UserIconUrl(
                url = "$TWITCH_BADGE_BASE/broadcaster/1/light/static",
                contentDescription = "Broadcaster",
                priority = 100,
                badgeType = BadgeType.STANDARD_TWITCH
            ))
            Log.d(TAG, "Added broadcaster badge")
        }

        // Process badges from Twitch IRC tags
        badges.forEach { badge ->
            // First check if this is a standard Twitch badge
            val twitchBadgeHandled = when {
                badge.startsWith("moderator") -> {
                    icons.add(UserIconUrl(
                        url = "$TWITCH_BADGE_BASE/moderator/1/light/static",
                        contentDescription = "Moderator",
                        priority = 90,
                        badgeType = BadgeType.STANDARD_TWITCH
                    ))
                    Log.d(TAG, "Added moderator badge")
                    true
                }

                badge.startsWith("vip") -> {
                    icons.add(UserIconUrl(
                        url = "$TWITCH_BADGE_BASE/vip/1/light/static",
                        contentDescription = "VIP",
                        priority = 80,
                        badgeType = BadgeType.STANDARD_TWITCH
                    ))
                    Log.d(TAG, "Added VIP badge")
                    true
                }

                badge.startsWith("subscriber") -> {
                    icons.add(UserIconUrl(
                        url = "$TWITCH_BADGE_BASE/subscriber/1/light/static",
                        contentDescription = "Subscriber",
                        priority = 70,
                        badgeType = BadgeType.STANDARD_TWITCH
                    ))
                    Log.d(TAG, "Added subscriber badge")
                    true
                }

                badge.startsWith("founder") -> {
                    icons.add(UserIconUrl(
                        url = "$TWITCH_BADGE_BASE/founder/1/light/static",
                        contentDescription = "Founder",
                        priority = 75,
                        badgeType = BadgeType.STANDARD_TWITCH
                    ))
                    Log.d(TAG, "Added founder badge")
                    true
                }

                badge.startsWith("bits") -> {
                    // Bits badges have different tiers (1, 100, 1000, etc.)
                    icons.add(UserIconUrl(
                        url = "$TWITCH_BADGE_BASE/bits/1/light/static",
                        contentDescription = "Bits Contributor",
                        priority = 65,
                        badgeType = BadgeType.STANDARD_TWITCH
                    ))
                    Log.d(TAG, "Added bits badge")
                    true
                }

                badge.startsWith("premium") -> {
                    icons.add(UserIconUrl(
                        url = "$TWITCH_BADGE_BASE/premium/1/light/static",
                        contentDescription = "Twitch Prime",
                        priority = 60,
                        badgeType = BadgeType.STANDARD_TWITCH
                    ))
                    Log.d(TAG, "Added premium badge")
                    true
                }

                badge.startsWith("admin") -> {
                    icons.add(UserIconUrl(
                        url = "$TWITCH_BADGE_BASE/admin/1/light/static",
                        contentDescription = "Admin",
                        priority = 95,
                        badgeType = BadgeType.STANDARD_TWITCH
                    ))
                    Log.d(TAG, "Added admin badge")
                    true
                }

                badge.startsWith("staff") -> {
                    icons.add(UserIconUrl(
                        url = "$TWITCH_BADGE_BASE/staff/1/light/static",
                        contentDescription = "Staff",
                        priority = 93,
                        badgeType = BadgeType.STANDARD_TWITCH
                    ))
                    Log.d(TAG, "Added staff badge")
                    true
                }

                badge.startsWith("partner") -> {
                    icons.add(UserIconUrl(
                        url = "$TWITCH_BADGE_BASE/partner/1/light/static",
                        contentDescription = "Twitch Partner",
                        priority = 92,
                        badgeType = BadgeType.STANDARD_TWITCH
                    ))
                    Log.d(TAG, "Added partner badge")
                    true
                }

                badge.startsWith("artist-badge") -> {
                    icons.add(UserIconUrl(
                        url = "$TWITCH_BADGE_BASE/artist-badge/1/light/static",
                        contentDescription = "Artist",
                        priority = 88,
                        badgeType = BadgeType.STANDARD_TWITCH
                    ))
                    Log.d(TAG, "Added artist badge")
                    true
                }

                badge.startsWith("moments") -> {
                    icons.add(UserIconUrl(
                        url = "$TWITCH_BADGE_BASE/moments/1/light/static",
                        contentDescription = "Moments Creator",
                        priority = 85,
                        badgeType = BadgeType.STANDARD_TWITCH
                    ))
                    Log.d(TAG, "Added moments badge")
                    true
                }

                else -> false
            }

            // If not a standard Twitch badge, try to get it from BadgeManager (channel or global custom badges)
            if (!twitchBadgeHandled) {
                val badgeUrl = BadgeManager.getBadgeUrl(badge, broadcasterId)
                if (badgeUrl != null) {
                    // Determine if this is a channel-specific or global badge
                    val isChannelBadge = broadcasterId != null && BadgeManager.isChannelBadge(badge, broadcasterId)
                    val badgeType = if (isChannelBadge) BadgeType.CHANNEL_CUSTOM else BadgeType.GLOBAL_CUSTOM

                    icons.add(UserIconUrl(
                        url = badgeUrl,
                        contentDescription = badge,
                        priority = if (isChannelBadge) 55 else 50, // Channel badges higher priority than global
                        badgeType = badgeType
                    ))
                    Log.d(TAG, "Added custom badge: $badge (type: $badgeType)")
                }
            }
        }

        // Sort by priority (highest first) - NO limit, display all badges
        val result = icons.sortedByDescending { it.priority }
        Log.d(TAG, "Returning ${result.size} total icons with priorities: ${result.map { "${it.contentDescription}(${it.priority})" }}")
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


