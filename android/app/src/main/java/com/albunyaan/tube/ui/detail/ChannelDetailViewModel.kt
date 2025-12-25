package com.albunyaan.tube.ui.detail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.channel.ChannelDetailRepository
import com.albunyaan.tube.data.channel.ChannelHeader
import com.albunyaan.tube.data.channel.ChannelLiveStream
import com.albunyaan.tube.data.channel.ChannelPlaylist
import com.albunyaan.tube.data.channel.ChannelShort
import com.albunyaan.tube.data.channel.ChannelTab
import com.albunyaan.tube.data.channel.ChannelVideo
import com.albunyaan.tube.data.channel.Page
import androidx.annotation.VisibleForTesting
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the Channel Detail screen.
 * Uses NewPipeExtractor directly via ChannelDetailRepository (no backend calls).
 *
 * Manages:
 * - Channel header state (loading, success, error)
 * - Per-tab paginated content states
 * - Rate limiting for pagination requests
 * - Selected tab persistence for state restoration
 */
@HiltViewModel(assistedFactory = ChannelDetailViewModel.Factory::class)
class ChannelDetailViewModel @AssistedInject constructor(
    private val repository: ChannelDetailRepository,
    @Assisted private val channelId: String
) : ViewModel() {

    /**
     * Time provider for rate limiting. Defaults to system clock.
     * Exposed for testing to allow deterministic time control.
     */
    @VisibleForTesting
    internal var timeProvider: () -> Long = { System.currentTimeMillis() }

    /**
     * Test-only method to force inconsistent internal state for edge-case testing.
     * This clears the controller state (hasReachedEnd=true, nextPage=null).
     * When combined with ErrorAppend state containing a valid nextPage, tests the
     * restoration logic in loadNextPage that recovers from this inconsistency.
     */
    @VisibleForTesting
    internal fun forceInconsistentState(tab: ChannelTab) {
        paginationControllers[tab]?.apply {
            hasReachedEnd = true
            nextPage = null
        }
    }

    // Header state
    private val _headerState = MutableStateFlow<HeaderState>(HeaderState.Loading)
    val headerState: StateFlow<HeaderState> = _headerState.asStateFlow()

    // Tab-specific paginated states
    private val _videosState = MutableStateFlow<PaginatedState<ChannelVideo>>(PaginatedState.Idle)
    val videosState: StateFlow<PaginatedState<ChannelVideo>> = _videosState.asStateFlow()

    private val _liveState = MutableStateFlow<PaginatedState<ChannelLiveStream>>(PaginatedState.Idle)
    val liveState: StateFlow<PaginatedState<ChannelLiveStream>> = _liveState.asStateFlow()

    private val _shortsState = MutableStateFlow<PaginatedState<ChannelShort>>(PaginatedState.Idle)
    val shortsState: StateFlow<PaginatedState<ChannelShort>> = _shortsState.asStateFlow()

    private val _playlistsState = MutableStateFlow<PaginatedState<ChannelPlaylist>>(PaginatedState.Idle)
    val playlistsState: StateFlow<PaginatedState<ChannelPlaylist>> = _playlistsState.asStateFlow()

    // About tab reuses header state
    val aboutState: StateFlow<HeaderState> get() = headerState

    // Selected tab for state restoration
    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    // Pagination controllers per tab
    private val paginationControllers = mutableMapOf<ChannelTab, TabPaginationController>()

    init {
        // Initialize pagination controllers
        ChannelTab.entries.forEach { tab ->
            paginationControllers[tab] = TabPaginationController()
        }
        // Load header on init
        loadHeader()
    }

    /**
     * Load channel header information.
     */
    fun loadHeader(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Loading header for channel: $channelId")
                _headerState.value = HeaderState.Loading

                val header = repository.getChannelHeader(channelId, forceRefresh)
                _headerState.value = HeaderState.Success(header)
                Log.d(TAG, "Header loaded: ${header.title}")

                // Pre-load first tab (Videos)
                loadInitial(ChannelTab.VIDEOS)
            } catch (e: Exception) {
                val errorMsg = "Failed to load channel: ${e.message}"
                _headerState.value = HeaderState.Error(errorMsg)
                Log.e(TAG, errorMsg, e)
            }
        }
    }

    /**
     * Load initial content for a tab.
     *
     * Always resets state and loads fresh data from the beginning.
     * Use for initial load or pull-to-refresh.
     *
     * @param tab The channel tab to load content for
     * @param forceRefresh Reserved for future use. When repository-level caching is
     *        implemented, this will bypass cache and fetch fresh data from NewPipe.
     *        Currently has no effect as tab content always fetches from NewPipe.
     *        TODO: Propagate to repository methods when per-tab caching is added.
     */
    @Suppress("UNUSED_PARAMETER")
    fun loadInitial(tab: ChannelTab, forceRefresh: Boolean = false) {
        val controller = paginationControllers[tab] ?: return

        // Skip if already loading
        if (controller.isInitialLoading) {
            Log.d(TAG, "Skipping loadInitial for $tab - already loading")
            return
        }

        viewModelScope.launch {
            try {
                Log.d(TAG, "Loading initial content for tab: $tab")
                controller.isInitialLoading = true
                controller.hasReachedEnd = false
                controller.nextPage = null

                updateTabState(tab, PaginatedState.LoadingInitial)

                when (tab) {
                    ChannelTab.VIDEOS -> loadVideosInitial(controller)
                    ChannelTab.LIVE -> loadLiveInitial(controller)
                    ChannelTab.SHORTS -> loadShortsInitial(controller)
                    ChannelTab.PLAYLISTS -> loadPlaylistsInitial(controller)
                    ChannelTab.ABOUT -> {
                        // About uses header state, no separate loading needed
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load $tab", e)
                updateTabState(tab, PaginatedState.ErrorInitial(e.message ?: "Unknown error"))
            } finally {
                controller.isInitialLoading = false
            }
        }
    }

    /**
     * Load next page of content for a tab.
     *
     * @return true if the request was accepted (will be processed), false if rejected
     *         (rate limited, already loading, etc.). Callers can use this to track
     *         actual fetch attempts vs. rejected requests.
     */
    fun loadNextPage(tab: ChannelTab): Boolean {
        val controller = paginationControllers[tab] ?: return false

        // Guard: already loading
        if (controller.isAppending) {
            Log.d(TAG, "Skipping loadNextPage for $tab - already appending")
            return false
        }

        // Restore controller state from ErrorAppend if needed (for retry path)
        // This must happen BEFORE the hasReachedEnd check because an error may have
        // occurred after hasReachedEnd was incorrectly set, and ErrorAppend.nextPage
        // is the source of truth for whether more pages actually exist.
        val errorNextPage = getNextPageFromErrorState(tab)
        if (errorNextPage != null && controller.nextPage == null) {
            controller.nextPage = errorNextPage
            controller.hasReachedEnd = false
            Log.d(TAG, "Restored nextPage for $tab from ErrorAppend state")
        }

        // Guard: reached end (checked after restoration to handle edge cases)
        if (controller.hasReachedEnd) {
            Log.d(TAG, "Skipping loadNextPage for $tab - reached end")
            return false
        }

        // Guard: no next page
        if (controller.nextPage == null) {
            Log.d(TAG, "Skipping loadNextPage for $tab - no next page")
            return false
        }

        val now = timeProvider()
        if (now - controller.lastAppendRequestMs < MIN_APPEND_INTERVAL_MS) {
            Log.d(TAG, "Skipping loadNextPage for $tab - rate limited")
            return false
        }
        // Update timestamp immediately to prevent race condition
        controller.lastAppendRequestMs = now

        viewModelScope.launch {
            try {
                Log.d(TAG, "Loading next page for tab: $tab")
                controller.isAppending = true

                when (tab) {
                    ChannelTab.VIDEOS -> loadVideosNextPage(controller)
                    ChannelTab.LIVE -> loadLiveNextPage(controller)
                    ChannelTab.SHORTS -> loadShortsNextPage(controller)
                    ChannelTab.PLAYLISTS -> loadPlaylistsNextPage(controller)
                    ChannelTab.ABOUT -> { /* No pagination for About */ }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load next page for $tab", e)
                handleAppendError(tab, e.message ?: "Unknown error")
            } finally {
                controller.isAppending = false
            }
        }
        return true // Request accepted and queued
    }

    /**
     * Retry initial load for a tab after error.
     */
    fun retryInitial(tab: ChannelTab) {
        loadInitial(tab, forceRefresh = true)
    }

    /**
     * Retry append after error.
     */
    fun retryAppend(tab: ChannelTab) {
        loadNextPage(tab)
    }

    /**
     * Set whether to show the "Load More" footer for a tab.
     * Called by fragments when autofill is capped but more pages exist.
     */
    fun setShowLoadMoreFooter(tab: ChannelTab, show: Boolean) {
        when (tab) {
            ChannelTab.VIDEOS -> {
                val current = _videosState.value
                if (current is PaginatedState.Loaded) {
                    _videosState.value = current.copy(showLoadMoreFooter = show)
                }
            }
            ChannelTab.LIVE -> {
                val current = _liveState.value
                if (current is PaginatedState.Loaded) {
                    _liveState.value = current.copy(showLoadMoreFooter = show)
                }
            }
            ChannelTab.SHORTS -> {
                val current = _shortsState.value
                if (current is PaginatedState.Loaded) {
                    _shortsState.value = current.copy(showLoadMoreFooter = show)
                }
            }
            ChannelTab.PLAYLISTS -> {
                val current = _playlistsState.value
                if (current is PaginatedState.Loaded) {
                    _playlistsState.value = current.copy(showLoadMoreFooter = show)
                }
            }
            ChannelTab.ABOUT -> { /* No pagination for About */ }
        }
    }

    /**
     * Called when user scrolls the list. Triggers pagination if near end.
     */
    fun onListScrolled(tab: ChannelTab, lastVisibleItem: Int, totalCount: Int) {
        if (totalCount - lastVisibleItem <= PAGINATION_THRESHOLD) {
            loadNextPage(tab)
        }
    }

    /**
     * Update selected tab index for state restoration.
     */
    fun setSelectedTab(position: Int) {
        _selectedTab.value = position
    }

    // Private loading methods for each tab type

    private suspend fun loadVideosInitial(controller: TabPaginationController) {
        // Keep LoadingInitial visible throughout auto-fetch loop to avoid flashing empty list
        // Loop through empty pages with continuations (bounded to prevent infinite loops)
        var accumulatedItems = emptyList<ChannelVideo>()
        var currentNextPage: Page? = null
        var fetchCount = 0

        while (fetchCount < MAX_EMPTY_PAGE_FETCHES) {
            fetchCount++
            val page = repository.getVideos(channelId, currentNextPage)
            accumulatedItems = accumulatedItems + page.items

            controller.nextPage = page.nextPage
            controller.hasReachedEnd = page.nextPage == null

            // If we have items or no more pages, we're done
            if (accumulatedItems.isNotEmpty() || page.nextPage == null) {
                break
            }

            // Empty page with continuation - continue fetching without updating UI
            Log.d(TAG, "Videos: empty page $fetchCount with continuation, auto-fetching next")
            currentNextPage = page.nextPage
        }

        // Final state determination
        if (accumulatedItems.isEmpty()) {
            _videosState.value = PaginatedState.Empty
        } else {
            _videosState.value = PaginatedState.Loaded(accumulatedItems, controller.nextPage)
        }
        Log.d(TAG, "Videos initial: ${accumulatedItems.size} items after $fetchCount fetches, hasMore=${controller.nextPage != null}")
    }

    private suspend fun loadVideosNextPage(controller: TabPaginationController) {
        val currentState = _videosState.value
        // Allow retry from ErrorAppend state by extracting items
        val currentShowLoadMore = when (currentState) {
            is PaginatedState.Loaded -> currentState.showLoadMoreFooter
            is PaginatedState.ErrorAppend -> currentState.showLoadMoreFooter
            else -> false
        }
        val (currentItems, canProceed) = when (currentState) {
            is PaginatedState.Loaded -> currentState.items to true
            is PaginatedState.ErrorAppend -> currentState.items to true
            else -> emptyList<ChannelVideo>() to false
        }
        if (!canProceed) return

        // Transition to Loaded with isAppending=true for retry from ErrorAppend
        _videosState.value = PaginatedState.Loaded(currentItems, controller.nextPage, isAppending = true, showLoadMoreFooter = currentShowLoadMore)

        // Loop through empty pages with continuations (bounded to prevent infinite loops)
        var accumulatedNewItems = emptyList<ChannelVideo>()
        var currentNextPage = controller.nextPage
        var fetchCount = 0

        while (fetchCount < MAX_EMPTY_PAGE_FETCHES && currentNextPage != null) {
            fetchCount++
            val page = repository.getVideos(channelId, currentNextPage)
            accumulatedNewItems = accumulatedNewItems + page.items

            controller.nextPage = page.nextPage
            controller.hasReachedEnd = page.nextPage == null

            // If we have new items or no more pages, we're done
            if (accumulatedNewItems.isNotEmpty() || page.nextPage == null) {
                break
            }

            // Empty page with continuation - continue fetching
            Log.d(TAG, "Videos append: empty page $fetchCount with continuation, auto-fetching next")
            currentNextPage = page.nextPage
        }

        val newItems = currentItems + accumulatedNewItems

        // Normalize to Empty if final result has no items and no continuation
        if (newItems.isEmpty() && controller.nextPage == null) {
            _videosState.value = PaginatedState.Empty
        } else {
            _videosState.value = PaginatedState.Loaded(newItems, controller.nextPage, showLoadMoreFooter = currentShowLoadMore)
        }
        Log.d(TAG, "Videos append: +${accumulatedNewItems.size} items after $fetchCount fetches, total=${newItems.size}")
    }

    private suspend fun loadLiveInitial(controller: TabPaginationController) {
        // Keep LoadingInitial visible throughout auto-fetch loop to avoid flashing empty list
        var accumulatedItems = emptyList<ChannelLiveStream>()
        var currentNextPage: Page? = null
        var fetchCount = 0

        while (fetchCount < MAX_EMPTY_PAGE_FETCHES) {
            fetchCount++
            val page = repository.getLiveStreams(channelId, currentNextPage)
            accumulatedItems = accumulatedItems + page.items

            controller.nextPage = page.nextPage
            controller.hasReachedEnd = page.nextPage == null

            if (accumulatedItems.isNotEmpty() || page.nextPage == null) {
                break
            }

            Log.d(TAG, "Live: empty page $fetchCount with continuation, auto-fetching next")
            currentNextPage = page.nextPage
        }

        if (accumulatedItems.isEmpty()) {
            _liveState.value = PaginatedState.Empty
        } else {
            _liveState.value = PaginatedState.Loaded(accumulatedItems, controller.nextPage)
        }
        Log.d(TAG, "Live initial: ${accumulatedItems.size} items after $fetchCount fetches, hasMore=${controller.nextPage != null}")
    }

    private suspend fun loadLiveNextPage(controller: TabPaginationController) {
        val currentState = _liveState.value
        // Allow retry from ErrorAppend state by extracting items
        val currentShowLoadMore = when (currentState) {
            is PaginatedState.Loaded -> currentState.showLoadMoreFooter
            is PaginatedState.ErrorAppend -> currentState.showLoadMoreFooter
            else -> false
        }
        val (currentItems, canProceed) = when (currentState) {
            is PaginatedState.Loaded -> currentState.items to true
            is PaginatedState.ErrorAppend -> currentState.items to true
            else -> emptyList<ChannelLiveStream>() to false
        }
        if (!canProceed) return

        // Transition to Loaded with isAppending=true for retry from ErrorAppend
        _liveState.value = PaginatedState.Loaded(currentItems, controller.nextPage, isAppending = true, showLoadMoreFooter = currentShowLoadMore)

        var accumulatedNewItems = emptyList<ChannelLiveStream>()
        var currentNextPage = controller.nextPage
        var fetchCount = 0

        while (fetchCount < MAX_EMPTY_PAGE_FETCHES && currentNextPage != null) {
            fetchCount++
            val page = repository.getLiveStreams(channelId, currentNextPage)
            accumulatedNewItems = accumulatedNewItems + page.items

            controller.nextPage = page.nextPage
            controller.hasReachedEnd = page.nextPage == null

            if (accumulatedNewItems.isNotEmpty() || page.nextPage == null) {
                break
            }

            Log.d(TAG, "Live append: empty page $fetchCount with continuation, auto-fetching next")
            currentNextPage = page.nextPage
        }

        val newItems = currentItems + accumulatedNewItems

        if (newItems.isEmpty() && controller.nextPage == null) {
            _liveState.value = PaginatedState.Empty
        } else {
            _liveState.value = PaginatedState.Loaded(newItems, controller.nextPage, showLoadMoreFooter = currentShowLoadMore)
        }
        Log.d(TAG, "Live append: +${accumulatedNewItems.size} items after $fetchCount fetches, total=${newItems.size}")
    }

    private suspend fun loadShortsInitial(controller: TabPaginationController) {
        // Keep LoadingInitial visible throughout auto-fetch loop to avoid flashing empty list
        var accumulatedItems = emptyList<ChannelShort>()
        var currentNextPage: Page? = null
        var fetchCount = 0

        while (fetchCount < MAX_EMPTY_PAGE_FETCHES) {
            fetchCount++
            val page = repository.getShorts(channelId, currentNextPage)
            accumulatedItems = accumulatedItems + page.items

            controller.nextPage = page.nextPage
            controller.hasReachedEnd = page.nextPage == null

            if (accumulatedItems.isNotEmpty() || page.nextPage == null) {
                break
            }

            Log.d(TAG, "Shorts: empty page $fetchCount with continuation, auto-fetching next")
            currentNextPage = page.nextPage
        }

        if (accumulatedItems.isEmpty()) {
            _shortsState.value = PaginatedState.Empty
        } else {
            _shortsState.value = PaginatedState.Loaded(accumulatedItems, controller.nextPage)
        }
        Log.d(TAG, "Shorts initial: ${accumulatedItems.size} items after $fetchCount fetches, hasMore=${controller.nextPage != null}")
    }

    private suspend fun loadShortsNextPage(controller: TabPaginationController) {
        val currentState = _shortsState.value
        // Allow retry from ErrorAppend state by extracting items
        val currentShowLoadMore = when (currentState) {
            is PaginatedState.Loaded -> currentState.showLoadMoreFooter
            is PaginatedState.ErrorAppend -> currentState.showLoadMoreFooter
            else -> false
        }
        val (currentItems, canProceed) = when (currentState) {
            is PaginatedState.Loaded -> currentState.items to true
            is PaginatedState.ErrorAppend -> currentState.items to true
            else -> emptyList<ChannelShort>() to false
        }
        if (!canProceed) return

        // Transition to Loaded with isAppending=true for retry from ErrorAppend
        _shortsState.value = PaginatedState.Loaded(currentItems, controller.nextPage, isAppending = true, showLoadMoreFooter = currentShowLoadMore)

        var accumulatedNewItems = emptyList<ChannelShort>()
        var currentNextPage = controller.nextPage
        var fetchCount = 0

        while (fetchCount < MAX_EMPTY_PAGE_FETCHES && currentNextPage != null) {
            fetchCount++
            val page = repository.getShorts(channelId, currentNextPage)
            accumulatedNewItems = accumulatedNewItems + page.items

            controller.nextPage = page.nextPage
            controller.hasReachedEnd = page.nextPage == null

            if (accumulatedNewItems.isNotEmpty() || page.nextPage == null) {
                break
            }

            Log.d(TAG, "Shorts append: empty page $fetchCount with continuation, auto-fetching next")
            currentNextPage = page.nextPage
        }

        val newItems = currentItems + accumulatedNewItems

        if (newItems.isEmpty() && controller.nextPage == null) {
            _shortsState.value = PaginatedState.Empty
        } else {
            _shortsState.value = PaginatedState.Loaded(newItems, controller.nextPage, showLoadMoreFooter = currentShowLoadMore)
        }
        Log.d(TAG, "Shorts append: +${accumulatedNewItems.size} items after $fetchCount fetches, total=${newItems.size}")
    }

    private suspend fun loadPlaylistsInitial(controller: TabPaginationController) {
        // Keep LoadingInitial visible throughout auto-fetch loop to avoid flashing empty list
        var accumulatedItems = emptyList<ChannelPlaylist>()
        var currentNextPage: Page? = null
        var fetchCount = 0

        while (fetchCount < MAX_EMPTY_PAGE_FETCHES) {
            fetchCount++
            val page = repository.getPlaylists(channelId, currentNextPage)
            accumulatedItems = accumulatedItems + page.items

            controller.nextPage = page.nextPage
            controller.hasReachedEnd = page.nextPage == null

            if (accumulatedItems.isNotEmpty() || page.nextPage == null) {
                break
            }

            Log.d(TAG, "Playlists: empty page $fetchCount with continuation, auto-fetching next")
            currentNextPage = page.nextPage
        }

        if (accumulatedItems.isEmpty()) {
            _playlistsState.value = PaginatedState.Empty
        } else {
            _playlistsState.value = PaginatedState.Loaded(accumulatedItems, controller.nextPage)
        }
        Log.d(TAG, "Playlists initial: ${accumulatedItems.size} items after $fetchCount fetches, hasMore=${controller.nextPage != null}")
    }

    private suspend fun loadPlaylistsNextPage(controller: TabPaginationController) {
        val currentState = _playlistsState.value
        // Allow retry from ErrorAppend state by extracting items
        val currentShowLoadMore = when (currentState) {
            is PaginatedState.Loaded -> currentState.showLoadMoreFooter
            is PaginatedState.ErrorAppend -> currentState.showLoadMoreFooter
            else -> false
        }
        val (currentItems, canProceed) = when (currentState) {
            is PaginatedState.Loaded -> currentState.items to true
            is PaginatedState.ErrorAppend -> currentState.items to true
            else -> emptyList<ChannelPlaylist>() to false
        }
        if (!canProceed) return

        // Transition to Loaded with isAppending=true for retry from ErrorAppend
        _playlistsState.value = PaginatedState.Loaded(currentItems, controller.nextPage, isAppending = true, showLoadMoreFooter = currentShowLoadMore)

        var accumulatedNewItems = emptyList<ChannelPlaylist>()
        var currentNextPage = controller.nextPage
        var fetchCount = 0

        while (fetchCount < MAX_EMPTY_PAGE_FETCHES && currentNextPage != null) {
            fetchCount++
            val page = repository.getPlaylists(channelId, currentNextPage)
            accumulatedNewItems = accumulatedNewItems + page.items

            controller.nextPage = page.nextPage
            controller.hasReachedEnd = page.nextPage == null

            if (accumulatedNewItems.isNotEmpty() || page.nextPage == null) {
                break
            }

            Log.d(TAG, "Playlists append: empty page $fetchCount with continuation, auto-fetching next")
            currentNextPage = page.nextPage
        }

        val newItems = currentItems + accumulatedNewItems

        if (newItems.isEmpty() && controller.nextPage == null) {
            _playlistsState.value = PaginatedState.Empty
        } else {
            _playlistsState.value = PaginatedState.Loaded(newItems, controller.nextPage, showLoadMoreFooter = currentShowLoadMore)
        }
        Log.d(TAG, "Playlists append: +${accumulatedNewItems.size} items after $fetchCount fetches, total=${newItems.size}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun updateTabState(tab: ChannelTab, state: PaginatedState<*>) {
        when (tab) {
            ChannelTab.VIDEOS -> _videosState.value = state as PaginatedState<ChannelVideo>
            ChannelTab.LIVE -> _liveState.value = state as PaginatedState<ChannelLiveStream>
            ChannelTab.SHORTS -> _shortsState.value = state as PaginatedState<ChannelShort>
            ChannelTab.PLAYLISTS -> _playlistsState.value = state as PaginatedState<ChannelPlaylist>
            ChannelTab.ABOUT -> { /* Uses header state */ }
        }
    }

    /**
     * Extract nextPage from ErrorAppend state for retry path.
     * Returns null if tab is not in ErrorAppend state.
     */
    private fun getNextPageFromErrorState(tab: ChannelTab): Page? {
        return when (tab) {
            ChannelTab.VIDEOS -> (_videosState.value as? PaginatedState.ErrorAppend)?.nextPage
            ChannelTab.LIVE -> (_liveState.value as? PaginatedState.ErrorAppend)?.nextPage
            ChannelTab.SHORTS -> (_shortsState.value as? PaginatedState.ErrorAppend)?.nextPage
            ChannelTab.PLAYLISTS -> (_playlistsState.value as? PaginatedState.ErrorAppend)?.nextPage
            ChannelTab.ABOUT -> null
        }
    }

    private fun handleAppendError(tab: ChannelTab, message: String) {
        when (tab) {
            ChannelTab.VIDEOS -> {
                val current = _videosState.value
                if (current is PaginatedState.Loaded) {
                    _videosState.value = PaginatedState.ErrorAppend(message, current.items, current.nextPage, current.showLoadMoreFooter)
                }
            }
            ChannelTab.LIVE -> {
                val current = _liveState.value
                if (current is PaginatedState.Loaded) {
                    _liveState.value = PaginatedState.ErrorAppend(message, current.items, current.nextPage, current.showLoadMoreFooter)
                }
            }
            ChannelTab.SHORTS -> {
                val current = _shortsState.value
                if (current is PaginatedState.Loaded) {
                    _shortsState.value = PaginatedState.ErrorAppend(message, current.items, current.nextPage, current.showLoadMoreFooter)
                }
            }
            ChannelTab.PLAYLISTS -> {
                val current = _playlistsState.value
                if (current is PaginatedState.Loaded) {
                    _playlistsState.value = PaginatedState.ErrorAppend(message, current.items, current.nextPage, current.showLoadMoreFooter)
                }
            }
            ChannelTab.ABOUT -> { /* No pagination for About */ }
        }
    }

    // State classes

    sealed class HeaderState {
        data object Loading : HeaderState()
        data class Success(val header: ChannelHeader) : HeaderState()
        data class Error(val message: String) : HeaderState()
    }

    sealed class PaginatedState<out T> {
        data object Idle : PaginatedState<Nothing>()
        data object LoadingInitial : PaginatedState<Nothing>()
        data class Loaded<T>(
            val items: List<T>,
            val nextPage: Page?,
            val isAppending: Boolean = false,
            /**
             * When true, UI should show a "Load More" button at the bottom.
             * Set by fragments when autofill pagination is capped but more pages exist.
             */
            val showLoadMoreFooter: Boolean = false
        ) : PaginatedState<T>()
        data object Empty : PaginatedState<Nothing>()
        data class ErrorInitial(val message: String) : PaginatedState<Nothing>()
        data class ErrorAppend<T>(
            val message: String,
            val items: List<T>,
            val nextPage: Page?,
            val showLoadMoreFooter: Boolean = false
        ) : PaginatedState<T>()
    }

    /**
     * Tracks pagination state for a single tab.
     */
    private data class TabPaginationController(
        var isInitialLoading: Boolean = false,
        var isAppending: Boolean = false,
        var nextPage: Page? = null,
        var hasReachedEnd: Boolean = false,
        var lastAppendRequestMs: Long = 0L
    )

    @AssistedFactory
    interface Factory {
        fun create(channelId: String): ChannelDetailViewModel
    }

    companion object {
        private const val TAG = "ChannelDetailViewModel"
        private const val MIN_APPEND_INTERVAL_MS = 1000L // Rate limit: 1 second between requests
        private const val PAGINATION_THRESHOLD = 5 // Trigger pagination when 5 items from end
        private const val MAX_EMPTY_PAGE_FETCHES = 5 // Max consecutive empty pages to fetch before giving up
    }
}
