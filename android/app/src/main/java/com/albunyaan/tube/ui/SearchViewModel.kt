package com.albunyaan.tube.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.source.ContentService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SearchViewModel(
    private val contentService: ContentService
) : ViewModel() {

    private val _searchResults = MutableStateFlow<SearchState>(SearchState.Empty)
    val searchResults: StateFlow<SearchState> = _searchResults.asStateFlow()

    fun search(query: String) {
        if (query.isBlank()) {
            _searchResults.value = SearchState.Empty
            return
        }

        viewModelScope.launch {
            _searchResults.value = SearchState.Loading
            try {
                android.util.Log.d("SearchViewModel", "Searching for: $query")
                val results = contentService.search(query = query, type = null, limit = 50)

                android.util.Log.d("SearchViewModel", "Received ${results.size} search results")

                if (results.isEmpty()) {
                    _searchResults.value = SearchState.NoResults(query)
                } else {
                    _searchResults.value = SearchState.Success(results)
                }
            } catch (e: Exception) {
                android.util.Log.e("SearchViewModel", "Search failed", e)
                _searchResults.value = SearchState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun clearResults() {
        _searchResults.value = SearchState.Empty
    }

    sealed class SearchState {
        object Empty : SearchState()
        object Loading : SearchState()
        data class Success(val results: List<ContentItem>) : SearchState()
        data class NoResults(val query: String) : SearchState()
        data class Error(val message: String) : SearchState()
    }

    class Factory(
        private val contentService: ContentService
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SearchViewModel::class.java)) {
                return SearchViewModel(contentService) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

