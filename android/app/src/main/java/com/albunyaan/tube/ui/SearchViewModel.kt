package com.albunyaan.tube.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.source.ContentService
import com.albunyaan.tube.data.source.SearchMatch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.albunyaan.tube.BuildConfig
import javax.inject.Inject
import javax.inject.Named

/**
 * P3-T4: SearchViewModel with Hilt DI
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    @Named("real") private val contentService: ContentService
) : ViewModel() {

    private val _searchResults = MutableStateFlow<SearchState>(SearchState.Empty)
    val searchResults: StateFlow<SearchState> = _searchResults.asStateFlow()

    /** True while a newer search runs with results still on screen: the thin progress bar (spec §6). */
    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private var searchJob: Job? = null

    fun search(query: String) {
        searchJob?.cancel() // a newer query wins; cancelling the coroutine cancels the OkHttp call
        if (query.isBlank()) {
            _isSearching.value = false
            _searchResults.value = SearchState.Empty
            return
        }

        searchJob = viewModelScope.launch {
            // Results already on screen stay while the next ones load; the spinner is for an empty screen.
            if (_searchResults.value is SearchState.Success) _isSearching.value = true
            else _searchResults.value = SearchState.Loading
            try {
                if (BuildConfig.DEBUG) android.util.Log.d("SearchViewModel", "Searching for: $query")
                val result = contentService.search(query = query, type = null, limit = 50)

                if (BuildConfig.DEBUG) android.util.Log.d("SearchViewModel", "Received ${result.items.size} search results")

                _searchResults.value = if (result.items.isEmpty()) SearchState.NoResults(query)
                else SearchState.Success(result.items, result.match)
            } catch (e: CancellationException) {
                throw e // superseded by a newer query: the screen belongs to that one
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) android.util.Log.e("SearchViewModel", "Search failed", e)
                _searchResults.value = SearchState.Error(e.message ?: "Unknown error")
            }
            _isSearching.value = false
        }
    }

    fun clearResults() {
        searchJob?.cancel()
        _isSearching.value = false
        _searchResults.value = SearchState.Empty
    }

    sealed class SearchState {
        object Empty : SearchState()
        object Loading : SearchState()
        data class Success(val results: List<ContentItem>, val match: SearchMatch) : SearchState()
        data class NoResults(val query: String) : SearchState()
        data class Error(val message: String) : SearchState()
    }
}
