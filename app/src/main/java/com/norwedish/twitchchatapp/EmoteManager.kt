package com.norwedish.twitchchatapp

import android.util.Log
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

// --- Data Classes for Emotes ---

enum class EmoteProvider {
    TWITCH,
    BTTV,
    SEVENTV,
    FRANKENFACEZ
}

// A generic emote data class
data class Emote(
    val id: String,
    val code: String,
    val url: String,
    val provider: EmoteProvider
)

// Represents a found emote in a message
data class ParsedEmote(
    val emote: Emote,
    val startIndex: Int,
    val endIndex: Int
)


// --- Data Classes for API responses ---

// BTTV
@Serializable
data class BttvEmote(
    val id: String,
    val code: String,
    val imageType: String
)

// 7TV
@Serializable
data class SevenTvEmote(
    val id: String,
    val name: String,
    val data: SevenTvEmoteData
)

@Serializable
data class SevenTvEmoteData(
    val host: SevenTvEmoteHost
)

@Serializable
data class SevenTvEmoteHost(
    val url: String,
    val files: List<SevenTvEmoteFile>
)

@Serializable
data class SevenTvEmoteFile(
    val name: String,
    val format: String
)

// FFZ
@Serializable
data class FfzResponse(
    val sets: Map<String, FfzEmoteSet>
)

@Serializable
data class FfzEmoteSet(
    val emoticons: List<FfzEmote>
)

@Serializable
data class FfzEmote(
    val id: Int,
    val name: String,
    val urls: Map<String, String>
)


// --- EmoteManager ---

object EmoteManager {

    private const val TAG = "EmoteManager"

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    // Cache for emotes to avoid re-fetching on every message
    private val channelEmotes = ConcurrentHashMap<String, List<Emote>>()
    // Separate global caches per provider so a partial failure doesn't permanently block other providers
    private var globalTwitch: List<Emote> = emptyList()
    private var globalBttv: List<Emote> = emptyList()
    private var global7tv: List<Emote> = emptyList()
    private var globalFfz: List<Emote> = emptyList()

    fun getAllEmotes(): List<Emote> {
        return globalTwitch + globalBttv + global7tv + globalFfz + channelEmotes.values.flatten()
    }

    suspend fun loadEmotesForChannel(twitchUserId: String, token: String, clientId: String) {
        channelEmotes.clear()

        // Load global provider emotes individually if not already loaded. This allows retry for providers that failed previously.
        if (globalTwitch.isEmpty()) {
            try {
                globalTwitch = TwitchApi.getGlobalTwitchEmotes(token, clientId).map {
                    Emote(
                        id = it.id,
                        code = it.name,
                        url = "https://static-cdn.jtvnw.net/emoticons/v2/${it.id}/default/dark/1.0",
                        provider = EmoteProvider.TWITCH
                    )
                }
            } catch (_: Exception) {
                globalTwitch = emptyList()
            }
            Log.d(TAG, "Loaded global Twitch emotes: ${globalTwitch.size}")
        }

        if (globalBttv.isEmpty()) {
            globalBttv = try { fetchGlobalBttvEmotes() } catch (_: Exception) { emptyList() }
            Log.d(TAG, "Loaded global BTTV emotes: ${globalBttv.size}")
        }

        if (global7tv.isEmpty()) {
            global7tv = try { fetchGlobal7tvEmotes() } catch (_: Exception) { emptyList() }
            Log.d(TAG, "Loaded global 7TV emotes: ${global7tv.size}")
        }

        if (globalFfz.isEmpty()) {
            globalFfz = try { fetchGlobalFfzEmotes() } catch (_: Exception) { emptyList() }
            Log.d(TAG, "Loaded global FFZ emotes: ${globalFfz.size}")
        }

        val channelBttv = fetchChannelBttvEmotes(twitchUserId)
        val channel7tv = fetchChannel7tvEmotes(twitchUserId)
        val channelFfz = fetchChannelFfzEmotes(twitchUserId)
        val allChannelEmotes = channelBttv + channel7tv + channelFfz
        channelEmotes[twitchUserId] = allChannelEmotes

        Log.d(TAG, "Channel emotes for $twitchUserId loaded: total=${allChannelEmotes.size} (bttv=${channelBttv.size}, 7tv=${channel7tv.size}, ffz=${channelFfz.size})")
        Log.d(TAG, "Global totals: twitch=${globalTwitch.size}, bttv=${globalBttv.size}, 7tv=${global7tv.size}, ffz=${globalFfz.size}")
    }

