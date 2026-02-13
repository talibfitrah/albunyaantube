package com.albunyaan.tube.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.source.ContentService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for paginated content lists (used by *FragmentNew screens).
 * Supports infinite scroll pagination with cursor-based loading and pull-to-refresh.
 *
 * Safety mechanisms:
 * - isLoadingMore: Prevents concurrent pagination requests (scroll spam protection)
 * - hasMoreData: Stops requests when all data is loaded
 * - isRefreshing: Prevents loadMore during refresh operation
 * - loadJob: Cancels previous requests on refresh to prevent race conditions
 *
 * NOT a @HiltViewModel - uses manual Factory with injected ContentService.
 */
class ContentListViewModel(
    private val contentService: ContentService,
    private val contentType: ContentType
) : ViewModel() {

    private val _content = MutableStateFlow<ContentState>(ContentState.Loading(LoadingType.INITIAL))
    val content: StateFlow<ContentState> = _content.asStateFlow()

    // Pagination state - private backing fields
    private var nextCursor: String? = null
    private var _hasMoreData = true
    private var _isLoadingMore = false
    private var _isRefreshing = false
    private var loadJob: Job? = null

    // Current filter state
    private var currentFilters: FilterState = FilterState()

    // Public read-only accessors for Fragment-side guards
    val canLoadMore: Boolean
        get() = !_isLoadingMore && !_isRefreshing && _hasMoreData

    // Accumulated items for pagination
    private val allItems = mutableListOf<ContentItem>()

    init {
        loadContent()
    }

    /**
     * Update filters and reload content if filters changed.
     * Called from Fragment when FilterManager.state changes.
     */
    fun setFilters(filters: FilterState) {
        if (currentFilters != filters) {
            Log.d(TAG, "Filters changed: $currentFilters -> $filters")
            currentFilters = filters
            loadContent()
        }
    }

    /**
     * Initial load or refresh - clears existing data and fetches from the beginning.
     */
    fun loadContent() {
        // Cancel any ongoing request to prevent race conditions
        loadJob?.cancel()

        // Reset pagination state
        nextCursor = null
        _hasMoreData = true
        _isLoadingMore = false
        _isRefreshing = false
        allItems.clear()

        loadJob = viewModelScope.launch {
            _content.value = ContentState.Loading(LoadingType.INITIAL)
            try {
                Log.d(TAG, "Fetching content for type=$contentType (initial load)")
                val response = contentService.fetchContent(
                    type = contentType,
                    cursor = null,
                    pageSize = PAGE_SIZE,
                    filters = currentFilters
                )

                allItems.addAll(response.data)
                nextCursor = response.pageInfo?.nextCursor
                _hasMoreData = nextCursor != null

                Log.d(TAG, "Received ${response.data.size} items, hasMore=$_hasMoreData")
                _content.value = ContentState.Success(
                    items = allItems.toList(),
                    hasMoreData = _hasMoreData
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error loading content for type=$contentType", e)
                _content.value = ContentState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Refresh - clears data and fetches from beginning.
     * Sets isRefreshing to prevent loadMore during refresh.
     */
    fun refresh() {
        // Guard: prevent refresh while already refreshing
        if (_isRefreshing) {
            Log.d(TAG, "refresh skipped: already refreshing")
            return
        }

        _isRefreshing = true
        // Cancel any ongoing request to prevent race conditions
        loadJob?.cancel()

        // Reset pagination state
        nextCursor = null
        _hasMoreData = true
        _isLoadingMore = false
        allItems.clear()

        loadJob = viewModelScope.launch {
            _content.value = ContentState.Loading(LoadingType.REFRESH)
            try {
                Log.d(TAG, "Refreshing content for type=$contentType")
                val response = contentService.fetchContent(
                    type = contentType,
                    cursor = null,
                    pageSize = PAGE_SIZE,
                    filters = currentFilters
                )

                allItems.addAll(response.data)
                nextCursor = response.pageInfo?.nextCursor
                _hasMoreData = nextCursor != null

                Log.d(TAG, "Refresh complete: ${response.data.size} items, hasMore=$_hasMoreData")
                _content.value = ContentState.Success(
                    items = allItems.toList(),
                    hasMoreData = _hasMoreData
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing content for type=$contentType", e)
                _content.value = ContentState.Error(e.message ?: "Unknown error")
            } finally {
                _isRefreshing = false
            }
        }
    }

    /**
     * Load more items for infinite scroll.
     * Protected against:
     * - Concurrent requests (isLoadingMore guard)
     * - Requests when no more data (hasMoreData guard)
     * - Requests during refresh (isRefreshing guard)
     */
    fun loadMore() {
        // Guard: prevent duplicate/spam requests and requests during refresh
        if (_isLoadingMore || _isRefreshing || !_hasMoreData) {
            Log.d(TAG, "loadMore skipped: isLoadingMore=$_isLoadingMore, isRefreshing=$_isRefreshing, hasMoreData=$_hasMoreData")
            return
        }

        _isLoadingMore = true

        // Update UI to show loading indicator at bottom
        _content.value = ContentState.Loading(LoadingType.PAGINATION)

        // Cancel any previous loadMore job to prevent race conditions with refresh
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                Log.d(TAG, "Loading more for type=$contentType, cursor=$nextCursor, filters=$currentFilters")
                val response = contentService.fetchContent(
                    type = contentType,
                    cursor = nextCursor,
                    pageSize = PAGE_SIZE,
                    filters = currentFilters
                )

                allItems.addAll(response.data)
                nextCursor = response.pageInfo?.nextCursor
                _hasMoreData = nextCursor != null

                Log.d(TAG, "Loaded ${response.data.size} more items, total=${allItems.size}, hasMore=$_hasMoreData")
                _content.value = ContentState.Success(
                    items = allItems.toList(),
                    hasMoreData = _hasMoreData
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Error loading more content", e)
                // On error, keep existing items but show pagination error state
                _content.value = ContentState.Success(
                    items = allItems.toList(),
                    hasMoreData = _hasMoreData,
                    paginationError = e.message ?: "Failed to load more"
                )
            } finally {
                _isLoadingMore = false
                loadJob = null
            }
        }
    }

    /**
     * Distinguishes between different loading scenarios for UI indicator selection.
     */
    enum class LoadingType {
        INITIAL,    // First load - show swipeRefresh (or full-screen loader)
        REFRESH,    // Pull-to-refresh - show swipeRefresh
        PAGINATION  // Infinite scroll - show bottom loadingMore indicator
    }

    sealed class ContentState {
        /**
         * Loading state with explicit type for correct UI indicator selection.
         * - INITIAL/REFRESH: Show swipeRefresh (top indicator)
         * - PAGINATION: Show loadingMore (bottom indicator)
         */
        data class Loading(val type: LoadingType) : ContentState()
        data class Success(
            val items: List<ContentItem>,
            val hasMoreData: Boolean = true,
            val paginationError: String? = null
        ) : ContentState()
        data class Error(val message: String) : ContentState()
    }

    class Factory(
        private val contentService: ContentService,
        private val contentType: ContentType
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ContentListViewModel::class.java)) {
                return ContentListViewModel(contentService, contentType) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    companion object {
        private const val TAG = "ContentListViewModel"
        private const val PAGE_SIZE = 20
    }
}
