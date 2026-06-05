package com.norwedish.twitcherchat

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


// --- Token Refresh Interface ---

interface TokenRefreshListener {
    suspend fun onTokenExpired(): String?
}


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

    private var tokenRefreshListener: TokenRefreshListener? = null
    private var clientIdCache: String = ""

    // Cache for emotes to avoid re-fetching on every message
    private val channelEmotes = ConcurrentHashMap<String, List<Emote>>()
    // Separate global caches per provider so a partial failure doesn't permanently block other providers
    private var globalTwitch: List<Emote> = emptyList()
    private var globalBttv: List<Emote> = emptyList()
    private var global7tv: List<Emote> = emptyList()
    private var globalFfz: List<Emote> = emptyList()

    fun setTokenRefreshListener(listener: TokenRefreshListener) {
        tokenRefreshListener = listener
    }

    fun getAllEmotes(): List<Emote> {
        return globalTwitch + globalBttv + global7tv + globalFfz + channelEmotes.values.flatten()
    }

    suspend fun loadEmotesForChannel(twitchUserId: String, token: String, clientId: String) {
        channelEmotes.clear()
        Log.d(TAG, "Starting emote load for channel $twitchUserId")

        var currentToken = token

        // Load global provider emotes individually if not already loaded. This allows retry for providers that failed previously.
        if (globalTwitch.isEmpty()) {
            try {
                val twitchEmotes = TwitchApi.getGlobalTwitchEmotes(currentToken, clientId).map {
                    Emote(
                        id = it.id,
                        code = it.name,
                        url = "https://static-cdn.jtvnw.net/emoticons/v2/${it.id}/default/dark/1.0",
                        provider = EmoteProvider.TWITCH
                    )
                }
                globalTwitch = twitchEmotes
                Log.d(TAG, "✓ Loaded global Twitch emotes: ${globalTwitch.size}")
            } catch (e: Exception) {
                Log.e(TAG, "✗ Failed to load Twitch emotes: ${e.message}", e)
                // Check if token expired (401 or Unauthorized)
                if (e.message?.contains("401") == true || e.message?.contains("Unauthorized") == true) {
                    Log.d(TAG, "Token expired, attempting refresh...")
                    val newToken = tokenRefreshListener?.onTokenExpired()
                    if (newToken != null) {
                        currentToken = newToken
                        try {
                            val twitchEmotes = TwitchApi.getGlobalTwitchEmotes(currentToken, clientId).map {
                                Emote(
                                    id = it.id,
                                    code = it.name,
                                    url = "https://static-cdn.jtvnw.net/emoticons/v2/${it.id}/default/dark/1.0",
                                    provider = EmoteProvider.TWITCH
                                )
                            }
                            globalTwitch = twitchEmotes
                            Log.d(TAG, "✓ Loaded global Twitch emotes after token refresh: ${globalTwitch.size}")
                        } catch (retryE: Exception) {
                            globalTwitch = emptyList()
                            Log.e(TAG, "✗ Failed to load Twitch emotes after token refresh: ${retryE.message}", retryE)
                        }
                    } else {
                        globalTwitch = emptyList()
                        Log.e(TAG, "Token refresh failed or returned null")
                    }
                } else {
                    globalTwitch = emptyList()
                }
            }
        }

        if (globalBttv.isEmpty()) {
            try {
                globalBttv = fetchGlobalBttvEmotes()
                Log.d(TAG, "✓ Loaded global BTTV emotes: ${globalBttv.size}")
            } catch (e: Exception) {
                globalBttv = emptyList()
                Log.e(TAG, "✗ Failed to load BTTV emotes: ${e.message}")
            }
        }

        if (global7tv.isEmpty()) {
            try {
                global7tv = fetchGlobal7tvEmotes()
                Log.d(TAG, "✓ Loaded global 7TV emotes: ${global7tv.size}")
            } catch (e: Exception) {
                global7tv = emptyList()
                Log.e(TAG, "✗ Failed to load 7TV emotes: ${e.message}")
            }
        }

        if (globalFfz.isEmpty()) {
            try {
                globalFfz = fetchGlobalFfzEmotes()
                Log.d(TAG, "✓ Loaded global FFZ emotes: ${globalFfz.size}")
            } catch (e: Exception) {
                globalFfz = emptyList()
                Log.e(TAG, "✗ Failed to load FFZ emotes: ${e.message}")
            }
        }

        val channelBttv = try { fetchChannelBttvEmotes(twitchUserId) } catch (e: Exception) { Log.w(TAG, "Channel BTTV failed: ${e.message}"); emptyList() }
        val channel7tv = try { fetchChannel7tvEmotes(twitchUserId) } catch (e: Exception) { Log.w(TAG, "Channel 7TV failed: ${e.message}"); emptyList() }
        val channelFfz = try { fetchChannelFfzEmotes(twitchUserId) } catch (e: Exception) { Log.w(TAG, "Channel FFZ failed: ${e.message}"); emptyList() }
        val allChannelEmotes = channelBttv + channel7tv + channelFfz
        channelEmotes[twitchUserId] = allChannelEmotes

        Log.d(TAG, "========== EMOTE LOAD SUMMARY ==========")
        Log.d(TAG, "Global Emotes:")
        Log.d(TAG, "  Twitch: ${globalTwitch.size}")
        Log.d(TAG, "  BTTV:   ${globalBttv.size}")
        Log.d(TAG, "  7TV:    ${global7tv.size}")
        Log.d(TAG, "  FFZ:    ${globalFfz.size}")
        Log.d(TAG, "Channel Emotes for $twitchUserId:")
        Log.d(TAG, "  BTTV:   ${channelBttv.size}")
        Log.d(TAG, "  7TV:    ${channel7tv.size}")
        Log.d(TAG, "  FFZ:    ${channelFfz.size}")
        Log.d(TAG, "TOTAL EMOTES AVAILABLE: ${getAllEmotes().size}")
        Log.d(TAG, "========================================")
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
                    val urlBase = emote.data.host.url.let {
                        if (it.startsWith("//")) "https:$it"
                        else if (it.startsWith("http")) it
                        else "https://$it"
                    }
                    Emote(
                        id = emote.id,
                        code = emote.name,
                        url = "$urlBase/${it.name}",
                        provider = EmoteProvider.SEVENTV
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch channel 7TV emotes: ${e.message}")
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