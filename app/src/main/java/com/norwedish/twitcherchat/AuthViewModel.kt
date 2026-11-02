package com.norwedish.twitcherchat

/**
 * Twitch OAuth (implicit grant) configuration for the in-app login.
 *
 * The authorization page is shown in [TwitchLoginWebView]. Twitch redirects to
 * [redirectUri] with the token in the URL fragment; the WebView intercepts that navigation
 * before any network request is made, so no local HTTP server and no cleartext traffic are
 * needed.
 *
 * Two client IDs are involved:
 *  - [authorizeClientId]: the app's own Twitch application. It is the only client for which our
 *    `http://localhost:3000` redirect is registered, so the login page is opened for it.
 *  - [gqlClientId]: the official Twitch mobile client, used for the GQL endpoints (channel points,
 *    poll voting, playback token). Twitch rejects tokens from other clients there, so a token
 *    issued by the app client is not accepted by it (HTTP 401) — a Twitch-side restriction.
 */

import androidx.lifecycle.ViewModel

class AuthViewModel : ViewModel() {

    /**
     * Client ID used only to *authorize* (issue the token). The mobile client does not allow our
     * redirect URI, so the login uses the app's own client (localhost redirect works).
     */
    private val authorizeClientId: String = BuildConfig.TWITCH_CLIENT_ID

    /** Client ID used for the GQL endpoints (channel points, polls, playback token). */
    val gqlClientId: String = GQL_MOBILE_CLIENT_ID

    /** Redirect URI registered for the app's Twitch application. */
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
            "&client_id=$authorizeClientId" +
            "&redirect_uri=$redirectUri" +
            "&scope=${scopes.joinToString("%20")}"
}
