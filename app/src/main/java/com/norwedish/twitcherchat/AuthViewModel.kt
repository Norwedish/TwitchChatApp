package com.norwedish.twitcherchat

/**
 * Twitch OAuth (implicit grant) configuration for the in-app login.
 *
 * The authorization page is shown in [TwitchLoginWebView]. Twitch redirects to
 * [redirectUri] with the token in the URL fragment; the WebView intercepts that navigation
 * before any network request is made, so no local HTTP server and no cleartext traffic are
 * needed.
 */

import androidx.lifecycle.ViewModel

class AuthViewModel : ViewModel() {

    private val clientId: String = BuildConfig.TWITCH_CLIENT_ID

    /**
     * Client ID used for the GQL endpoints (channel points, polls, playback token).
     *
     * Twitch's public web client ID is required here: GQL answers "The Client-ID header is invalid"
     * (HTTP 400) for the app's own client ID, and a token issued by the app's client is rejected
     * with HTTP 401 when sent with the web client ID. So GQL calls are made with the web client and
     * no Authorization header — the same as the Twitch website does for anonymous viewers.
     */
    val gqlClientId: String = GQL_CLIENT_ID

    /** Redirect URI registered for the Twitch application. Intercepted by the WebView. */
    val redirectUri: String = "http://localhost:3000"

    private val scopes = listOf(
        "chat:read",
        "chat:edit",
        "user:read:follows",
        "user:edit:follows",
        "channel:read:polls",
        "user:read:broadcast",
        "moderation:read",
        "channel:read:vips",
        "moderator:read:chatters",
        // Channel points: read the balance/rewards and redeem them.
        // Twitch asks for a 2FA code during login when these scopes are requested; that is
        // expected and happens on every login for this account.
        "channel:read:redemptions",
        "channel:manage:redemptions"
    )

    /** Authorization URL loaded by the in-app WebView. */
    val authUrl: String
        get() = "https://id.twitch.tv/oauth2/authorize" +
            "?response_type=token" +
            "&client_id=$clientId" +
            "&redirect_uri=$redirectUri" +
            "&scope=${scopes.joinToString("%20")}"
}
