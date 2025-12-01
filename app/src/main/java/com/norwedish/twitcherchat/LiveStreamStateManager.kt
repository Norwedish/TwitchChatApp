package com.norwedish.twitcherchat

import android.content.Context
import android.util.Log

object LiveStreamStateManager {
    private const val PREFS_NAME = "live_stream_prefs"
    private const val KEY_KNOWN_STREAMS = "known_live_streams"
    private const val TAG = "LiveStreamStateManager"

    fun updateKnownLiveStreams(context: Context, liveStreamIds: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_KNOWN_STREAMS, liveStreamIds).apply()
        Log.d(TAG, "Updated known live streams. Count: ${liveStreamIds.size}")
    }

    fun getKnownLiveStreams(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_KNOWN_STREAMS, emptySet()) ?: emptySet()
    }
}
