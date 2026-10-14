package com.albunyaan.tube.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.source.ContentService
import com.albunyaan.tube.data.source.SearchMatch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private var query: String? = null // the one being searched or shown
    private var shownQuery: String? = null // trimmed, set with each Success or NoResults

    /**
     * Typing passes [debounceMs] = 300 (spec §6). The keystroke cancels the previous search at once, so its late
     * answer can never land, and the old results stay up until the new ones replace them. A submit passes 0.
     */
    fun search(query: String, debounceMs: Long = 0L) {
        val state = _searchResults.value
        // Typing the same query (a restored view re-sends its text) changes nothing; after a failure it retries.
        // A submit always runs: a history tap types then submits, and must not wait out that debounce.
        if (debounceMs > 0 && query.trim() == this.query?.trim() && state !is SearchState.Error) return
        searchJob?.cancel() // a newer query wins; cancelling the coroutine cancels the OkHttp call
        // Typed back to what is on screen (a backspace): drop the newer search instead of fetching the same rows.
        if (debounceMs > 0 && query.trim() == shownQuery && (state is SearchState.Success || state is SearchState.NoResults)) {
            this.query = query
            _isSearching.value = false
            return
        }
        // Typing under 2 characters is the history screen; a submit searches any non-blank query.
        if (query.isBlank() || (debounceMs > 0 && query.trim().length < 2)) return clearResults()
        this.query = query

        searchJob = viewModelScope.launch {
            delay(debounceMs)
            // Results (or "no results") already on screen stay while the next ones load; the spinner is for an empty screen.
            val onScreen = _searchResults.value
            if (onScreen is SearchState.Success || onScreen is SearchState.NoResults) _isSearching.value = true
            else _searchResults.value = SearchState.Loading
            try {
                if (BuildConfig.DEBUG) android.util.Log.d("SearchViewModel", "Searching for: $query")
                val result = contentService.search(query = query, type = null, limit = 50)

                if (BuildConfig.DEBUG) android.util.Log.d("SearchViewModel", "Received ${result.items.size} search results")

                _searchResults.value = if (result.items.isEmpty()) SearchState.NoResults(query)
                else SearchState.Success(result.items, result.match)
                shownQuery = query.trim()
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
        query = null
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

    companion object {
        const val TYPING_DEBOUNCE_MS = 300L // spec §6: both apps
    }
}
