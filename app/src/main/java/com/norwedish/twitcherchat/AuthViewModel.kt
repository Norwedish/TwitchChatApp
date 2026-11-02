package com.norwedish.twitcherchat

/**
 * Twitch OAuth (implicit grant) configuration for the in-app login.
 *
 * The authorization page is shown in [TwitchLoginWebView]. Twitch redirects to
 * [redirectUri] with the token in the URL fragment; the WebView intercepts that navigation
 * before any network request is made, so no local HTTP server and no cleartext traffic are
 * needed.
 *
 * Login uses the official Twitch mobile client ID ([GQL_MOBILE_CLIENT_ID]) so that the token also
 * works for channel points and poll voting, which only accept the mobile/web clients. The mobile
 * client has no localhost redirect registered, so the WebView also watches for the token in any
 * navigation.
 */

import androidx.lifecycle.ViewModel

class AuthViewModel : ViewModel() {

    /** Client ID used for the OAuth login and for every GQL call. */
    private val clientId: String = GQL_MOBILE_CLIENT_ID

    /** Client ID used for the GQL endpoints (channel points, polls, playback token). */
    val gqlClientId: String = GQL_MOBILE_CLIENT_ID

    /** Redirect URI. Must be registered for the client in the Twitch Developer Console. */
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
