package com.norwedish.twitcherchat

/**
 * ViewModel for the main/home screen. Loads followed streams, enriches them with user info and
 * exposes state for the `MainScreen` composable.
 *
 * - EnrichedStream combines stream metadata with the streamer's user profile for easier UI rendering.
 * - State is exposed as StateFlows (loading / error / data) so the UI can react to failures
 *   and offer a retry instead of failing silently.
 * - A failed refresh never wipes the list that is already on screen; the user keeps seeing the
 *   last known live channels plus an error message.
 */

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Data class that holds the stream info AND the user's profile image
data class EnrichedStream(
    val stream: FollowedStream,
    val user: TwitchUser
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _followedStreams = MutableStateFlow<List<EnrichedStream>>(emptyList())
    val followedStreams: StateFlow<List<EnrichedStream>> = _followedStreams.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** Number of channels the account follows; used to explain an empty "live" list. */
    private val _followedChannelCount = MutableStateFlow<Int?>(null)
    val followedChannelCount: StateFlow<Int?> = _followedChannelCount.asStateFlow()

    fun loadFollowedStreams() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val user = UserManager.currentUser
                val token = UserManager.accessToken
                if (user == null || token == null) {
                    _errorMessage.value = getApplication<Application>()
                        .getString(R.string.error_session_expired)
                    _followedStreams.value = emptyList()
                } else {
                    // 1. Get the list of live streams. This throws on HTTP errors instead of
                    //    pretending the user follows no live channels.
                    val streams = TwitchApi.getFollowedStreams(user.id, token, UserManager.CLIENT_ID)
                    val liveStreamIds = streams.map { it.userId }.toSet()

                    // Update the central state manager with the latest live streams
                    LiveStreamStateManager.updateKnownLiveStreams(getApplication(), liveStreamIds)

                    if (streams.isEmpty()) {
                        _followedStreams.value = emptyList()
                        // Explain the empty state: "you follow N channels, none are live".
                        _followedChannelCount.value = try {
                            TwitchApi.getFollowedChannels(user.id, token, UserManager.CLIENT_ID).size
                        } catch (e: Exception) {
                            Log.w("MainViewModel", "Could not load followed channel count: ${e.message}")
                            null
                        }
                    } else {
                        _followedChannelCount.value = null
                        // 2. Fetch the user profiles for all those IDs in one call. When this
                        //    fails we still show the live channels with a fallback profile.
                        val users = try {
                            TwitchApi.getUsers(streams.map { it.userId }, token, UserManager.CLIENT_ID)
                        } catch (e: Exception) {
                            Log.w("MainViewModel", "Could not load streamer profiles: ${e.message}")
                            emptyList()
                        }
                        val userMap = users.associateBy { it.id }

                        // 3. Combine the stream data with the user data
                        _followedStreams.value = streams.map { stream ->
                            EnrichedStream(
                                stream = stream,
                                user = userMap[stream.userId] ?: stream.toFallbackUser()
                            )
                        }
                    }
                }
            } catch (e: TwitchApiException) {
                Log.e("MainViewModel", "Failed to load followed streams", e)
                _errorMessage.value = describeApiError(e)
                // Keep whatever is already on screen instead of clearing it.
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to load followed streams", e)
                _errorMessage.value = e.message ?: getApplication<Application>()
                    .getString(R.string.error_loading)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refreshFollowedStreams() {
        loadFollowedStreams()
    }

    /** Turns an HTTP failure into something a user can understand and act on. */
    private fun describeApiError(e: TwitchApiException): String {
        val app = getApplication<Application>()
        return when (e.status) {
            401 -> app.getString(R.string.error_session_expired)
            429 -> app.getString(R.string.error_rate_limited)
            in 500..599 -> app.getString(R.string.error_twitch_unavailable)
            else -> app.getString(R.string.error_loading)
        }
    }
}

/** Builds a minimal user profile from stream data when the Helix users call fails. */
private fun FollowedStream.toFallbackUser(): TwitchUser = TwitchUser(
    id = userId,
    login = userLogin,
    displayName = userName,
    profileImageUrl = "",
    chatColor = null
)
