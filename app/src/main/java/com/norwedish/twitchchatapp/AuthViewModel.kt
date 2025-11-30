package com.norwedish.twitchchatapp

import android.content.Context
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.ViewModel
import androidx.core.net.toUri

class AuthViewModel : ViewModel() {

    // Gebruik je echte Client ID hier
    private val clientId: String = BuildConfig.TWITCH_CLIENT_ID
    // Let op: De redirect URI is nu een echte, werkende URL voor onze lokale server
    private val redirectUri = "http://localhost:3000"

    fun startLoginFlow(context: Context, onTokenReceived: (String) -> Unit) {
        // 1. Start onze lokale server die luistert naar de redirect
        LoginServer.start {
            onTokenReceived(it)
            stopLoginFlow()
        }

        // 2. Bouw de Twitch authenticatie-URL
        val twitchAuthUrl = "https://id.twitch.tv/oauth2/authorize" +
                "?response_type=token" + // Vraagt een token direct in de URL fragment (#)
                "&client_id=$clientId" +
                "&redirect_uri=$redirectUri" +
                "&scope=chat:read chat:edit user:read:follows user:edit:follows channel:read:polls user:read:broadcast moderation:read channel:read:vips moderator:read:chatters user:manage:whispers"

        // 3. Open de URL in een Custom Tab (externe browser)
        val builder = CustomTabsIntent.Builder()
        val customTabsIntent = builder.build()
        customTabsIntent.launchUrl(context, twitchAuthUrl.toUri())
    }

    fun stopLoginFlow() {
        LoginServer.stop()
    }
}