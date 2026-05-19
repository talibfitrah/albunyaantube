package com.albunyaan.tube.ui.me.suggest

import com.albunyaan.tube.data.search.dto.SearchHitDto
import com.albunyaan.tube.data.search.dto.YouTubeContentTypeDto

sealed class SuggestUiState {
    object Idle : SuggestUiState()
    object Loading : SuggestUiState()
    /** [query] is the string that *issued* [nextPageToken]; loadMore must
     *  send them together or the backend returns corrupted page-2 results. */
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
