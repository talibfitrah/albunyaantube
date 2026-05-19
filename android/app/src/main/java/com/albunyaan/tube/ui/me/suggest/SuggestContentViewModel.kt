package com.albunyaan.tube.ui.me.suggest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.search.SearchResult
import com.albunyaan.tube.data.search.YouTubeSearchRepository
import com.albunyaan.tube.data.search.dto.YouTubeContentTypeDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
@OptIn(FlowPreview::class)
class SuggestContentViewModel @Inject constructor(
    private val repo: YouTubeSearchRepository
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val type  = MutableStateFlow(YouTubeContentTypeDto.CHANNEL)

    private val _uiState = MutableStateFlow<SuggestUiState>(SuggestUiState.Idle)
    val uiState: StateFlow<SuggestUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(query.debounce(300L).distinctUntilChanged(), type) { q, t -> q to t }
                .collect { (q, t) ->
                    if (q.isBlank()) {
                        _uiState.value = SuggestUiState.Idle
                    } else {
                        _uiState.value = SuggestUiState.Loading
                        when (val r = repo.search(q, t, null)) {
                            is SearchResult.Success ->
                                _uiState.value = if (r.page.items.isEmpty()) SuggestUiState.Empty
                                                 else SuggestUiState.Results(r.page.items, r.page.nextPageToken, t)
                            SearchResult.Forbidden       -> _uiState.value = SuggestUiState.Error("Not allowed")
                            is SearchResult.RateLimited  -> _uiState.value = SuggestUiState.RateLimited(r.retryAfterSec)
                            SearchResult.NetworkError    -> _uiState.value = SuggestUiState.Error("Network error")
                            is SearchResult.Unknown      -> _uiState.value = SuggestUiState.Error("Server error ${r.code}")
                        }
                    }
                }
        }
    }

    fun onQueryChange(q: String) { query.value = q }
    fun onTypeChange(t: YouTubeContentTypeDto) { type.value = t }

    fun loadMore() {
        val current = _uiState.value as? SuggestUiState.Results ?: return
        if (current.loadingMore || current.nextPageToken == null) return
        _uiState.value = current.copy(loadingMore = true)
        viewModelScope.launch {
            when (val r = repo.search(query.value, current.type, current.nextPageToken)) {
                is SearchResult.Success -> _uiState.value = current.copy(
                    items = current.items + r.page.items,
                    nextPageToken = r.page.nextPageToken,
                    loadingMore = false)
                else -> _uiState.value = current.copy(loadingMore = false)
            }
        }
    }
}
