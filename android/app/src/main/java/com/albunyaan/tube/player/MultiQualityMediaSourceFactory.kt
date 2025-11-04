package com.albunyaan.tube.player

import android.content.Context
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.VideoTrack
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.MergingMediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource

/**
 * Factory for creating MediaSources from NewPipe extractor data.
 *
 * NOTE: ExoPlayer's quality selection in settings menu ONLY works with adaptive streams (HLS/DASH).
 * Since NewPipe provides discrete progressive URLs, we cannot show quality in ExoPlayer's native
 * settings menu. Quality selection must be handled via custom UI.
 *
 * This factory creates a simple progressive MediaSource for the selected quality.
 */
class MultiQualityMediaSourceFactory(context: Context) {

    private val dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(
        context,
        DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
            .setConnectTimeoutMs(10000)  // Reduced from 30s to 10s for faster quality switching
            .setReadTimeoutMs(10000)      // Reduced from 30s to 10s
            .setAllowCrossProtocolRedirects(true)  // Allow HTTP -> HTTPS redirects
    )

    /**
     * Creates a MediaSource for the selected quality.
     *
     * For progressive streams (NewPipe), we can only load ONE quality at a time.
     * ExoPlayer's settings menu won't show quality options for progressive streams.
     *
     * @param resolved The resolved streams from NewPipe extractor
     * @param audioOnly Whether to create audio-only MediaSource
     * @param selectedQuality Optional specific quality to select (by VideoTrack reference)
     * @return MediaSource configured for ExoPlayer playback
     */
    fun createMediaSource(
        resolved: ResolvedStreams,
        audioOnly: Boolean,
        selectedQuality: VideoTrack? = null
    ): MediaSource {
        if (audioOnly) {
            return createAudioOnlySource(resolved)
        }

        // Get the selected video quality or default to best available
        val videoTrack = selectedQuality ?: resolved.videoTracks.maxByOrNull { it.height ?: 0 }

        if (videoTrack == null) {
            // Fallback to audio if no video available
            return createAudioOnlySource(resolved)
        }

        return createVideoMediaSource(videoTrack, resolved)
    }

    /**
     * Creates a MediaSource for a specific video quality.
     * For video-only streams (high quality), merges with best audio.
     * For muxed streams (low quality), uses as-is.
     */
    private fun createVideoMediaSource(videoTrack: VideoTrack, resolved: ResolvedStreams): MediaSource {
        val videoMediaItem = MediaItem.Builder()
            .setUri(videoTrack.url)
            .setMimeType(videoTrack.mimeType ?: "video/mp4")
            .build()

        val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(videoMediaItem)

        // If we have separate audio tracks, merge video with best audio
        if (resolved.audioTracks.isNotEmpty()) {
            val audioTrack = resolved.audioTracks.maxByOrNull { it.bitrate ?: 0 }
                ?: resolved.audioTracks.first()

            val audioMediaItem = MediaItem.Builder()
                .setUri(audioTrack.url)
                .setMimeType(audioTrack.mimeType ?: "audio/mp4")
                .build()

            val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(audioMediaItem)

            return MergingMediaSource(videoSource, audioSource)
        }

        // No separate audio, assume video is muxed with audio
        return videoSource
    }

    /**
     * Creates an audio-only MediaSource.
     */
    private fun createAudioOnlySource(resolved: ResolvedStreams): MediaSource {
        val audioTrack = resolved.audioTracks.maxByOrNull { it.bitrate ?: 0 }
            ?: resolved.audioTracks.firstOrNull()
            ?: throw IllegalArgumentException("No audio tracks available")

        val mediaItem = MediaItem.Builder()
            .setUri(audioTrack.url)
            .setMimeType(audioTrack.mimeType ?: "audio/mp4")
            .build()

        return ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(mediaItem)
    }
}
