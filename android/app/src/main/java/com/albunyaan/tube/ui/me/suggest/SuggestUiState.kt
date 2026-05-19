package com.albunyaan.tube.ui.me.suggest

import com.albunyaan.tube.data.search.dto.SearchHitDto
import com.albunyaan.tube.data.search.dto.YouTubeContentTypeDto

sealed class SuggestUiState {
    object Idle : SuggestUiState()
    object Loading : SuggestUiState()
    /**
     * Search results bundle.
     *
     * Plan G review-fix (reviewer Important #1): [query] is captured here so
     * [SuggestContentViewModel.loadMore] can pair its page-token request with
     * the originating query string. Previously loadMore read the latest
     * `query.value`, which after the user typed past a returned first page
     * would be a different string than the one the [nextPageToken] belongs
     * to — sending a token bound to "isl" with the new query "islam"
     * produces corrupted page-2 results with no visible error.
     */
    data class Results(
        val items: List<SearchHitDto>,
        val nextPageToken: String?,
        val type: YouTubeContentTypeDto,
        val query: String,
        val loadingMore: Boolean = false
    ) : SuggestUiState()
    object Empty : SuggestUiState()
    data class Error(val message: String) : SuggestUiState()
    data class RateLimited(val retryAfterSec: Long) : SuggestUiState()
}
