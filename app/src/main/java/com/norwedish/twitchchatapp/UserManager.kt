package com.norwedish.twitchchatapp

/**
 * Centralized manager for user session/state: stores access tokens, current user, and persistence.
 * Used across app components to read or update authentication state.
 */

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "twitch_app_prefs")

object UserManager {
    // Broadcast actions to notify services/components about login/logout events
    const val ACTION_USER_LOGGED_IN = "com.norwedish.twitchchatapp.ACTION_USER_LOGGED_IN"
    const val ACTION_USER_LOGGED_OUT = "com.norwedish.twitchchatapp.ACTION_USER_LOGGED_OUT"

    // Keep a reference to application context (set in init) for broadcasting events
    private var appContext: Context? = null

    private val ACCESS_TOKEN = stringPreferencesKey("access_token")
    private val CURRENT_USER = stringPreferencesKey("current_user")
    private val gson = Gson()

    val CLIENT_ID = BuildConfig.TWITCH_CLIENT_ID
    var accessToken: String? by mutableStateOf(null)
        private set
    var currentUser: TwitchUser? by mutableStateOf(null)
        private set
    var isLoading: Boolean by mutableStateOf(true)
        private set

    private var _dataStore: DataStore<Preferences>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun init(context: Context) {
        _dataStore = context.dataStore
        appContext = context.applicationContext
        scope.launch(Dispatchers.IO) {
            val prefs = _dataStore?.data?.first()
            val token = prefs?.get(ACCESS_TOKEN)
            val userJson = prefs?.get(CURRENT_USER)
            withContext(Dispatchers.Main) {
                accessToken = token
                if (userJson != null) {
                    currentUser = gson.fromJson(userJson, TwitchUser::class.java)
                }
                isLoading = false
            }
        }
    }

    suspend fun login(token: String, user: TwitchUser) {
        withContext(Dispatchers.Main) {
            accessToken = token
            currentUser = user
        }
        _dataStore?.edit { settings ->
            settings[ACCESS_TOKEN] = token
            settings[CURRENT_USER] = gson.toJson(user)
        }
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
        _dataStore?.edit { settings ->
            settings[CURRENT_USER] = gson.toJson(updatedUser)
        }
    }

    suspend fun logout() {
        accessToken?.let {
            withContext(Dispatchers.IO) {
                TwitchApi.revokeToken(it, CLIENT_ID)
            }
        }
        withContext(Dispatchers.Main) {
            accessToken = null
            currentUser = null
        }
        _dataStore?.edit {
            it.clear()
        }
        // Notify listeners that user logged out
        try {
            appContext?.sendBroadcast(Intent(ACTION_USER_LOGGED_OUT))
        } catch (_: Exception) {}
    }
}
