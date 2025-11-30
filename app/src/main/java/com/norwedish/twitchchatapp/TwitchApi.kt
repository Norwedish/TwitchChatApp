package com.norwedish.twitchchatapp

import android.util.Log
import io.ktor.client.* 
import io.ktor.client.call.*
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

// --- Data Classes ---

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

// --- CHATTER LIST DATA CLASSES --- //

// FOR HELIX (NEW API)
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

object TwitchApi {

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
        install(WebSockets)
    }

    suspend fun getFollowRelationship(userId: String, broadcasterId: String, token: String, clientId: String): FollowedChannel? {
        if (userId.isBlank() || broadcasterId.isBlank()) return null
        return try {
            // The older /helix/channels/followed endpoint has been removed (returned 410).
            // Use GET /helix/users/follows with from_id and to_id and map the result into FollowedChannel.
            val response: UsersFollowsResponse = client.get("https://api.twitch.tv/helix/users/follows") {
                url {
                    parameters.append("from_id", userId)
                    parameters.append("to_id", broadcasterId)
                }
                headers {
                    header("Client-Id", clientId)
                    header("Authorization", "Bearer $token")
                }
            }.body()

            val first = response.data?.firstOrNull()
            first?.let {
                FollowedChannel(
                    broadcasterId = it.toId,
                    broadcasterLogin = it.toLogin ?: "",
                    broadcasterName = it.toName ?: "",
                    followedAt = it.followedAt
                )
            }
        } catch (e: ClientRequestException) {
            // ClientRequestException can wrap non-2xx responses; check for 410 Gone specifically
            try {
                val status = (e.response.status)
                if (status == HttpStatusCode.Gone) {
                    Log.e("TwitchApi", "Endpoint returned 410 Gone - endpoint may have been removed or requires different scopes. Returning null.")
                    return null
                }
            } catch (_: Exception) {
                // ignore and fall through to generic error logging
            }
            Log.e("TwitchApi", "Error fetching follow relationship: ${e.message}", e)
            null
        } catch (e: Exception) {
            Log.e("TwitchApi", "Error fetching follow relationship: ${e.message}", e)
            null
        }
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

        val playbackAccessToken = try {
            val gqlRequest = """{
                "operationName": "PlaybackAccessToken",
                "variables": {
                    "isLive": true,
                    "login": "$channelName",
                    "isVod": false,
                    "vodID": "",
                    "playerType": "embed"
                },
                "extensions": {
                    "persistedQuery": {
                        "version": 1,
                        "sha256Hash": "0828119ded1c13477966434e15800ff57ddacf13ba1911c129dc2200705b0712"
                    }
                }
            }"""
            val response: PlaybackAccessTokenResponse = client.post("https://gql.twitch.tv/gql") {
                header("Client-ID", gqlClientId)
                contentType(ContentType.Application.Json)
                setBody(gqlRequest)
            }.body()
            response.data?.streamPlaybackAccessToken
        } catch (e: Exception) {
            Log.e("TwitchApi", "Failed to get playback access token", e)
            null
        }

        return playbackAccessToken?.let {
            val url = "https://usher.ttvnw.net/api/channel/hls/$channelName.m3u8"
            val params = Parameters.build {
                append("client_id", gqlClientId)
                append("token", it.value)
                append("sig", it.signature)
                append("allow_source", "true")
                append("allow_audio_only", "true")
            }.formUrlEncode()
            url + "?" + params
        }
    }

    suspend fun getHelixChatters(broadcasterId: String, moderatorId: String, token: String, clientId: String): List<Chatter>? {
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
            if (response.status.isSuccess()) {
                response.body<HelixChattersResponse>().data
            } else {
                Log.w("TwitchApi", "Failed to get Helix chatters, status: ${response.status}")
                null // Return null on failure (e.g., 403 Forbidden)
            }
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
        try {
            do {
                val response: GetFollowedStreamsResponse = client.get("https://api.twitch.tv/helix/streams/followed") {
                    url {
                        parameters.append("user_id", userId)
                        parameters.append("first", "100") // Fetch max results per page
                        cursor?.let { parameters.append("after", it) }
                    }
                    headers {
                        append("Authorization", "Bearer $token")
                        append("Client-Id", clientId)
                    }
                }.body()

                if (response.data != null) {
                    allStreams.addAll(response.data)
                }
                cursor = response.pagination?.cursor

            } while (cursor != null)
        } catch (e: Exception) {
            Log.e("TwitchApi", "Failed to get followed streams", e)
        }
        return allStreams
    }

    suspend fun getCurrentUser(token: String, clientId: String): TwitchUser? {
        return try {
            val response: GetUsersResponse = client.get("https://api.twitch.tv/helix/users") {
                headers {
                    append("Authorization", "Bearer $token")
                    append("Client-Id", clientId)
                }
            }.body()
            response.data?.firstOrNull()
        } catch (e: Exception) {
            Log.e("TwitchApi", "Failed to get current user", e)
            null
        }
    }

    suspend fun getUsers(userIds: List<String>, token: String, clientId: String): List<TwitchUser> {
        if (userIds.isEmpty()) return emptyList()
        return try {
            val response: GetUsersResponse = client.get("https://api.twitch.tv/helix/users") {
                url {
                    userIds.forEach { parameter("id", it) }
                }
                headers {
                    append("Authorization", "Bearer $token")
                    append("Client-Id", clientId)
                }
            }.body()
            response.data ?: emptyList()
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
        try {
            // Twitch allows up to 100 user_id parameters per request
            val batches = userIds.chunked(100)
            for (batch in batches) {
                val response: GetStreamsResponse = client.get("https://api.twitch.tv/helix/streams") {
                    url {
                        batch.forEach { parameter("user_id", it) }
                    }
                    headers {
                        append("Authorization", "Bearer $token")
                        append("Client-Id", clientId)
                    }
                }.body()
                if (response.data != null) {
                    result.addAll(response.data)
                }
            }
        } catch (e: Exception) {
            Log.e("TwitchApi", "Failed to get streams for users", e)
        }
        return result
    }
}
