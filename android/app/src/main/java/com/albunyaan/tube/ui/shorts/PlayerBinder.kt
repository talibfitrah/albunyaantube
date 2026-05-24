package com.albunyaan.tube.ui.shorts

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
// (Player import retained for REPEAT_MODE_ONE constant)
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.AudioTrackKind
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.VideoTrack
import com.albunyaan.tube.player.CronetDataSourceFactory
import com.albunyaan.tube.player.PlayerRepository
import com.albunyaan.tube.util.HttpConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Encapsulates rebinding a shared [Player] instance to the currently visible
 * [PlayerView] and resolving + setting a fresh [MediaSource] for the selected
 * shorts video. The binder owns the "detach previous view, attach new view,
 * rebuild source, prepare, play" lifecycle — ShortsPlayerFragment only calls
 * [bind] on page change.
 *
 * Stream resolution uses [PlayerRepository.resolveStreams]. Shorts first try the
 * same adaptive DASH/HLS factory as the regular player. Progressive playback is
 * only a fallback, and that fallback uses the configured Cronet/cache transport
 * instead of a plain uncached HTTP stack.
 *
 * Resolution failures are exposed via [failureEvents] so the fragment can call
 * `vm.onPlaybackError(index)` without the binder holding a reference to the VM.
 *
 * ### Rapid-swipe race protection
 *
 * [bind] is safe to call repeatedly in quick succession: each call cancels the
 * previous in-flight resolve + apply job and bumps a monotonically-increasing
 * [generation] counter. Stale resolutions that finish after a newer bind check
 * their captured generation against the current value and drop the result
 * before touching the player, so a slow resolve of video A can never play its
 * source on top of a subsequent bind to video B.
 */
