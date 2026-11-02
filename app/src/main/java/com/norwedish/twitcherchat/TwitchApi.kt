package com.norwedish.twitcherchat

import android.util.Log
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.client.plugins.ClientRequestException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.*

/**
 * Thrown when Twitch answers with a non-success HTTP status. Callers can use [status] to
 * distinguish an expired session (401) from a rate limit (429) or a server error (5xx).
 */
class TwitchApiException(val status: Int, message: String) : Exception(message)

// --- Data Classes (top-level so other modules can reference them) ---

@Serializable
data class TwitchUser(
    val id: String,
    val login: String,
    @SerialName("display_name")
    val displayName: String,
    @SerialName("profile_image_url")
    val profileImageUrl: String,
    @SerialName("chat_color")
    val chatColor: String? = null
)

@Serializable
data class GetUsersResponse(val data: List<TwitchUser>? = null)

@Serializable
data class FollowedChannel(
    @SerialName("broadcaster_id") val broadcasterId: String,
    @SerialName("broadcaster_login") val broadcasterLogin: String,
    @SerialName("broadcaster_name") val broadcasterName: String,
    @SerialName("followed_at") val followedAt: String
)

@Serializable
data class GetFollowedChannelsResponse(
    val data: List<FollowedChannel>? = null,
    val pagination: Pagination? = null,
    val error: String? = null,
    val status: Int? = null,
    val message: String? = null
)

@Serializable
data class Pagination(val cursor: String? = null)

@Serializable
data class FollowedStream(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    @SerialName("user_login")
    val userLogin: String,
    @SerialName("user_name")
    val userName: String,
    @SerialName("game_id")
    val gameId: String,
    @SerialName("game_name")
    val gameName: String,
    val title: String,
    @SerialName("thumbnail_url")
    val thumbnailUrl: String,
    @SerialName("viewer_count")
    val viewerCount: Int
)

@Serializable
data class GetFollowedStreamsResponse(val data: List<FollowedStream>? = null, val pagination: Pagination? = null)

@Serializable
data class SearchedChannel(
    val id: String,
    @SerialName("broadcaster_login")
    val broadcasterLogin: String,
    @SerialName("display_name")
    val displayName: String,
    @SerialName("game_name")
    val gameName: String,
    @SerialName("is_live")
    val isLive: Boolean,
    val title: String,
    @SerialName("thumbnail_url")
    val thumbnailUrl: String
)

@Serializable
data class SearchChannelsResponse(val data: List<SearchedChannel>? = null)

@Serializable
data class GetGlobalEmotesResponse(val data: List<GlobalEmote>? = null)

@Serializable
data class GlobalEmote(
    val id: String,
    val name: String,
)

@Serializable
data class GetStreamsResponse(val data: List<FollowedStream>? = null)

@Serializable
data class PlaybackAccessTokenResponse(
    val data: PlaybackAccessTokenData? = null,
    val errors: List<GQLError>? = null
)

@Serializable
data class GQLError(
    val message: String
)

@Serializable
data class PlaybackAccessTokenData(
    val streamPlaybackAccessToken: PlaybackAccessToken
)

@Serializable
data class PlaybackAccessToken(
    val value: String,
    val signature: String
)

// --- CHATTER LIST DATA CLASSES (top-level) ---

@Serializable
data class HelixChattersResponse(
    val data: List<Chatter>? = null, // Nullable for safety
    val pagination: Pagination? = null,
    val total: Int? = null
)

@Serializable
data class Chatter(
    @SerialName("user_login") val userLogin: String,
    @SerialName("user_name") val userName: String,
    @SerialName("user_id") val userId: String
)

@Serializable
private data class SubscriptionsResponse(
    val data: List<HelixSubscriptionData>? = null
)

@Serializable
private data class HelixSubscriptionData(
    @SerialName("user_id") val userId: String? = null
) : Any()

@Serializable
private data class UsersFollowsResponse(
    val data: List<UserFollow>? = null,
    val pagination: Pagination? = null,
    val error: String? = null,
    val status: Int? = null,
    val message: String? = null
)

@Serializable
private data class UserFollow(
    @SerialName("from_id") val fromId: String,
    @SerialName("from_login") val fromLogin: String? = null,
    @SerialName("from_name") val fromName: String? = null,
    @SerialName("to_id") val toId: String,
    @SerialName("to_login") val toLogin: String? = null,
    @SerialName("to_name") val toName: String? = null,
    @SerialName("followed_at") val followedAt: String
)

