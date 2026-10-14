package com.albunyaan.tube.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.source.ContentService
import kotlinx.coroutines.CancellationException
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

    // Search query — kept separate from FilterState to avoid DataStore persistence
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val isSearchActive: Boolean
        get() = _searchQuery.value.isNotEmpty()

    fun setSearchQuery(q: String) {
        if (_searchQuery.value == q && _content.value !is ContentState.Error) return // after a failure it is a retry
        _searchQuery.value = q
        loadContent(keepListOnScreen = true) // the fragments' 300 ms is the only debounce (spec §6)
    }

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
     * [keepListOnScreen]: a search-query change keeps the visible list under the top spinner
     * (REFRESH) instead of swapping to the skeleton (INITIAL) - spec §6.1 step 7.
     */
    fun loadContent(keepListOnScreen: Boolean = false) {
        val onScreen = _content.value.items
        val loadingType = if (keepListOnScreen && onScreen.isNotEmpty()) LoadingType.REFRESH else LoadingType.INITIAL

        // Cancel any ongoing request to prevent race conditions
        loadJob?.cancel()

        // Reset pagination state
        nextCursor = null
        // A search that keeps the list up must not be cancelled by a scroll-triggered loadMore();
        // the Success below re-enables paging.
        _hasMoreData = loadingType != LoadingType.REFRESH
        _isLoadingMore = false
        _isRefreshing = false
        allItems.clear()

        loadJob = viewModelScope.launch {
            _content.value = ContentState.Loading(loadingType, if (loadingType == LoadingType.REFRESH) onScreen else emptyList())
            try {
                Log.d(TAG, "Fetching content for type=$contentType (initial load)")
                val response = contentService.fetchContent(
                    type = contentType,
                    cursor = null,
                    pageSize = PAGE_SIZE,
                    filters = currentFilters,
                    query = _searchQuery.value.takeIf { it.isNotBlank() }
                )

                allItems.addAll(response.data)
                nextCursor = response.pageInfo?.nextCursor
                _hasMoreData = nextCursor != null

                Log.d(TAG, "Received ${response.data.size} items, hasMore=$_hasMoreData")
                _content.value = ContentState.Success(
                    items = allItems.toList(),
                    hasMoreData = _hasMoreData,
                    isSearchActive = isSearchActive
                )
            } catch (e: CancellationException) {
                throw e // superseded by a newer load: never flash an error for it
            } catch (e: Exception) {
                Log.e(TAG, "Error loading content for type=$contentType", e)
                _hasMoreData = true // paging stays open: a scroll to the end retries page 1
                _content.value = ContentState.Error(e.message ?: "Unknown error", onScreen)
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
        val onScreen = _content.value.items
        // Cancel any ongoing request to prevent race conditions
        loadJob?.cancel()

        // Reset pagination state
        nextCursor = null
        _hasMoreData = true
        _isLoadingMore = false
        allItems.clear()

        loadJob = viewModelScope.launch {
            _content.value = ContentState.Loading(LoadingType.REFRESH, onScreen)
            try {
                Log.d(TAG, "Refreshing content for type=$contentType")
                val response = contentService.fetchContent(
                    type = contentType,
                    cursor = null,
                    pageSize = PAGE_SIZE,
                    filters = currentFilters,
                    query = _searchQuery.value.takeIf { it.isNotBlank() }
                )

                allItems.addAll(response.data)
                nextCursor = response.pageInfo?.nextCursor
                _hasMoreData = nextCursor != null

                Log.d(TAG, "Refresh complete: ${response.data.size} items, hasMore=$_hasMoreData")
                _content.value = ContentState.Success(
                    items = allItems.toList(),
                    hasMoreData = _hasMoreData,
                    isSearchActive = isSearchActive
                )
            } catch (e: CancellationException) {
                throw e // superseded (e.g. by a search): never flash an error for it
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing content for type=$contentType", e)
                _content.value = ContentState.Error(e.message ?: "Unknown error", onScreen)
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
        val onScreen = _content.value.items

        // Update UI to show loading indicator at bottom
        _content.value = ContentState.Loading(LoadingType.PAGINATION, onScreen)

        // Cancel any previous loadMore job to prevent race conditions with refresh
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                Log.d(TAG, "Loading more for type=$contentType, cursor=$nextCursor, filters=$currentFilters")
                val response = contentService.fetchContent(
                    type = contentType,
                    cursor = nextCursor,
                    pageSize = PAGE_SIZE,
                    filters = currentFilters,
                    query = _searchQuery.value.takeIf { it.isNotBlank() }
                )

                allItems.addAll(response.data)
                nextCursor = response.pageInfo?.nextCursor
                _hasMoreData = nextCursor != null

                Log.d(TAG, "Loaded ${response.data.size} more items, total=${allItems.size}, hasMore=$_hasMoreData")
                _content.value = ContentState.Success(
                    items = allItems.toList(),
                    hasMoreData = _hasMoreData,
                    isSearchActive = isSearchActive
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Error loading more content", e)
                // On error, keep existing items but show pagination error state. With none loaded this was a
                // page-1 retry after a failed load: it failed again, which is not "no results".
                _content.value = if (allItems.isEmpty()) ContentState.Error(e.message ?: "Failed to load more", onScreen)
                else ContentState.Success(
                    items = allItems.toList(),
                    hasMoreData = _hasMoreData,
                    paginationError = e.message ?: "Failed to load more",
                    isSearchActive = isSearchActive
                )
            } finally {
                // Never `loadJob = null` here: a cancelled loadMore's finally can run after a newer load took the slot.
                _isLoadingMore = false
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
        /** The rows on screen. A refresh, a search or a page load keeps the previous rows up, and so does its
         *  failure; a recreated view redraws them from here. INITIAL shows the skeleton instead. */
        abstract val items: List<ContentItem>

        /**
         * Loading state with explicit type for correct UI indicator selection.
         * - INITIAL/REFRESH: Show swipeRefresh (top indicator)
         * - PAGINATION: Show loadingMore (bottom indicator)
         */
        data class Loading(val type: LoadingType, override val items: List<ContentItem> = emptyList()) : ContentState()
        data class Success(
            override val items: List<ContentItem>,
            val hasMoreData: Boolean = true,
            val paginationError: String? = null,
            val isSearchActive: Boolean = false
        ) : ContentState()
        data class Error(val message: String, override val items: List<ContentItem> = emptyList()) : ContentState()
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
