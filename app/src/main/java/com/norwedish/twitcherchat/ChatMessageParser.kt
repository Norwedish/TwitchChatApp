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
                tags = tagsPart.split(';').associate {
                    val parts = it.split('=', limit = 2)
                    if (parts.size == 2) parts[0] to unescapeTagValue(parts[1]) else parts[0] to ""
                }
                color = tags["color"]
                displayName = tags["display-name"]

                val badgesTag = tags["badges"] ?: ""
                if (badgesTag.isNotBlank()) {
                    badges = badgesTag.split(",").map { it.split("/").first() }
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
                        finalMessage += "\n$userMessage"
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

            // Now we must map emote indices (which are relative to the original userMessage) to indices in finalMessage.
            // There are a few cases:
            //  - For PRIVMSG where we trimmed an @mention prefix, finalMessage will be a suffix of userMessage. We should subtract the prefix length.
            //  - For USERNOTICE where finalMessage = system-msg + "\n" + userMessage, userMessage appears inside finalMessage at some index; we should add that index to each emote index.
            //  - Other cases: fallback to attempting to find a best-effort mapping.

            val parsedTwitchEmotes = mutableListOf<ParsedEmote>()
            if (twitchEmotes.isNotEmpty()) {
                for (t in twitchEmotes) {
                    val startInFinal = when {
                        // If finalMessage contains the original userMessage, shift by its index in finalMessage
                        finalMessage.contains(userMessage) && userMessage.isNotEmpty() -> finalMessage.indexOf(userMessage) + t.startIndex
                        // If userMessage contains finalMessage (we trimmed prefix), then subtract the trimmed prefix length
                        userMessage.contains(finalMessage) && finalMessage.isNotEmpty() -> t.startIndex - userMessage.indexOf(finalMessage)
                        // Otherwise try to map by locating the emote text itself in finalMessage
                        else -> {
                            // Try to get the emote code from userMessage and search for it in finalMessage
                            val safeStart = t.startIndex.coerceAtLeast(0).coerceAtMost(userMessage.length - 1)
                            val safeEnd = t.endIndex.coerceAtLeast(0).coerceAtMost(userMessage.length - 1)
                            val code = try {
                                userMessage.substring(safeStart, safeEnd + 1)
                            } catch (_: Exception) {
                                null
                            }
                            if (code != null && code.isNotEmpty()) {
                                finalMessage.indexOf(code).takeIf { it >= 0 } ?: t.startIndex
                            } else {
                                t.startIndex
                            }
                        }
                    }

                    val endInFinal = startInFinal + (t.endIndex - t.startIndex)

                    // Extract code safely from finalMessage if indices valid, else fallback to code from userMessage
                    val code = if (startInFinal >= 0 && endInFinal < finalMessage.length && finalMessage.isNotEmpty()) {
                        finalMessage.substring(startInFinal, endInFinal + 1)
                    } else {
                        // try to get from original userMessage
                        try {
                            userMessage.substring(t.startIndex, t.endIndex + 1)
                        } catch (_: Exception) {
                            ""
                        }
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

            // Parse third-party emotes from the finalMessage (what will be displayed) so positions match
            val thirdPartyEmotes = EmoteManager.parseThirdPartyEmotes(finalMessage)

            val allEmotes = (parsedTwitchEmotes + thirdPartyEmotes).sortedBy { it.startIndex }

            var finalColor = color
            if (finalColor.isNullOrEmpty()) {
                finalColor = "#8A2BE2" // Default color
            } else if (finalColor.equals("#000000", ignoreCase = true)) {
                finalColor = "#FFFFFF" // Change black to white for readability
            }

            // For USERNOTICE (system messages like subs/raids), prefer tag-derived author info (tags may contain login/display-name).
            // The prefix-derived loginName for USERNOTICE can be "tmi.twitch.tv", which is not a real user.
            // Use a list of possible tag keys returned in various USERNOTICE variants (gifts, recipients, anon gifter, etc.)
            val (authorLoginForMsg, author) = if (command == "USERNOTICE") {
                val loginCandidates = listOf(
                    "login",
                    "msg-param-sender-login",
                    "msg-param-gifter-login",
                    "msg-param-recipient-login",
                    "msg-param-recipient-user-login",
                    "msg-param-user-login"
                )
                val displayNameCandidates = listOf(
                    "display-name",
                    "msg-param-sender-display-name",
                    "msg-param-gifter-display-name",
                    "msg-param-recipient-display-name",
                    "msg-param-recipient-user-name"
                )

                val foundLogin = loginCandidates.asSequence().mapNotNull { tags[it]?.takeIf { it.isNotEmpty() } }.firstOrNull()
                var foundName = displayNameCandidates.asSequence().mapNotNull { tags[it]?.takeIf { it.isNotEmpty() } }.firstOrNull() ?: foundLogin

                // If still null for subscription-type notices, try recipient tags (common for gifted subs)
                if (messageType == MessageType.SUBSCRIPTION && foundName.isNullOrEmpty()) {
                    val recipientCandidates = listOf(
                        "msg-param-recipient-display-name",
                        "msg-param-recipient-user-name",
                        "msg-param-recipient-login",
                        "msg-param-recipient-user-login"
                    )
                    foundName = recipientCandidates.asSequence().mapNotNull { tags[it]?.takeIf { it.isNotEmpty() } }.firstOrNull() ?: foundName

                    if (foundName.isNullOrEmpty()) {
                        val sys = tags["system-msg"] ?: ""
                        val plain = sys.replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim()
                        foundName = Regex("^([\\w\\u00C0-\\u017F'\\-]+)").find(plain)?.groupValues?.get(1)
                    }
                }

                Pair(foundLogin, foundName)
            } else {
                Pair(loginName.takeIf { it.isNotEmpty() }, displayName?.takeIf { it.isNotEmpty() } ?: loginName.takeIf { it.isNotEmpty() })
            }

            return ChatMessage(
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

    // Debug helper: returns information about regex matching/group extraction for troubleshooting.
    fun debugParseInfo(rawMessage: String): String {
        val pattern = Regex("^(?:@([^ ]+) )?(?::([^! ]+)(?:![^ ]+)? )?([^ ]+)(?: (?!:)([^ ]+))?(?: :(.+))?$")
        val match = pattern.find(rawMessage)
        if (match == null) {
            return "NO_MATCH: raw=\n${rawMessage.replace("\n", "\\n")}"
        }
        val groups = (1..match.groupValues.size - 1).joinToString(separator = " | ") { idx ->
            "g$idx='${match.groupValues[idx]}'"
        }
        return "MATCH: command=${match.groupValues[3]} | groups: $groups"
    }
}
