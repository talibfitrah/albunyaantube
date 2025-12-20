package com.albunyaan.tube.ui.player

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.R
import com.albunyaan.tube.analytics.ExtractorMetricsReporter
import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.PlaybackSelection
import com.albunyaan.tube.data.extractor.QualitySelectionOrigin
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.SubtitleTrack
import com.albunyaan.tube.data.extractor.VideoTrack
import com.albunyaan.tube.data.local.FavoritesRepository
import com.albunyaan.tube.download.DownloadEntry
import com.albunyaan.tube.download.DownloadRepository
import com.albunyaan.tube.download.DownloadRequest
import com.albunyaan.tube.player.ExtractionRateLimiter
import com.albunyaan.tube.player.PlayerRepository
import com.albunyaan.tube.player.StreamPrefetchService
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import com.albunyaan.tube.data.channel.Page
import com.albunyaan.tube.data.playlist.PlaylistItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * P3-T4: PlayerViewModel with Hilt DI
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val repository: PlayerRepository,
    private val downloadRepository: DownloadRepository,
    private val playlistDetailRepository: com.albunyaan.tube.data.playlist.PlaylistDetailRepository,
    private val rateLimiter: ExtractionRateLimiter,
    private val prefetchService: StreamPrefetchService,
    private val favoritesRepository: FavoritesRepository,
    private val metricsReporter: ExtractorMetricsReporter
) : ViewModel() {

    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    /**
     * UI events for user feedback (errors, confirmations).
     * Uses SharedFlow with extraBufferCapacity to ensure events aren't dropped.
     */
    private val _uiEvents = MutableSharedFlow<PlayerUiEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val uiEvents: SharedFlow<PlayerUiEvent> = _uiEvents.asSharedFlow()

    private val queue = mutableListOf<UpNextItem>()
    private val previousItems = mutableListOf<UpNextItem>()
    private val maxHistorySize = 100 // Limit history to prevent unbounded memory growth
    private var currentItem: UpNextItem? = null
    private var resolveJob: Job? = null
    private var prefetchJob: Job? = null
    private var latestDownloads: List<DownloadEntry> = emptyList()

    // Prefetch cache: stores resolved streams for next items
    private val prefetchCache = mutableMapOf<String, ResolvedStreams>()
    private val maxPrefetchItems = 2

    // Pending quality cap: stored when URLs expire during quality switch, applied after refresh
    private var pendingQualityCap: VideoTrack? = null

    // PR5: Pending refresh job for cancellation when video changes or new refresh requested
    private var pendingRefreshJob: Job? = null

    // Playlist playback state
    private var currentPlaylistId: String? = null
    private var isPlaylistMode: Boolean = false

    // PR6.6: Playlist paging state for lazy loading
    private var playlistPagingState: PlaylistPagingState? = null
    private val pagingMutex = Mutex()
    private var pagingJob: Job? = null

    // PR6.6: Auto-skip tracking for unplayable items in playlist mode
    private var consecutiveSkips = 0

    private val _analyticsEvents = MutableSharedFlow<PlaybackAnalyticsEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val analyticsEvents: SharedFlow<PlaybackAnalyticsEvent> = _analyticsEvents

    val playerListener: Player.Listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            updateState { it.copy(hasVideoTrack = videoSize != VideoSize.UNKNOWN) }
        }
    }

    init {
        hydrateQueue()
        observeDownloads()
        observeFavoriteStatus()
    }

    /**
     * Observe favorite status for the current video.
     * Uses flatMapLatest to correctly switch to new favorite Flows when currentItem changes.
     * Updates state.isFavorite reactively when favorites change.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeFavoriteStatus() {
        viewModelScope.launch(dispatcher) {
            _state
                .map { it.currentItem?.streamId }
                .distinctUntilChanged()
                .flatMapLatest { videoId ->
                    if (videoId != null) {
                        favoritesRepository.isFavorite(videoId)
                    } else {
                        flowOf(false)
                    }
                }
                .collect { isFavorite ->
                    updateState { it.copy(isFavorite = isFavorite) }
                }
        }
    }

    /**
     * Toggle favorite status for the current video.
     * Emits UI event on failure for toast/snackbar display.
     */
    fun toggleFavorite() {
        val item = _state.value.currentItem ?: return
        viewModelScope.launch(dispatcher) {
            try {
                val isNowFavorite = favoritesRepository.toggleFavorite(
                    videoId = item.streamId,
                    title = item.title,
                    channelName = item.channelName,
                    thumbnailUrl = item.thumbnailUrl,
                    durationSeconds = item.durationSeconds
                )
                // State will be updated by observeFavoriteStatus collector
                android.util.Log.d("PlayerViewModel", "Toggled favorite for ${item.streamId}: $isNowFavorite")
            } catch (e: Exception) {
                android.util.Log.e("PlayerViewModel", "Failed to toggle favorite for ${item.streamId}", e)

                // Report error to analytics/telemetry for monitoring
                metricsReporter.onFavoriteToggleFailed(item.streamId, e)

                // Emit UI event for user feedback (toast/snackbar)
                _uiEvents.tryEmit(
                    PlayerUiEvent.FavoriteToggleFailed(
                        videoId = item.streamId,
                        messageRes = R.string.player_favorite_toggle_error,
                        canRetry = true
                    )
                )
            }
        }
    }

    fun setAudioOnly(audioOnly: Boolean) {
        if (_state.value.audioOnly == audioOnly) return
        updateState { it.copy(audioOnly = audioOnly) }
        publishAnalytics(PlaybackAnalyticsEvent.AudioOnlyToggled(audioOnly))
    }

    fun playItem(item: UpNextItem) {
        val current = currentItem
        if (current?.id == item.id) return
        val removed = queue.remove(item)
        if (!removed) return
        // PR5: Cancel any pending delayed refresh for the old video
        pendingRefreshJob?.cancel()
        pendingRefreshJob = null
        current?.let { addToHistory(it) }
        currentItem = item
        applyQueueState()
        publishAnalytics(PlaybackAnalyticsEvent.PlaybackStarted(item, PlaybackStartReason.USER_SELECTED))
        resolveStreamFor(item, PlaybackStartReason.USER_SELECTED)
    }

    fun markCurrentComplete(): Boolean = advanceToNext(PlaybackStartReason.AUTO, markComplete = true)

    fun skipToNext(): Boolean = advanceToNext(PlaybackStartReason.USER_SELECTED, markComplete = false)

    fun skipToPrevious(): Boolean {
        val current = currentItem ?: return false
        if (previousItems.isEmpty()) return false
        // PR5: Cancel any pending delayed refresh for the old video
        pendingRefreshJob?.cancel()
        pendingRefreshJob = null
        val previous = previousItems.removeLast()
        queue.add(0, current)
        currentItem = previous
        applyQueueState()
        publishAnalytics(PlaybackAnalyticsEvent.PlaybackStarted(previous, PlaybackStartReason.USER_SELECTED))
        resolveStreamFor(previous, PlaybackStartReason.USER_SELECTED)
        return true
    }

    /**
     * Downloads the current video with specified quality.
     *
     * @param targetHeight Maximum video height (720, 1080, etc.) or null for audio-only
     * @param audioOnly True if user explicitly requested audio-only download
     * @return True if download was started, false if no current item
     */
    fun downloadCurrent(targetHeight: Int? = null, audioOnly: Boolean = false): Boolean {
        val state = _state.value
        val item = state.currentItem ?: return false
        val request = DownloadRequest(
            id = item.streamId + "_" + System.currentTimeMillis(),
            title = item.title,
            videoId = item.streamId,
            audioOnly = audioOnly,
            targetHeight = targetHeight
        )
        downloadRepository.enqueue(request)
        return true
    }

    private fun observeDownloads() {
        viewModelScope.launch(dispatcher) {
            downloadRepository.downloads.collect { entries ->
                latestDownloads = entries
                updateState { state ->
                    state.copy(currentDownload = findDownloadFor(state.currentItem, entries))
                }
            }
        }
    }

    /**
     * Get available quality options for current stream.
     * Deduplicates by resolution height, preferring muxed streams over video-only
     * for better reliability (no audio/video merge needed).
     */
    fun getAvailableQualities(): List<QualityOption> {
        val streamState = _state.value.streamState
        if (streamState !is StreamState.Ready) return emptyList()

        val videoTracks = streamState.selection.resolved.videoTracks

        // Deduplicate by height: prefer muxed over video-only, then highest bitrate
        val deduped = videoTracks
            .filter { it.height != null && it.qualityLabel != null }
            .groupBy { it.height }
            .mapValues { (_, tracksAtHeight) ->
                tracksAtHeight
                    .sortedWith(
                        compareBy<VideoTrack> { it.isVideoOnly } // muxed first (false < true)
                            .thenByDescending { it.bitrate ?: 0 }
                    )
                    .first()
            }
            .values

        return deduped.mapNotNull { track ->
            track.qualityLabel?.let { label ->
                QualityOption(label, track)
            }
        }.sortedByDescending { it.track.height ?: 0 }
    }

    /**
     * Set user quality cap (ceiling) from manual selection.
     * This treats the user's choice as a maximum resolution cap. ABR can still drop
     * below when network dips, then recover back up to the cap.
     *
     * For adaptive streaming (HLS/DASH): Applied via track selector constraints
     * For progressive streaming: Selects the best track under the cap
     */
    fun setUserQualityCap(track: VideoTrack) {
        val streamState = _state.value.streamState
        if (streamState !is StreamState.Ready) return

        val resolved = streamState.selection.resolved
        val isProgressiveStream = resolved.hlsUrl == null && resolved.dashUrl == null

        // PR4: URL Lifecycle Hardening - check if progressive URLs are expired
        if (isProgressiveStream && resolved.areUrlsExpired()) {
            android.util.Log.w("PlayerViewModel", "setUserQualityCap: URLs expired, storing pending cap and forcing refresh")
            // Store the user's quality preference to apply after refresh
            pendingQualityCap = track
            if (!forceRefreshCurrentStream()) {
                // Refresh blocked by rate limiter - clear pending cap to avoid applying to wrong video later
                pendingQualityCap = null
                android.util.Log.w("PlayerViewModel", "setUserQualityCap: Refresh blocked, quality cap not applied")
            }
            return
        }

        // Guard against null/invalid height - treat as "use this exact track" without setting a cap
        val capHeight = track.height
        if (capHeight == null || capHeight <= 0) {
            android.util.Log.w("PlayerViewModel", "setUserQualityCap: track has no valid height, using track directly")
            val newSelection = PlaybackSelection(
                streamId = streamState.streamId,
                video = track,
                audio = streamState.selection.audio,
                resolved = resolved,
                userQualityCapHeight = null, // No cap - can't determine height
                selectionOrigin = QualitySelectionOrigin.MANUAL
            )
            updateState { it.copy(streamState = StreamState.Ready(streamState.streamId, newSelection)) }
            publishAnalytics(PlaybackAnalyticsEvent.QualityChanged(track.qualityLabel ?: "Unknown"))
            return
        }

        // Find the best track that respects the cap; fallback to lowest available if none under cap
        val bestUnderCap = findBestTrackUnderCap(resolved.videoTracks, capHeight)
            ?: resolved.videoTracks.minByOrNull { it.height ?: Int.MAX_VALUE }
            ?: track // final fallback if no tracks available

        val newSelection = PlaybackSelection(
            streamId = streamState.streamId,
            video = bestUnderCap,
            audio = streamState.selection.audio,
            resolved = resolved,
            userQualityCapHeight = capHeight,
            selectionOrigin = QualitySelectionOrigin.MANUAL
        )

        updateState { it.copy(streamState = StreamState.Ready(streamState.streamId, newSelection)) }
        publishAnalytics(PlaybackAnalyticsEvent.QualityChanged(track.qualityLabel ?: "Unknown"))
    }

    /**
     * Apply automatic quality step-down during playback stalls.
     * This does NOT change the user's quality cap - it's a temporary recovery action.
     * Used only when playing progressive streams (not adaptive).
     *
     * @return true if step-down was applied, false if URLs were expired (refresh triggered)
     */
    fun applyAutoQualityStepDown(track: VideoTrack): Boolean {
        val streamState = _state.value.streamState
        if (streamState !is StreamState.Ready) return false

        val resolved = streamState.selection.resolved
        val isProgressiveStream = resolved.hlsUrl == null && resolved.dashUrl == null

        // PR4: URL Lifecycle Hardening - check if progressive URLs are expired
        if (isProgressiveStream && resolved.areUrlsExpired()) {
            android.util.Log.w("PlayerViewModel", "applyAutoQualityStepDown: URLs expired, forcing stream refresh instead of step-down")
            // PR5: Use AUTO_RECOVERY for automatic quality step-down paths - ensures recovery
            // is not blocked by manual refresh limits
            forceRefreshForAutoRecovery()
            return false
        }

        // Preserve user's cap if they set one - auto step-down should not override it
        val newSelection = PlaybackSelection(
            streamId = streamState.streamId,
            video = track,
            audio = streamState.selection.audio,
            resolved = resolved,
            userQualityCapHeight = streamState.selection.userQualityCapHeight,
            selectionOrigin = QualitySelectionOrigin.AUTO_RECOVERY
        )

        updateState { it.copy(streamState = StreamState.Ready(streamState.streamId, newSelection)) }
        android.util.Log.d("PlayerViewModel", "Auto step-down to ${track.qualityLabel} (cap preserved: ${streamState.selection.userQualityCapHeight}p)")
        return true
    }

    /**
     * Legacy method - delegates to setUserQualityCap for backward compatibility.
     * @deprecated Use setUserQualityCap instead
     */
    fun selectQuality(track: VideoTrack) = setUserQualityCap(track)

    /**
     * Find the best video track that respects the given height cap.
     * Prefers muxed streams over video-only for reliability.
     */
    private fun findBestTrackUnderCap(tracks: List<VideoTrack>, capHeight: Int): VideoTrack? {
        return tracks
            .filter { (it.height ?: 0) <= capHeight }
            .sortedWith(
                compareByDescending<VideoTrack> { it.height ?: 0 }
                    .thenBy { it.isVideoOnly } // prefer muxed
                    .thenByDescending { it.bitrate ?: 0 }
            )
            .firstOrNull()
    }

    /**
     * Get available subtitle/caption tracks
     */
    fun getAvailableSubtitles(): List<SubtitleTrack> {
        val streamState = _state.value.streamState
        if (streamState !is StreamState.Ready) return emptyList()

        return streamState.selection.resolved.subtitleTracks
    }

    /**
     * Get currently selected subtitle track
     */
    fun getSelectedSubtitle(): SubtitleTrack? {
        return _state.value.selectedSubtitle
    }

    /**
     * Select a subtitle track (or null to disable subtitles)
     */
    fun selectSubtitle(track: SubtitleTrack?) {
        updateState { it.copy(selectedSubtitle = track) }
        track?.let {
            publishAnalytics(PlaybackAnalyticsEvent.SubtitleChanged(it.languageName))
        } ?: run {
            publishAnalytics(PlaybackAnalyticsEvent.SubtitleChanged("Off"))
        }
    }

    /**
     * Load and play a specific video by ID.
     *
     * Fast-path: Starts stream resolution immediately without blocking on metadata fetch.
     * Metadata (title, thumbnail, etc.) should be passed via navigation arguments.
     * This eliminates the 15-20s potential delay from backend calls.
     *
     * @param videoId YouTube video ID
     * @param title Video title (passed via nav args for instant display)
     * @param channelName Channel name (optional, passed via nav args)
     * @param thumbnailUrl Thumbnail URL (optional, passed via nav args)
     * @param description Video description (optional)
     * @param durationSeconds Video duration in seconds (optional)
     * @param viewCount View count (optional)
     */
    fun loadVideo(
        videoId: String,
        title: String = "Video",
        channelName: String = "",
        thumbnailUrl: String? = null,
        description: String? = null,
        durationSeconds: Int = 0,
        viewCount: Long? = null
    ) {
        // PR5: Cancel any pending delayed refresh for the old video
        pendingRefreshJob?.cancel()
        pendingRefreshJob = null

        // PR6.6: Clear playlist mode and paging state to prevent stale hasNext
        isPlaylistMode = false
        currentPlaylistId = null
        pagingJob?.cancel()
        pagingJob = null
        playlistPagingState = null
        consecutiveSkips = 0

        // Create item immediately from nav args - no backend fetch needed
        val item = UpNextItem(
            id = videoId,
            title = title,
            channelName = channelName,
            durationSeconds = durationSeconds,
            streamId = videoId,
            thumbnailUrl = thumbnailUrl,
            description = description,
            viewCount = viewCount
        )

        currentItem = item
        queue.clear()
        previousItems.clear()
        applyQueueState()

        publishAnalytics(PlaybackAnalyticsEvent.PlaybackStarted(item, PlaybackStartReason.USER_SELECTED))

        // Start stream resolution immediately - this is the fast path
        resolveStreamFor(item, PlaybackStartReason.USER_SELECTED)
    }

    /**
     * Load and play a playlist from the specified position.
     *
     * PR6.6: Now supports deep starts via targetVideoId. Will page through playlist
     * until target video is found (bounded by MAX_ITEMS_TO_SCAN and MAX_SCAN_TIME_MS).
     *
     * @param playlistId YouTube playlist ID
     * @param targetVideoId The video ID to start playing (authoritative), or null to use startIndex
     * @param startIndexHint 0-based index hint for optimization (used if targetVideoId matches)
     * @param shuffled If true, randomize the order of videos in the queue
     */
    fun loadPlaylist(
        playlistId: String,
        targetVideoId: String? = null,
        startIndexHint: Int = 0,
        shuffled: Boolean = false
    ) {
        isPlaylistMode = true
        currentPlaylistId = playlistId
        consecutiveSkips = 0  // Reset auto-skip counter

        viewModelScope.launch(dispatcher) {
            updateState { it.copy(streamState = StreamState.Loading) }

            try {
                loadPlaylistWithTarget(playlistId, targetVideoId, startIndexHint, shuffled)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("PlayerViewModel", "Failed to load playlist: $playlistId", e)
                updateState { it.copy(streamState = StreamState.Error(R.string.player_stream_error)) }
            }
        }
    }

    /**
     * PR6.6: Internal implementation for loading playlist with deep start support.
     * Pages through playlist until targetVideoId is found or bounds are exceeded.
     *
     * Time budget: MAX_SCAN_TIME_MS applies to the TOTAL deep-start operation, not per-page.
     * Each subsequent page fetch gets min(PAGE_FETCH_TIMEOUT_MS, remainingBudget).
     */
    private suspend fun loadPlaylistWithTarget(
        playlistId: String,
        targetVideoId: String?,
        startIndexHint: Int,
        shuffled: Boolean
    ) {
        val startTime = System.currentTimeMillis()
        val allItems = mutableListOf<PlaylistItem>()
        var nextPage: Page?
        var nextItemOffset: Int

        // Load first page (always allowed full timeout - not part of deep-start budget)
        val firstPage = try {
            withTimeoutOrNull(PAGE_FETCH_TIMEOUT_MS) {
                playlistDetailRepository.getItems(playlistId, page = null, itemOffset = 1)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("PlayerViewModel", "Playlist first page fetch failed: ${e.message}")
            null
        }

        if (firstPage == null) {
            android.util.Log.e("PlayerViewModel", "Playlist first page fetch timed out or failed")
            updateState { it.copy(streamState = StreamState.Error(R.string.player_stream_error)) }
            return
        }

        allItems.addAll(firstPage.items)
        nextPage = firstPage.nextPage
        nextItemOffset = firstPage.nextItemOffset

        if (allItems.isEmpty()) {
            updateState { it.copy(streamState = StreamState.Error(R.string.player_stream_unavailable)) }
            return
        }

        // Fast path: check if startIndexHint matches targetVideoId on first page
        if (targetVideoId != null && startIndexHint in allItems.indices) {
            if (allItems[startIndexHint].videoId == targetVideoId) {
                android.util.Log.d("PlayerViewModel", "Fast path: targetVideoId found at hinted index $startIndexHint")
                initializePlaylistQueue(playlistId, allItems, startIndexHint, shuffled, nextPage, nextItemOffset)
                return
            }
        }

        // Search loaded items for targetVideoId
        var foundIndex = if (targetVideoId != null) {
            allItems.indexOfFirst { it.videoId == targetVideoId }
        } else {
            -1
        }

        // Deep-start: page through until found or bounds hit (only if we have a targetVideoId to find)
        // Time budget enforcement: each page fetch gets min(PAGE_FETCH_TIMEOUT_MS, remainingBudget)
        while (targetVideoId != null && foundIndex == -1 && nextPage != null) {
            val elapsed = System.currentTimeMillis() - startTime
            val remainingBudget = MAX_SCAN_TIME_MS - elapsed

            // Check bounds AFTER computing remaining budget
            if (remainingBudget <= 0 || allItems.size >= MAX_ITEMS_TO_SCAN) {
                android.util.Log.d("PlayerViewModel", "Deep start: bounds exceeded (elapsed=${elapsed}ms, items=${allItems.size})")
                break
            }

            android.util.Log.d("PlayerViewModel", "Deep start: paging for targetVideoId=$targetVideoId, loaded=${allItems.size} items, budget=${remainingBudget}ms")

            // Use remaining budget as timeout (clamped to PAGE_FETCH_TIMEOUT_MS)
            val pageTimeout = minOf(PAGE_FETCH_TIMEOUT_MS, remainingBudget)
            val morePage = try {
                withTimeoutOrNull(pageTimeout) {
                    playlistDetailRepository.getItems(playlistId, nextPage, nextItemOffset)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("PlayerViewModel", "Deep start: page fetch error ${e.message}, using loaded items")
                null
            }

            if (morePage == null) {
                android.util.Log.w("PlayerViewModel", "Deep start: page fetch timed out or failed, using loaded items")
                break
            }

            allItems.addAll(morePage.items)
            nextPage = morePage.nextPage
            nextItemOffset = morePage.nextItemOffset

            foundIndex = allItems.indexOfFirst { it.videoId == targetVideoId }
        }

        // Determine effective start index
        val effectiveStartIndex = when {
            foundIndex >= 0 -> {
                android.util.Log.d("PlayerViewModel", "Deep start: found targetVideoId at index $foundIndex")
                foundIndex
            }
            targetVideoId != null -> {
                // Target not found - fall back to startIndexHint or 0
                android.util.Log.w("PlayerViewModel", "Deep start: targetVideoId not found after loading ${allItems.size} items, using hint $startIndexHint")
                startIndexHint.coerceIn(0, allItems.lastIndex.coerceAtLeast(0))
            }
            else -> {
                // No targetVideoId provided - use startIndexHint
                startIndexHint.coerceIn(0, allItems.lastIndex.coerceAtLeast(0))
            }
        }

        initializePlaylistQueue(playlistId, allItems, effectiveStartIndex, shuffled, nextPage, nextItemOffset)
    }

    /**
     * PR6.6: Initialize the queue from loaded playlist items.
     */
    private fun initializePlaylistQueue(
        playlistId: String,
        items: List<PlaylistItem>,
        startIndex: Int,
        shuffled: Boolean,
        nextPage: Page?,
        nextItemOffset: Int
    ) {
        var orderedItems = items

        // If shuffled, randomize the order but preserve the starting video
        if (shuffled) {
            val startItem = orderedItems.getOrNull(startIndex)
            orderedItems = orderedItems.shuffled()
            // Move the starting video to the front if it was specified
            if (startItem != null) {
                orderedItems = listOf(startItem) + orderedItems.filter { it.videoId != startItem.videoId }
            }
        }

        // Convert to UpNextItems
        val upNextItems = orderedItems.map { playlistItem ->
            UpNextItem(
                id = playlistItem.videoId,
                title = playlistItem.title,
                channelName = playlistItem.channelName ?: "",
                durationSeconds = playlistItem.durationSeconds ?: 0,
                streamId = playlistItem.videoId,
                thumbnailUrl = playlistItem.thumbnailUrl,
                viewCount = playlistItem.viewCount
            )
        }

        // Set up the queue
        queue.clear()
        previousItems.clear()
        val effectiveStartIndex = if (shuffled) 0 else startIndex.coerceIn(0, upNextItems.lastIndex.coerceAtLeast(0))

        // Current item is the video at startIndex
        currentItem = upNextItems.getOrNull(effectiveStartIndex)

        // Queue is everything after the current item
        if (effectiveStartIndex + 1 < upNextItems.size) {
            queue.addAll(upNextItems.subList(effectiveStartIndex + 1, upNextItems.size))
        }

        // PR6.6: Store paging state for lazy loading
        val hasMore = nextPage != null && !shuffled  // Disable paging for shuffled playlists
        playlistPagingState = PlaylistPagingState(
            playlistId = playlistId,
            nextPage = nextPage,
            nextItemOffset = nextItemOffset,
            hasMore = hasMore,
            pagingFailed = false,
            lastPageFetchMs = System.currentTimeMillis()
        )

        android.util.Log.d("PlayerViewModel", "Playlist initialized: ${upNextItems.size} items, startIndex=$effectiveStartIndex, hasMore=$hasMore")

        applyQueueState()

        currentItem?.let { item ->
            publishAnalytics(
                PlaybackAnalyticsEvent.PlaybackStarted(
                    item,
                    PlaybackStartReason.USER_SELECTED
                )
            )
            resolveStreamFor(item, PlaybackStartReason.USER_SELECTED)
        } ?: run {
            updateState { it.copy(streamState = StreamState.Error(R.string.player_stream_unavailable)) }
        }
    }

    private fun hydrateQueue() {
        val stubItems = stubUpNextItems()
        val (playable, excluded) = stubItems.partition { !it.isExcluded }
        queue.clear()
        previousItems.clear()
        queue.addAll(playable)
        currentItem = if (queue.isNotEmpty()) queue.removeAt(0) else null
        updateState {
            it.copy(
                currentItem = currentItem,
                upNext = queue.toList(),
                excludedItems = excluded,
                currentDownload = findDownloadFor(currentItem, latestDownloads),
                hasNext = queue.isNotEmpty(),
                hasPrevious = previousItems.isNotEmpty()
            )
        }
        publishAnalytics(
            PlaybackAnalyticsEvent.QueueHydrated(
                totalItems = stubItems.size,
                excludedItems = excluded.size,
                firstItem = currentItem
            )
        )
        currentItem?.let {
            publishAnalytics(PlaybackAnalyticsEvent.PlaybackStarted(it, PlaybackStartReason.AUTO))
            resolveStreamFor(it, PlaybackStartReason.AUTO)
        }
    }

    private fun applyQueueState() {
        // PR6.6: hasNext is true if queue has items OR if more pages can be loaded
        val pagingState = playlistPagingState
        val hasMorePages = pagingState?.hasMore == true && !pagingState.pagingFailed

        updateState { state ->
            state.copy(
                currentItem = currentItem,
                upNext = queue.toList(),
                currentDownload = findDownloadFor(currentItem, latestDownloads),
                hasNext = queue.isNotEmpty() || hasMorePages,
                hasPrevious = previousItems.isNotEmpty()
            )
        }
    }

    /**
     * Retry resolving the current stream after an error.
     * Called from UI when user taps retry button.
     */
    fun retryCurrentStream() {
        val item = currentItem ?: return
        resolveStreamFor(item, PlaybackStartReason.USER_SELECTED, forceRefresh = false)
    }

    /**
     * Force re-resolve stream URLs, bypassing the cache.
     * Called from PlayerFragment when user manually triggers refresh.
     *
     * PR5: Rate-limited to prevent excessive extraction calls.
     * Uses MANUAL request kind with strict limits.
     *
     * @return true if refresh was initiated, false if rate-limited
     */
    fun forceRefreshCurrentStream(): Boolean {
        return forceRefreshCurrentStreamWithKind(ExtractionRateLimiter.RequestKind.MANUAL)
    }

    /**
     * Force re-resolve stream URLs for automatic recovery.
     * Called from PlaybackRecoveryManager during REFRESH_URLS step.
     *
     * PR5: Uses AUTO_RECOVERY request kind with reserved budget that won't be
     * blocked by manual refresh limits - ensures recovery can always proceed.
     *
     * @return true if refresh was initiated, false if rate-limited
     */
    fun forceRefreshForAutoRecovery(): Boolean {
        return forceRefreshCurrentStreamWithKind(ExtractionRateLimiter.RequestKind.AUTO_RECOVERY)
    }

    /**
     * Internal implementation for force refresh with configurable request kind.
     */
    private fun forceRefreshCurrentStreamWithKind(kind: ExtractionRateLimiter.RequestKind): Boolean {
        val item = currentItem ?: return false

        // Cancel any pending delayed refresh job
        pendingRefreshJob?.cancel()
        pendingRefreshJob = null

        // Capture streamId at call time for validation in delayed execution
        val targetStreamId = item.streamId

        // PR5: Acquire permit - records attempt BEFORE extraction
        when (val result = rateLimiter.acquire(targetStreamId, kind)) {
            is ExtractionRateLimiter.RateLimitResult.Allowed -> {
                // Proceed immediately
                forceRefreshCurrentStreamInternal(item)
                return true
            }
            is ExtractionRateLimiter.RateLimitResult.Delayed -> {
                android.util.Log.w("PlayerViewModel", "Force refresh ($kind) delayed: ${result.reason}, wait ${result.delayMs}ms")
                // Schedule delayed refresh with streamId validation
                pendingRefreshJob = viewModelScope.launch(dispatcher) {
                    kotlinx.coroutines.delay(result.delayMs)
                    // Validate streamId hasn't changed during delay
                    val currentStreamId = currentItem?.streamId
                    if (currentStreamId != targetStreamId) {
                        android.util.Log.d("PlayerViewModel", "Delayed refresh cancelled: video changed from $targetStreamId to $currentStreamId")
                        return@launch
                    }
                    // Re-acquire permit for delayed execution
                    val newResult = rateLimiter.acquire(targetStreamId, kind)
                    if (newResult is ExtractionRateLimiter.RateLimitResult.Allowed) {
                        currentItem?.let { forceRefreshCurrentStreamInternal(it) }
                    } else {
                        android.util.Log.w("PlayerViewModel", "Delayed refresh still blocked after delay: $newResult")
                    }
                }
                return true // Refresh scheduled
            }
            is ExtractionRateLimiter.RateLimitResult.Blocked -> {
                android.util.Log.e("PlayerViewModel", "Force refresh ($kind) BLOCKED: ${result.reason}, retry after ${result.retryAfterMs}ms")
                return false
            }
        }
    }

    private fun forceRefreshCurrentStreamInternal(item: UpNextItem) {
        // Invalidate the prefetch cache as well since URLs are likely stale
        synchronized(prefetchCache) { prefetchCache.remove(item.streamId) }
        resolveStreamFor(item, PlaybackStartReason.USER_SELECTED, forceRefresh = true)
    }

    /**
     * Retry resolving the current stream (same as retryCurrentStream).
     * Legacy method name retained for UI compatibility.
     *
     * Note: Audio-only fallback is intentionally not applied automatically. Video playback should
     * remain the default and recovery should focus on re-resolving streams / adjusting quality.
     */
    fun retryWithAudioOnly() {
        retryCurrentStream()
    }

    private fun resolveStreamFor(
        item: UpNextItem,
        @Suppress("UNUSED_PARAMETER") reason: PlaybackStartReason,
        forceRefresh: Boolean = false
    ) {
        resolveJob?.cancel()
        updateState { it.copy(streamState = StreamState.Loading, retryCount = 0) }
        resolveJob = viewModelScope.launch(dispatcher) {
            resolveWithRetry(item, maxAttempts = MAX_RETRY_ATTEMPTS, forceRefresh = forceRefresh)
        }
    }

    /**
     * PR6.6: Handle stream resolution failure with auto-skip in playlist mode.
     * If in playlist mode and consecutive skip limit not reached, auto-advances to next item.
     *
     * @param item The item that failed to resolve
     * @return true if auto-skip was triggered, false if error state should be shown
     */
    private fun handleStreamResolutionFailure(item: UpNextItem): Boolean {
        if (!isPlaylistMode) return false

        consecutiveSkips++
        if (consecutiveSkips > MAX_CONSECUTIVE_SKIPS) {
            android.util.Log.w("PlayerViewModel", "Auto-skip limit reached ($MAX_CONSECUTIVE_SKIPS consecutive failures)")
            consecutiveSkips = 0  // Reset for next attempt
            return false
        }

        android.util.Log.d("PlayerViewModel", "Auto-skipping unplayable video ${item.streamId} ($consecutiveSkips/$MAX_CONSECUTIVE_SKIPS)")

        // Emit UI event for toast
        _analyticsEvents.tryEmit(PlaybackAnalyticsEvent.VideoSkipped(item, consecutiveSkips))

        // Advance to next item
        return advanceToNext(PlaybackStartReason.AUTO, markComplete = false)
    }

    /**
     * Resolve streams with exponential backoff retry.
     * Checks tap-to-prefetch service first (awaiting in-flight if needed), then local prefetch cache.
     * Attempts: 3 times with delays of 1s, 2s, 4s between attempts.
     *
     * @param forceRefresh If true, bypass all caches (prefetch and stream URL cache)
     */
    private suspend fun resolveWithRetry(item: UpNextItem, maxAttempts: Int, forceRefresh: Boolean = false) {
        // Check tap-to-prefetch service first (triggered when user taps video in list)
        // This will await in-flight prefetch for up to 3 seconds, providing in-flight dedupe
        if (!forceRefresh) {
            val tapPrefetched = prefetchService.awaitOrConsumePrefetch(item.streamId)
            if (tapPrefetched != null) {
                android.util.Log.d("PlayerViewModel", "Using tap-prefetched stream for ${item.streamId}")
                val selection = tapPrefetched.toDefaultSelection()
                if (selection != null) {
                    publishAnalytics(PlaybackAnalyticsEvent.StreamResolved(item.streamId, selection.video?.qualityLabel))
                    updateState { it.copy(streamState = StreamState.Ready(tapPrefetched.streamId, selection), retryCount = 0) }
                    return
                }
                android.util.Log.w("PlayerViewModel", "Tap-prefetched stream invalid, checking local cache")
            }
        }

        // Check local prefetch cache (for queue items) - skip if forceRefresh
        if (!forceRefresh) {
            val prefetched = synchronized(prefetchCache) { prefetchCache.remove(item.streamId) }
            if (prefetched != null) {
                android.util.Log.d("PlayerViewModel", "Using queue-prefetched stream for ${item.streamId}")
                val selection = prefetched.toDefaultSelection()
                if (selection != null) {
                    publishAnalytics(PlaybackAnalyticsEvent.StreamResolved(item.streamId, selection.video?.qualityLabel))
                    updateState { it.copy(streamState = StreamState.Ready(prefetched.streamId, selection), retryCount = 0) }
                    return
                }
                // Prefetch data was invalid, fall through to normal resolution
                android.util.Log.w("PlayerViewModel", "Queue-prefetched stream invalid, resolving fresh")
            }
        } else {
            android.util.Log.d("PlayerViewModel", "Force refresh requested for ${item.streamId}, bypassing caches")
        }

        for (attempt in 1..maxAttempts) {
            updateState { it.copy(retryCount = attempt - 1) }

            val resolved = try {
                // Add timeout wrapper to prevent indefinite hangs during extraction
                // Use forceRefresh on first attempt to bypass stream URL cache
                kotlinx.coroutines.withTimeout(EXTRACTOR_TIMEOUT_MS) {
                    repository.resolveStreams(item.streamId, forceRefresh = forceRefresh && attempt == 1)
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                val errorMessage = when (t) {
                    is kotlinx.coroutines.TimeoutCancellationException -> "Timed out after ${EXTRACTOR_TIMEOUT_MS/1000}s"
                    else -> t.message
                }
                android.util.Log.w("PlayerViewModel", "Stream resolve attempt $attempt failed: $errorMessage")

                if (attempt < maxAttempts) {
                    // Exponential backoff: 1s, 2s, 4s
                    val delayMs = RETRY_BASE_DELAY_MS * (1 shl (attempt - 1))
                    android.util.Log.d("PlayerViewModel", "Retrying in ${delayMs}ms...")
                    kotlinx.coroutines.delay(delayMs)
                    continue
                } else {
                    // All retries exhausted - try auto-skip in playlist mode
                    publishAnalytics(PlaybackAnalyticsEvent.StreamFailed(item.streamId))
                    if (handleStreamResolutionFailure(item)) {
                        return  // Auto-skipped to next item
                    }
                    updateState { it.copy(streamState = StreamState.Error(R.string.player_stream_error)) }
                    return
                }
            }

            if (resolved == null) {
                android.util.Log.w("PlayerViewModel", "Stream resolved to null on attempt $attempt")
                if (attempt < maxAttempts) {
                    val delayMs = RETRY_BASE_DELAY_MS * (1 shl (attempt - 1))
                    kotlinx.coroutines.delay(delayMs)
                    continue
                } else {
                    // All retries exhausted - try auto-skip in playlist mode
                    publishAnalytics(PlaybackAnalyticsEvent.StreamFailed(item.streamId))
                    if (handleStreamResolutionFailure(item)) {
                        return  // Auto-skipped to next item
                    }
                    updateState { it.copy(streamState = StreamState.Error(R.string.player_stream_unavailable)) }
                    return
                }
            }

            val selection = resolved.toDefaultSelection()
            if (selection == null) {
                updateState { it.copy(streamState = StreamState.Error(R.string.player_stream_unavailable)) }
                publishAnalytics(PlaybackAnalyticsEvent.StreamFailed(item.streamId))
                return
            }

            // Success!
            android.util.Log.d("PlayerViewModel", "Stream resolved successfully on attempt $attempt")
            // PR5: Signal success to reset backoff state (attempt was already recorded in acquire())
            rateLimiter.onExtractionSuccess(item.streamId)
            publishAnalytics(PlaybackAnalyticsEvent.StreamResolved(item.streamId, selection.video?.qualityLabel))
            updateState { it.copy(streamState = StreamState.Ready(resolved.streamId, selection), retryCount = 0) }

            // Apply pending quality cap if set (stored when URLs expired during quality switch)
            val pendingCap = pendingQualityCap
            if (pendingCap != null) {
                pendingQualityCap = null
                android.util.Log.d("PlayerViewModel", "Applying pending quality cap: ${pendingCap.qualityLabel}")
                // Use post to ensure state is updated before applying cap
                setUserQualityCap(pendingCap)
            }
            return
        }
    }

    /**
     * Call when playback starts successfully to reset rate limit backoff state.
     * This allows future refreshes without exponential backoff penalty.
     */
    fun onPlaybackSuccess(videoId: String) {
        rateLimiter.resetForVideo(videoId)
    }

    /**
     * Prefetch streams for the next items in the queue.
     * Called when current video starts playing to reduce wait time for next video.
     *
     * PR5: Rate-limited with PREFETCH kind - lowest priority, skipped if budget pressure.
     */
    fun prefetchNextItems() {
        prefetchJob?.cancel()
        // Snapshot the queue on Main thread before switching to IO for thread safety
        val itemsToPrefetch = queue.take(maxPrefetchItems).toList()
        if (itemsToPrefetch.isEmpty()) return

        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            for (item in itemsToPrefetch) {
                val alreadyCached = synchronized(prefetchCache) { prefetchCache.containsKey(item.streamId) }
                if (alreadyCached) {
                    android.util.Log.d("PlayerViewModel", "Prefetch: ${item.streamId} already cached")
                    continue
                }

                // PR5: Acquire permit with PREFETCH kind - lowest priority, can be skipped
                when (val result = rateLimiter.acquire(item.streamId, ExtractionRateLimiter.RequestKind.PREFETCH)) {
                    is ExtractionRateLimiter.RateLimitResult.Allowed -> {
                        // Proceed with prefetch
                    }
                    is ExtractionRateLimiter.RateLimitResult.Delayed -> {
                        android.util.Log.d("PlayerViewModel", "Prefetch: Skipping ${item.streamId} (rate limited: ${result.reason})")
                        continue // Skip this item, try next - don't wait for prefetch
                    }
                    is ExtractionRateLimiter.RateLimitResult.Blocked -> {
                        android.util.Log.d("PlayerViewModel", "Prefetch: Skipping ${item.streamId} (blocked: ${result.reason})")
                        continue // Skip this item, try next
                    }
                }

                try {
                    android.util.Log.d("PlayerViewModel", "Prefetch: Starting for ${item.streamId}")
                    val resolved = repository.resolveStreams(item.streamId)
                    if (resolved != null) {
                        // PR5: Signal success to reset backoff state
                        rateLimiter.onExtractionSuccess(item.streamId)
                        synchronized(prefetchCache) {
                            // Evict old entries if cache is full
                            if (prefetchCache.size >= maxPrefetchItems * 2) {
                                val oldest = prefetchCache.keys.firstOrNull()
                                oldest?.let { prefetchCache.remove(it) }
                            }
                            prefetchCache[item.streamId] = resolved
                        }
                        android.util.Log.d("PlayerViewModel", "Prefetch: Completed for ${item.streamId}")
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    android.util.Log.w("PlayerViewModel", "Prefetch failed for ${item.streamId}: ${e.message}")
                    // Don't propagate - prefetch failures are non-fatal
                }
            }
        }
    }

    companion object {
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_BASE_DELAY_MS = 1000L
        private const val EXTRACTOR_TIMEOUT_MS = 20000L // 20s timeout for extraction (NewPipe can be slow)

        // PR6.6: Playlist paging constants
        private const val PAGE_FETCH_TIMEOUT_MS = 10000L  // 10s timeout per page fetch
        private const val PAGE_FETCH_COOLDOWN_MS = 2000L  // 2s minimum gap between page fetches
        private const val MAX_ITEMS_TO_SCAN = 250         // Max items to scan for deep start
        private const val MAX_SCAN_TIME_MS = 3000L        // Max time for deep start scanning
        private const val QUEUE_PREFETCH_THRESHOLD = 5    // Trigger background fetch when queue drops below this
        private const val MAX_CONSECUTIVE_SKIPS = 3       // Max auto-skips for unplayable items
    }

    private fun findDownloadFor(item: UpNextItem?, entries: List<DownloadEntry>): DownloadEntry? {
        return item?.let { current ->
            entries.firstOrNull { it.request.videoId == current.streamId }
        }
    }

    private fun publishAnalytics(event: PlaybackAnalyticsEvent) {
        _analyticsEvents.tryEmit(event)
        updateState { it.copy(lastAnalyticsEvent = event) }
    }

    private fun updateState(transform: (PlayerState) -> PlayerState) {
        _state.value = transform(_state.value)
    }

    private fun advanceToNext(reason: PlaybackStartReason, markComplete: Boolean): Boolean {
        val finished = currentItem ?: return false
        // PR5: Cancel any pending delayed refresh for the old video
        pendingRefreshJob?.cancel()
        pendingRefreshJob = null
        if (markComplete) {
            publishAnalytics(PlaybackAnalyticsEvent.PlaybackCompleted(finished))
        }

        // PR6.6: If queue is empty but more pages exist, fetch asynchronously (no runBlocking)
        if (queue.isEmpty() && playlistPagingState?.hasMore == true && !playlistPagingState!!.pagingFailed) {
            android.util.Log.d("PlayerViewModel", "advanceToNext: queue empty, fetching more items async")
            addToHistory(finished)
            currentItem = null
            updateState { it.copy(streamState = StreamState.Loading, currentItem = null) }

            // Launch async page fetch, then continue playback
            viewModelScope.launch(dispatcher) {
                val loaded = fetchNextPlaylistPage()
                if (loaded && queue.isNotEmpty()) {
                    val next = queue.removeAt(0)
                    currentItem = next
                    consecutiveSkips = 0
                    applyQueueState()
                    publishAnalytics(PlaybackAnalyticsEvent.PlaybackStarted(next, reason))
                    resolveStreamFor(next, reason)
                } else {
                    // Failed to load more or still empty - end of playlist
                    android.util.Log.w("PlayerViewModel", "advanceToNext: async fetch failed or empty")
                    applyQueueState()
                    updateState { it.copy(streamState = StreamState.Idle) }
                }
            }
            return true // Indicate we're handling the advance asynchronously
        }

        val next = if (queue.isNotEmpty()) queue.removeAt(0) else null
        currentItem = next
        addToHistory(finished)
        applyQueueState()

        // PR6.6: Trigger background prefetch if queue is getting low
        if (queue.size <= QUEUE_PREFETCH_THRESHOLD && playlistPagingState?.hasMore == true) {
            triggerBackgroundPageFetch()
        }

        return if (next != null) {
            consecutiveSkips = 0  // Reset on successful advance
            publishAnalytics(PlaybackAnalyticsEvent.PlaybackStarted(next, reason))
            resolveStreamFor(next, reason)
            true
        } else {
            updateState { state -> state.copy(streamState = StreamState.Idle) }
            false
        }
    }

    /**
     * PR6.6: Trigger background page fetch if queue is running low.
     * Single-flight: only one fetch at a time.
     */
    private fun triggerBackgroundPageFetch() {
        if (pagingJob?.isActive == true) return  // Already fetching

        pagingJob = viewModelScope.launch(dispatcher) {
            fetchNextPlaylistPage()
        }
    }

    /**
     * PR6.6: Fetch the next page of playlist items.
     * Thread-safe via pagingMutex. Updates queue and paging state.
     *
     * @return true if page was fetched and items added, false otherwise
     */
    private suspend fun fetchNextPlaylistPage(): Boolean {
        val state = playlistPagingState ?: return false
        if (state.nextPage == null || state.pagingFailed) return false

        // Simple cooldown check
        val now = System.currentTimeMillis()
        val timeSinceLastFetch = now - state.lastPageFetchMs
        if (timeSinceLastFetch < PAGE_FETCH_COOLDOWN_MS) {
            kotlinx.coroutines.delay(PAGE_FETCH_COOLDOWN_MS - timeSinceLastFetch)
        }

        return pagingMutex.withLock {
            // Re-check state after acquiring lock (may have changed)
            val currentState = playlistPagingState ?: return@withLock false
            if (currentState.nextPage == null || currentState.pagingFailed) return@withLock false

            android.util.Log.d("PlayerViewModel", "fetchNextPlaylistPage: loading more items, offset=${currentState.nextItemOffset}")

            val page = try {
                withTimeoutOrNull(PAGE_FETCH_TIMEOUT_MS) {
                    playlistDetailRepository.getItems(
                        currentState.playlistId,
                        currentState.nextPage,
                        currentState.nextItemOffset
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("PlayerViewModel", "fetchNextPlaylistPage: error ${e.message}")
                null
            }

            if (page == null) {
                android.util.Log.w("PlayerViewModel", "fetchNextPlaylistPage: failed (timeout or error)")
                playlistPagingState = currentState.copy(pagingFailed = true)
                applyQueueState()
                return@withLock false
            }

            // Convert and add to queue
            val newItems = page.items.map { playlistItem ->
                UpNextItem(
                    id = playlistItem.videoId,
                    title = playlistItem.title,
                    channelName = playlistItem.channelName ?: "",
                    durationSeconds = playlistItem.durationSeconds ?: 0,
                    streamId = playlistItem.videoId,
                    thumbnailUrl = playlistItem.thumbnailUrl,
                    viewCount = playlistItem.viewCount
                )
            }

            queue.addAll(newItems)
            playlistPagingState = currentState.copy(
                nextPage = page.nextPage,
                nextItemOffset = page.nextItemOffset,
                hasMore = page.nextPage != null,
                lastPageFetchMs = System.currentTimeMillis()
            )

            android.util.Log.d("PlayerViewModel", "fetchNextPlaylistPage: added ${newItems.size} items, queue now ${queue.size}, hasMore=${page.nextPage != null}")
            applyQueueState()
            true
        }
    }

    /** Add item to history, maintaining max size limit */
    private fun addToHistory(item: UpNextItem) {
        previousItems.add(item)
        while (previousItems.size > maxHistorySize) {
            previousItems.removeAt(0)
        }
    }

    /** Add multiple items to history, maintaining max size limit */
    private fun addAllToHistory(items: List<UpNextItem>) {
        previousItems.addAll(items)
        while (previousItems.size > maxHistorySize) {
            previousItems.removeAt(0)
        }
    }

    // --- Recovery State Management ---

    /**
     * Transition to Recovering state for UI to show recovery overlay.
     * Preserves the underlying stream/selection so playback can continue.
     */
    fun setRecoveringState(
        streamId: String,
        selection: PlaybackSelection,
        step: RecoveryStep,
        attempt: Int
    ) {
        updateState { it.copy(streamState = StreamState.Recovering(streamId, selection, step, attempt)) }
    }

    /**
     * Clear recovering state and return to Ready state.
     * Called when recovery succeeds.
     */
    fun clearRecoveringState() {
        val current = _state.value.streamState
        when (current) {
            is StreamState.Recovering -> {
                updateState { it.copy(streamState = StreamState.Ready(current.streamId, current.selection)) }
            }
            is StreamState.RecoveryExhausted -> {
                updateState { it.copy(streamState = StreamState.Ready(current.streamId, current.selection)) }
            }
            else -> { /* No-op for other states */ }
        }
    }

    /**
     * Transition to RecoveryExhausted state when all automatic recovery attempts fail.
     * Called by PlaybackRecoveryManager.onRecoveryExhausted callback.
     */
    fun setRecoveryExhaustedState() {
        val current = _state.value.streamState
        val (streamId, selection) = when (current) {
            is StreamState.Recovering -> current.streamId to current.selection
            is StreamState.Ready -> current.streamId to current.selection
            else -> return // Can't transition from Idle/Loading/Error
        }
        updateState { it.copy(streamState = StreamState.RecoveryExhausted(streamId, selection)) }
    }

    /**
     * Set error state for UI to show error overlay.
     * Called when recovery is exhausted.
     */
    fun setErrorState(@StringRes messageRes: Int) {
        updateState { it.copy(streamState = StreamState.Error(messageRes)) }
    }

}

data class PlayerState(
    val audioOnly: Boolean = false,
    val hasVideoTrack: Boolean = true,
    val currentItem: UpNextItem? = null,
    val upNext: List<UpNextItem> = emptyList(),
    val excludedItems: List<UpNextItem> = emptyList(),
    val currentDownload: DownloadEntry? = null,
    val streamState: StreamState = StreamState.Idle,
    val selectedSubtitle: SubtitleTrack? = null,
    val lastAnalyticsEvent: PlaybackAnalyticsEvent? = null,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    /** Current retry attempt (0 = first attempt, 1 = first retry, etc.) */
    val retryCount: Int = 0,
    /** Whether the current video is in favorites */
    val isFavorite: Boolean = false
)

data class UpNextItem(
    val id: String,
    val title: String,
    val channelName: String,
    val durationSeconds: Int,
    val isExcluded: Boolean = false,
    val exclusionReason: String? = null,
    val streamId: String,
    val thumbnailUrl: String? = null,
    val description: String? = null,
    val viewCount: Long? = null
)

sealed class StreamState {
    object Idle : StreamState()
    object Loading : StreamState()
    data class Ready(val streamId: String, val selection: PlaybackSelection) : StreamState()
    data class Error(@StringRes val messageRes: Int) : StreamState()
    /**
     * Automatic recovery in progress. UI should show "Recovering..." overlay.
     * Contains the underlying Ready state so playback can continue while recovering.
     */
    data class Recovering(
        val streamId: String,
        val selection: PlaybackSelection,
        val step: RecoveryStep,
        val attempt: Int
    ) : StreamState()

    /**
     * All automatic recovery attempts exhausted. UI should show manual retry option.
     * Contains the underlying selection so user can trigger manual retry.
     */
    data class RecoveryExhausted(
        val streamId: String,
        val selection: PlaybackSelection
    ) : StreamState()
}

/**
 * Recovery steps for automatic playback recovery.
 */
enum class RecoveryStep {
    RE_PREPARE,
    SEEK_TO_CURRENT,
    QUALITY_DOWNSHIFT,
    REFRESH_URLS,
    REBUILD_PLAYER
}

data class QualityOption(
    val label: String,
    val track: VideoTrack
)

/**
 * PR6.6: Tracks playlist paging state for lazy loading.
 * Enables Next/Prev to work across playlist page boundaries.
 */
data class PlaylistPagingState(
    val playlistId: String,
    val nextPage: Page?,
    val nextItemOffset: Int,
    val hasMore: Boolean,
    val pagingFailed: Boolean,
    val lastPageFetchMs: Long
)

sealed class PlaybackAnalyticsEvent {
    data class QueueHydrated(
        val totalItems: Int,
        val excludedItems: Int,
        val firstItem: UpNextItem?
    ) : PlaybackAnalyticsEvent()

    data class PlaybackStarted(
        val item: UpNextItem,
        val reason: PlaybackStartReason
    ) : PlaybackAnalyticsEvent()

    data class PlaybackCompleted(val item: UpNextItem) : PlaybackAnalyticsEvent()

    data class AudioOnlyToggled(val enabled: Boolean) : PlaybackAnalyticsEvent()

    data class StreamResolved(val streamId: String, val qualityLabel: String?) : PlaybackAnalyticsEvent()

    data class StreamFailed(val streamId: String) : PlaybackAnalyticsEvent()

    data class QualityChanged(val qualityLabel: String) : PlaybackAnalyticsEvent()

    data class SubtitleChanged(val languageName: String) : PlaybackAnalyticsEvent()

    /** PR6.6: Emitted when an unplayable video is auto-skipped in playlist mode */
    data class VideoSkipped(val item: UpNextItem, val consecutiveSkipCount: Int) : PlaybackAnalyticsEvent()
}

enum class PlaybackStartReason(@StringRes val labelRes: Int) {
    AUTO(R.string.player_start_reason_auto),
    USER_SELECTED(R.string.player_start_reason_user_selected),
    RESUME(R.string.player_start_reason_resume)
}

/**
 * Smart quality selection based on available tracks.
 * - When adaptive manifests are available (HLS/DASH): 720p is a good UI-default reference.
 * - When only progressive is available: prefer a more conservative default (480p muxed) to reduce
 *   startup stalls on slower connections (progressive cannot ABR).
 */
private fun ResolvedStreams.toDefaultSelection(): PlaybackSelection? {
    if (videoTracks.isEmpty()) return null

    val hasAdaptiveManifest = !hlsUrl.isNullOrBlank() || !dashUrl.isNullOrBlank()

    val preferredVideo = if (hasAdaptiveManifest) {
        // Smart quality selection: prefer 720p for balance; fallback to best available
        videoTracks.firstOrNull { it.height == 720 && !it.isVideoOnly }
            ?: videoTracks.firstOrNull { it.height == 480 && !it.isVideoOnly }
            ?: videoTracks.firstOrNull { it.height == 720 }
            ?: videoTracks.firstOrNull { it.height == 480 }
            ?: videoTracks.maxWithOrNull(
                compareBy<VideoTrack> { it.height ?: 0 }
                    .thenBy { it.bitrate ?: 0 }
            )
    } else {
        // Progressive-only: prefer conservative muxed tracks first to avoid startup buffering.
        videoTracks.firstOrNull { it.height == 480 && !it.isVideoOnly }
            ?: videoTracks.firstOrNull { it.height == 360 && !it.isVideoOnly }
            ?: videoTracks.firstOrNull { it.height == 720 && !it.isVideoOnly }
            ?: videoTracks.filter { !it.isVideoOnly && (it.height ?: 0) >= 240 }.minByOrNull { it.height ?: Int.MAX_VALUE }
            ?: videoTracks.firstOrNull { it.height == 480 }
            ?: videoTracks.firstOrNull { it.height == 360 }
            ?: videoTracks.maxWithOrNull(
                compareBy<VideoTrack> { it.height ?: 0 }
                    .thenBy { it.bitrate ?: 0 }
            )
    }

    val preferredAudio = (audioTracks.maxByOrNull { it.bitrate ?: 0 }
        ?: preferredVideo?.let {
            AudioTrack(
                url = it.url,
                mimeType = it.mimeType,
                bitrate = it.bitrate,
                codec = null
            )
        }) ?: return null
    return PlaybackSelection(streamId, preferredVideo, preferredAudio, this)
}

private fun stubUpNextItems(): List<UpNextItem> = emptyList()

/**
 * UI events emitted by PlayerViewModel for user feedback.
 * Collected by PlayerFragment to show toasts/snackbars.
 */
sealed class PlayerUiEvent {
    /**
     * Emitted when toggling favorite status fails.
     *
     * @param videoId The video ID that failed to toggle
     * @param messageRes String resource ID for localized error message
     * @param canRetry True if user can retry the operation
     */
    data class FavoriteToggleFailed(
        val videoId: String,
        @StringRes val messageRes: Int,
        val canRetry: Boolean
    ) : PlayerUiEvent()
}
