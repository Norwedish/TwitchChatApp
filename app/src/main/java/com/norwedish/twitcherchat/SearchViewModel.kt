package com.norwedish.twitcherchat

/**
 * ViewModel handling search queries and presenting search results to the UI.
 * Wraps Twitch API search endpoints and exposes observable state for the SearchScreen,
 * including loading and error state with debounce.
 */

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SearchViewModel : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchedChannel>>(emptyList())
    val searchResults: StateFlow<List<SearchedChannel>> = _searchResults.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var searchJob: Job? = null

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        _errorMessage.value = null

        // Cancel any previous search job to avoid spamming the API
        searchJob?.cancel()

        // Don't search if the query is blank
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }

        // Debounce the search by 500ms
        searchJob = viewModelScope.launch {
            delay(500)
            performSearch(query)
        }
    }

    private fun performSearch(query: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val token = UserManager.accessToken
                if (token == null) {
                    _errorMessage.value = "Not logged in"
                    _searchResults.value = emptyList()
                } else {
                    _searchResults.value = TwitchApi.searchChannels(query, token, UserManager.CLIENT_ID)
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Search failed"
                _searchResults.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }
}
