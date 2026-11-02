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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private const val TAG = "ChannelTools"

/** The public client-id of the Twitch web app (same one the web player uses). */
internal const val GQL_CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"

/** A message that the streamer or a moderator pinned to the top of chat. */
data class PinnedMessage(
    val messageId: String,
    val author: String,
    val message: String,
    val pinnedBy: String? = null
)

/** A channel points reward the viewer can redeem. */
data class ChannelReward(
    val id: String,
    val title: String,
    val cost: Int,
    val prompt: String? = null,
    val isEnabled: Boolean = true,
    val isInStock: Boolean = true,
    val requiresInput: Boolean = false
)

/** A channel points redemption the viewer did in this session (shown as a receipt). */
data class RewardRedemption(
    val title: String,
    val cost: Int,
    val success: Boolean,
    val message: String? = null
)

/** Everything the channel-points menu needs to render. */
data class ChannelPointsState(
    val balance: Int? = null,
    val rewards: List<ChannelReward> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val lastRedemption: RewardRedemption? = null
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
            status != null -> Log.w(TAG, "$operation failed: HTTP $status")
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

    /**
     * Loads the viewer's channel points balance and the channel's redeemable rewards.
     * Returns a state with [ChannelPointsState.error] set when it failed.
     *
     * Uses Twitch's persisted `ChannelPointsContext` query (the full query is not part of the
     * public schema, so the hash is the only way in). When the hash rotates, Twitch answers with
     * PersistedQueryNotFound and the menu shows the "unavailable" state instead of crashing.
     */
    suspend fun loadChannelPoints(
        client: HttpClient,
        channelLogin: String,
        channelId: String
    ): ChannelPointsState {
        val variables = buildJsonObject {
            put("channelID", channelId)
            put("channelLogin", channelLogin)
        }
        val data = gqlPersisted(
            client,
            operationName = "ChannelPointsContext",
            variables = variables,
            hash = "1530a003a7d374b0380b79db0be0534f30ff46e61cffa2bc0e2468a909fbc024"
        ) ?: return ChannelPointsState(error = "unavailable")

        val channel = data.jsonObject["community"]?.jsonObject?.get("channel")?.jsonObject
        val self = channel?.get("self")?.jsonObject
        val balance = self?.get("communityPoints")?.jsonObject?.get("balance")?.jsonPrimitive?.intOrNull

        val rewards = channel
            ?.get("communityPointsSettings")
            ?.jsonObject
            ?.get("customRewards")
            ?.jsonArray
            ?.mapNotNull { element ->
                val obj = element.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val cost = obj["cost"]?.jsonPrimitive?.intOrNull ?: 0
                ChannelReward(
                    id = id,
                    title = title,
                    cost = cost,
                    prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull,
                    isEnabled = obj["isEnabled"]?.jsonPrimitive?.contentOrNull != "false",
                    isInStock = obj["isInStock"]?.jsonPrimitive?.contentOrNull != "false",
                    requiresInput = obj["isUserInputRequired"]?.jsonPrimitive?.contentOrNull == "true"
                )
            }
            .orEmpty()

        // No communityPoints object at all means the channel has channel points disabled
        val pointsDisabled = channel != null && self != null && self["communityPoints"] == null
        return ChannelPointsState(
            balance = balance,
            rewards = rewards,
            error = if (pointsDisabled) "disabled" else null
        )
    }

    /**
     * Redeems a channel points reward. [input] is required for rewards that ask the viewer for a
     * message. Returns a receipt describing what happened.
     */
    suspend fun redeemReward(
        client: HttpClient,
        channelId: String,
        reward: ChannelReward,
        input: String? = null
    ): RewardRedemption {
        val query = """
            mutation RedeemCustomReward(${'$'}input: RedeemCommunityPointsCustomRewardInput!) {
              redeemCustomReward(input: ${'$'}input) {
                error {
                  code
                }
                redemption {
                  id
                }
              }
            }
        """.trimIndent()
        val variables = buildJsonObject {
            putJsonObject("input") {
                put("channelID", channelId)
                put("rewardID", reward.id)
                put("cost", reward.cost)
                put("redemptionContext", "CHANNEL_POINTS")
                if (!input.isNullOrBlank()) put("textInput", input)
            }
        }
        val data = gqlQuery(client, query, variables) ?: return RewardRedemption(
            title = reward.title,
            cost = reward.cost,
            success = false,
            message = "unavailable"
        )
        val error = data.jsonObject["redeemCustomReward"]?.jsonObject?.get("error")?.jsonObject
        val code = error?.get("code")?.jsonPrimitive?.contentOrNull
        return if (code == null) {
            RewardRedemption(title = reward.title, cost = reward.cost, success = true)
        } else {
            Log.w(TAG, "RedeemCustomReward error: $code")
            RewardRedemption(
                title = reward.title,
                cost = reward.cost,
                success = false,
                message = code
            )
        }
    }

    /** Sends a persisted GQL query (operationName + hash) and returns its `data` object. */
    private suspend fun gqlPersisted(
        client: HttpClient,
        operationName: String,
        variables: JsonObject,
        hash: String
    ): JsonElement? {
        val auth = token() ?: return null
        val body = buildJsonObject {
            put("operationName", operationName)
            put("variables", variables)
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", hash)
                }
            }
        }.toString()
        return try {
            val response: HttpResponse = client.post("https://gql.twitch.tv/gql") {
                header("Client-ID", GQL_CLIENT_ID)
                header("Authorization", "OAuth $auth")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (!response.status.isSuccess()) {
                logFailure(operationName, response.status.value)
                return null
            }
            val text = response.bodyAsText()
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            root["errors"]?.let { Log.w(TAG, "$operationName returned errors: ${it.toString().take(200)}") }
            root["data"]
        } catch (e: Exception) {
            Log.e(TAG, "$operationName request failed", e)
            null
        }
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
