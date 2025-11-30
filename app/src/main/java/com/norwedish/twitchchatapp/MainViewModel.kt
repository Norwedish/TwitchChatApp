package com.norwedish.twitchchatapp

/**
 * ViewModel for the main/home screen. Loads followed streams, enriches with user info and exposes state
 * for the `MainScreen` composable.
 *
 * - EnrichedStream combines stream metadata with the streamer's user profile for easier UI rendering.
 * - MainViewModel exposes followedStreams and loading state and is responsible for fetching and
 *   combining Twitch API data into UI-ready models.
 */

import android.app.Application
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

// NEW: Data class that holds the stream info AND the user's profile image
data class EnrichedStream(
    val stream: FollowedStream,
    val user: TwitchUser
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    // UPDATED: The state now holds the new, richer data class
    private val _followedStreams = mutableStateOf<List<EnrichedStream>>(emptyList())
    val followedStreams: State<List<EnrichedStream>> = _followedStreams

    private val _isLoading = mutableStateOf(false)
    val isLoading: State<Boolean> = _isLoading

    fun loadFollowedStreams() {
        viewModelScope.launch {
            _isLoading.value = true
            val user = UserManager.currentUser
            val token = UserManager.accessToken
            if (user != null && token != null) {
                // 1. Get the list of live streams
                val streams = TwitchApi.getFollowedStreams(user.id, token, UserManager.CLIENT_ID)
                val liveStreamIds = streams.map { it.userId }.toSet()

                // **NEW**: Update the central state manager with the latest live streams
                LiveStreamStateManager.updateKnownLiveStreams(getApplication(), liveStreamIds)

                if (streams.isNotEmpty()) {
                    // 2. Get all the user IDs from the streams
                    val userIds = streams.map { it.userId }

                    // 3. Fetch the user profiles for all those IDs in one call
                    val users = TwitchApi.getUsers(userIds, token, UserManager.CLIENT_ID)
                    val userMap = users.associateBy { it.id }

                    // 4. Combine the stream data with the user data
                    _followedStreams.value = streams.mapNotNull { stream ->
                        userMap[stream.userId]?.let { streamUser ->
                            EnrichedStream(stream = stream, user = streamUser)
                        }
                    }
                } else {
                    _followedStreams.value = emptyList()
                }
            }
            _isLoading.value = false
        }
    }
}