    fun parseThirdPartyEmotes(message: String): List<ParsedEmote> {
        val foundEmotes = mutableListOf<ParsedEmote>()
        val thirdPartyEmotes = getAllEmotes().filter {
            it.provider == EmoteProvider.BTTV || it.provider == EmoteProvider.SEVENTV || it.provider == EmoteProvider.FRANKENFACEZ
        }

        val words = message.split(' ').toSet()

        for (emote in thirdPartyEmotes) {
            if (words.contains(emote.code)) {
                var startIndex = message.indexOf(emote.code, 0)
                while (startIndex >= 0) {
                     val endIndex = startIndex + emote.code.length - 1
                     foundEmotes.add(ParsedEmote(emote, startIndex, endIndex))
                     startIndex = message.indexOf(emote.code, startIndex + 1)
                }
            }
        }
        return foundEmotes
    }


    // --- Private Fetching Functions ---

    private suspend fun fetchGlobalBttvEmotes(): List<Emote> {
        return try {
            val emotes: List<BttvEmote> = client.get("https://api.betterttv.net/3/cached/emotes/global").body()
            emotes.map {
                val url = "https://cdn.betterttv.net/emote/${it.id}/2x.${if (it.imageType == "gif") "gif" else "png"}"
                Emote(
                    id = it.id,
                    code = it.code,
                    url = url,
                    provider = EmoteProvider.BTTV
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchChannelBttvEmotes(twitchUserId: String): List<Emote> {
        return try {
            @Serializable
            data class BttvChannelResponse(
                val channelEmotes: List<BttvEmote>,
                val sharedEmotes: List<BttvEmote>
            )
            val response: BttvChannelResponse = client.get("https://api.betterttv.net/3/cached/users/twitch/$twitchUserId").body()
            val allBttvEmotes = response.channelEmotes + response.sharedEmotes
            allBttvEmotes.map {
                val url = "https://cdn.betterttv.net/emote/${it.id}/2x.${if (it.imageType == "gif") "gif" else "png"}"
                Emote(
                    id = it.id,
                    code = it.code,
                    url = url,
                    provider = EmoteProvider.BTTV
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchGlobal7tvEmotes(): List<Emote> {
        return try {
            @Serializable
            data class SevenTvGlobalResponse(val emotes: List<SevenTvEmote>)

            val response: SevenTvGlobalResponse = client.get("https://api.7tv.app/v2/emotes/global").body()
            response.emotes.mapNotNull { emote ->
                val file = emote.data.host.files.find { it.name == "2x.gif" } 
                    ?: emote.data.host.files.find { it.name == "2x.webp" } 
                    ?: emote.data.host.files.firstOrNull()
                file?.let {
                    Emote(
                        id = emote.id,
                        code = emote.name,
                        url = "https://".plus(emote.data.host.url.removePrefix("//")) + "/${it.name}",
                        provider = EmoteProvider.SEVENTV
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchChannel7tvEmotes(twitchUserId: String): List<Emote> {
        return try {
            @Serializable
            data class SevenTvEmoteSet(val emotes: List<SevenTvEmote>)
            
            @Serializable
            data class SevenTvUserResponse(@SerialName("emote_set") val emoteSet: SevenTvEmoteSet)

            val response: SevenTvUserResponse = client.get("https://7tv.io/v3/users/twitch/$twitchUserId").body()
            response.emoteSet.emotes.mapNotNull { emote ->
                 val file = emote.data.host.files.find { it.name == "2x.gif" } 
                    ?: emote.data.host.files.find { it.name == "2x.webp" } 
                    ?: emote.data.host.files.firstOrNull()
                file?.let {
                    Emote(
                        id = emote.id,
                        code = emote.name,
                        url = "https:${emote.data.host.url}/${it.name}", // v3 API gives schemeless URL
                        provider = EmoteProvider.SEVENTV
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchGlobalFfzEmotes(): List<Emote> {
        return try {
            val response: FfzResponse = client.get("https://api.frankerfacez.com/v1/set/global").body()
            response.sets.values.flatMap { it.emoticons }.mapNotNull { emote ->
                val url = emote.urls.values.firstOrNull()
                url?.let {
                    Emote(
                        id = emote.id.toString(),
                        code = emote.name,
                        url = "https:$it",
                        provider = EmoteProvider.FRANKENFACEZ
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchChannelFfzEmotes(twitchUserId: String): List<Emote> {
        return try {
            val response: FfzResponse = client.get("https://api.frankerfacez.com/v1/room/id/$twitchUserId").body()
            response.sets.values.flatMap { it.emoticons }.mapNotNull { emote ->
                val url = emote.urls.values.firstOrNull()
                url?.let {
                    Emote(
                        id = emote.id.toString(),
                        code = emote.name,
                        url = "https:$it",
                        provider = EmoteProvider.FRANKENFACEZ
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}