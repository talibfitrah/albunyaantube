package com.albunyaan.tube.ui.me.suggest

import androidx.annotation.StringRes
import com.albunyaan.tube.data.search.dto.SearchHitDto
import com.albunyaan.tube.data.search.dto.YouTubeContentTypeDto

sealed class SuggestUiState {
    object Idle : SuggestUiState()
    object Loading : SuggestUiState()
    /**
     * [query] is the string that *issued* [nextPageToken]; loadMore must
     * send them together or the backend returns corrupted page-2 results.
     * [searchType] is the type sent to the backend (ALL for text searches,
     * specific type for URL-resolved searches).
     * [allItems] is the full unfiltered backend result set; [items] is the
     * subset currently shown after [activeFilter] is applied.
     */
    data class Results(
        val items: List<SearchHitDto>,
        val allItems: List<SearchHitDto>,
        val activeFilter: YouTubeContentTypeDto,
        val nextPageToken: String?,
        val searchType: YouTubeContentTypeDto,
        val query: String,
        val loadingMore: Boolean = false
    ) : SuggestUiState()
    object Empty : SuggestUiState()
    data class Error(@StringRes val messageRes: Int, val formatArg: String? = null) : SuggestUiState()
    data class RateLimited(val retryAfterSec: Long) : SuggestUiState()
}
