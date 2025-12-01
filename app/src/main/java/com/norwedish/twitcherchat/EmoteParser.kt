package com.norwedish.twitcherchat

/**
 * Utility functions to parse emote indices and tokenization helpers for message rendering.
 */

// Renamed to avoid conflict and clarify its purpose
data class ParsedTwitchEmote(
    val id: String,
    val startIndex: Int,
    val endIndex: Int
)

object EmoteParser {
    /**
     * Parses the 'emotes' tag from a Twitch IRC message.
     * Example tag: "25:5-9,15-19/33:21-25"
     * @param emoteTag The raw string from the 'emotes' tag.
     * @return A list of ParsedTwitchEmote objects, sorted by start position.
     */
    fun parse(emoteTag: String): List<ParsedTwitchEmote> {
        if (emoteTag.isBlank()) {
            return emptyList()
        }

        val emotes = mutableListOf<ParsedTwitchEmote>()
        val emoteParts = emoteTag.split('/')

        for (part in emoteParts) {
            val sections = part.split(':', limit = 2)
            if (sections.size != 2) continue

            val emoteId = sections[0]
            val positions = sections[1]

            val positionParts = positions.split(',')
            for (position in positionParts) {
                val indices = position.split('-', limit = 2)
                if (indices.size != 2) continue

                try {
                    val start = indices[0].toInt()
                    val end = indices[1].toInt()
                    emotes.add(ParsedTwitchEmote(id = emoteId, startIndex = start, endIndex = end))
                } catch (e: NumberFormatException) {
                    // Ignore invalid numbers, but don't crash the app.
                    println("Error parsing emote position: $position")
                }
            }
        }
        return emotes.sortedBy { it.startIndex }
    }
}