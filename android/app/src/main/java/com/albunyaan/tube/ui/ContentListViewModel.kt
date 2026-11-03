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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
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
    private val contentType: ContentType,
    foregroundReturns: Flow<Unit> = emptyFlow(),
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

    // The query (trimmed) of the rows on screen, and the cursor of their next page. Null once those rows are being
    // replaced by other filters or a refresh: they are then no restore target.
    private var shownQuery: String? = null
    private var shownCursor: String? = null

    // The page-1 fetch that is running (a search past its debounce, or a refresh), and the query it fetches
    private var fetching: Pair<Job, String>? = null

    /**
     * Typing passes [debounceMs] = 300 (spec §6). The keystroke cancels the previous load at once, so its late answer
     * never lands, and the list on screen stays until the new one replaces it. A submit passes 0. An emptied box (the
     * clear button) brings the full list back at once.
     */
    fun setSearchQuery(q: String, debounceMs: Long = 0L) {
        val state = _content.value
        // The same text again (a restored view re-sends it), or a submit of the query on screen or already being
        // fetched, changes nothing; after a failure it is a retry. A submit of a query still in its debounce runs now.
        if (_searchQuery.value == q && state !is ContentState.Error &&
            (debounceMs > 0 || q.trim() == shownQuery || fetching?.let { (job, query) -> job.isActive && query == q.trim() } == true)
        ) return
        _searchQuery.value = q
        // Typed back to the query on screen (an undone keystroke): drop the newer load, keep the rows and their pages.
        if (debounceMs > 0 && q.trim() == shownQuery && state !is ContentState.Error &&
            (state is ContentState.Success || state.items.isNotEmpty())
        ) {
            loadJob?.cancel()
            allItems.clear()
            allItems.addAll(state.items)
            nextCursor = shownCursor
            _hasMoreData = shownCursor != null
            _isLoadingMore = false
            _isRefreshing = false
            _content.value = ContentState.Success(allItems.toList(), _hasMoreData, isSearchActive = isSearchActive)
            return
        }
        loadContent(keepListOnScreen = true, if (q.isEmpty()) 0L else debounceMs)
    }

    /** The rows just loaded are on screen: remember their query and next page. */
    private fun markShown() {
        shownQuery = _searchQuery.value.trim()
        shownCursor = nextCursor
    }

    // Public read-only accessors for Fragment-side guards
    val canLoadMore: Boolean
        get() = !_isLoadingMore && !_isRefreshing && !refreshingInPlace && _hasMoreData

    // The return-after-idle reload: paging waits for it, so a scroll does not cancel it
    private var inPlaceJob: Job? = null
    private val refreshingInPlace: Boolean get() = inPlaceJob?.isActive == true

    // Accumulated items for pagination
    private val allItems = mutableListOf<ContentItem>()

    // How many of [allItems] page 1 holds: a return after idle swaps only those
    private var firstPageSize = 0

    init {
        loadContent()
        viewModelScope.launch { foregroundReturns.collect { refreshFirstPageInPlace() } } // AppLifecycleTracker.returnsAfterIdle
    }

    /**
     * A return after idle: page 1 again, swapped in where it stands. The pages loaded below it, their cursor and the
     * scroll place stay; a failure changes nothing on screen. A load already running fetches fresh rows itself, and a
     * newer load cancels this one. A failed load on screen (a skeleton has no pull-to-refresh) is retried instead.
     *
     * ponytail: rows pushed off page 1 by new ones at the top drop out until a pull-to-refresh, since the kept cursor
     * is past them; refetch the loaded pages if that matters more than the backend load.
     */
    private fun refreshFirstPageInPlace() {
        if (loadJob?.isActive == true) return
        if (_content.value !is ContentState.Success) return refresh()
        inPlaceJob = viewModelScope.launch {
            val response = try {
                contentService.fetchContent(contentType, null, PAGE_SIZE, currentFilters, _searchQuery.value.takeIf { it.isNotBlank() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Return-after-idle reload failed for type=$contentType; keeping the list", e)
                return@launch
            }
            val fresh = response.data.map { it.key }.toSet()
            val below = allItems.drop(firstPageSize).filter { it.key !in fresh }
            if (allItems.size <= firstPageSize) nextCursor = response.pageInfo?.nextCursor // only page 1 was loaded
            allItems.clear()
            allItems.addAll(response.data + below)
            firstPageSize = response.data.size
            _hasMoreData = nextCursor != null
            markShown()
            _content.value = ContentState.Success(allItems.toList(), _hasMoreData, isSearchActive = isSearchActive, inPlace = true)
        }
        loadJob = inPlaceJob
    }

    private val ContentItem.key: String
        get() = when (this) {
            is ContentItem.Video -> id
            is ContentItem.Channel -> id
            is ContentItem.Playlist -> id
        }

    /**
     * Update filters and reload content if filters changed.
     * Called from Fragment when FilterManager.state changes.
     */
    fun setFilters(filters: FilterState) {
        if (currentFilters != filters) {
            Log.d(TAG, "Filters changed: $currentFilters -> $filters")
            currentFilters = filters
            shownQuery = null
            loadContent()
        }
    }

    /**
     * Initial load or refresh - clears existing data and fetches from the beginning.
     * [keepListOnScreen]: a search-query change keeps the visible list under the top spinner
     * (REFRESH) instead of swapping to the skeleton (INITIAL) - spec §6.1 step 7. The fetch waits [debounceMs]; the
     * cancel and the cursor reset do not.
     */
    fun loadContent(keepListOnScreen: Boolean = false, debounceMs: Long = 0L) {
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
            delay(debounceMs)
            fetching = coroutineContext.job to _searchQuery.value.trim()
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
                firstPageSize = response.data.size
                nextCursor = response.pageInfo?.nextCursor
                _hasMoreData = nextCursor != null

                Log.d(TAG, "Received ${response.data.size} items, hasMore=$_hasMoreData")
                markShown()
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
        shownQuery = null
        val onScreen = _content.value.items
        // Cancel any ongoing request to prevent race conditions
        loadJob?.cancel()

        // Reset pagination state
        nextCursor = null
        _hasMoreData = true
        _isLoadingMore = false
        allItems.clear()

        loadJob = viewModelScope.launch {
            fetching = coroutineContext.job to _searchQuery.value.trim()
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
                firstPageSize = response.data.size
                nextCursor = response.pageInfo?.nextCursor
                _hasMoreData = nextCursor != null

                Log.d(TAG, "Refresh complete: ${response.data.size} items, hasMore=$_hasMoreData")
                markShown()
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
        if (_isLoadingMore || _isRefreshing || refreshingInPlace || !_hasMoreData) {
            Log.d(TAG, "loadMore skipped: isLoadingMore=$_isLoadingMore, isRefreshing=$_isRefreshing, inPlace=$refreshingInPlace, hasMoreData=$_hasMoreData")
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

                if (allItems.isEmpty()) firstPageSize = response.data.size // a page-1 retry after a failed load
                // A cursor kept across a return-after-idle refresh can hand back rows page 1 now shows
                val shown = allItems.mapTo(HashSet()) { it.key }
                allItems.addAll(response.data.filter { it.key !in shown })
                nextCursor = response.pageInfo?.nextCursor
                _hasMoreData = nextCursor != null

                Log.d(TAG, "Loaded ${response.data.size} more items, total=${allItems.size}, hasMore=$_hasMoreData")
                markShown()
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
            val isSearchActive: Boolean = false,
            /** The rows on screen, refreshed where they stand (a return after idle): the list keeps its scroll place. */
            val inPlace: Boolean = false,
        ) : ContentState()
        data class Error(val message: String, override val items: List<ContentItem> = emptyList()) : ContentState()
    }

    class Factory(
        private val contentService: ContentService,
        private val contentType: ContentType,
        private val foregroundReturns: Flow<Unit>,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ContentListViewModel::class.java)) {
                return ContentListViewModel(contentService, contentType, foregroundReturns) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    companion object {
        const val TYPING_DEBOUNCE_MS = 300L // spec §6: both apps
        private const val TAG = "ContentListViewModel"
        private const val PAGE_SIZE = 20
    }
}

/**
 * Whether a tab's next list opens at its top. A new list (another query) opens at its best match; a page appended below,
 * or the same list, keeps its place. One refreshed in place keeps it too, unless the user sat at the top: rows added
 * there would land above the screen.
 */
internal fun <T> opensAtTop(shown: List<T>, next: List<T>, inPlace: Boolean, atTop: Boolean): Boolean =
    if (inPlace) atTop else shown.isNotEmpty() && next.take(shown.size) != shown
