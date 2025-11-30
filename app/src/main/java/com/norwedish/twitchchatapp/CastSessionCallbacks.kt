package com.norwedish.twitchchatapp

import android.util.Log

/**
 * Lightweight helper to notify CastManager when a session starts/ends.
 * The MainActivity SessionManagerListener calls these when Cast lifecycle events occur.
 */
object CastSessionCallbacks {
    private const val TAG = "CastSessionCallbacks"

    fun onSessionStarted() {
        Log.i(TAG, "onSessionStarted called - Attempting to load any pending URL")
        // Attempt to load pending url via CastManager
        CastManager.tryLoadPendingIfAny()
    }

    fun onSessionEnded() {
        Log.i(TAG, "onSessionEnded called")
        // Clear the session reference
        CastManager.setCurrentSession(null)
    }
}
