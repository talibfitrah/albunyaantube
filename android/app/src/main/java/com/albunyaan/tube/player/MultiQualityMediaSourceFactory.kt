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
            .setConnectTimeoutMs(8000)   // Faster timeout for quicker failure detection
            .setReadTimeoutMs(8000)      // Faster read timeout
            .setAllowCrossProtocolRedirects(true)  // Allow HTTP -> HTTPS redirects
    )

    // Cache factory to enable HTTP response caching for faster subsequent loads
    private val cacheDataSourceFactory: DataSource.Factory = com.google.android.exoplayer2.upstream.cache.CacheDataSource.Factory()
        .setCache(getOrCreateCache(context))
        .setUpstreamDataSourceFactory(dataSourceFactory)
        .setFlags(com.google.android.exoplayer2.upstream.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    companion object {
        private var simpleCache: com.google.android.exoplayer2.upstream.cache.SimpleCache? = null

        @Synchronized
        private fun getOrCreateCache(context: Context): com.google.android.exoplayer2.upstream.cache.SimpleCache {
            if (simpleCache == null) {
                val cacheDir = java.io.File(context.cacheDir, "exoplayer")
                val databaseProvider = com.google.android.exoplayer2.database.StandaloneDatabaseProvider(context)
                // 100MB cache for video chunks
                val evictor = com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor(100 * 1024 * 1024L)
                simpleCache = com.google.android.exoplayer2.upstream.cache.SimpleCache(cacheDir, evictor, databaseProvider)
            }
            return simpleCache!!
        }

        /**
         * Releases the cache and clears references to prevent memory leaks.
         * Should be called from the Application lifecycle (e.g., in onTerminate).
         */
        @Synchronized
        fun releaseCache() {
            simpleCache?.release()
            simpleCache = null
        }
    }

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

        // Use cached data source for faster subsequent loads
        val videoSource = ProgressiveMediaSource.Factory(cacheDataSourceFactory)
            .createMediaSource(videoMediaItem)

        // If we have separate audio tracks, merge video with best audio
        if (resolved.audioTracks.isNotEmpty()) {
            val audioTrack = resolved.audioTracks.maxByOrNull { it.bitrate ?: 0 }
                ?: resolved.audioTracks.first()

            val audioMediaItem = MediaItem.Builder()
                .setUri(audioTrack.url)
                .setMimeType(audioTrack.mimeType ?: "audio/mp4")
                .build()

            val audioSource = ProgressiveMediaSource.Factory(cacheDataSourceFactory)
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

        return ProgressiveMediaSource.Factory(cacheDataSourceFactory)
            .createMediaSource(mediaItem)
    }
}
