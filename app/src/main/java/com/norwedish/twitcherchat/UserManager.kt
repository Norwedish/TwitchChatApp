package com.norwedish.twitcherchat

/**
 * Centralized manager for user session/state: stores the access token and current user.
 *
 * Security: the OAuth token and user profile are stored in EncryptedSharedPreferences
 * (AES256-GCM via a hardware-backed Android Keystore key), never in plaintext.
 * On first launch after this change, any session stored in the legacy plaintext
 * DataStore is migrated into the encrypted store and then removed.
 */

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Legacy plaintext store, only read once for migration purposes.
private val Context.legacyDataStore: DataStore<Preferences> by preferencesDataStore(name = "twitch_app_prefs")

object UserManager {
    // Broadcast actions to notify services/components about login/logout events
    const val ACTION_USER_LOGGED_IN = "com.norwedish.twitcherchat.ACTION_USER_LOGGED_IN"
    const val ACTION_USER_LOGGED_OUT = "com.norwedish.twitcherchat.ACTION_USER_LOGGED_OUT"

    private const val SECURE_PREFS_NAME = "twitch_secure_prefs"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_CURRENT_USER = "current_user"

    // Keep a reference to application context (set in init) for broadcasting events
    private var appContext: Context? = null

    private var securePrefs: SharedPreferences? = null
    private val gson = Gson()

    val CLIENT_ID = BuildConfig.TWITCH_CLIENT_ID
    var accessToken: String? by mutableStateOf(null)
        private set
    var currentUser: TwitchUser? by mutableStateOf(null)
        private set
    var isLoading: Boolean by mutableStateOf(true)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Suppress("DEPRECATION") // security-crypto 1.1.0 marks these deprecated in favor of Tink; still fully functional
    fun init(context: Context) {
        appContext = context.applicationContext
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        securePrefs = EncryptedSharedPreferences.create(
            context.applicationContext,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        scope.launch(Dispatchers.IO) {
            migrateFromLegacyDataStore(context.applicationContext)
            val prefs = securePrefs
            val token = prefs?.getString(KEY_ACCESS_TOKEN, null)
            val userJson = prefs?.getString(KEY_CURRENT_USER, null)
            withContext(Dispatchers.Main) {
                accessToken = token
                if (userJson != null) {
                    currentUser = runCatching { gson.fromJson(userJson, TwitchUser::class.java) }.getOrNull()
                }
                isLoading = false
            }
        }
    }

    /**
     * One-time migration: if the encrypted store is empty but the legacy DataStore holds a
     * session, copy it over and wipe the legacy store so the token is never stored in plaintext.
     */
    private suspend fun migrateFromLegacyDataStore(context: Context) {
        val prefs = securePrefs ?: return
        if (prefs.contains(KEY_ACCESS_TOKEN)) return
        val legacy = context.legacyDataStore.data.first()
        val tokenKey = stringPreferencesKey(KEY_ACCESS_TOKEN)
        val userKey = stringPreferencesKey(KEY_CURRENT_USER)
        val token = legacy[tokenKey]
        val userJson = legacy[userKey]
        if (token != null || userJson != null) {
            prefs.edit().apply {
                if (token != null) putString(KEY_ACCESS_TOKEN, token)
                if (userJson != null) putString(KEY_CURRENT_USER, userJson)
            }.apply()
            runCatching { context.legacyDataStore.edit { it.clear() } }
        }
    }

    suspend fun login(token: String, user: TwitchUser) {
        withContext(Dispatchers.Main) {
            accessToken = token
            currentUser = user
        }
        securePrefs?.edit()?.apply {
            putString(KEY_ACCESS_TOKEN, token)
            putString(KEY_CURRENT_USER, gson.toJson(user))
        }?.apply()
        // Notify listeners that a user logged in
        try {
            appContext?.sendBroadcast(Intent(ACTION_USER_LOGGED_IN))
        } catch (_: Exception) {}
    }

    suspend fun updateUserChatColor(newColor: String) {
        val updatedUser = currentUser?.copy(chatColor = newColor)
        withContext(Dispatchers.Main) {
            currentUser = updatedUser
        }
        securePrefs?.edit()?.putString(KEY_CURRENT_USER, gson.toJson(updatedUser))?.apply()
    }

    suspend fun logout() {
        val tokenToRevoke = accessToken
        // Clear the session first: a failing revoke call must never leave the user "logged in".
        withContext(Dispatchers.Main) {
            accessToken = null
            currentUser = null
        }
        securePrefs?.edit()?.clear()?.apply()
        if (tokenToRevoke != null) {
            runCatching { TwitchApi.revokeToken(tokenToRevoke, CLIENT_ID) }
        }
        // Notify listeners that user logged out
        try {
            appContext?.sendBroadcast(Intent(ACTION_USER_LOGGED_OUT))
        } catch (_: Exception) {}
    }

    /**
     * Checks the stored OAuth token against Twitch. Returns false when Twitch rejects it (expired
     * or revoked), which is the most common reason features like channel points fail with HTTP 401
     * while the app still looks logged in.
     */
    suspend fun isTokenValid(): Boolean {
        val token = accessToken ?: return false
        return runCatching { TwitchApi.getCurrentUser(token, CLIENT_ID) != null }.getOrDefault(false)
    }

    /**
     * True when Twitch accepts the stored token on the GQL API. Helix and GQL can disagree, so this
     * is checked separately before using channel points / polls / playback.
     */
    suspend fun isGqlTokenValid(): Boolean {
        val token = accessToken ?: return false
        return TwitchApi.isGqlTokenValid(token)
    }
}
