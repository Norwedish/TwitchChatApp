package com.norwedish.twitcherchat

/**
 * Data classes and GQL access used by the "channel tools" feature: pinned messages, polls and
 * channel points.
 *
 * Pinned messages and polls arrive over the IRC connection (USERNOTICE / CLEARMSG); channel points
 * need GQL because the IRC API does not expose a viewer's balance or the reward list.
 */

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private const val TAG = "ChannelTools"

/**
 * Client ID of the official Twitch mobile app. Twitch's GQL accepts this one for the
 * channel-points queries, which the web client ID does not (that returns 400 "Client-ID header is
 * invalid"). It is a public identifier, not a secret.
 *
 * Note: GQL only accepts a token issued by the same client, so this ID alone is not enough —
 * see [AuthViewModel]. Channel points were removed from the app because of that restriction.
 */
internal const val GQL_MOBILE_CLIENT_ID = "ue6666qo983tsx6so1t0vnawi233wa"

/** The public client-id of the Twitch web app (same one the web player uses). */
internal const val GQL_CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"

/** A message that the streamer or a moderator pinned to the top of chat. */
data class PinnedMessage(
    val messageId: String,
    val author: String,
    val message: String,
    val pinnedBy: String? = null
)

/**
 * GQL helpers for polls and channel points. All calls fail soft (return null / a state with an
 * error) so the chat keeps working when Twitch changes something.
 */
object ChannelToolsApi {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun token(): String? = UserManager.accessToken

    /**
     * Logs why a GQL call could not be made or was rejected. Tokens are never logged.
     * HTTP 401 means Twitch rejected the stored OAuth token (expired or revoked), which is
     * different from a network error — useful when a feature "does nothing".
     */
    private fun logFailure(operation: String, status: Int?) {
        when {
            token() == null -> Log.w(TAG, "$operation skipped: not logged in (no OAuth token)")
            status != null -> Log.w(TAG, "$operation failed: HTTP $status (client_id=$GQL_CLIENT_ID)")
            else -> Log.w(TAG, "$operation failed: network error")
        }
    }

    /** Votes on a poll. Returns true when Twitch accepted the vote. */
    suspend fun voteOnPoll(client: HttpClient, pollId: String, choiceId: String): Boolean {
        val query = """
            mutation PollVote(${'$'}input: PollVoteInput!) {
              votePoll(input: ${'$'}input) {
                poll {
                  id
                }
              }
            }
        """.trimIndent()
        val variables = buildJsonObject {
            putJsonObject("input") {
                put("pollID", pollId)
                put("choiceID", choiceId)
            }
        }
        val data = gqlQuery(client, query, variables) ?: return false
        return data.jsonObject["votePoll"]?.jsonObject?.get("poll") != null
    }

    /** Sends a full (non-persisted) GQL query and returns its `data` object. */
    private suspend fun gqlQuery(
        client: HttpClient,
        query: String,
        variables: JsonObject
    ): JsonElement? {
        val auth = token() ?: return null
        val body = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }.toString()
        return try {
            val response: HttpResponse = client.post("https://gql.twitch.tv/gql") {
                header("Client-ID", GQL_CLIENT_ID)
                header("Authorization", "OAuth $auth")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (!response.status.isSuccess()) {
                logFailure("GQL query", response.status.value)
                return null
            }
            val text = response.bodyAsText()
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            root["errors"]?.let { Log.w(TAG, "GQL returned errors: ${it.toString().take(200)}") }
            root["data"]
        } catch (e: Exception) {
            Log.e(TAG, "GQL request failed", e)
            null
        }
    }
}
