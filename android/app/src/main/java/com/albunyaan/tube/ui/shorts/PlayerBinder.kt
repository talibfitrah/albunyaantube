package com.albunyaan.tube.ui.shorts

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
// (Player import retained for REPEAT_MODE_ONE constant)
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.VideoTrack
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
 * Stream resolution uses [PlayerRepository.resolveStreams]. Because shorts are
 * short progressive clips (<= 60 s), we avoid [com.albunyaan.tube.player.MultiQualityMediaSourceFactory]'s
 * adaptive DASH/HLS machinery and build a simple [ProgressiveMediaSource]:
 *
 * 1. Prefer a muxed ([VideoTrack.isVideoOnly] == false) video track's URL — this
 *    delivers both video and audio in a single progressive stream.
 * 2. Otherwise pick the best video-only track + best audio track and merge them
 *    with [MergingMediaSource] (standard Media3 pattern).
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
    private val attach: PlayerViewAttach
) {

    /** Production constructor — wires the real ExoPlayer-backed ops. */
    constructor(
        player: ExoPlayer,
        playerRepository: PlayerRepository,
        mediaSourceFactory: com.albunyaan.tube.player.MultiQualityMediaSourceFactory
    ) : this(
        player = player,
        playerRepository = playerRepository,
        mediaSourceFactory = mediaSourceFactory,
        playerOps = ExoPlayerOps(player),
        attach = PlayerViewAttach { view, attached ->
            if (attached) view.player = player else view.player = null
        }
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
        fun release()
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
        override fun release() = player.release()
        override fun getCurrentPosition(): Long = player.currentPosition
        override fun seekTo(positionMs: Long) { player.seekTo(positionMs) }
    }

    private var boundView: PlayerView? = null

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
    fun bind(target: PlayerView, videoId: String) {
        check(!scopeCancelled) {
            "PlayerBinder.bind called after cancelScope/release; binder must not be reused"
        }
        val myGen = generation.incrementAndGet()

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
        }
        playerOps.stop()
        playerOps.clearMediaItems()

        bindJob = scope.launch {
            prepareAndPlay(videoId, myGen)
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

    private suspend fun prepareAndPlay(videoId: String, myGen: Int) {
        val resolved: ResolvedStreams? = runCatching {
            playerRepository.resolveStreams(videoId, forceRefresh = false)
        }.getOrNull()

        // Discard if a newer bind has superseded this one.
        if (myGen != generation.get()) return

        if (resolved == null) {
            _failureEvents.tryEmit(videoId)
            return
        }

        synchronized(resolvedCache) { resolvedCache[videoId] = resolved }
        _resolvedEvents.tryEmit(videoId to resolved)

        // Prefer the adaptive factory — same path the main PlayerFragment uses.
        // When available it returns a DASH/HLS source with ABR; ExoPlayer's
        // default track selector auto-picks highest quality that fits the
        // bandwidth. Progressive is kept as a safety net for resolve paths
        // where the factory can't build an adaptive source.
        val adaptive = mediaSourceFactory?.let {
            runCatching {
                it.createMediaSourceWithType(
                    resolved = resolved,
                    audioOnly = false,
                    selectedQuality = null,       // auto-select highest
                    userQualityCapHeight = null,  // no cap
                    forceProgressive = false,     // prefer adaptive
                    videoId = videoId
                )
            }.getOrNull()
        }
        val source = adaptive?.source ?: buildProgressiveSource(resolved)
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
    }

    private fun buildProgressiveSource(resolved: ResolvedStreams): MediaSource? {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(HttpConstants.YOUTUBE_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)

        // Prefer muxed (video+audio) progressive tracks — simplest, no merge overhead.
        val muxed: VideoTrack? = resolved.videoTracks
            .filter { !it.isVideoOnly && !it.url.isNullOrBlank() }
            .maxByOrNull { it.bitrate ?: 0 }

        if (muxed != null) {
            return ProgressiveMediaSource.Factory(httpFactory)
                .createMediaSource(MediaItem.fromUri(muxed.url))
        }

        // Fall back: best video-only + best audio, merged.
        val bestVideo: VideoTrack? = resolved.videoTracks
            .filter { !it.url.isNullOrBlank() }
            .maxByOrNull { it.bitrate ?: (it.height ?: 0) * 1000 }
        val bestAudio: AudioTrack? = resolved.audioTracks
            .filter { !it.url.isNullOrBlank() }
            .maxByOrNull { it.bitrate ?: 0 }

        if (bestVideo != null && bestAudio != null) {
            val videoSource = ProgressiveMediaSource.Factory(httpFactory)
                .createMediaSource(MediaItem.fromUri(bestVideo.url))
            val audioSource = ProgressiveMediaSource.Factory(httpFactory)
                .createMediaSource(MediaItem.fromUri(bestAudio.url))
            return MergingMediaSource(videoSource, audioSource)
        }

        // Last resort: if only a video track is available (audio muxed in but
        // flagged video-only due to metadata glitches), play it alone.
        if (bestVideo != null) {
            return ProgressiveMediaSource.Factory(httpFactory)
                .createMediaSource(MediaItem.fromUri(bestVideo.url))
        }

        return null
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
        val source = adaptive?.source ?: buildProgressiveSource(filtered) ?: return

        // Refresh cache so a subsequent download picker reflects the active
        // audio choice alongside the existing video tracks.
        synchronized(resolvedCache) { resolvedCache[videoId] = filtered }

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
     *
     * Distinct from [release] — this does NOT touch [playerOps.release].
     */
    fun cancelScope() {
        scope.cancel()
        bindJob = null
        scopeCancelled = true
    }

    /**
     * Release the underlying player AND cancel the internal scope.
     *
     * Only safe to call from contexts that own the player (currently unused,
     * since the player is owned by [ShortsPlayerViewModel]). Kept as an
     * escape hatch for future refactors where the binder owns the player.
     */
    fun release() {
        scope.cancel()
        bindJob = null
        scopeCancelled = true
        detach()
        playerOps.release()
    }

    companion object {
        /** Max number of resolved-stream entries kept for the download picker. */
        private const val MAX_RESOLVED_CACHE = 8
    }
}
