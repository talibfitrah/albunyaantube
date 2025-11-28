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
     */
    fun loadNextPage(tab: ChannelTab) {
        val controller = paginationControllers[tab] ?: return

        // Guard conditions for rate limiting
        if (controller.isAppending) {
            Log.d(TAG, "Skipping loadNextPage for $tab - already appending")
            return
        }
        if (controller.hasReachedEnd) {
            Log.d(TAG, "Skipping loadNextPage for $tab - reached end")
            return
        }
        if (controller.nextPage == null) {
            Log.d(TAG, "Skipping loadNextPage for $tab - no next page")
            return
        }

        val now = System.currentTimeMillis()
        if (now - controller.lastAppendRequestMs < MIN_APPEND_INTERVAL_MS) {
            Log.d(TAG, "Skipping loadNextPage for $tab - rate limited")
            return
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
        val page = repository.getVideos(channelId, null)
        controller.nextPage = page.nextPage
        controller.hasReachedEnd = page.nextPage == null

        if (page.items.isEmpty()) {
            _videosState.value = PaginatedState.Empty
        } else {
            _videosState.value = PaginatedState.Loaded(page.items, page.nextPage)
        }
        Log.d(TAG, "Videos initial: ${page.items.size} items, hasMore=${page.nextPage != null}")
    }

    private suspend fun loadVideosNextPage(controller: TabPaginationController) {
        val currentState = _videosState.value
        if (currentState !is PaginatedState.Loaded) return

        _videosState.value = currentState.copy(isAppending = true)

        val page = repository.getVideos(channelId, controller.nextPage)
        controller.nextPage = page.nextPage
        controller.hasReachedEnd = page.nextPage == null

        val newItems = currentState.items + page.items
        _videosState.value = PaginatedState.Loaded(newItems, page.nextPage)
        Log.d(TAG, "Videos append: +${page.items.size} items, total=${newItems.size}")
    }

    private suspend fun loadLiveInitial(controller: TabPaginationController) {
        val page = repository.getLiveStreams(channelId, null)
        controller.nextPage = page.nextPage
        controller.hasReachedEnd = page.nextPage == null

        if (page.items.isEmpty()) {
            _liveState.value = PaginatedState.Empty
        } else {
            _liveState.value = PaginatedState.Loaded(page.items, page.nextPage)
        }
    }

    private suspend fun loadLiveNextPage(controller: TabPaginationController) {
        val currentState = _liveState.value
        if (currentState !is PaginatedState.Loaded) return

        _liveState.value = currentState.copy(isAppending = true)

        val page = repository.getLiveStreams(channelId, controller.nextPage)
        controller.nextPage = page.nextPage
        controller.hasReachedEnd = page.nextPage == null

        val newItems = currentState.items + page.items
        _liveState.value = PaginatedState.Loaded(newItems, page.nextPage)
    }

    private suspend fun loadShortsInitial(controller: TabPaginationController) {
        val page = repository.getShorts(channelId, null)
        controller.nextPage = page.nextPage
        controller.hasReachedEnd = page.nextPage == null

        if (page.items.isEmpty()) {
            _shortsState.value = PaginatedState.Empty
        } else {
            _shortsState.value = PaginatedState.Loaded(page.items, page.nextPage)
        }
    }

    private suspend fun loadShortsNextPage(controller: TabPaginationController) {
        val currentState = _shortsState.value
        if (currentState !is PaginatedState.Loaded) return

        _shortsState.value = currentState.copy(isAppending = true)

        val page = repository.getShorts(channelId, controller.nextPage)
        controller.nextPage = page.nextPage
        controller.hasReachedEnd = page.nextPage == null

        val newItems = currentState.items + page.items
        _shortsState.value = PaginatedState.Loaded(newItems, page.nextPage)
    }

    private suspend fun loadPlaylistsInitial(controller: TabPaginationController) {
        val page = repository.getPlaylists(channelId, null)
        controller.nextPage = page.nextPage
        controller.hasReachedEnd = page.nextPage == null

        if (page.items.isEmpty()) {
            _playlistsState.value = PaginatedState.Empty
        } else {
            _playlistsState.value = PaginatedState.Loaded(page.items, page.nextPage)
        }
    }

    private suspend fun loadPlaylistsNextPage(controller: TabPaginationController) {
        val currentState = _playlistsState.value
        if (currentState !is PaginatedState.Loaded) return

        _playlistsState.value = currentState.copy(isAppending = true)

        val page = repository.getPlaylists(channelId, controller.nextPage)
        controller.nextPage = page.nextPage
        controller.hasReachedEnd = page.nextPage == null

        val newItems = currentState.items + page.items
        _playlistsState.value = PaginatedState.Loaded(newItems, page.nextPage)
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

    private fun handleAppendError(tab: ChannelTab, message: String) {
        when (tab) {
            ChannelTab.VIDEOS -> {
                val current = _videosState.value
                if (current is PaginatedState.Loaded) {
                    _videosState.value = PaginatedState.ErrorAppend(message, current.items, current.nextPage)
                }
            }
            ChannelTab.LIVE -> {
                val current = _liveState.value
                if (current is PaginatedState.Loaded) {
                    _liveState.value = PaginatedState.ErrorAppend(message, current.items, current.nextPage)
                }
            }
            ChannelTab.SHORTS -> {
                val current = _shortsState.value
                if (current is PaginatedState.Loaded) {
                    _shortsState.value = PaginatedState.ErrorAppend(message, current.items, current.nextPage)
                }
            }
            ChannelTab.PLAYLISTS -> {
                val current = _playlistsState.value
                if (current is PaginatedState.Loaded) {
                    _playlistsState.value = PaginatedState.ErrorAppend(message, current.items, current.nextPage)
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
            val isAppending: Boolean = false
        ) : PaginatedState<T>()
        data object Empty : PaginatedState<Nothing>()
        data class ErrorInitial(val message: String) : PaginatedState<Nothing>()
        data class ErrorAppend<T>(
            val message: String,
            val items: List<T>,
            val nextPage: Page?
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
    }
}
