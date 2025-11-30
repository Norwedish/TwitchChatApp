package com.norwedish.twitchchatapp

/**
 * Lightweight persistence helper for whisper conversations and unread counts using SharedPreferences.
 */

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

class WhisperPreferenceManager(context: Context) {
    companion object {
        private const val PREFS_NAME = "whisper_prefs"
        private const val KEY_CONVERSATIONS = "conversations_"
        private const val KEY_UNREAD_COUNT = "unread_count_"
        private const val KEY_LAST_MESSAGE_TIME = "last_msg_time_"
    }

    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun saveConversation(conversation: WhisperConversation) {
        val key = "$KEY_CONVERSATIONS${conversation.otherUserLogin}"
        val jsonString = json.encodeToString(conversation)
        sharedPreferences.edit().putString(key, jsonString).apply()
    }

    fun getConversation(userLogin: String): WhisperConversation? {
        val key = "$KEY_CONVERSATIONS$userLogin"
        val jsonString = sharedPreferences.getString(key, null) ?: return null
        return try {
            this.json.decodeFromString(jsonString)
        } catch (e: Exception) {
            null
        }
    }

    fun getAllConversations(): List<WhisperConversation> {
        return sharedPreferences.all.filter { it.key.startsWith(KEY_CONVERSATIONS) }
            .mapNotNull { (_, value) ->
                try {
                    if (value is String) json.decodeFromString<WhisperConversation>(value) else null
                } catch (e: Exception) {
                    null
                }
            }
            .sortedByDescending { it.lastMessageTime }
    }

    fun updateUnreadCount(userLogin: String, count: Int) {
        val key = "$KEY_UNREAD_COUNT$userLogin"
        sharedPreferences.edit().putInt(key, count).apply()
    }

    fun getUnreadCount(userLogin: String): Int {
        val key = "$KEY_UNREAD_COUNT$userLogin"
        return sharedPreferences.getInt(key, 0)
    }

    fun getTotalUnreadCount(): Int {
        return sharedPreferences.all.filter { it.key.startsWith(KEY_UNREAD_COUNT) }
            .values.sumOf { (it as? Int) ?: 0 }
    }

    fun clearUnreadCount(userLogin: String) {
        val key = "$KEY_UNREAD_COUNT$userLogin"
        sharedPreferences.edit().remove(key).apply()
    }

    fun deleteConversation(userLogin: String) {
        val conversationKey = "$KEY_CONVERSATIONS$userLogin"
        val unreadKey = "$KEY_UNREAD_COUNT$userLogin"
        val timeKey = "$KEY_LAST_MESSAGE_TIME$userLogin"
        sharedPreferences.edit()
            .remove(conversationKey)
            .remove(unreadKey)
            .remove(timeKey)
            .apply()
    }

    fun clearAllConversations() {
        sharedPreferences.edit().clear().apply()
    }
}
