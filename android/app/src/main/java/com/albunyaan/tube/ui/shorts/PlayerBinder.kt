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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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
 */
class PlayerBinder(
    private val player: ExoPlayer,
    private val playerRepository: PlayerRepository
) {

    private var boundView: PlayerView? = null

    private val _failureEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    /** Emits the failing videoId when stream resolution throws or returns null. */
    val failureEvents: SharedFlow<String> = _failureEvents.asSharedFlow()

    /**
     * Detach the player from any previously bound PlayerView and attach it to
     * [target], then resolve and begin playback for [videoId].
     *
     * Suspends until the stream is resolved; the caller should launch this in
     * a coroutine scoped to the fragment's lifecycle. On failure, emits to
     * [failureEvents] (suppressed — does not throw) and returns.
     */
    suspend fun bind(target: PlayerView, videoId: String) {
        // 1. Detach previous attachment first to guarantee single-audio-stream.
        if (boundView !== target) {
            boundView?.player = null
            target.player = player
            boundView = target
        }
        // 2. Reset existing playback so the prior short's audio doesn't bleed
        //    through while we resolve the new one.
        player.stop()
        player.clearMediaItems()

        prepareAndPlay(videoId)
    }

    private suspend fun prepareAndPlay(videoId: String) {
        val resolved: ResolvedStreams? = runCatching {
            playerRepository.resolveStreams(videoId, forceRefresh = false)
        }.getOrNull()

        if (resolved == null) {
            _failureEvents.tryEmit(videoId)
            return
        }

        val source = buildProgressiveSource(resolved)
        if (source == null) {
            _failureEvents.tryEmit(videoId)
            return
        }

        player.setMediaSource(source)
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.prepare()
        player.playWhenReady = true
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

    /** Flip between play and pause on a tap. */
    fun togglePlayPause() {
        player.playWhenReady = !player.playWhenReady
    }

    /** Detach the player from any bound PlayerView. Safe to call multiple times. */
    fun detach() {
        boundView?.player = null
        boundView = null
    }

    /** Release the underlying player. Call from ViewModel.onCleared(). */
    fun release() {
        detach()
        player.release()
    }
}
