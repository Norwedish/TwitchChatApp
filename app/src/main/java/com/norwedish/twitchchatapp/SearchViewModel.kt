package com.norwedish.twitchchatapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SearchViewModel : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchedChannel>>(emptyList())
    val searchResults = _searchResults.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private var searchJob: Job? = null

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query

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
            val token = UserManager.accessToken
            if (token != null) {
                val results = TwitchApi.searchChannels(query, token, UserManager.CLIENT_ID)
                _searchResults.value = results
            }
            _isLoading.value = false
        }
    }
}