class PlayerBinder private constructor(
    private val player: ExoPlayer?,
    private val playerRepository: PlayerRepository,
    /**
     * Factory that turns [ResolvedStreams] into an adaptive DASH/HLS
     * [MediaSource]. Shorts play highest-quality by default with ABR when
     * the factory picks an adaptive path; progressive is used only as a
     * fallback when the factory returns a non-success result.
     * Null in the test-only constructor path.
     */
    private val mediaSourceFactory: com.albunyaan.tube.player.MultiQualityMediaSourceFactory?,
    /**
     * Thin seam over the player mutations we perform from the resolve
     * coroutine. Production binds directly to the real ExoPlayer; tests
     * substitute a fake so we can assert apply-ordering without constructing
     * a real Media3 instance (ExoPlayer's static init pulls in Android
     * framework state that's awkward in JVM unit tests).
     */
    private val playerOps: PlayerOps,
    /**
     * Attach strategy: production assigns the shared [ExoPlayer] to the
     * supplied [PlayerView]. Tests inject a no-op so PlayerView's Android
     * superclass chain doesn't need to be loaded.
     */
    private val attach: PlayerViewAttach,
    private val context: Context? = null,
    private val cronetDataSourceFactory: CronetDataSourceFactory? = null,
    private val simpleCache: SimpleCache? = null,
    private val mpdRegistry: com.albunyaan.tube.player.SyntheticDashMpdRegistry? = null,
    private val featureFlags: com.albunyaan.tube.player.PlaybackFeatureFlags? = null
) {

    /** Production constructor — wires the real ExoPlayer-backed ops. */
    constructor(
        player: ExoPlayer,
        playerRepository: PlayerRepository,
        mediaSourceFactory: com.albunyaan.tube.player.MultiQualityMediaSourceFactory,
        context: Context,
        cronetDataSourceFactory: CronetDataSourceFactory,
        simpleCache: SimpleCache,
        mpdRegistry: com.albunyaan.tube.player.SyntheticDashMpdRegistry? = null,
        featureFlags: com.albunyaan.tube.player.PlaybackFeatureFlags? = null
    ) : this(
        player = player,
        playerRepository = playerRepository,
        mediaSourceFactory = mediaSourceFactory,
        playerOps = ExoPlayerOps(player),
        attach = PlayerViewAttach { view, attached ->
            if (attached) view.player = player else view.player = null
        },
        context = context.applicationContext,
        cronetDataSourceFactory = cronetDataSourceFactory,
        simpleCache = simpleCache,
        mpdRegistry = mpdRegistry,
        featureFlags = featureFlags
    )

    /** Test-only constructor — fully decoupled from ExoPlayer / PlayerView. */
    internal constructor(
        playerRepository: PlayerRepository,
        ops: PlayerOps,
        attach: PlayerViewAttach
    ) : this(null, playerRepository, null, ops, attach)

    /**
     * Minimal surface of player mutations needed for testing the rapid-swipe
     * race. Keeps PlayerBinder free of direct ExoPlayer calls in the apply
     * path so tests can verify ordering with a plain fake. Also exposes
     * [getPlayWhenReady] so togglePlayPause can flip state without reaching
     * through to the real player.
     */
    internal interface PlayerOps {
        fun stop()
        fun clearMediaItems()
        fun setMediaSource(source: MediaSource)
        fun setRepeatModeOne()
        fun prepare()
        fun setPlayWhenReady(value: Boolean)
        fun getPlayWhenReady(): Boolean
        /** Current playback position in ms (0 if unknown). Default 0 for test fakes. */
        fun getCurrentPosition(): Long = 0L
        /** Seek to the given position. Default no-op for test fakes that don't care. */
        fun seekTo(positionMs: Long) {}
    }

    /** Attach strategy — binds or unbinds the shared player from a PlayerView. */
    internal fun interface PlayerViewAttach {
        fun attach(view: PlayerView, attached: Boolean)
    }

    private class ExoPlayerOps(private val player: ExoPlayer) : PlayerOps {
        override fun stop() = player.stop()
        override fun clearMediaItems() = player.clearMediaItems()
        override fun setMediaSource(source: MediaSource) = player.setMediaSource(source)
        override fun setRepeatModeOne() { player.repeatMode = Player.REPEAT_MODE_ONE }
        override fun prepare() = player.prepare()
        override fun setPlayWhenReady(value: Boolean) { player.playWhenReady = value }
        override fun getPlayWhenReady(): Boolean = player.playWhenReady
        override fun getCurrentPosition(): Long = player.currentPosition
        override fun seekTo(positionMs: Long) { player.seekTo(positionMs) }
    }

    private var boundView: PlayerView? = null
    private var ttlWatcher: com.albunyaan.tube.player.MpdTtlWatcher? = null

    /**
     * Force captions to land at the bottom of the frame regardless of the
     * cue's embedded `line` / `lineAnchor`. YouTube TTML — auto-gen tracks
     * especially — frequently uses `tts:displayAlign="before"` (top), and
     * `SubtitleView` is `final` so we can't subclass to ignore positioning.
     *
     * Listener-order is the load-bearing detail: PlayerView's internal
     * listener (added when `view.player = player` runs in `attach`) calls
     * `setCues` synchronously with the original positions. We need our
     * `setCues` to fire AFTER it so the bottom override wins. Listeners
     * fire in registration order, so we re-add this listener at the end of
     * each `bind` to push it to the back of the queue. Synchronous setCues
     * (no `View.post`) keeps the rewrite in the same frame as PlayerView's
     * call — without the round trip to the message loop, users never see
     * the unmodified top placement.
     */
    private val cueRewriteListener = object : Player.Listener {
        override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
            val subView = boundView?.subtitleView ?: return
            val rewritten = cueGroup.cues.map { cue ->
                cue.buildUpon()
                    .setLine(0.92f, androidx.media3.common.text.Cue.LINE_TYPE_FRACTION)
                    .setLineAnchor(androidx.media3.common.text.Cue.ANCHOR_TYPE_END)
                    .build()
            }
            subView.setCues(rewritten)
        }
    }

    /**
     * Monotonically-increasing token identifying the "current" bind request.
     * Every [bind] call increments this; any coroutine that was started by a
     * previous bind compares its captured generation against [generation] and
     * aborts if stale. Guarded by [AtomicInteger] so reads/writes across
     * Dispatchers are safe.
     */
    private val generation = AtomicInteger(0)

    /**
     * Internal scope for bind coroutines. A [SupervisorJob] so one failing
     * bind doesn't cancel the scope, and [Dispatchers.Main.immediate] so the
     * [Player] mutations (which require the main thread) stay on-thread.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The in-flight bind job — cancelled on the next [bind] or [release]. */
    private var bindJob: Job? = null

    /**
     * Set true after [cancelScope] / [release]. Once true, [bind] is a no-op
     * because [scope] is permanently dead — preventing silent failures where
     * a fragment caller mistakenly re-uses a torn-down binder.
     */
    private var scopeCancelled: Boolean = false

    private val _failureEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    /** Emits the failing videoId when stream resolution throws or returns null. */
    val failureEvents: SharedFlow<String> = _failureEvents.asSharedFlow()

    private val _resolvedEvents = MutableSharedFlow<Pair<String, ResolvedStreams>>(
        replay = 1, // late collectors (fragment attaches after cached resolve) still see the last event
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    /**
     * Emits `(videoId, resolvedStreams)` whenever stream resolution succeeds.
     * Used by the shorts fragment to decide whether to show the audio-language
     * rail button for the currently-playing short. Does not replay — callers
     * should also query [resolvedStreamsFor] at attach time.
     */
    val resolvedEvents: SharedFlow<Pair<String, ResolvedStreams>> = _resolvedEvents.asSharedFlow()

    /** Retained across bind calls so forceRefreshCurrent can re-use the same channel context. */
    @Volatile private var boundSourceChannelId: String? = null

    /** Exact currently bound short id. Do not infer this from cache recency. */
    @Volatile private var boundVideoId: String? = null

    /**
     * Detach the player from any previously bound PlayerView, attach it to
     * [target], then resolve and begin playback for [videoId].
     *
     * Non-suspending: self-serializes via an internal scope. Each call
     * cancels the prior in-flight bind and bumps a generation token, so late
     * resolutions from previous binds cannot mutate the player.
     *
     * On stream-resolution failure, emits to [failureEvents] (suppressed —
     * never throws) so the fragment can skip past the bad short.
     */
    fun bind(target: PlayerView, videoId: String, sourceChannelId: String? = null) {
        bindInternal(target, videoId, sourceChannelId, forceRefresh = false, resetPlayerBeforeResolve = true)
    }

    private fun bindInternal(
        target: PlayerView,
        videoId: String,
        sourceChannelId: String?,
        forceRefresh: Boolean,
        resetPlayerBeforeResolve: Boolean
    ) {
        check(!scopeCancelled) {
            "PlayerBinder.bind called after cancelScope; binder must not be reused"
        }
        val myGen = generation.incrementAndGet()
        boundSourceChannelId = sourceChannelId
        boundVideoId = videoId

        // Cancel any in-flight resolve for the prior bind. The coroutine body
        // also checks myGen against generation as a second line of defence in
        // case the resolution network call completes between cancel() and the
        // player mutation (cancellation is cooperative).
        bindJob?.cancel()

        // Synchronously attach the PlayerView and stop current playback so the
        // previous short's audio/video doesn't bleed through while we resolve.
        if (boundView !== target) {
            boundView?.let { attach.attach(it, attached = false) }
            attach.attach(target, attached = true)
            boundView = target
            // Re-register cueRewriteListener AFTER PlayerView's internal
            // listener (which was just added by `attach.attach(target, true)`
            // → `view.player = player`). Player.Listener#onCues fires in
            // registration order, so re-adding ours last guarantees our
            // bottom-anchor setCues is the final write per cue tick.
            player?.removeListener(cueRewriteListener)
            player?.addListener(cueRewriteListener)
        }
        if (resetPlayerBeforeResolve) {
            playerOps.stop()
            playerOps.clearMediaItems()
        }

        bindJob = scope.launch {
            prepareAndPlay(videoId, myGen, sourceChannelId, forceRefresh)
        }
    }

    /**
     * Caches the last successfully resolved streams per videoId so the download
     * button can show the quality picker immediately without re-resolving. The
     * map is bounded: we only keep the most recent [MAX_RESOLVED_CACHE] entries,
     * which in practice is "the currently playing short plus a prefetch buffer".
     */
    private val resolvedCache: LinkedHashMap<String, ResolvedStreams> =
        object : LinkedHashMap<String, ResolvedStreams>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ResolvedStreams>?): Boolean =
                size > MAX_RESOLVED_CACHE
        }

    /**
     * Return the last successfully resolved streams for [videoId], or null if
     * no successful resolution has happened yet for this session.
     */
    fun resolvedStreamsFor(videoId: String): ResolvedStreams? =
        synchronized(resolvedCache) { resolvedCache[videoId] }

    /**
     * Once the user picks an audio language for a video, remember it here so
     * any subsequent re-resolve (URL expiry, rebuffer recovery, prefetch
     * refresh, etc.) keeps playing that language — otherwise the factory's
     * "max bitrate audio" rule can flip the user's ORIGINAL pick to a dubbed
     * track of equal bitrate on every reprepare.
     */
    private val stickyAudioLanguageByVideoId = mutableMapOf<String, String>()

    /** Record the user's preferred audio language for [videoId]. */
    fun rememberAudioLanguage(videoId: String, language: String?) {
        synchronized(stickyAudioLanguageByVideoId) {
            if (language.isNullOrBlank()) stickyAudioLanguageByVideoId.remove(videoId)
            else stickyAudioLanguageByVideoId[videoId] = language
        }
    }

    /** Return remembered audio language for [videoId], or null if user hasn't picked. */
    fun rememberedAudioLanguage(videoId: String): String? =
        synchronized(stickyAudioLanguageByVideoId) { stickyAudioLanguageByVideoId[videoId] }

    /**
     * Re-resolve the currently bound video with `forceRefresh=true` and
     * re-prepare the player. Used by the fragment's stall-watchdog when a
     * BUFFERING state has lasted longer than the threshold — typical cause
     * is an expired progressive URL.
     */
    fun forceRefreshCurrent() {
        val view = boundView ?: return
        val currentVideoId = boundVideoId ?: return
        bindInternal(
            view,
            currentVideoId,
            boundSourceChannelId,
            forceRefresh = true,
            resetPlayerBeforeResolve = false
        )
    }

    private suspend fun prepareAndPlay(
        videoId: String,
        myGen: Int,
        sourceChannelId: String?,
        forceRefresh: Boolean
    ) {
        val resolved: ResolvedStreams? = runCatching {
            playerRepository.resolveStreams(videoId, forceRefresh = forceRefresh, sourceChannelId = sourceChannelId)
        }.getOrNull()

        // Discard if a newer bind has superseded this one.
        if (myGen != generation.get()) return

        if (resolved == null) {
            _failureEvents.tryEmit(videoId)
            return
        }

        synchronized(resolvedCache) { resolvedCache[videoId] = resolved }
        _resolvedEvents.tryEmit(videoId to resolved)

        // Pin a default language on first resolve so subsequent re-resolves
        // (URL expiry, rebuffer recovery, prefetch warm-up) keep the same
        // audio. Without this the factory picks "max bitrate audio" each
        // time, which can flip between dub languages of equal bitrate
        // mid-stall — user-visible bug. Default = ORIGINAL track when
        // NewPipe marks one, else the first language.
        val stickyLang = rememberedAudioLanguage(videoId) ?: run {
            val default = resolved.audioTracks.firstOrNull { it.trackType == AudioTrackKind.ORIGINAL }
                ?.language
                ?: resolved.audioTracks.firstOrNull { !it.language.isNullOrBlank() }?.language
            if (!default.isNullOrBlank()) rememberAudioLanguage(videoId, default)
            default
        }
        val effectiveResolved = if (!stickyLang.isNullOrBlank()) {
            val stickyTracks = resolved.audioTracks.filter { it.language == stickyLang }
            if (stickyTracks.isNotEmpty()) resolved.copy(audioTracks = stickyTracks) else resolved
        } else resolved

        // Prefer the adaptive factory — same path the main PlayerFragment uses.
        // When available it returns a DASH/HLS source with ABR; ExoPlayer's
        // default track selector auto-picks highest quality that fits the
        // bandwidth. Progressive is kept as a safety net for resolve paths
        // where the factory can't build an adaptive source.
        val adaptive = mediaSourceFactory?.let {
            runCatching {
                it.createMediaSourceWithType(
                    resolved = effectiveResolved,
                    audioOnly = false,
                    selectedQuality = null,       // auto-select highest
                    userQualityCapHeight = null,  // no cap
                    forceProgressive = false,     // prefer adaptive
                    videoId = videoId
                )
            }.getOrNull()
        }
        val source = adaptive?.source ?: buildProgressiveSource(effectiveResolved)
        if (source == null) {
            _failureEvents.tryEmit(videoId)
            return
        }

        // Final staleness gate — in case buildProgressiveSource or any prior
        // suspension point yielded and a newer bind arrived in the meantime.
        if (myGen != generation.get()) return

        playerOps.setMediaSource(source)
        playerOps.setRepeatModeOne()
        playerOps.prepare()
        playerOps.setPlayWhenReady(true)
        ttlWatcher?.cancel()
        ttlWatcher = null
        if (featureFlags?.isTtlWatcherEnabled == true &&
            mpdRegistry != null &&
            adaptive?.adaptiveType == com.albunyaan.tube.player.MediaSourceResult.AdaptiveType.SYNTH_ADAPTIVE) {
            ttlWatcher = com.albunyaan.tube.player.MpdTtlWatcher(
                videoId = videoId,
                registry = mpdRegistry,
                onRefreshNeeded = { forceRefreshCurrent() }
            ).also { it.start(scope) }
        }
    }

    private fun buildProgressiveSource(
        resolved: ResolvedStreams,
        qualityCapHeight: Int? = SHORTS_FALLBACK_STARTUP_MAX_HEIGHT
    ): MediaSource? {
        val dataSourceFactory = buildFallbackDataSourceFactory()

        // Prefer muxed (video+audio) progressive tracks. Pick a fast-start
        // quality first; shorts can upgrade through the adaptive path when it
        // is available, but fallback should not start at max bitrate.
        val muxed: VideoTrack? = chooseProgressiveTrack(
            tracks = resolved.videoTracks.filter { !it.isVideoOnly && !it.url.isNullOrBlank() },
            qualityCapHeight = qualityCapHeight
        )

        if (muxed != null) {
            return ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(muxed.url))
        }

        val bestVideo: VideoTrack? = chooseProgressiveTrack(
            tracks = resolved.videoTracks.filter { !it.url.isNullOrBlank() },
            qualityCapHeight = qualityCapHeight
        )
        val bestAudio: AudioTrack? = resolved.audioTracks
            .filter { !it.url.isNullOrBlank() }
            .maxByOrNull { it.bitrate ?: 0 }

        if (bestVideo != null && bestAudio != null) {
            val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(bestVideo.url))
            val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(bestAudio.url))
            return MergingMediaSource(videoSource, audioSource)
        }

        if (bestVideo != null) {
            return ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(bestVideo.url))
        }

        return null
    }

    private fun buildFallbackDataSourceFactory(): DataSource.Factory {
        val httpFactory = if (featureFlags?.isCronetEnabled == true && cronetDataSourceFactory != null) {
            cronetDataSourceFactory.createForAndroidUA()
        } else {
            DefaultHttpDataSource.Factory()
                .setUserAgent(HttpConstants.YOUTUBE_USER_AGENT)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
                .setAllowCrossProtocolRedirects(true)
        }
        val upstreamFactory = context?.let { DefaultDataSource.Factory(it, httpFactory) } ?: httpFactory
        val cache = simpleCache ?: return upstreamFactory
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun chooseProgressiveTrack(
        tracks: List<VideoTrack>,
        qualityCapHeight: Int?
    ): VideoTrack? {
        val playable = tracks.filter { !it.url.isNullOrBlank() }
        val highestQuality = compareBy<VideoTrack> { trackStartupSize(it) }.thenBy { it.bitrate ?: 0 }
        if (qualityCapHeight == null) {
            return playable.maxWithOrNull(highestQuality)
        }

        val capped = playable.filter { track -> trackStartupSize(track) in 1..qualityCapHeight }
        if (capped.isNotEmpty()) {
            return capped.maxWithOrNull(highestQuality)
        }

        return playable.minWithOrNull(
            compareBy<VideoTrack> { trackStartupSize(it).takeIf { size -> size > 0 } ?: Int.MAX_VALUE }
                .thenBy { it.bitrate ?: Int.MAX_VALUE }
        )
    }

    private fun trackStartupSize(track: VideoTrack): Int {
        val dimensions = listOfNotNull(track.width, track.height).filter { it > 0 }
        return dimensions.minOrNull() ?: track.height ?: 0
    }

    /**
     * Swap the audio track for the currently-bound video without tearing the
     * player down. Rebuilds the MediaSource with [chosen] as the sole audio
     * stream — the adaptive factory (or progressive fallback) merges it with
     * the current video path. Preserves playback position and play/pause
     * state so the short resumes exactly where the user was.
     *
     * Safe no-op if there is no cached resolved-streams entry for [videoId]
     * (e.g. called before the first successful resolve).
     */
    fun switchAudioTrack(videoId: String, chosen: AudioTrack) {
        val resolved = resolvedStreamsFor(videoId) ?: return
        // Pin the user's language choice so subsequent re-resolves keep the
        // same audio instead of letting the factory re-pick by bitrate.
        rememberAudioLanguage(videoId, chosen.language)
        val filtered = resolved.copy(audioTracks = listOf(chosen))
        val position = playerOps.getCurrentPosition()
        val wasPlaying = playerOps.getPlayWhenReady()

        val adaptive = mediaSourceFactory?.let {
            runCatching {
                it.createMediaSourceWithType(
                    resolved = filtered,
                    audioOnly = false,
                    selectedQuality = null,
                    userQualityCapHeight = null,
                    forceProgressive = false,
                    videoId = videoId
                )
            }.getOrNull()
        }
        val source = adaptive?.source ?: buildProgressiveSource(filtered, qualityCapHeight = null) ?: return

        // Refresh cache so a subsequent download picker reflects the active
        // audio choice alongside the existing video tracks.
        synchronized(resolvedCache) { resolvedCache[videoId] = filtered }

        playerOps.setMediaSource(source)
        playerOps.setRepeatModeOne()
        playerOps.prepare()
        playerOps.seekTo(position)
        playerOps.setPlayWhenReady(wasPlaying)
    }

    /**
     * Switch the playback quality cap for the currently-bound video.
     * `capHeightPx == 0` (or negative) clears the cap (auto / ABR-driven).
     *
     * Most YouTube videos on shorts end up as single-rep synthetic DASH
     * (video tracks have inconsistent containers, so SYNTH_ADAPTIVE
     * fails). With single-rep the manifest holds exactly one video
     * track, so a track-selector cap can't pick a different quality —
     * the manifest itself has to be regenerated with a different track.
     * This rebuilds the MediaSource via the same factory path used at
     * initial prep, passing the new cap so the synthetic-DASH builder
     * picks the appropriate track.
     *
     * Preserves position and playWhenReady so the short resumes exactly
     * where the user was. Safe no-op if there is no cached resolved-
     * streams entry for [videoId].
     */
    fun switchQuality(videoId: String, capHeightPx: Int) {
        val resolved = resolvedStreamsFor(videoId) ?: return
        val cap = capHeightPx.takeIf { it > 0 }
        val origin = if (cap != null) {
            com.albunyaan.tube.data.extractor.QualitySelectionOrigin.MANUAL
        } else {
            com.albunyaan.tube.data.extractor.QualitySelectionOrigin.AUTO
        }
        val position = playerOps.getCurrentPosition()
        val wasPlaying = playerOps.getPlayWhenReady()

        val adaptive = mediaSourceFactory?.let {
            runCatching {
                it.createMediaSourceWithType(
                    resolved = resolved,
                    audioOnly = false,
                    selectedQuality = null,
                    userQualityCapHeight = cap,
                    selectionOrigin = origin,
                    forceProgressive = false,
                    videoId = videoId
                )
            }.getOrNull()
        }
        val source = adaptive?.source ?: buildProgressiveSource(resolved, qualityCapHeight = cap) ?: return

        playerOps.setMediaSource(source)
        playerOps.setRepeatModeOne()
        playerOps.prepare()
        playerOps.seekTo(position)
        playerOps.setPlayWhenReady(wasPlaying)
    }

    /** Flip between play and pause on a tap. */
    fun togglePlayPause() {
        playerOps.setPlayWhenReady(!playerOps.getPlayWhenReady())
    }

    /** True if playback is currently running. */
    fun isPlaying(): Boolean = playerOps.getPlayWhenReady()

    /**
     * Pause playback without releasing the player. Used for lifecycle
     * transitions (fragment backgrounded) so audio/video stop bleeding through
     * when the user isn't looking. Distinct from [togglePlayPause] which the
     * user triggers via the tap target.
     */
    fun pause() { playerOps.setPlayWhenReady(false) }

    /** Resume playback. Pair with [pause] on lifecycle return. */
    fun resume() { playerOps.setPlayWhenReady(true) }

    /** Detach the player from any bound PlayerView. Safe to call multiple times. */
    fun detach() {
        boundView?.let { attach.attach(it, attached = false) }
        boundView = null
    }

    /**
     * Cancel the internal coroutine scope without releasing the player.
     *
     * Use this from the fragment lifecycle (e.g. `onDestroyView`) when the
     * player itself is owned by another component (the ViewModel) that will
     * outlive the fragment. Cancelling the scope aborts any in-flight
     * [bind] resolution and prevents late-arriving resolutions from mutating
     * the player after the fragment view is gone.
     */
    fun cancelScope() {
        ttlWatcher?.cancel()
        ttlWatcher = null
        scope.cancel()
        bindJob = null
        boundVideoId = null
        boundSourceChannelId = null
        scopeCancelled = true
    }


    companion object {
        /** Max number of resolved-stream entries kept for the download picker. */
        private const val MAX_RESOLVED_CACHE = 8

        /** Progressive fallback starts low for first frame speed, then adaptive can upgrade. */
        private const val SHORTS_FALLBACK_STARTUP_MAX_HEIGHT = 480
    }
}
