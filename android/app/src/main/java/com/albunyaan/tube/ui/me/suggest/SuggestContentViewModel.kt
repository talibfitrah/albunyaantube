package com.albunyaan.tube.ui.me.suggest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.search.SearchResult
import com.albunyaan.tube.data.search.YouTubeSearchRepository
import com.albunyaan.tube.data.search.dto.YouTubeContentTypeDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SuggestContentViewModel @Inject constructor(
    private val repo: YouTubeSearchRepository
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val type  = MutableStateFlow(YouTubeContentTypeDto.CHANNEL)

    private val _uiState = MutableStateFlow<SuggestUiState>(SuggestUiState.Idle)
    val uiState: StateFlow<SuggestUiState> = _uiState.asStateFlow()

    init {
        // Plan G review-fix (codex P2 + reviewer Important #2):
        // flatMapLatest cancels the in-flight repo.search when a new
        // (query, type) pair arrives. The old `combine.collect { repo.search() }`
        // pattern serialised emissions — a slow first search could write its
        // results to _uiState AFTER the user had moved on, producing visible
        // stale-then-fresh flicker. flatMapLatest gives us the cancel-and-
        // restart behaviour the handoff claimed but the code did not have.
        viewModelScope.launch {
            combine(query.debounce(300L).distinctUntilChanged(), type) { q, t -> q to t }
                .flatMapLatest { (q, t) ->
                    if (q.isBlank()) {
                        flowOf<SuggestUiState>(SuggestUiState.Idle)
                    } else {
                        flow<SuggestUiState> {
                            emit(SuggestUiState.Loading)
                            emit(mapSearchResult(repo.search(q, t, null), t, q))
                        }
                    }
                }
                .collect { _uiState.value = it }
        }
    }

    private fun mapSearchResult(
        r: SearchResult,
        t: YouTubeContentTypeDto,
        q: String,
    ): SuggestUiState = when (r) {
        is SearchResult.Success ->
            if (r.page.items.isEmpty()) SuggestUiState.Empty
            else SuggestUiState.Results(
                items = r.page.items,
                nextPageToken = r.page.nextPageToken,
                type = t,
                query = q,
            )
        SearchResult.Forbidden       -> SuggestUiState.Error("Not allowed")
        is SearchResult.RateLimited  -> SuggestUiState.RateLimited(r.retryAfterSec)
        SearchResult.NetworkError    -> SuggestUiState.Error("Network error")
        is SearchResult.Unknown      -> SuggestUiState.Error("Server error ${r.code}")
    }

    fun onQueryChange(q: String) { query.value = q }
    fun onTypeChange(t: YouTubeContentTypeDto) { type.value = t }

    fun loadMore() {
        val current = _uiState.value as? SuggestUiState.Results ?: return
        if (current.loadingMore || current.nextPageToken == null) return
        _uiState.value = current.copy(loadingMore = true)
        viewModelScope.launch {
            // Plan G review-fix (reviewer Important #1): pair the page token
            // with the query that *issued* it, not the latest typed text.
            // Mid-paginate type-ahead would otherwise send q="islam" with a
            // pageToken bound to q="isl" and the backend returns corrupted
            // page-2 results (token is server-opaque, tied to its query).
            when (val r = repo.search(current.query, current.type, current.nextPageToken)) {
                is SearchResult.Success -> _uiState.value = current.copy(
                    items = current.items + r.page.items,
                    nextPageToken = r.page.nextPageToken,
                    loadingMore = false)
                else -> _uiState.value = current.copy(loadingMore = false)
            }
        }
    }
}