@Serializable
data class HelixChattersGrouped(
    val broadcaster: List<Chatter> = emptyList(),
    val moderators: List<Chatter> = emptyList(),
    val vips: List<Chatter> = emptyList(),
    val viewers: List<Chatter> = emptyList()
)

// --- API object exposing functions ---
object TwitchApi {

    // Shared Ktor client used by the API helpers
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(WebSockets)
    }

    /**
     * Throws [TwitchApiException] for non-2xx responses, with Twitch's error message in the
     * exception text so the UI can tell the user what actually went wrong.
     */
    private suspend fun HttpResponse.requireSuccess(what: String): HttpResponse {
        if (status.isSuccess()) return this
        val bodyText = runCatching { bodyAsText() }.getOrDefault("")
        Log.e("TwitchApi", "$what failed: HTTP ${status.value} - ${bodyText.take(500)}")
        throw TwitchApiException(
            status.value,
            "$what failed (HTTP ${status.value})" +
                if (bodyText.isNotBlank()) ": ${bodyText.take(200)}" else ""
        )
    }

    // Returns true if the user is subscribed to the broadcaster, false if not, or null if unknown/error.
    suspend fun isUserSubscribed(userId: String, broadcasterId: String, token: String, clientId: String): Boolean? {
        if (userId.isBlank() || broadcasterId.isBlank()) return null
        return try {
            val response: SubscriptionsResponse = client.get("https://api.twitch.tv/helix/subscriptions") {
                url {
                    parameters.append("broadcaster_id", broadcasterId)
                    parameters.append("user_id", userId)
                }
                headers {
                    header("Client-Id", clientId)
                    header("Authorization", "Bearer $token")
                }
            }.body()

            val data = response.data
            if (data != null && data.isNotEmpty()) true else false
        } catch (e: Exception) {
            Log.w("TwitchApi", "Unable to determine subscription status: ${e.message}")
            null
        }
    }

    suspend fun getStreamUrl(channelName: String): String? {
        // This is the public, non-secret client-id used by the Twitch website.
        val gqlClientId = "kimne78kx3ncx6brgo4mv6wki5h1ko"

        // Build the JSON with kotlinx.serialization so a channel name can never break/inject the body
        fun gqlRequest(channel: String) = buildJsonObject {
            put("operationName", "PlaybackAccessToken")
            putJsonObject("variables") {
                put("isLive", true)
                put("login", channel)
                put("isVod", false)
                put("vodID", "")
                put("playerType", "site")
            }
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", "0828119ded1c13477966434e15800ff57ddacf13ba1911c129dc2200705b0712")
                }
            }
        }.toString()

        suspend fun requestToken(channel: String, withAuth: Boolean): io.ktor.client.statement.HttpResponse? {
            return try {
                client.post("https://gql.twitch.tv/gql") {
                    header("Client-ID", gqlClientId)
                    // If we have a logged-in user's OAuth token, include it so Twitch can return a
                    // playback token that reflects subscriber status / Turbo / Prime, which is what
                    // makes Twitch serve the stream without ads for that user.
                    if (withAuth) {
                        UserManager.accessToken?.let { auth ->
                            header("Authorization", "Bearer $auth")
                        }
                    }
                    contentType(ContentType.Application.Json)
                    setBody(gqlRequest(channel))
                }
            } catch (e: Exception) {
                Log.e("TwitchApi", "Failed to get playback access token", e)
                null
            }
        }

        val login = channelName.lowercase()
        var response = requestToken(login, withAuth = true)
        // Expired or invalid token? Fall back to an anonymous request so the stream still works
        // (it will then contain ads, but playback is not blocked).
        if (response != null && (response.status.value == 401 || response.status.value == 403)) {
            Log.w("TwitchApi", "PlaybackAccessToken rejected with HTTP ${response.status.value}; retrying anonymously")
            response = requestToken(login, withAuth = false)
        }
        if (response == null || !response.status.isSuccess()) {
            Log.e("TwitchApi", "PlaybackAccessToken failed: HTTP ${response?.status?.value ?: "network error"}")
            return null
        }

        val playbackAccessToken = try {
            val body: PlaybackAccessTokenResponse = response.body()
            body.data?.streamPlaybackAccessToken
        } catch (e: Exception) {
            Log.e("TwitchApi", "Failed to parse playback access token", e)
            null
        }

        // Twitch decides on ads from this token. Log only the ad-related flags (never the token
        // itself) so it is possible to see whether the logged-in account actually gets ad-free
        // playback.
        playbackAccessToken?.let { logPlaybackTokenClaims(login, it.value) }

        return playbackAccessToken?.let {
            val url = "https://usher.ttvnw.net/api/channel/hls/$login.m3u8"
            val params = Parameters.build {
                append("client_id", gqlClientId)
                append("token", it.value)
                append("sig", it.signature)
                append("allow_source", "true")
                append("allow_audio_only", "true")
                append("player", "twitchweb")
                append("p", (1_000_000..9_999_999).random().toString())
                append("type", "any")
                append("allow_spectre", "false")
            }.formUrlEncode()
            url + "?" + params
        }
    }

    /**
     * The playback token is a secret credential: never write the full stream URL (which contains
     * token+sig in the query) to logs or error messages.
     */
    fun redactStreamUrl(url: String): String =
        url.replace(Regex("([?&](?:token|sig|nauth|nauthsig)=)[^&]*", RegexOption.IGNORE_CASE), "$1***")

    /**
     * Logs the ad-related flags from a PlaybackAccessToken (never the token itself). Twitch reports
     * `hide_ads`, `subscriber` and `turbo` there, which is what determines whether the stream is
     * served without ad breaks.
     */
    private fun logPlaybackTokenClaims(channel: String, tokenValue: String) {
        val claims = runCatching {
            val payload = tokenValue.substringAfter('.', tokenValue).substringBefore('.')
            String(
                android.util.Base64.decode(
                    payload,
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
                ),
                Charsets.UTF_8
            )
        }.getOrNull() ?: return
        val adFree = Regex(""""hide_ads"\s*:\s*(true|false)""").find(claims)?.groupValues?.get(1)
        val subscriber = Regex(""""subscriber"\s*:\s*(true|false)""").find(claims)?.groupValues?.get(1)
        val turbo = Regex(""""turbo"\s*:\s*(true|false)""").find(claims)?.groupValues?.get(1)
        val userId = Regex(""""user_id"\s*:\s*"?(\d+)?"?""").find(claims)?.groupValues?.get(1)
        Log.d(
            "TwitchApi",
            "PlaybackAccessToken for $channel: hide_ads=$adFree subscriber=$subscriber turbo=$turbo " +
                "user_id=${userId ?: "anonymous"}"
        )
    }

    /**
     * Fetches all channels the user follows (paginated), including login and display name.
     * Used to register streamers with EventSub / intelligent polling.
     */
    suspend fun getFollowedChannels(userId: String, token: String, clientId: String): List<FollowedChannel> {
        val all = mutableListOf<FollowedChannel>()
        var cursor: String? = null
        val seenCursors = mutableSetOf<String>()
        try {
            while (true) {
                val httpResponse = client.get("https://api.twitch.tv/helix/channels/followed") {
                    url {
                        parameters.append("user_id", userId)
                        parameters.append("first", "100")
                        cursor?.let { parameters.append("after", it) }
                    }
                    headers {
                        append("Authorization", "Bearer $token")
                        append("Client-Id", clientId)
                    }
                }
                httpResponse.requireSuccess("Loading your followed channels")

                val response: GetFollowedChannelsResponse = httpResponse.body()
                response.data?.let { all.addAll(it) }
                Log.d(
                    "TwitchApi",
                    "getFollowedChannels page: ${response.data?.size ?: 0} channel(s), cursor=${response.pagination?.cursor ?: "-"}"
                )

                val next = response.pagination?.cursor
                if (next.isNullOrEmpty() || !seenCursors.add(next)) break
                cursor = next
            }
            Log.d("TwitchApi", "getFollowedChannels: user=$userId -> ${all.size} channel(s)")
        } catch (e: TwitchApiException) {
            throw e
        } catch (e: Exception) {
            Log.w("TwitchApi", "Failed to get followed channels: ${e.message}")
        }
        return all
    }

    /**
     * Creates a websocket EventSub subscription (stream.online) for a broadcaster on the given session.
     */
    suspend fun createEventSubSubscription(sessionId: String, broadcasterId: String, token: String, clientId: String): Boolean {
        return try {
            val body = buildJsonObject {
                put("type", "stream.online")
                put("version", "1")
                putJsonObject("condition") {
                    put("broadcaster_user_id", broadcasterId)
                }
                putJsonObject("transport") {
                    put("method", "websocket")
                    put("session_id", sessionId)
                }
            }.toString()
            val response = client.post("https://api.twitch.tv/helix/eventsub/subscriptions") {
                headers {
                    append("Authorization", "Bearer $token")
                    append("Client-Id", clientId)
                }
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (!response.status.isSuccess()) {
                Log.w("TwitchApi", "EventSub subscription failed for $broadcasterId: HTTP ${response.status.value}")
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            Log.w("TwitchApi", "Failed to create EventSub subscription for $broadcasterId: ${e.message}")
            false
        }
    }

    suspend fun getHelixChatters(broadcasterId: String, moderatorId: String, token: String, clientId: String): HelixChattersGrouped? {
        return try {
            val response = client.get("https://api.twitch.tv/helix/chat/chatters") {
                url {
                    parameters.append("broadcaster_id", broadcasterId)
                    parameters.append("moderator_id", moderatorId)
                    parameters.append("first", "1000")
                }
                headers {
                    append("Authorization", "Bearer $token")
                    append("Client-Id", clientId)
                }
            }

            if (!response.status.isSuccess()) {
                Log.w("TwitchApi", "Failed to get Helix chatters, status: ${response.status}")
                return null
            }

            val text = response.bodyAsText()
            val json = Json { ignoreUnknownKeys = true }
            val root = try {
                json.parseToJsonElement(text)
            } catch (e: Exception) {
                Log.e("TwitchApi", "Failed to parse Helix chatters response JSON", e)
                return null
            }

            val broadcasterList = mutableListOf<Chatter>()
            val modsList = mutableListOf<Chatter>()
            val vipsList = mutableListOf<Chatter>()
            val viewersList = mutableListOf<Chatter>()

            fun parseChatterFromElement(el: JsonElement): Chatter? {
                if (el !is JsonObject) return null
                val obj = el
                val userLogin = obj["user_login"]?.jsonPrimitive?.contentOrNull
                    ?: obj["login"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                val userName = obj["user_name"]?.jsonPrimitive?.contentOrNull
                    ?: obj["user_display_name"]?.jsonPrimitive?.contentOrNull
                    ?: obj["display_name"]?.jsonPrimitive?.contentOrNull
                    ?: userLogin
                val userId = obj["user_id"]?.jsonPrimitive?.contentOrNull ?: obj["id"]?.jsonPrimitive?.contentOrNull ?: ""
                return Chatter(userLogin, userName, userId)
            }

            // The Helix response may present data as an array or as an object with role keys. Handle both.
            val dataElement = when {
                root is JsonObject && root.containsKey("data") -> root["data"]
                else -> root
            }

            if (dataElement is JsonArray) {
                // Flat array: each element may have a type/role field
                dataElement.forEach { el ->
                    if (el !is JsonObject) return@forEach
                    val role = el["type"]?.jsonPrimitive?.contentOrNull
                        ?: el["role"]?.jsonPrimitive?.contentOrNull
                    val chatter = parseChatterFromElement(el) ?: return@forEach
                    when (role?.lowercase()) {
                        "broadcaster" -> broadcasterList.add(chatter)
                        "moderator", "mod" -> modsList.add(chatter)
                        "vip" -> vipsList.add(chatter)
                        else -> viewersList.add(chatter)
                    }
                }
            } else if (dataElement is JsonObject) {
                // Might be shaped like: { data: { broadcaster: [...], moderator: [...], vip: [...], viewer: [...] } }
                dataElement.jsonObject.forEach { (key, value) ->
                    if (value is JsonArray) {
                        value.forEach { el ->
                            val chatter = parseChatterFromElement(el) ?: return@forEach
                            when (key.lowercase()) {
                                "broadcaster", "broadcasters" -> broadcasterList.add(chatter)
                                "moderator", "moderators", "mods" -> modsList.add(chatter)
                                "vip", "vips" -> vipsList.add(chatter)
                                "viewer", "viewers" -> viewersList.add(chatter)
                                else -> viewersList.add(chatter)
                            }
                        }
                    }
                }
            }

            // As a fallback, if broadcaster list is empty try to find the broadcaster among all lists by matching id
            if (broadcasterList.isEmpty()) {
                val all = (modsList + vipsList + viewersList).toMutableList()
                val found = all.firstOrNull { it.userId.isNotBlank() && it.userId == broadcasterId }
                found?.let {
                    // remove from its previous list
                    modsList.removeIf { c -> c.userId == it.userId }
                    vipsList.removeIf { c -> c.userId == it.userId }
                    viewersList.removeIf { c -> c.userId == it.userId }
                    broadcasterList.add(it)
                }
            }

            // Sort each list by display/user name case-insensitively
            fun sortChatters(list: MutableList<Chatter>) = list.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.userName })

            sortChatters(broadcasterList)
            sortChatters(modsList)
            sortChatters(vipsList)
            sortChatters(viewersList)

            HelixChattersGrouped(
                broadcaster = broadcasterList,
                moderators = modsList,
                vips = vipsList,
                viewers = viewersList
            )

        } catch (e: Exception) {
            Log.e("TwitchApi", "Exception getting Helix chatters for $broadcasterId", e)
            null
        }
    }

    suspend fun revokeToken(token: String, clientId: String): Boolean {
        return try {
            val response: HttpResponse = client.post("https://id.twitch.tv/oauth2/revoke") {
                url {
                    parameters.append("client_id", clientId)
                    parameters.append("token", token)
                }
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            Log.e("TwitchApi", "Failed to revoke token", e)
            false
        }
    }

    suspend fun getStream(userId: String, token: String, clientId: String): FollowedStream? {
        return try {
            val response: GetStreamsResponse = client.get("https://api.twitch.tv/helix/streams") {
                url {
                    parameters.append("user_id", userId)
                }
                headers {
                    append("Authorization", "Bearer $token")
                    append("Client-Id", clientId)
                }
            }.body()
            response.data?.firstOrNull()
        } catch (e: Exception) {
            Log.e("TwitchApi", "Failed to get stream for user $userId", e)
            null
        }
    }

    suspend fun getFollowedStreams(userId: String, token: String, clientId: String): List<FollowedStream> {
        val allStreams = mutableListOf<FollowedStream>()
        var cursor: String? = null
        val seenCursors = mutableSetOf<String>()
        while (true) {
            val httpResponse = client.get("https://api.twitch.tv/helix/streams/followed") {
                url {
                    parameters.append("user_id", userId)
                    parameters.append("first", "100") // Fetch max results per page
                    cursor?.let { parameters.append("after", it) }
                }
                headers {
                    append("Authorization", "Bearer $token")
                    append("Client-Id", clientId)
                }
            }

            // Do NOT swallow errors here: an empty list would make the home screen claim
            // "no live channels" while the real problem is an expired token / rate limit.
            httpResponse.requireSuccess("Loading your live followed channels")

            val response: GetFollowedStreamsResponse = httpResponse.body()
            response.data?.let { allStreams.addAll(it) }
            Log.d(
                "TwitchApi",
                "getFollowedStreams page: ${response.data?.size ?: 0} live " +
                    "(${response.data?.joinToString { it.userLogin }.orEmpty()}), " +
                    "cursor=${response.pagination?.cursor ?: "-"}"
            )

            val next = response.pagination?.cursor
            // An empty or repeating cursor means "no more pages"; without this guard a
            // misbehaving cursor would loop forever and burn through the rate limit.
            if (next.isNullOrEmpty() || !seenCursors.add(next)) break
            cursor = next
        }
        Log.d(
            "TwitchApi",
            "getFollowedStreams: user=$userId -> ${allStreams.size} live channel(s)"
        )
        return allStreams
    }

    suspend fun getCurrentUser(token: String, clientId: String): TwitchUser? {
        val httpResponse = client.get("https://api.twitch.tv/helix/users") {
            headers {
                append("Authorization", "Bearer $token")
                append("Client-Id", clientId)
            }
        }
        httpResponse.requireSuccess("Loading your Twitch profile")
        val response: GetUsersResponse = httpResponse.body()
        return response.data?.firstOrNull()
    }

    /**
     * Checks whether Twitch still accepts [token] on the GQL API (the API used for channel points,
     * polls and the playback token). Helix can accept a token that GQL rejects, so this is the
     * check that matters for those features.
     */
    suspend fun isGqlTokenValid(token: String): Boolean {
        return try {
            val response = client.post("https://gql.twitch.tv/gql") {
                header("Client-ID", GQL_CLIENT_ID)
                header("Authorization", "OAuth $token")
                contentType(ContentType.Application.Json)
                setBody("""{"query":"query { currentUser { id login } }"}""")
            }
            if (!response.status.isSuccess()) {
                Log.w("TwitchApi", "GQL token check failed: HTTP ${response.status.value}")
                return false
            }
            val body: String = response.bodyAsText()
            val user = Json.parseToJsonElement(body)
                .jsonObject["data"]?.jsonObject?.get("currentUser")
            user != null && user !is kotlinx.serialization.json.JsonNull
        } catch (e: Exception) {
            Log.e("TwitchApi", "GQL token check failed", e)
            false
        }
    }

    /**
     * Checks the token against Twitch's OAuth validation endpoint. Unlike the GQL check this works
     * for any client ID and reports the scopes the token actually has, which makes it possible to
     * tell "token rejected" apart from "token valid but missing scopes".
     */
    suspend fun validateOAuthToken(token: String): String? {
        return try {
            val response = client.get("https://id.twitch.tv/oauth2/validate") {
                header("Authorization", "OAuth $token")
            }
            val body: String = response.bodyAsText()
            if (!response.status.isSuccess()) {
                Log.w("TwitchApi", "OAuth validate failed: HTTP ${response.status.value} ${body.take(200)}")
                return null
            }
            val json = Json.parseToJsonElement(body).jsonObject
            val clientId = json["client_id"]?.jsonPrimitive?.contentOrNull ?: "?"
            val login = json["login"]?.jsonPrimitive?.contentOrNull ?: "?"
            val scopes = json["scopes"]?.jsonArray?.joinToString(" ") { it.jsonPrimitive.contentOrNull ?: "" } ?: ""
            Log.d("TwitchApi", "OAuth token valid for $login (client_id=$clientId, expires_in=${json["expires_in"]})")
            Log.d("TwitchApi", "OAuth token scopes: $scopes")
            clientId
        } catch (e: Exception) {
            Log.e("TwitchApi", "OAuth validate failed", e)
            null
        }
    }

    suspend fun getUsers(userIds: List<String>, token: String, clientId: String): List<TwitchUser> {
        if (userIds.isEmpty()) return emptyList()
        return try {
            val httpResponse = client.get("https://api.twitch.tv/helix/users") {
                url {
                    userIds.forEach { parameter("id", it) }
                }
                headers {
                    append("Authorization", "Bearer $token")
                    append("Client-Id", clientId)
                }
            }
            httpResponse.requireSuccess("Loading streamer profiles")
            val response: GetUsersResponse = httpResponse.body()
            response.data ?: emptyList()
        } catch (e: TwitchApiException) {
            throw e
        } catch (e: Exception) {
            Log.e("TwitchApi", "Failed to get users for ids=${userIds.joinToString()}", e)
            emptyList()
        }
    }

    suspend fun searchChannels(query: String, token: String, clientId: String): List<SearchedChannel> {
        if (query.isBlank()) return emptyList()
        return try {
            val response: SearchChannelsResponse = client.get("https://api.twitch.tv/helix/search/channels") {
                url { parameters.append("query", query) }
                headers {
                    append("Authorization", "Bearer $token")
                    append("Client-Id", clientId)
                }
            }.body()
            response.data ?: emptyList()
        } catch (e: Exception) {
            Log.e("TwitchApi", "Failed to search channels for query='$query'", e)
            emptyList()
        }
    }

    suspend fun getGlobalTwitchEmotes(token: String, clientId: String): List<GlobalEmote> {
        return try {
            val response: GetGlobalEmotesResponse = client.get("https://api.twitch.tv/helix/chat/emotes/global") {
                headers {
                    append("Authorization", "Bearer $token")
                    append("Client-Id", clientId)
                }
            }.body()
            response.data ?: emptyList()
        } catch (e: Exception) {
            Log.e("TwitchApi", "Failed to get global twitch emotes", e)
            emptyList()
        }
    }

    @Serializable
    data class ChatBadgeVersion(
        val id: String,
        @SerialName("image_url_1x") val imageUrl1x: String,
        @SerialName("image_url_2x") val imageUrl2x: String,
        @SerialName("image_url_4x") val imageUrl4x: String
    )

    @Serializable
    data class ChatBadgeSet(
        val set_id: String,
        val categories: List<String>,
        @SerialName("last_updated") val lastUpdated: String?,
        val versions: List<ChatBadgeVersion>
    )

    @Serializable
    data class GetChatBadgesResponse(
        val data: List<ChatBadgeSet>? = null,
        @SerialName("effective_min_width") val effectiveMinWidth: Int? = null,
        @SerialName("effective_max_width") val effectiveMaxWidth: Int? = null
    )

    suspend fun getUserChatBadges(userId: String, token: String, clientId: String): List<ChatBadgeSet> {
        return try {
            val response: GetChatBadgesResponse = client.get("https://api.twitch.tv/helix/chat/badges") {
                url { parameters.append("broadcaster_id", userId) }
                headers {
                    append("Authorization", "Bearer $token")
                    append("Client-Id", clientId)
                }
            }.body()
            response.data ?: emptyList()
        } catch (e: Exception) {
            Log.e("TwitchApi", "Failed to get user chat badges for userId=$userId", e)
            emptyList()
        }
    }

    // Fetch the list of broadcaster IDs the user follows (paginated)
    suspend fun getFollowedChannelIds(userId: String, token: String, clientId: String): List<String> {
        val allIds = mutableListOf<String>()
        var cursor: String? = null
        try {
            do {
                val httpResponse = client.get("https://api.twitch.tv/helix/users/follows") {
                    url {
                        parameters.append("from_id", userId)
                        parameters.append("first", "100")
                        cursor?.let { parameters.append("after", it) }
                    }
                    headers {
                        append("Authorization", "Bearer $token")
                        append("Client-Id", clientId)
                    }
                }

                // Check HTTP status code first
                val statusCode = httpResponse.status.value
                if (statusCode !in 200..299) {
                    // Try to read the error body for better diagnostics
                    val bodyText = try {
                        httpResponse.bodyAsText()
                    } catch (e: Exception) {
                        "Unable to read error body: ${e.message}"
                    }
                    Log.w("TwitchApi", "Failed to get followed channels: HTTP $statusCode - $bodyText")

                    when (statusCode) {
                        401 -> {
                            Log.e("TwitchApi", "Unauthorized - token may have expired")
                            UserManager.logout()
                        }
                        410 -> {
                            Log.e("TwitchApi", "Endpoint returned 410 Gone - endpoint may have been removed or requires different scopes. Falling back to live-only followed streams.")
                            // Simpler fallback: return IDs of currently live followed channels (if any)
                            val liveStreams = getFollowedStreams(userId, token, clientId)
                            return liveStreams.map { it.userId }
                        }
                        else -> {
                            // Other non-success statuses - we've already logged the body
                        }
                    }

                    break
                }

                val response: GetFollowedChannelsResponse = httpResponse.body()

                // Check if response has valid data
                if (response.data == null) {
                    Log.w("TwitchApi", "API returned null data field. Error: ${response.message ?: response.error}")
                    break
                }

                allIds.addAll(response.data.map { it.broadcasterId })
                cursor = response.pagination?.cursor

            } while (cursor != null)
        } catch (e: Exception) {
            Log.e("TwitchApi", "Failed to get followed channel ids", e)
        }
        return allIds
    }

    // Query the streams endpoint for a list of user IDs (batched to 100 per request)
    suspend fun getStreamsForUsers(userIds: List<String>, token: String, clientId: String): List<FollowedStream> {
        if (userIds.isEmpty()) return emptyList()
        val result = mutableListOf<FollowedStream>()
        // Twitch allows up to 100 user_id parameters per request
        val batches = userIds.chunked(100)
        for (batch in batches) {
            val httpResponse = client.get("https://api.twitch.tv/helix/streams") {
                url {
                    batch.forEach { parameter("user_id", it) }
                }
                headers {
                    append("Authorization", "Bearer $token")
                    append("Client-Id", clientId)
                }
            }
            // Propagate failures: returning an empty list here would make every polled
            // streamer look offline and fire false "went offline" events.
            httpResponse.requireSuccess("Loading stream status")
            val response: GetStreamsResponse = httpResponse.body()
            response.data?.let { result.addAll(it) }
        }
        return result
    }

}
