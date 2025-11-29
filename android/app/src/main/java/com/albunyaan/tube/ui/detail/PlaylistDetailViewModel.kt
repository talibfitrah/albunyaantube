package com.albunyaan.tube.ui.detail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.channel.Page
import com.albunyaan.tube.data.playlist.PlaylistDetailRepository
import com.albunyaan.tube.data.playlist.PlaylistHeader
import com.albunyaan.tube.data.playlist.PlaylistItem
import com.albunyaan.tube.download.DownloadPolicy
import com.albunyaan.tube.download.DownloadRepository
import com.albunyaan.tube.download.DownloadStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * ViewModel for the Playlist Detail screen.
 * Uses NewPipeExtractor directly via PlaylistDetailRepository (no backend calls).
 *
 * Manages:
 * - Playlist header state (loading, success, error)
 * - Paginated items state
 * - Rate limiting for pagination requests
 * - Download state aggregation for playlist downloads
 */
@HiltViewModel(assistedFactory = PlaylistDetailViewModel.Factory::class)
class PlaylistDetailViewModel @AssistedInject constructor(
    private val repository: PlaylistDetailRepository,
    private val downloadRepository: DownloadRepository,
    @Assisted("playlistId") private val playlistId: String,
    @Assisted("initialTitle") private val initialTitle: String?,
    @Assisted("initialCategory") private val initialCategory: String?,
    @Assisted("initialCount") private val initialCount: Int,
    @Assisted("downloadPolicy") private val downloadPolicy: DownloadPolicy,
    @Assisted("excluded") private val excluded: Boolean
) : ViewModel() {

    // Header state
    private val _headerState = MutableStateFlow<HeaderState>(HeaderState.Loading)
    val headerState: StateFlow<HeaderState> = _headerState.asStateFlow()

    // Items (videos) paginated state
    private val _itemsState = MutableStateFlow<PaginatedState<PlaylistItem>>(PaginatedState.Idle)
    val itemsState: StateFlow<PaginatedState<PlaylistItem>> = _itemsState.asStateFlow()

    // Download state for the playlist
    private val _downloadUiState = MutableStateFlow(PlaylistDownloadUiState())
    val downloadUiState: StateFlow<PlaylistDownloadUiState> = _downloadUiState.asStateFlow()

    // One-shot UI events
    private val _uiEvents = MutableSharedFlow<PlaylistUiEvent>()
    val uiEvents: SharedFlow<PlaylistUiEvent> = _uiEvents.asSharedFlow()

    // Pagination controller
    private val paginationController = PaginationController()

    init {
        loadHeader()
        observeDownloads()
    }

    /**
     * Load playlist header information.
     */
    fun loadHeader(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Loading header for playlist: $playlistId")
                _headerState.value = HeaderState.Loading

                val header = repository.getHeader(
                    playlistId = playlistId,
                    forceRefresh = forceRefresh,
                    category = initialCategory,
                    excluded = excluded,
                    downloadPolicy = downloadPolicy
                )
                _headerState.value = HeaderState.Success(header)
                Log.d(TAG, "Header loaded: ${header.title}")

                // Auto-load items after header success
                loadInitial()
            } catch (e: Exception) {
                val errorMsg = "Failed to load playlist: ${e.message}"
                _headerState.value = HeaderState.Error(errorMsg)
                Log.e(TAG, errorMsg, e)
            }
        }
    }

    /**
     * Load initial page of items.
     */
    fun loadInitial() {
        // Skip if already loading
        if (paginationController.isInitialLoading) {
            Log.d(TAG, "Skipping loadInitial - already loading")
            return
        }

        viewModelScope.launch {
            try {
                Log.d(TAG, "Loading initial items for playlist: $playlistId")
                paginationController.isInitialLoading = true
                paginationController.hasReachedEnd = false
                paginationController.nextPage = null
                paginationController.nextItemOffset = 1

                _itemsState.value = PaginatedState.LoadingInitial

                val page = repository.getItems(playlistId, null, itemOffset = 1)
                paginationController.nextPage = page.nextPage
                paginationController.nextItemOffset = page.nextItemOffset
                paginationController.hasReachedEnd = page.nextPage == null

                _itemsState.value = if (page.items.isEmpty()) {
                    PaginatedState.Empty
                } else {
                    PaginatedState.Loaded(page.items, page.nextPage)
                }
                Log.d(TAG, "Initial items loaded: ${page.items.size}, hasMore=${page.nextPage != null}")
            } catch (e: Exception) {
                val errorMsg = "Failed to load items: ${e.message}"
                _itemsState.value = PaginatedState.ErrorInitial(errorMsg)
                Log.e(TAG, errorMsg, e)
            } finally {
                paginationController.isInitialLoading = false
            }
        }
    }

    /**
     * Load next page of items.
     */
    fun loadNextPage() {
        // Guard conditions for rate limiting
        if (paginationController.isAppending) {
            Log.d(TAG, "Skipping loadNextPage - already appending")
            return
        }
        if (paginationController.hasReachedEnd) {
            Log.d(TAG, "Skipping loadNextPage - reached end")
            return
        }
        if (paginationController.nextPage == null) {
            Log.d(TAG, "Skipping loadNextPage - no next page")
            return
        }

        val now = System.currentTimeMillis()
        if (now - paginationController.lastAppendRequestMs < MIN_APPEND_INTERVAL_MS) {
            Log.d(TAG, "Skipping loadNextPage - rate limited")
            return
        }
        paginationController.lastAppendRequestMs = now

        viewModelScope.launch {
            try {
                Log.d(TAG, "Loading next page for playlist: $playlistId")
                paginationController.isAppending = true

                val currentState = _itemsState.value
                if (currentState !is PaginatedState.Loaded) return@launch

                _itemsState.value = currentState.copy(isAppending = true)

                val page = repository.getItems(
                    playlistId,
                    paginationController.nextPage,
                    itemOffset = paginationController.nextItemOffset
                )
                paginationController.nextPage = page.nextPage
                paginationController.nextItemOffset = page.nextItemOffset
                paginationController.hasReachedEnd = page.nextPage == null

                val newItems = currentState.items + page.items
                _itemsState.value = PaginatedState.Loaded(newItems, page.nextPage)
                Log.d(TAG, "Appended ${page.items.size} items, total=${newItems.size}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load next page", e)
                val currentState = _itemsState.value
                if (currentState is PaginatedState.Loaded) {
                    _itemsState.value = PaginatedState.ErrorAppend(
                        e.message ?: "Unknown error",
                        currentState.items,
                        currentState.nextPage
                    )
                }
            } finally {
                paginationController.isAppending = false
            }
        }
    }

    /**
     * Retry initial load after error.
     */
    fun retryInitial() {
        loadInitial()
    }

    /**
     * Retry append after error.
     */
    fun retryAppend() {
        loadNextPage()
    }

    /**
     * Called when user scrolls the list. Triggers pagination if near end.
     */
    fun onListScrolled(lastVisibleItem: Int, totalCount: Int) {
        if (totalCount - lastVisibleItem <= PAGINATION_THRESHOLD) {
            loadNextPage()
        }
    }

    /**
     * Handle play all action.
     */
    fun onPlayAllClicked(startIndex: Int = 0) {
        viewModelScope.launch {
            _uiEvents.emit(PlaylistUiEvent.NavigateToPlayer(startIndex, shuffled = false))
        }
    }

    /**
     * Handle shuffle action.
     */
    fun onShuffleClicked() {
        viewModelScope.launch {
            _uiEvents.emit(PlaylistUiEvent.NavigateToPlayer(startIndex = 0, shuffled = true))
        }
    }

    /**
     * Handle download playlist button click.
     */
    fun onDownloadPlaylistClicked() {
        val header = (_headerState.value as? HeaderState.Success)?.header ?: return
        viewModelScope.launch {
            _uiEvents.emit(
                PlaylistUiEvent.ShowDownloadQualitySheet(
                    playlistId = playlistId,
                    playlistTitle = header.title,
                    itemCount = header.itemCount?.toInt() ?: 0,
                    suggestedQuality = PlaylistQualityOption.QUALITY_360P // Default
                )
            )
        }
    }

    /**
     * Start downloading the playlist with selected quality.
     */
    fun startPlaylistDownload(selectedQuality: PlaylistQualityOption) {
        viewModelScope.launch {
            try {
                _downloadUiState.value = _downloadUiState.value.copy(
                    isDownloading = true,
                    currentQualityLabel = selectedQuality.label,
                    errorMessage = null
                )

                // Collect all items first (paginating through the playlist)
                val allItems = mutableListOf<PlaylistItem>()
                var currentPage: Page? = null
                var nextItemOffset = 1
                var pageCount = 0

                // Get items from current state first if available
                val currentState = _itemsState.value
                if (currentState is PaginatedState.Loaded) {
                    allItems.addAll(currentState.items)
                    currentPage = paginationController.nextPage
                    nextItemOffset = paginationController.nextItemOffset
                } else {
                    // Load initial page
                    val initialPage = repository.getItems(playlistId, null, itemOffset = 1)
                    allItems.addAll(initialPage.items)
                    currentPage = initialPage.nextPage
                    nextItemOffset = initialPage.nextItemOffset
                }

                // Paginate to get all items (with cap to prevent memory issues)
                while (currentPage != null && allItems.size < MAX_DOWNLOAD_ITEMS_CAP) {
                    pageCount++
                    kotlinx.coroutines.delay(PAGINATION_DELAY_MS) // Backpressure

                    val page = repository.getItems(playlistId, currentPage, itemOffset = nextItemOffset)
                    allItems.addAll(page.items)
                    currentPage = page.nextPage
                    nextItemOffset = page.nextItemOffset

                    Log.d(TAG, "Download preparation: loaded page $pageCount, total items=${allItems.size}")
                }

                if (allItems.size >= MAX_DOWNLOAD_ITEMS_CAP) {
                    Log.w(TAG, "Playlist capped at $MAX_DOWNLOAD_ITEMS_CAP items for download")
                }

                // Get header for metadata
                val header = (_headerState.value as? HeaderState.Success)?.header
                    ?: throw IllegalStateException("Header not loaded")

                // Emit event to start downloads
                _uiEvents.emit(
                    PlaylistUiEvent.StartPlaylistDownload(
                        playlistId = playlistId,
                        playlistTitle = header.title,
                        quality = selectedQuality,
                        items = allItems
                    )
                )

                _downloadUiState.value = _downloadUiState.value.copy(
                    isDownloading = false,
                    totalCount = allItems.size,
                    currentQualityLabel = selectedQuality.label
                )

            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare playlist download", e)
                _downloadUiState.value = _downloadUiState.value.copy(
                    isDownloading = false,
                    errorMessage = e.message ?: "Download preparation failed"
                )
                _uiEvents.emit(PlaylistUiEvent.ShowError(e.message ?: "Download preparation failed"))
            }
        }
    }

    /**
     * Observe download repository for playlist download progress.
     *
     * Groups downloads by quality label to avoid mixing different quality downloads.
     * Shows progress for the currently active download quality.
     */
    private fun observeDownloads() {
        viewModelScope.launch {
            downloadRepository.downloads.collect { downloads ->
                // Filter downloads for this playlist
                val playlistDownloads = downloads.filter { entry ->
                    entry.request.id.startsWith("$playlistId|")
                }

                if (playlistDownloads.isEmpty()) {
                    _downloadUiState.value = PlaylistDownloadUiState()
                    return@collect
                }

                // Group by quality label (extracted from request ID: playlistId|qualityLabel|videoId)
                // Or from the playlistQualityLabel field on the request
                val byQuality = playlistDownloads.groupBy { entry ->
                    entry.request.playlistQualityLabel ?: run {
                        // Fallback: extract from request ID
                        val parts = entry.request.id.split("|")
                        if (parts.size >= 2) parts[1] else "unknown"
                    }
                }

                // Find the active quality (one with running/queued downloads)
                val activeQuality = byQuality.entries.firstOrNull { (_, entries) ->
                    entries.any { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED }
                }

                // If no active downloads, show the most recently completed quality
                // Use completedAtMillis timestamp for deterministic ordering
                val (qualityLabel, qualityDownloads) = activeQuality
                    ?: byQuality.entries.maxByOrNull { (_, entries) ->
                        // Find the most recent completion timestamp in this quality group
                        entries.maxOfOrNull { entry ->
                            entry.metadata?.completedAtMillis ?: 0L
                        } ?: 0L
                    }
                    ?: return@collect

                val completed = qualityDownloads.count { it.status == DownloadStatus.COMPLETED }
                val total = qualityDownloads.size
                val inProgress = qualityDownloads.any {
                    it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED
                }
                val failed = qualityDownloads.any { it.status == DownloadStatus.FAILED }

                val avgProgress = if (total > 0) {
                    qualityDownloads.sumOf { it.progress } / total
                } else {
                    0
                }

                _downloadUiState.value = PlaylistDownloadUiState(
                    isDownloading = inProgress,
                    downloadedCount = completed,
                    totalCount = total,
                    progressPercent = avgProgress,
                    currentQualityLabel = qualityLabel,
                    errorMessage = if (failed) "Some downloads failed" else null
                )
            }
        }
    }

    // State classes

    sealed class HeaderState {
        data object Loading : HeaderState()
        data class Success(val header: PlaylistHeader) : HeaderState()
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
     * UI state for playlist download progress.
     */
    data class PlaylistDownloadUiState(
        val isDownloading: Boolean = false,
        val downloadedCount: Int = 0,
        val totalCount: Int = 0,
        val progressPercent: Int = 0,
        val currentQualityLabel: String? = null,
        val errorMessage: String? = null
    )

    /**
     * One-shot UI events emitted by the ViewModel.
     */
    sealed class PlaylistUiEvent {
        data class NavigateToPlayer(val startIndex: Int, val shuffled: Boolean) : PlaylistUiEvent()
        data class ShowDownloadQualitySheet(
            val playlistId: String,
            val playlistTitle: String,
            val itemCount: Int,
            val suggestedQuality: PlaylistQualityOption
        ) : PlaylistUiEvent()
        data class StartPlaylistDownload(
            val playlistId: String,
            val playlistTitle: String,
            val quality: PlaylistQualityOption,
            val items: List<PlaylistItem>
        ) : PlaylistUiEvent()
        data class ShowError(val message: String) : PlaylistUiEvent()
    }

    /**
     * Tracks pagination state.
     */
    private data class PaginationController(
        var isInitialLoading: Boolean = false,
        var isAppending: Boolean = false,
        var nextPage: Page? = null,
        var nextItemOffset: Int = 1,
        var hasReachedEnd: Boolean = false,
        var lastAppendRequestMs: Long = 0L
    )

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("playlistId") playlistId: String,
            @Assisted("initialTitle") initialTitle: String?,
            @Assisted("initialCategory") initialCategory: String?,
            @Assisted("initialCount") initialCount: Int,
            @Assisted("downloadPolicy") downloadPolicy: DownloadPolicy,
            @Assisted("excluded") excluded: Boolean
        ): PlaylistDetailViewModel
    }

    companion object {
        private const val TAG = "PlaylistDetailVM"
        private const val MIN_APPEND_INTERVAL_MS = 1000L // Rate limit: 1 second between requests
        private const val PAGINATION_THRESHOLD = 5 // Trigger pagination when 5 items from end
        private const val MAX_DOWNLOAD_ITEMS_CAP = 500 // Max items to download in a playlist
        private const val PAGINATION_DELAY_MS = 500L // Delay between pages when collecting for download
    }
}

/**
 * Quality options for playlist download.
 *
 * @param label Display label for the quality option
 * @param audioOnly Whether this option downloads audio only (no video)
 * @param targetHeight Target video height for stream selection (null for audio-only)
 */
enum class PlaylistQualityOption(
    val label: String,
    val audioOnly: Boolean,
    val targetHeight: Int?
) {
    AUDIO_ONLY("Audio Only", audioOnly = true, targetHeight = null),
    QUALITY_144P("144p", audioOnly = false, targetHeight = 144),
    QUALITY_360P("360p", audioOnly = false, targetHeight = 360),
    QUALITY_720P("720p", audioOnly = false, targetHeight = 720),
    QUALITY_1080P("1080p", audioOnly = false, targetHeight = 1080),
    QUALITY_4K("4K", audioOnly = false, targetHeight = 2160)
}
