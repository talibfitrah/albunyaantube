package com.albunyaan.tube.ui.player

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.R
import com.albunyaan.tube.analytics.ExtractorMetricsReporter
import com.albunyaan.tube.analytics.PlaybackMetricsCollector
import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.AudioTrackSource
import com.albunyaan.tube.data.extractor.DubAudioEnumerator
import com.albunyaan.tube.data.extractor.DubAudioResolver
import com.albunyaan.tube.data.extractor.DubLanguage
import com.albunyaan.tube.data.extractor.ExtractionClient
import com.albunyaan.tube.data.extractor.PlaybackSelection
import com.albunyaan.tube.data.extractor.Priority
import com.albunyaan.tube.data.extractor.QualitySelectionOrigin
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.SubtitleTrack
import com.albunyaan.tube.data.extractor.VideoTrack
import com.albunyaan.tube.data.extractor.availableAudioLanguages
import com.albunyaan.tube.data.extractor.withDubLanguages
import com.albunyaan.tube.data.local.FavoritesRepository
import com.albunyaan.tube.download.DownloadEntry
import com.albunyaan.tube.download.DownloadRepository
import com.albunyaan.tube.download.DownloadRequest
import com.albunyaan.tube.player.ExtractionRateLimiter
import com.albunyaan.tube.player.PlayerRepository
import com.albunyaan.tube.player.StreamPrefetchService
import com.albunyaan.tube.player.SyntheticDashMpdRegistry
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import com.albunyaan.tube.data.channel.Page
import com.albunyaan.tube.data.extractor.ExtractorClient
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
import kotlinx.coroutines.delay
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
    private val metricsReporter: ExtractorMetricsReporter,
    private val playbackMetrics: PlaybackMetricsCollector,
    private val mpdRegistry: SyntheticDashMpdRegistry,
    private val extractorClient: ExtractorClient,
    private val dubAudioEnumerator: DubAudioEnumerator,
    private val dubAudioResolver: DubAudioResolver,
    @javax.inject.Named("real") private val contentService: com.albunyaan.tube.data.source.ContentService,
) : ViewModel() {

    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate

    /** Cached dub languages per videoId (so a URL-refresh re-resolve keeps the globe lit). */
    private val dubCache = java.util.concurrent.ConcurrentHashMap<String, List<DubLanguage>>()
    private val dubEnumerating = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Last fully-resolved dub (URL + nsig + pot) per "videoId|lang", so a re-attach after a refresh
     * reuses it instantly instead of re-running nsig (~3 s) — that re-resolve was the visible English
     * blip during the flap. The cached URL stays valid ~6 h (its own expire); if it ever 403s the
     * normal dub-failure path falls back to VR original.
     */
    private val resolvedDubCache = java.util.concurrent.ConcurrentHashMap<String, AudioTrack>()

    /**
     * Fire-and-forget dub enumeration for a freshly-resolved single-audio stream
     * (the VR-primary case the globe regression is about). If 2+ languages exist,
     * re-emit Ready with lazy WEB_DUB tracks appended so the globe lights up. Never
     * blocks playback; the re-emit is a fragment cache hit (no re-prepare — verified
     * against checkCacheHit, which ignores audioTracks). Cached so a URL-refresh
     * re-resolve re-applies without a second network call.
     */
    private fun maybeEnumerateDubs(selection: PlaybackSelection) {
        val resolved = selection.resolved
        android.util.Log.d("DubFlow", "maybeEnumerateDubs ${resolved.streamId} audioTracks=${resolved.audioTracks.size} langs=${resolved.availableAudioLanguages().size}")
        // Skip only if the resolve ALREADY exposes 2+ distinct languages. VR returns the single
        // original language as multiple format variants (m4a/webm × bitrates) — so a track-count
        // guard (size > 1) wrongly skips the enumerate on every dubbed video.
        if (resolved.availableAudioLanguages().size >= 2) return
        val streamId = resolved.streamId
        dubCache[streamId]?.let { applyDubLanguages(streamId, it); return }
        if (!dubEnumerating.add(streamId)) return
        viewModelScope.launch(dispatcher) {
            try {
                val dubs = dubAudioEnumerator.enumerate(streamId)
                if (dubs.size < 2) return@launch
                dubCache[streamId] = dubs
                applyDubLanguages(streamId, dubs)
            } finally {
                // Always release the in-flight guard — a bare remove() leaks the id on coroutine
                // cancellation (user leaves mid-enumerate), permanently suppressing re-enumeration.
                dubEnumerating.remove(streamId)
            }
        }
    }

    private fun applyDubLanguages(streamId: String, dubs: List<DubLanguage>) {
        val cur = _state.value.streamState
        if (cur is StreamState.Ready && cur.streamId == streamId &&
            cur.selection.resolved.audioTracks.none { it.source == AudioTrackSource.WEB_DUB }
        ) {
            val augmented = cur.selection.copy(resolved = cur.selection.resolved.withDubLanguages(dubs))
            updateState { it.copy(streamState = StreamState.Ready(streamId, augmented)) }
            android.util.Log.d(
                "DubFlow",
                "dub globe lit for $streamId: ${dubs.size} dubs -> audioTracks=${augmented.resolved.audioTracks.size}"
            )
            // Build-order step 4 (prewarm): resolve ALL languages once now (1 nsig + 1 pot covers all),
            // so a language pick skips the ~3 s per-switch nsig and feels instant. Fire once per video.
            if (resolvedDubCache.keys.none { it.startsWith("$streamId|") }) {
                viewModelScope.launch(dispatcher) {
                    dubAudioResolver.resolveAllDubAudio(streamId).forEach { dub ->
                        dub.language?.let { resolvedDubCache["$streamId|$it"] = dub }
                    }
                }
            }
            // Build-order step 3 — dub-aware refresh: a URL-refresh re-resolve (TTL / 403 / stall)
            // re-resolves the VR original only, so the selected dub silently reverts to English
            // ("played briefly then fell back"). Now that the globe is re-lit with the lazy WEB_DUB
            // tracks, re-attach the sticky dub so it persists. The line-113 guard (only runs when no
            // WEB_DUB present) breaks the re-merge → swap → rebuild cycle, so this can't loop.
            val sticky = stickyAudioLanguage
            if (sticky != null) {
                // Prefer the cached RESOLVED dub (URL present): selectWebDubTrack reuses it without
                // re-running nsig (~3 s), so the re-attach is instant and the flap stops being visible.
                // Fall back to the lazy (URL-less) track, which re-resolves, if nothing is cached yet.
                val cached = resolvedDubCache["$streamId|$sticky"]
                val reattach = cached ?: augmented.resolved.audioTracks.firstOrNull {
                    it.source == AudioTrackSource.WEB_DUB && it.language == sticky
                }
                reattach?.let {
                    android.util.Log.d("DubFlow", "re-attaching sticky dub lang=$sticky (cached=${cached != null})")
                    selectWebDubTrack(it)
                }
            }
        } else {
            android.util.Log.d("DubFlow", "applyDubLanguages SKIP $streamId dubs=${dubs.size} curReady=${cur is StreamState.Ready}")
        }
    }

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

    /**
     * Cached prefetch result paired with the wall-clock timestamp at which it
     * was stored. Drives the [PREFETCH_CACHE_TTL_MS] eviction inside
     * [resolveWithRetry] so an admin archive that lands AFTER prefetch resolved
     * the streams can't keep playing the archived video forever.
     */
    private data class CachedPrefetch(val streams: ResolvedStreams, val cachedAtMs: Long)

    // Prefetch cache: stores resolved streams for next items, with TTL for archive safety (N9 fix)
    private val prefetchCache = mutableMapOf<String, CachedPrefetch>()
    private val maxPrefetchItems = 2

    /**
     * Time source for prefetch-cache TTL bookkeeping. Defaults to wall-clock.
     * Tests replace this via [setClockForTesting] to drive the TTL clock
     * deterministically without depending on `runTest` virtual time.
     */
    @Volatile
    private var clock: () -> Long = { System.currentTimeMillis() }

    /**
     * Test seam: replace the clock used for [prefetchCache] TTL eviction.
     * Production code MUST NOT call this.
     */
    @androidx.annotation.VisibleForTesting
    internal fun setClockForTesting(newClock: () -> Long) {
        clock = newClock
    }

    // Pending quality cap: stored when URLs expire during quality switch, applied after refresh
    private var pendingQualityCap: VideoTrack? = null

    // PR5: Pending refresh job for cancellation when video changes or new refresh requested
    private var pendingRefreshJob: Job? = null

    // Sticky audio language: preserves user's audio language choice across force-refreshes,
    // retries, and playlist advances. Set by selectAudioTrack, read by toSelectionWithPreferredAudio.
    // Global (session-scoped), unlike PlayerBinder.stickyAudioLanguageByVideoId which is per-video.
    @Volatile private var stickyAudioLanguage: String? = null

    /**
     * The language of the LATEST web-dub pick, set synchronously on tap. Distinct from
     * [stickyAudioLanguage] (which is pinned only after a dub successfully swaps in): a slow resolve
     * checks this before applying so a rapid A→B switch can't let A override the newer B.
     */
    @Volatile private var pendingAudioLanguage: String? = null
    private var metadataHydrationJob: Job? = null

    // Live stream proactive refresh: job that schedules URL refresh before expiration
    private var liveRefreshJob: Job? = null

    // Playlist playback state
    private var isPlaylistMode: Boolean = false

    /**
     * True only when the current playlist's parent channel is APPROVED in the registry. A standalone
     * playlist (curated playlist whose parent channel is NOT in our system) keeps this false so the
     * player never surfaces the uncurated channel name (mirrors PlaylistHeader.isChannelLinkable).
     * Fail-closed default.
     */
    private var playlistChannelApproved: Boolean = false

    /**
     * The playlist currently loaded, captured at load start. Guards the async channel-approval result
     * against playlist switches: a late approval from playlist A must not flip the gate for playlist B
     * the user has since opened.
     */
    private var activePlaylistId: String? = null

    // PR6.6: Playlist paging state for lazy loading
    private var playlistPagingState: PlaylistPagingState? = null
    private val pagingMutex = Mutex()
    private var pagingJob: Job? = null

    // PR6.6: Auto-skip tracking for unplayable items in playlist mode
    private var consecutiveSkips = 0

    // Quality switching debounce: prevents rapid re-prepares when user quickly changes quality
    private var qualitySwitchJob: Job? = null

    private val _analyticsEvents = MutableSharedFlow<PlaybackAnalyticsEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val analyticsEvents: SharedFlow<PlaybackAnalyticsEvent> = _analyticsEvents

    /** Expose playback metrics for fragment-level tracking (first frame, rebuffer, 403 errors) */
    val metrics: PlaybackMetricsCollector get() = playbackMetrics

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
        metadataHydrationJob?.cancel()
        metadataHydrationJob = null
        // Cancel live stream refresh for the old video
        liveRefreshJob?.cancel()
        liveRefreshJob = null
        current?.let { addToHistory(it) }
        currentItem = item
        applyQueueState()
        hydrateCurrentItemMetadataIfNeeded(item)
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
        metadataHydrationJob?.cancel()
        metadataHydrationJob = null
        // Cancel live stream refresh for the old video
        liveRefreshJob?.cancel()
        liveRefreshJob = null
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
            targetHeight = targetHeight,
            thumbnailUrl = item.thumbnailUrl
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
    fun getAvailableQualities(adaptiveActive: Boolean): List<QualityOption> {
        // Source from ready-OR-recovering so the picker is populated even while the player
        // is stalling/recovering — otherwise the user can't drop resolution during a stall.
        val selection = readyOrRecoveringSelection()?.selection ?: return emptyList()
        return buildQualityOptions(selection.resolved.videoTracks, selection.resolved.extractionClient, adaptiveActive)
    }

    /**
     * Label of the currently-selected video quality (ready or recovering), or null.
     * Used by the picker to highlight the active choice even during a stall.
     */
    fun currentVideoQualityLabel(): String? =
        readyOrRecoveringSelection()?.selection?.video?.qualityLabel

    /**
     * Set user quality cap (ceiling) from manual selection.
     * This treats the user's choice as a maximum resolution cap. ABR can still drop
     * below when network dips, then recover back up to the cap.
     *
     * For adaptive streaming (HLS/DASH): Applied via track selector constraints
     * For progressive streaming: Selects the best track under the cap
     *
     * Uses debouncing to prevent crashes from rapid quality switches (user clicking
     * multiple qualities quickly). Only the last selection within the debounce window
     * is applied.
     */
    fun setUserQualityCap(track: VideoTrack) {
        // Cancel any pending quality switch - only the latest selection wins
        qualitySwitchJob?.cancel()

        // Capture current streamId to verify after debounce - prevents applying
        // a quality cap to a different video if the user navigated away. Ready-or-recovering
        // so a quality change requested during a stall is still honored.
        val targetStreamId = readyOrRecoveringSelection()?.streamId

        qualitySwitchJob = viewModelScope.launch(dispatcher) {
            // Debounce: wait before applying to coalesce rapid clicks
            delay(QUALITY_SWITCH_DEBOUNCE_MS)
            applyQualityCapInternal(track, targetStreamId)
        }
    }

    /**
     * Internal implementation of quality cap application (called after debounce).
     *
     * @param track The video track representing the quality cap
     * @param targetStreamId The streamId that was active when the user requested the quality change.
     *        If this doesn't match the current streamId, the quality change is discarded
     *        to prevent applying settings to the wrong video.
     */
    private fun applyQualityCapInternal(track: VideoTrack, targetStreamId: String?) {
        // A manual quality change during a stall must still apply.
        val streamState = readyOrRecoveringSelection() ?: return

        // Verify we're still on the same video - user may have navigated during debounce
        if (targetStreamId != null && streamState.streamId != targetStreamId) {
            android.util.Log.d(
                "PlayerViewModel",
                "applyQualityCapInternal: streamId changed ($targetStreamId -> ${streamState.streamId}), discarding quality change"
            )
            return
        }

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
     * Handle decoder error by stepping down quality AND clamping the user's cap.
     *
     * Unlike the network-based auto step-down (now removed) which preserved the user's cap
     * for cases where higher quality can recover, decoder errors indicate the device
     * CANNOT play the current quality at all. We must clamp the cap to prevent the
     * track selector from immediately re-selecting the undecodable quality.
     *
     * @param track The lower quality track to use
     * @return true if step-down was applied, false if stream state was invalid
     */
    fun applyDecoderErrorStepDown(track: VideoTrack): Boolean {
        val streamState = readyOrRecoveringSelection() ?: return false

        val resolved = streamState.selection.resolved
        val isProgressiveStream = resolved.hlsUrl == null && resolved.dashUrl == null

        // PR4: URL Lifecycle Hardening - check if progressive URLs are expired
        if (isProgressiveStream && resolved.areUrlsExpired()) {
            android.util.Log.w("PlayerViewModel", "applyDecoderErrorStepDown: URLs expired, forcing stream refresh")
            forceRefreshForAutoRecovery()
            return false
        }

        val newCapHeight = track.height
        val oldCap = streamState.selection.userQualityCapHeight

        // Clamp the cap to the new track's height - prevents track selector from
        // re-selecting the undecodable quality when using adaptive manifests
        val newSelection = PlaybackSelection(
            streamId = streamState.streamId,
            video = track,
            audio = streamState.selection.audio,
            resolved = resolved,
            userQualityCapHeight = newCapHeight,
            selectionOrigin = QualitySelectionOrigin.AUTO_RECOVERY
        )

        updateState { it.copy(streamState = StreamState.Ready(streamState.streamId, newSelection)) }
        android.util.Log.i(
            "PlayerViewModel",
            "Decoder error step-down to ${track.qualityLabel}: cap changed from ${oldCap}p to ${newCapHeight}p"
        )
        return true
    }

    private fun readyOrRecoveringSelection(): StreamState.Ready? {
        return when (val streamState = _state.value.streamState) {
            is StreamState.Ready -> streamState
            is StreamState.RecoveryExhausted -> StreamState.Ready(streamState.streamId, streamState.selection)
            else -> null
        }
    }

    /** Apply a user-selected quality cap from the quality picker. */
    fun selectQuality(track: VideoTrack) = setUserQualityCap(track)

    /**
     * User picked a different audio language from the rail's audio-language
     * dialog. Updates [PlaybackSelection.audio] and emits an
     * [PlayerUiEvent.AudioTrackSwapReady] event so the fragment can rebuild
     * the MediaSource seamlessly (preserves position + playWhenReady, same
     * pattern as [LiveStreamRefreshReady]).
     *
     * No-op when:
     * - no stream is currently Ready (shouldn't happen — button is hidden)
     * - the picked track equals the currently-active one
     */
    fun selectAudioTrack(track: AudioTrack) {
        if (track.source == AudioTrackSource.WEB_DUB) {
            selectWebDubTrack(track)
            return
        }
        // VR-native / Original: there is no web dub to re-pin after a refresh, so CLEAR the sticky
        // web-dub language (and any pending pick). Otherwise a later refresh's reattach could match a
        // prewarm-cached WEB_DUB entry for the original language and play a dub instead of VR audio.
        stickyAudioLanguage = null
        pendingAudioLanguage = null
        // Always emit, even when `track == ready.selection.audio`. AudioTrack
        // is a data class so equality compares URL too — and the player can
        // drift from the VM (ABR may pick a dubbed track without notifying
        // the VM, so the user taps "Original" but the equality guard would
        // silently drop the swap). The fragment picks the right apply path
        // (trackSelectionParameters for real DASH/HLS, MediaSource rebuild
        // for synthetic / progressive); both are safe to re-apply.
        val ready = _state.value.streamState as? StreamState.Ready ?: return
        // Drop any cached synthetic MPD for this video so a downstream
        // rebuild — either AudioTrackSwapReady's handleLiveStreamRefresh
        // path or maybePrepareStream's cache-miss path — regenerates the
        // MPD with the chosen audio track. Without this, the multi-rep
        // factory's per-videoId cache returns the OLD MPD (with the prior
        // audio track baked in) and the language never changes.
        // No-op for real DASH/HLS — the registry isn't used there.
        mpdRegistry.unregisterBoth(ready.streamId)
        val newSelection = ready.selection.copy(audio = track)
        android.util.Log.d(
            "PlayerViewModel",
            "selectAudioTrack: streamId=${ready.streamId} lang=${track.language} trackName=${track.trackName}"
        )
        updateState { it.copy(streamState = StreamState.Ready(ready.streamId, newSelection)) }
        viewModelScope.launch(dispatcher) {
            _uiEvents.emit(PlayerUiEvent.AudioTrackSwapReady(ready.streamId, newSelection))
        }
    }

    /**
     * Resolve and switch to a web-sourced dub. Resolves the streamable URL off the main thread
     * (MWEB + nsig + web pot), then composes a selection carrying the VR-native audio PLUS the
     * resolved dub so [com.albunyaan.tube.player.DashSourceBuilder] merges them (VR video + dub
     * audio). On failure emits [PlayerUiEvent.DubAudioResolveFailed] and leaves the current audio.
     *
     * Dub-aware refresh (build-order step 3): a URL-refresh re-resolve (TTL / 403 / stall) emits a
     * fresh VR-only selection, but [maybeEnumerateDubs] runs on every successful resolve, re-lights
     * the globe from [dubCache], and [applyDubLanguages] re-attaches the sticky dub via the prewarmed
     * [resolvedDubCache] — so the selected language persists across a refresh without a re-tap (kills
     * the earlier "played briefly then fell back to English" flap). If the re-resolve itself fails the
     * normal dub-failure path keeps the VR original (never breaks playback).
     */
    private fun selectWebDubTrack(track: AudioTrack) {
        val ready = _state.value.streamState as? StreamState.Ready ?: return
        val lang = track.language ?: return
        android.util.Log.d("DubFlow", "selectWebDubTrack lang=$lang stream=${ready.streamId}")
        // Latest pick wins for staleness; sticky is pinned only on a SUCCESSFUL swap (below) so a failed
        // resolve never leaves a phantom sticky that later refreshes keep trying to reattach.
        pendingAudioLanguage = lang
        viewModelScope.launch(dispatcher) {
            val resolvedDub = if (track.url.isNotEmpty()) track
                else resolvedDubCache["${ready.streamId}|$lang"] // prewarmed (instant)
                ?: dubAudioResolver.resolveDubAudio(ready.streamId, lang)
            android.util.Log.d(
                "DubFlow",
                "resolveDubAudio lang=$lang -> ${if (resolvedDub != null) "OK len=${resolvedDub.url.length}" else "NULL"}"
            )
            if (resolvedDub == null || resolvedDub.url.isEmpty()) {
                _uiEvents.emit(PlayerUiEvent.DubAudioResolveFailed)
                return@launch
            }
            resolvedDubCache["${ready.streamId}|$lang"] = resolvedDub
            val cur = _state.value.streamState as? StreamState.Ready ?: return@launch
            if (cur.streamId != ready.streamId) return@launch
            // Drop a stale resolve: if the user has since picked a different language, applying this one
            // would override the newer selection (rapid A→B tap where A resolves slower than B).
            if (pendingAudioLanguage != lang) return@launch
            // Success: NOW pin the sticky language. Readers (refresh-reattach, toSelectionWithPreferredAudio)
            // only ever see a language that actually played.
            stickyAudioLanguage = lang
            val vrNative = cur.selection.resolved.audioTracks.filter { it.source == AudioTrackSource.VR_NATIVE }
            // Keep the OTHER dub languages so the globe still lists all 14 — otherwise selecting one
            // collapses the picker to just "<that dub> + Unknown" and the user can't switch. They MUST
            // be URL-less (lazy): DashSourceBuilder merges the FIRST WEB_DUB with a non-empty URL, so a
            // previously-resolved dub that kept its URL would be merged instead of the just-picked one
            // (user taps Arabic, still hears German). Stripping the URL makes them lazy placeholders
            // that power the picker and re-resolve when picked.
            val otherDubs = cur.selection.resolved.audioTracks.filter {
                it.source == AudioTrackSource.WEB_DUB && it.language != resolvedDub.language
            }.map { if (it.url.isEmpty()) it else it.copy(url = "") }
            val mergedResolved = cur.selection.resolved.copy(audioTracks = vrNative + otherDubs + resolvedDub)
            val newSelection = cur.selection.copy(resolved = mergedResolved, audio = resolvedDub)
            mpdRegistry.unregisterBoth(cur.streamId)
            updateState { it.copy(streamState = StreamState.Ready(cur.streamId, newSelection)) }
            _uiEvents.emit(PlayerUiEvent.AudioTrackSwapReady(cur.streamId, newSelection))
            android.util.Log.d("DubFlow", "dub swap emitted lang=$lang audioTracks=${mergedResolved.audioTracks.size}")
        }
    }

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
        viewCount: Long? = null,
        sourceChannelId: String? = null,
    ) {
        // PR5: Cancel any pending delayed refresh for the old video
        pendingRefreshJob?.cancel()
        pendingRefreshJob = null
        // Cancel live stream refresh for the old video
        liveRefreshJob?.cancel()
        liveRefreshJob = null

        // PR6.6: Clear playlist mode and paging state to prevent stale hasNext
        isPlaylistMode = false
        playlistChannelApproved = false
        activePlaylistId = null
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
            viewCount = viewCount,
            sourceChannelId = sourceChannelId,
        )

        currentItem = item
        queue.clear()
        previousItems.clear()
        applyQueueState()
        hydrateCurrentItemMetadataIfNeeded(item)

        publishAnalytics(PlaybackAnalyticsEvent.PlaybackStarted(item, PlaybackStartReason.USER_SELECTED))

        // A1 fix (Stage-3 review): the duplicate availability gate that used to
        // live here is gone. The chokepoint inside [GlobalStreamResolver] runs
        // the HEAD probe once per extraction; if the video is archived a
        // [ContentUnavailableException] propagates up through
        // [resolveWithRetry] and the existing handler renders
        // ContentUnavailable. Two HEAD calls per cold-open dropped to one.
        //
        // I6 still satisfied: [resolveStreamFor] sets [StreamState.Loading]
        // synchronously before launching its background job, so the UI
        // transitions away from any previous video's Ready / Error state
        // immediately on the main thread.
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
    /**
     * Whether the current playlist's parent channel is APPROVED in our registry — the gate for showing
     * the channel name on the player. Mirrors PlaylistDetailViewModel.resolveChannelLinkability:
     * canonicalize the uploader id, then a backend registry check. Fail-closed: any failure (no
     * canonical id, 404, network, exception) returns false so an uncurated channel is never surfaced.
     */
    private suspend fun isPlaylistChannelApproved(playlistId: String): Boolean = try {
        val header = playlistDetailRepository.getHeader(playlistId)
        val rawId = header.channelId
        val canonicalId = if (rawId != null && CANONICAL_CHANNEL_ID_REGEX.matches(rawId)) {
            rawId
        } else {
            playlistDetailRepository.resolveCanonicalChannelId(header.parentChannelUrl)
        }
        canonicalId != null && contentService.isInApprovedRegistry(
            com.albunyaan.tube.data.source.AvailabilityCheckType.CHANNEL, canonicalId
        )
    } catch (e: Exception) {
        false
    }

    private suspend fun loadPlaylistWithTarget(
        playlistId: String,
        targetVideoId: String?,
        startIndexHint: Int,
        shuffled: Boolean
    ) {
        val startTime = System.currentTimeMillis()
        // A standalone playlist (parent channel NOT in our registry) must not surface a channel name.
        // Resolve approval OFF the playback-start critical path: default fail-closed (hidden — the gate
        // lives in applyQueueState), then reveal names via a re-emit only if approved. The activePlaylistId
        // guard drops a late result if the user has since switched playlists; if approval lands before the
        // queue is built, initializePlaylistQueue's own emit picks up the flag (no re-emit needed).
        activePlaylistId = playlistId
        playlistChannelApproved = false
        viewModelScope.launch(dispatcher) {
            val approved = withTimeoutOrNull(PAGE_FETCH_TIMEOUT_MS) {
                isPlaylistChannelApproved(playlistId)
            } ?: false
            if (approved && activePlaylistId == playlistId) {
                playlistChannelApproved = true
                if (playlistPagingState?.playlistId == playlistId) {
                    applyQueueState()
                }
            }
        }
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

        // Convert to UpNextItems. sourceChannelId is intentionally absent: playlist videos
        // are individually registered in the backend registry, so the per-video check applies.
        val upNextItems = orderedItems.map { playlistItem ->
            UpNextItem(
                id = playlistItem.videoId,
                title = playlistItem.title,
                // Raw name retained; suppression for unapproved playlists happens at emit (gateChannelName).
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
                currentItem = currentItem?.gateChannelName(),
                upNext = queue.map { item -> item.gateChannelName() },
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
            hydrateCurrentItemMetadataIfNeeded(it)
            publishAnalytics(PlaybackAnalyticsEvent.PlaybackStarted(it, PlaybackStartReason.AUTO))
            resolveStreamFor(it, PlaybackStartReason.AUTO)
        }
    }

    private fun hydrateCurrentItemMetadataIfNeeded(item: UpNextItem) {
        if (!needsMetadataHydration(item)) {
            return
        }

        metadataHydrationJob?.cancel()
        metadataHydrationJob = viewModelScope.launch(dispatcher) {
            val metadata = runCatching {
                withTimeoutOrNull(EXTRACTOR_TIMEOUT_MS) {
                    extractorClient.fetchVideoMetadata(listOf(item.streamId))[item.streamId]
                }
            }.getOrNull() ?: return@launch

            val current = currentItem ?: return@launch
            if (current.streamId != item.streamId) {
                return@launch
            }

            val shouldReplaceTitle = current.title.isBlank() ||
                current.title.equals("Video", ignoreCase = true) ||
                (current.channelName.isBlank() && current.description.isNullOrBlank() && current.viewCount == null)

            val hydrated = current.copy(
                title = if (shouldReplaceTitle) {
                    metadata.title?.takeIf { it.isNotBlank() } ?: current.title
                } else {
                    current.title
                },
                channelName = if (current.channelName.isNotBlank()) current.channelName else (metadata.channelName ?: current.channelName),
                durationSeconds = if (current.durationSeconds > 0) current.durationSeconds else (metadata.durationSeconds ?: 0),
                thumbnailUrl = current.thumbnailUrl ?: metadata.thumbnailUrl,
                description = current.description ?: metadata.description,
                viewCount = current.viewCount ?: metadata.viewCount
            )

            if (hydrated != current) {
                currentItem = hydrated
                applyQueueState()
            }
        }
    }

    private fun needsMetadataHydration(item: UpNextItem): Boolean {
        return item.title.isBlank() ||
            item.title.equals("Video", ignoreCase = true) ||
            item.thumbnailUrl.isNullOrBlank() ||
            item.description.isNullOrBlank() ||
            item.viewCount == null ||
            item.durationSeconds <= 0 ||
            item.channelName.isBlank()
    }

    /**
     * Standalone playlists (curated playlist whose parent channel is NOT in our registry) must not
     * surface a channel name anywhere on the player. Gate the name at emit time (the raw value stays in
     * the queue) so: (a) an async approval can reveal it later via a re-emit, and (b) metadata hydration
     * can't re-leak a suppressed name. No-op outside playlist mode — single videos keep their channel.
     */
    private fun UpNextItem.gateChannelName(): UpNextItem =
        if (isPlaylistMode && !playlistChannelApproved && channelName.isNotEmpty()) {
            copy(channelName = "")
        } else {
            this
        }

    private fun applyQueueState() {
        // PR6.6: hasNext is true if queue has items OR if more pages can be loaded
        val pagingState = playlistPagingState
        val hasMorePages = pagingState?.hasMore == true && !pagingState.pagingFailed

        updateState { state ->
            state.copy(
                currentItem = currentItem?.gateChannelName(),
                upNext = queue.map { it.gateChannelName() },
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
     * Called when the player needs fresh stream URLs (stall watchdog, error refresh).
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
     * Force re-resolve stream URLs for planned synthetic-DASH MPD TTL refresh.
     * This is separate from AUTO_RECOVERY so proactive refreshes do not consume
     * the reserved error-recovery budget used after stalls or HTTP failures.
     */
    fun forceRefreshForProactiveTtl(): Boolean {
        return forceRefreshCurrentStreamWithKind(ExtractionRateLimiter.RequestKind.PROACTIVE_TTL_REFRESH)
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
        // Invalidate MPD cache BEFORE resolving to ensure fresh streams aren't
        // mapped to stale MPD (fixes race condition where fast resolve could
        // hit cached MPD that was generated with expired URLs)
        mpdRegistry.unregister(item.streamId)
        // Invalidate the prefetch cache as well since URLs are likely stale
        synchronized(prefetchCache) { prefetchCache.remove(item.streamId) }
        resolveStreamFor(item, PlaybackStartReason.USER_SELECTED, forceRefresh = true)
    }

    private fun resolveStreamFor(
        item: UpNextItem,
        @Suppress("UNUSED_PARAMETER") reason: PlaybackStartReason,
        forceRefresh: Boolean = false
    ) {
        resolveJob?.cancel()

        // Phase 0 metrics: mark playback requested if not already marked (for playlist advance)
        playbackMetrics.onPlaybackRequested(item.streamId)

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
     * Consume the queue-prefetch cache entry for [streamId] iff it exists AND is
     * younger than [PREFETCH_CACHE_TTL_MS]. Stale entries are evicted (the
     * insertion-order semantics of the underlying [prefetchCache] don't matter
     * here — it's a small `mutableMapOf` with manual-eviction at write time).
     *
     * Returns null in both the absent and expired cases so the caller falls
     * through to a fresh resolve via [repository.resolveStreams], which the
     * archived-content fix has already gated through the backend availability
     * HEAD check (see [DefaultPlayerRepository] + [GlobalStreamResolver]).
     *
     * The synchronization scope mirrors the writer in [prefetchNextItems] —
     * both touch [prefetchCache] inside `synchronized(prefetchCache)`.
     */
    private fun consumeFreshPrefetchCache(streamId: String): ResolvedStreams? {
        return synchronized(prefetchCache) {
            val cached = prefetchCache[streamId] ?: return@synchronized null
            val ageMs = clock() - cached.cachedAtMs
            if (ageMs > PREFETCH_CACHE_TTL_MS) {
                // N9: drop stale entry — caller re-resolves through the gate.
                prefetchCache.remove(streamId)
                android.util.Log.d(
                    "PlayerViewModel",
                    "Queue-prefetch cache expired for $streamId (age=${ageMs}ms); will re-resolve"
                )
                null
            } else {
                prefetchCache.remove(streamId)
                cached.streams
            }
        }
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
            val tapPrefetched = try {
                prefetchService.awaitOrConsumePrefetch(item.streamId)
            } catch (cu: com.albunyaan.tube.player.ContentUnavailableException) {
                // NB1 fix: the chokepoint inside [GlobalStreamResolver] propagates
                // [ContentUnavailableException] all the way out through the
                // prefetch await path (the prefetch service forwards the in-flight
                // job's exception). Since 404 is now fail-open, ContentUnavailableException
                // here always means the backend returned 410 (explicit admin block) — respect it
                // regardless of sourceChannelId.
                android.util.Log.i(
                    "PlayerViewModel",
                    "Content unavailable for ${item.streamId} via prefetch await; halting retries",
                )
                playbackMetrics.onPlaybackFailed(item.streamId, "content_unavailable")
                publishAnalytics(PlaybackAnalyticsEvent.StreamFailed(item.streamId))
                if (handleStreamResolutionFailure(item)) {
                    return  // Auto-skipped to next item in playlist
                }
                updateState { it.copy(streamState = StreamState.ContentUnavailable) }
                return
            }
            if (tapPrefetched != null) {
                android.util.Log.d("PlayerViewModel", "Using tap-prefetched stream for ${item.streamId}")
                val selection = tapPrefetched.toSelectionWithPreferredAudio(stickyAudioLanguage)
                if (selection != null) {
                    publishAnalytics(PlaybackAnalyticsEvent.StreamResolved(item.streamId, selection.video?.qualityLabel))
                    updateState { it.copy(streamState = StreamState.Ready(tapPrefetched.streamId, selection), retryCount = 0) }
                    maybeEnumerateDubs(selection)
                    return
                }
                android.util.Log.w("PlayerViewModel", "Tap-prefetched stream invalid, checking local cache")
            }
        }

        // Check local prefetch cache (for queue items) - skip if forceRefresh
        if (!forceRefresh) {
            val prefetched = consumeFreshPrefetchCache(item.streamId)
            if (prefetched != null) {
                android.util.Log.d("PlayerViewModel", "Using queue-prefetched stream for ${item.streamId}")
                val selection = prefetched.toSelectionWithPreferredAudio(stickyAudioLanguage)
                if (selection != null) {
                    publishAnalytics(PlaybackAnalyticsEvent.StreamResolved(item.streamId, selection.video?.qualityLabel))
                    updateState { it.copy(streamState = StreamState.Ready(prefetched.streamId, selection), retryCount = 0) }
                    maybeEnumerateDubs(selection)
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
                    repository.resolveStreams(
                        item.streamId,
                        forceRefresh = forceRefresh && attempt == 1,
                        sourceChannelId = item.sourceChannelId,
                    )
                }
            } catch (cu: com.albunyaan.tube.player.ContentUnavailableException) {
                // C1 fix: backend availability gate inside DefaultPlayerRepository
                // signalled the video is archived/unavailable. Do NOT retry — the
                // outcome is deterministic. In playlist mode, auto-skip to the next
                // item (same UX as resolve failures). In single-video mode (or after
                // the auto-skip limit is hit), surface ContentUnavailable so the UI
                // shows the localized "content not available" overlay.
                android.util.Log.i(
                    "PlayerViewModel",
                    "Content unavailable for ${item.streamId} per backend gate; halting retries",
                )
                playbackMetrics.onPlaybackFailed(item.streamId, "content_unavailable")
                publishAnalytics(PlaybackAnalyticsEvent.StreamFailed(item.streamId))
                if (handleStreamResolutionFailure(item)) {
                    return  // Auto-skipped to next item in playlist
                }
                updateState { it.copy(streamState = StreamState.ContentUnavailable) }
                return
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
                    playbackMetrics.onPlaybackFailed(item.streamId, "extraction_timeout")
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
                    playbackMetrics.onPlaybackFailed(item.streamId, "stream_null")
                    publishAnalytics(PlaybackAnalyticsEvent.StreamFailed(item.streamId))
                    if (handleStreamResolutionFailure(item)) {
                        return  // Auto-skipped to next item
                    }
                    updateState { it.copy(streamState = StreamState.Error(R.string.player_stream_unavailable)) }
                    return
                }
            }

            val selection = resolved.toSelectionWithPreferredAudio(stickyAudioLanguage)
            if (selection == null) {
                playbackMetrics.onPlaybackFailed(item.streamId, "no_selection")
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
            maybeEnumerateDubs(selection)

            // Schedule proactive URL refresh for live streams
            scheduleLiveStreamRefresh(resolved, selection)

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
     * Schedule proactive URL refresh for live streams before they expire.
     * This fetches fresh URLs in the background and emits an event for seamless swap.
     */
    private fun scheduleLiveStreamRefresh(resolved: ResolvedStreams, currentSelection: PlaybackSelection) {
        // Cancel any existing refresh job
        liveRefreshJob?.cancel()

        // Only schedule for live streams
        if (!resolved.isLive) return

        val delayMs = resolved.timeUntilProactiveRefreshMs() ?: return
        if (delayMs <= 0) {
            // Already time to refresh - do it immediately in a coroutine
            android.util.Log.d("PlayerViewModel", "Live stream: immediate proactive refresh needed")
            liveRefreshJob = viewModelScope.launch(Dispatchers.IO) {
                performLiveStreamRefresh(resolved.streamId, currentSelection)
            }
            return
        }

        android.util.Log.d("PlayerViewModel", "Live stream: scheduling proactive refresh in ${delayMs / 1000}s")

        liveRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(delayMs)

            // Verify we're still playing the same stream
            val currentState = _state.value.streamState
            if (currentState !is StreamState.Ready || currentState.streamId != resolved.streamId) {
                android.util.Log.d("PlayerViewModel", "Live stream: skipping refresh, stream changed")
                return@launch
            }

            performLiveStreamRefresh(resolved.streamId, currentState.selection)
        }
    }

    /**
     * Perform the actual live stream URL refresh in the background.
     */
    private suspend fun performLiveStreamRefresh(streamId: String, currentSelection: PlaybackSelection) {
        android.util.Log.d("PlayerViewModel", "Live stream: proactively refreshing URLs for $streamId")

        try {
            // Force refresh to get new URLs. Pass sourceChannelId so channel-sourced live
            // streams use CHANNEL availability check (not per-video registry check).
            val freshStreams = repository.resolveStreams(
                streamId,
                forceRefresh = true,
                sourceChannelId = currentItem?.sourceChannelId,
            )
            if (freshStreams == null || !freshStreams.isLive) {
                android.util.Log.w("PlayerViewModel", "Live stream: refresh failed or stream no longer live")
                return
            }

            // Build selection maintaining current audio-only and quality preferences
            val freshSelection = buildFreshSelection(freshStreams, currentSelection)
            if (freshSelection == null) {
                android.util.Log.w("PlayerViewModel", "Live stream: failed to build fresh selection")
                return
            }

            android.util.Log.d("PlayerViewModel", "Live stream: fresh URLs ready, emitting event")
            _uiEvents.emit(PlayerUiEvent.LiveStreamRefreshReady(streamId, freshSelection))

            // Schedule next refresh
            scheduleLiveStreamRefresh(freshStreams, freshSelection)

        } catch (cu: com.albunyaan.tube.player.ContentUnavailableException) {
            // Live stream became unavailable mid-broadcast (channel removed, video
            // archived, geo-changed). Surface ContentUnavailable instead of an
            // opaque error — same as the regular resolve path. No need to schedule
            // another refresh attempt.
            android.util.Log.i("PlayerViewModel", "Live stream: ${cu.videoId} now unavailable per backend; emitting ContentUnavailable")
            updateState { it.copy(streamState = StreamState.ContentUnavailable) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("PlayerViewModel", "Live stream: proactive refresh failed", e)
            // Will fall back to error-based refresh when URLs actually expire
        }
    }

    /**
     * Build a fresh PlaybackSelection maintaining current preferences.
     */
    private fun buildFreshSelection(
        freshStreams: ResolvedStreams,
        currentSelection: PlaybackSelection
    ): PlaybackSelection? {
        // Try to find matching video quality
        val video = if (currentSelection.video != null) {
            freshStreams.videoTracks.find { it.height == currentSelection.video.height }
                ?: freshStreams.videoTracks.maxByOrNull { it.height ?: 0 }
        } else null

        // Try to find matching audio — prefer language match over bitrate match
        val currentLang = currentSelection.audio.language
        val langMatchAudio = if (!currentLang.isNullOrBlank()) {
            freshStreams.audioTracks
                .filter { it.language == currentLang }
                .maxByOrNull { it.bitrate ?: 0 }
        } else null
        val audio = langMatchAudio
            ?: freshStreams.audioTracks.find { it.bitrate == currentSelection.audio.bitrate }
            ?: freshStreams.audioTracks.maxByOrNull { it.bitrate ?: 0 }
            ?: return null

        return PlaybackSelection(
            streamId = freshStreams.streamId,
            video = video,
            audio = audio,
            resolved = freshStreams,
            userQualityCapHeight = currentSelection.userQualityCapHeight,
            selectionOrigin = currentSelection.selectionOrigin
        )
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
                    // Queue prefetch is background work: keep it behind visible
                    // channel/search loads and let it skip when the NewPipe budget
                    // is under pressure.
                    val resolved = repository.resolveStreams(
                        item.streamId,
                        priority = Priority.BACKGROUND_REFRESH,
                        sourceChannelId = item.sourceChannelId,
                    )
                    if (resolved != null) {
                        // PR5: Signal success to reset backoff state
                        rateLimiter.onExtractionSuccess(item.streamId)
                        synchronized(prefetchCache) {
                            // Evict old entries if cache is full
                            if (prefetchCache.size >= maxPrefetchItems * 2) {
                                val oldest = prefetchCache.keys.firstOrNull()
                                oldest?.let { prefetchCache.remove(it) }
                            }
                            // N9 fix: stamp with wall-clock so the consumer enforces
                            // PREFETCH_CACHE_TTL_MS and forces a re-resolve through
                            // the repository's availability gate after the TTL.
                            prefetchCache[item.streamId] = CachedPrefetch(resolved, clock())
                        }
                        android.util.Log.d("PlayerViewModel", "Prefetch: Completed for ${item.streamId}")
                    }
                } catch (cu: com.albunyaan.tube.player.ContentUnavailableException) {
                    // Skip prefetching archived items — when the user advances to
                    // them, the live resolve path will surface ContentUnavailable
                    // (or auto-skip in playlist mode). No retry, no warning.
                    android.util.Log.d("PlayerViewModel", "Prefetch: skipping archived ${item.streamId}")
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    android.util.Log.w("PlayerViewModel", "Prefetch failed for ${item.streamId}: ${e.message}")
                    // Don't propagate - prefetch failures are non-fatal
                }
            }
        }
    }

    companion object {
        /** YouTube canonical channel id (UC + 22 chars). Non-UC uploader ids are resolved first. */
        private val CANONICAL_CHANNEL_ID_REGEX = Regex("^UC[A-Za-z0-9_-]{22}$")

        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_BASE_DELAY_MS = 1000L
        private const val EXTRACTOR_TIMEOUT_MS = 20000L // 20s timeout for extraction (NewPipe can be slow)
        private const val QUALITY_SWITCH_DEBOUNCE_MS = 300L // Debounce delay for quality switches

        // PR6.6: Playlist paging constants
        private const val PAGE_FETCH_TIMEOUT_MS = 10000L  // 10s timeout per page fetch
        private const val PAGE_FETCH_COOLDOWN_MS = 2000L  // 2s minimum gap between page fetches
        private const val MAX_ITEMS_TO_SCAN = 250         // Max items to scan for deep start
        private const val MAX_SCAN_TIME_MS = 3000L        // Max time for deep start scanning
        private const val QUEUE_PREFETCH_THRESHOLD = 5    // Trigger background fetch when queue drops below this
        private const val MAX_CONSECUTIVE_SKIPS = 3       // Max auto-skips for unplayable items

        /**
         * TTL for the queue-prefetch cache (N9 fix).
         *
         * Bounds the archive-bypass window for queue-prefetched items: if a video
         * is archived AFTER [prefetchNextItems] resolved its streams, the cached
         * entry expires after this window so the next consumer has to re-resolve
         * through the gate (which surfaces ContentUnavailable / triggers auto-skip).
         * 30s matches the prefetch service TTL — both have the same threat model.
         */
        @androidx.annotation.VisibleForTesting
        internal const val PREFETCH_CACHE_TTL_MS = 30_000L

        /**
         * Pure: which quality options to offer for [videoTracks] given the [extractionClient]
         * that minted their URLs. Extracted from [getAvailableQualities] so it is unit-testable
         * without constructing the ViewModel.
         *
         * On the NewPipe fallback path (`extractionClient != ANDROID_VR`) playback is served as a
         * SINGLE progressive muxed track: `DashSourceBuilder.decide()` gates out the adaptive
         * video-only ladder because those iOS/android-client segments 403 mid-stream (verified for
         * One4kids "Zaky's Learning Club"). Offering the full 720p/1080p metadata list there is
         * misleading — the cap is silently ignored and playback stays at the muxed quality (the
         * documented forceProgressive limitation). So we offer exactly the one track decide() would
         * serve: the highest muxed, or (if none) the highest video-only. ANDROID_VR keeps the full
         * ladder — its adaptive segments sustain and track-selector switching works.
         *
         * [adaptiveActive] is the player's actual adaptive state for the current source. The full
         * ladder is offered only when the stream is genuinely playing adaptive (ANDROID_VR with a
         * built MPD). When ANDROID_VR's own MPD generation fails and the player falls back to a
         * single progressive track, [adaptiveActive] is false and only the served track is offered —
         * closing the prior "menu over-promises on MPD-gen failure" gap.
         */
        @androidx.annotation.VisibleForTesting
        internal fun buildQualityOptions(
            videoTracks: List<VideoTrack>,
            extractionClient: ExtractionClient,
            adaptiveActive: Boolean,
        ): List<QualityOption> {
            val offerable = if (extractionClient == ExtractionClient.ANDROID_VR && adaptiveActive) {
                videoTracks
            } else {
                // Mirror DashSourceBuilder.decide()'s progressive pick: highest muxed, else highest video-only.
                val playable = videoTracks.filter { !it.isVideoOnly }.maxByOrNull { it.height ?: 0 }
                    ?: videoTracks.filter { it.isVideoOnly }.maxByOrNull { it.height ?: 0 }
                listOfNotNull(playable)
            }

            // Deduplicate by height: prefer muxed over video-only, then highest bitrate.
            return offerable
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
                .mapNotNull { track -> track.qualityLabel?.let { label -> QualityOption(label, track) } }
                .sortedByDescending { it.track.height ?: 0 }
        }
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
        // Cancel live stream refresh for the old video
        liveRefreshJob?.cancel()
        liveRefreshJob = null
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

            val newItems = page.items.map { playlistItem ->
                UpNextItem(
                    id = playlistItem.videoId,
                    title = playlistItem.title,
                    // Raw name retained; suppression for unapproved playlists happens at emit (gateChannelName).
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

    // --- Recovery State Management ---

    /**
     * Clear recovering state and return to Ready state.
     * Called when recovery succeeds.
     */
    fun clearRecoveringState() {
        val current = _state.value.streamState
        when (current) {
            is StreamState.RecoveryExhausted -> {
                updateState { it.copy(streamState = StreamState.Ready(current.streamId, current.selection)) }
            }
            else -> { /* No-op for other states */ }
        }
    }

    /**
     * Transition to RecoveryExhausted state when all automatic recovery attempts fail.
     * Surfaced when automatic recovery is exhausted (terminal error / rate-limit) to
     * show the manual-retry escape hatch.
     */
    fun setRecoveryExhaustedState() {
        val current = _state.value.streamState
        val (streamId, selection) = when (current) {
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
    val viewCount: Long? = null,
    val sourceChannelId: String? = null,
)

sealed class StreamState {
    object Idle : StreamState()
    object Loading : StreamState()
    data class Ready(val streamId: String, val selection: PlaybackSelection) : StreamState()
    data class Error(@StringRes val messageRes: Int) : StreamState()
    /**
     * The video has been archived and is no longer available.
     * The player must not attempt any NewPipe extraction when in this state.
     */
    object ContentUnavailable : StreamState()
    /**
     * All automatic recovery attempts exhausted. UI should show manual retry option.
     * Contains the underlying selection so user can trigger manual retry.
     */
    data class RecoveryExhausted(
        val streamId: String,
        val selection: PlaybackSelection
    ) : StreamState()
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
    USER_SELECTED(R.string.player_start_reason_user_selected)
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

private fun ResolvedStreams.toSelectionWithPreferredAudio(preferredLanguage: String?): PlaybackSelection? {
    val base = toDefaultSelection() ?: return null
    if (preferredLanguage.isNullOrBlank()) return base
    val matchingAudio = audioTracks
        .filter { it.language == preferredLanguage }
        .maxByOrNull { it.bitrate ?: 0 }
        ?: return base
    return base.copy(audio = matchingAudio)
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

    /**
     * Emitted when fresh URLs are ready for a live stream.
     * PlayerFragment should seamlessly swap to the new source without stopping playback.
     *
     * @param streamId The stream ID for verification
     * @param newSelection The new PlaybackSelection with fresh URLs
     */
    data class LiveStreamRefreshReady(
        val streamId: String,
        val newSelection: PlaybackSelection
    ) : PlayerUiEvent()

    /**
     * Emitted when the user picks a different audio language. Fragment should
     * do a seamless MediaSource swap (preserving position + play state) with
     * the resolved streams filtered to just [newSelection.audio] so the
     * factory uses that track.
     */
    data class AudioTrackSwapReady(
        val streamId: String,
        val newSelection: PlaybackSelection
    ) : PlayerUiEvent()

    /** Emitted when a web-sourced dub audio resolve failed; fragment toasts and stays on current audio. */
    object DubAudioResolveFailed : PlayerUiEvent()
}
