package com.norwedish.twitcherchat

/**
 * Centralized parser for raw IRC messages from Twitch chat. Produces ChatMessage objects consumed by UI.
 */

object ChatMessageParser {
    // Expose a pure function that parses raw IRC chat lines into ChatMessage objects.
    // This is pure Kotlin and has no Android dependencies so it can be unit tested on the JVM.
    fun parse(rawMessage: String): ChatMessage? {
        // Accept prefixes both with and without the '!user@host' part (e.g. ':nick!user@host' and ':tmi.twitch.tv')
        val pattern = Regex("^(?:@([^ ]+) )?(?::([^! ]+)(?:![^ ]+)? )?([^ ]+)(?: (?!:)([^ ]+))?(?: :(.+))?$")
        val match = pattern.find(rawMessage)

        if (match != null) {
            val command = match.groupValues[3]
            if (command != "PRIVMSG" && command != "USERNOTICE") {
                return null
            }

            val tagsPart = match.groupValues[1]
            val loginName = match.groupValues[2]
            val userMessage = match.groupValues[5]

            var color: String? = null
            var displayName: String? = null
            var twitchEmotes: List<TwitchEmoteInfo> = emptyList()
            var badges: List<String> = emptyList()
            var messageType = MessageType.STANDARD
            var tags: Map<String, String> = emptyMap()
            var finalMessage = ""

            if (tagsPart.isNotEmpty()) {
                // Decode IRC tags and derive display attributes + message category.
                tags = tagsPart.split(';').associate {
                    val parts = it.split('=', limit = 2)
                    if (parts.size == 2) parts[0] to unescapeTagValue(parts[1]) else parts[0] to ""
                }
                color = tags["color"]
                displayName = tags["display-name"]

                val badgesTag = tags["badges"] ?: ""
                if (badgesTag.isNotBlank()) {
                    // Keep the full "name/version" string (e.g. "subscriber/3") so BadgeManager can find the right icon
                    badges = badgesTag.split(",").filter { it.isNotBlank() }
                }

                val emoteTag = tags["emotes"] ?: ""
                if (emoteTag.isNotEmpty()) {
                    // Note: Emote indices are based on the user's raw trailing message
                    twitchEmotes = EmoteParser.parse(emoteTag).map { TwitchEmoteInfo(it.id, it.startIndex, it.endIndex) }
                }

                if (command == "USERNOTICE") {
                    val msgId = tags["msg-id"]

                    // Poll-related notices are handled elsewhere in the service pipeline; ignore here
                    if (msgId?.startsWith("channel.poll.") == true) {
                        return null
                    }

                    messageType = when (msgId) {
                        "sub", "resub", "subgift", "anonsubgift", "submysterygift", "anonsubmysterygift", "primepaidupgrade", "giftpaidupgrade" -> MessageType.SUBSCRIPTION
                        "raid" -> MessageType.RAID
                        "announcement" -> MessageType.ANNOUNCEMENT
                        else -> messageType
                    }

                    // For USERNOTICE, prefer the system message text (this contains the human-readable sub/raid text).
                    // Some USERNOTICE variants include the user's own message in the trailing portion; append it when present.
                    finalMessage = tags["system-msg"] ?: ""
                    if (userMessage.isNotEmpty()) {
                        finalMessage += if (finalMessage.isNotEmpty()) "\n$userMessage" else userMessage
                    }
                } else { // PRIVMSG
                    finalMessage = userMessage
                    val isReply = tags.containsKey("reply-parent-msg-id")
                    if (isReply) {
                        val replyTo = tags["reply-parent-user-login"]
                        if (replyTo != null && finalMessage.startsWith("@$replyTo", ignoreCase = true)) {
                            val endOfMention = finalMessage.indexOf(' ')
                            if (endOfMention != -1) {
                                finalMessage = finalMessage.substring(endOfMention + 1)
                            }
                        }
                    }
                }
            }

            // Re-map emote positions so they match the final rendered message text.
            val parsedTwitchEmotes = mutableListOf<ParsedEmote>()
            if (twitchEmotes.isNotEmpty()) {
                for (t in twitchEmotes) {
                    val startInFinal = when {
                        finalMessage.contains(userMessage) && userMessage.isNotEmpty() -> finalMessage.indexOf(userMessage) + t.startIndex
                        userMessage.contains(finalMessage) && finalMessage.isNotEmpty() -> t.startIndex - userMessage.indexOf(finalMessage)
                        else -> {
                            val safeStart = t.startIndex.coerceAtLeast(0).coerceAtMost(userMessage.length - 1)
                            val safeEnd = t.endIndex.coerceAtLeast(0).coerceAtMost(userMessage.length - 1)
                            val code = try { userMessage.substring(safeStart, safeEnd + 1) } catch (_: Exception) { null }
                            if (!code.isNullOrEmpty()) finalMessage.indexOf(code).takeIf { it >= 0 } ?: t.startIndex else t.startIndex
                        }
                    }

                    val endInFinal = startInFinal + (t.endIndex - t.startIndex)
                    val code = if (startInFinal >= 0 && endInFinal < finalMessage.length && finalMessage.isNotEmpty()) {
                        finalMessage.substring(startInFinal, endInFinal + 1)
                    } else {
                        try { userMessage.substring(t.startIndex, t.endIndex + 1) } catch (_: Exception) { "" }
                    }

                    if (code.isNotEmpty()) {
                        parsedTwitchEmotes.add(
                            ParsedEmote(
                                emote = Emote(
                                    id = t.id,
                                    code = code,
                                    url = "https://static-cdn.jtvnw.net/emoticons/v2/${t.id}/default/dark/1.0",
                                    provider = EmoteProvider.TWITCH
                                ),
                                startIndex = startInFinal,
                                endIndex = endInFinal
                            )
                        )
                    }
                }
            }

            val thirdPartyEmotes = EmoteManager.parseThirdPartyEmotes(finalMessage)
            val allEmotes = (parsedTwitchEmotes + thirdPartyEmotes).sortedBy { it.startIndex }

            var finalColor = color ?: "#8A2BE2"
            if (finalColor.equals("#000000", ignoreCase = true)) finalColor = "#FFFFFF"

            // USERNOTICE can expose the sender through different tags; pick first non-empty candidate.
            val (authorLoginForMsg, author) = if (command == "USERNOTICE") {
                val loginCandidates = listOf("login", "msg-param-sender-login", "msg-param-gifter-login", "msg-param-recipient-login", "msg-param-user-login")
                val displayNameCandidates = listOf("display-name", "msg-param-sender-display-name", "msg-param-gifter-display-name", "msg-param-recipient-display-name")
                val foundLogin = loginCandidates.asSequence().mapNotNull { tags[it]?.takeIf { it.isNotEmpty() } }.firstOrNull()
                val foundName = displayNameCandidates.asSequence().mapNotNull { tags[it]?.takeIf { it.isNotEmpty() } }.firstOrNull() ?: foundLogin
                Pair(foundLogin, foundName)
            } else {
                Pair(loginName.takeIf { it.isNotEmpty() }, displayName?.takeIf { it.isNotEmpty() } ?: loginName.takeIf { it.isNotEmpty() })
            }

            return ChatMessage(
                id = tags["id"] ?: java.util.UUID.randomUUID().toString(),
                author = author,
                authorLogin = authorLoginForMsg,
                message = finalMessage,
                authorColor = finalColor,
                emotes = allEmotes,
                badges = badges,
                type = messageType,
                tags = tags,
                replyParentMsgId = tags["reply-parent-msg-id"],
                replyParentUserLogin = tags["reply-parent-user-login"],
                replyParentMsgBody = tags["reply-parent-msg-body"]
            )
        }
        return null
    }

    private fun unescapeTagValue(value: String?): String {
        if (value == null) return ""
        return value.replace("\\s", " ")
            .replace("\\:", ";")
            .replace("\\r", "\r")
            .replace("\\n", "\n")
            .replace("\\\\", "\\")
    }
}
