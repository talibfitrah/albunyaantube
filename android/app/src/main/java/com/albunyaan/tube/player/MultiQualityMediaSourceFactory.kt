package com.albunyaan.tube.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.VideoTrack

/**
 * Factory for creating MediaSources from NewPipe extractor data.
 *
 * Streaming strategy:
 * - For videos >= 10 minutes: Prefer HLS/DASH adaptive streaming for better seek performance and
 *   automatic quality adaptation. Falls back to progressive if adaptive not available.
 * - For shorter videos: Use progressive streams with selected quality.
 *
 * NOTE: Media3's quality selection in settings menu ONLY works with adaptive streams (HLS/DASH).
 * For progressive streams, quality selection is handled via custom UI.
 */
@OptIn(UnstableApi::class)
class MultiQualityMediaSourceFactory(private val context: Context) {

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
        // Timeouts balanced for reliability and responsiveness
        .setConnectTimeoutMs(15000)  // 15s connect timeout
        .setReadTimeoutMs(20000)     // 20s read timeout (balances reliability with responsiveness)
        .setAllowCrossProtocolRedirects(true)  // Allow HTTP -> HTTPS redirects

    private val dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(
        context,
        httpDataSourceFactory
    )

    // Cache factory to enable HTTP response caching for faster subsequent loads
    private val cacheDataSourceFactory: DataSource.Factory = CacheDataSource.Factory()
        .setCache(getOrCreateCache(context))
        .setUpstreamDataSourceFactory(dataSourceFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    companion object {
        private const val TAG = "MultiQualityMediaSource"
        /** Videos >= 10 minutes use adaptive streaming when available */
        private const val LONG_VIDEO_THRESHOLD_SECONDS = 600

        private var simpleCache: SimpleCache? = null

        @Synchronized
        private fun getOrCreateCache(context: Context): SimpleCache {
            if (simpleCache == null) {
                val cacheDir = java.io.File(context.cacheDir, "media3")
                val databaseProvider = StandaloneDatabaseProvider(context)
                // 100MB cache for video chunks
                val evictor = LeastRecentlyUsedCacheEvictor(100 * 1024 * 1024L)
                simpleCache = SimpleCache(cacheDir, evictor, databaseProvider)
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
     * For long videos (>= 10 min), prefers HLS/DASH adaptive streaming for:
     * - Better seek performance (no re-download from start)
     * - Automatic quality adaptation based on network
     * - More reliable playback on variable connections
     *
     * For short videos or when adaptive unavailable, uses progressive streaming.
     *
     * @param resolved The resolved streams from NewPipe extractor
     * @param audioOnly Whether to create audio-only MediaSource
     * @param selectedQuality Optional specific quality to select (by VideoTrack reference)
     * @param forceProgressive If true, forces selection of the lowest available quality.
     *        Prefer using [createMediaSourceWithType] with userQualityCapHeight for fine-grained control.
     * @return MediaSource configured for ExoPlayer playback
     */
    fun createMediaSource(
        resolved: ResolvedStreams,
        audioOnly: Boolean,
        selectedQuality: VideoTrack? = null,
        forceProgressive: Boolean = false
    ): MediaSource {
        // When forcing progressive/lowest quality, use the minimum available height
        val qualityCap = if (forceProgressive) {
            // Fall back to 1 if all heights are null, which will select lowest bitrate track
            resolved.videoTracks.mapNotNull { it.height }.minOrNull() ?: 1
        } else {
            null
        }
        return createMediaSourceWithType(resolved, audioOnly, selectedQuality, qualityCap).first
    }

    /**
     * Creates a MediaSource and indicates whether it's adaptive (HLS/DASH) or progressive.
     *
     * Strategy:
     * - For long videos (>= 10 min): Use HLS/DASH adaptive streaming when available.
     *   Quality cap is applied via track selector constraints (ABR can drop below, not above).
     * - For short videos or when adaptive unavailable: Use progressive streaming.
     *   Quality cap selects the best track under the cap.
     *
     * **IMPORTANT**: For adaptive sources (when isAdaptive=true is returned), this factory does NOT
     * apply the quality cap to the MediaSource itself. Callers MUST configure the player's
     * DefaultTrackSelector to enforce the quality cap. Example:
     * ```
     * val (source, isAdaptive) = factory.createMediaSourceWithType(resolved, false, null, 720)
     * if (isAdaptive && userQualityCapHeight != null) {
     *     trackSelector.setParameters(
     *         trackSelector.buildUponParameters()
     *             .setMaxVideoSize(Int.MAX_VALUE, userQualityCapHeight)
     *             .build()
     *     )
     * }
     * ```
     *
     * @param resolved The resolved streams from NewPipe extractor
     * @param audioOnly Whether to create audio-only MediaSource
     * @param selectedQuality The video track to use (for progressive) or reference quality
     * @param userQualityCapHeight User's quality cap height (null = AUTO, ABR chooses freely).
     *        For progressive sources, this selects the best track under the cap.
     *        For adaptive sources, caller must apply via DefaultTrackSelector.
     * @return Pair of (MediaSource, isAdaptive)
     */
    fun createMediaSourceWithType(
        resolved: ResolvedStreams,
        audioOnly: Boolean,
        selectedQuality: VideoTrack? = null,
        userQualityCapHeight: Int? = null
    ): Pair<MediaSource, Boolean> {
        if (audioOnly) {
            return createAudioOnlySource(resolved) to false
        }

        val durationSeconds = resolved.durationSeconds ?: 0
        val isLongVideo = durationSeconds >= LONG_VIDEO_THRESHOLD_SECONDS

        // For long videos, prefer HLS/DASH adaptive streaming
        // Quality cap is applied via track selector, NOT by forcing progressive
        if (isLongVideo) {
            val adaptiveSource = tryCreateAdaptiveSource(resolved)
            if (adaptiveSource != null) {
                val capInfo = userQualityCapHeight?.let { "${it}p cap" } ?: "no cap (ABR free)"
                android.util.Log.d(TAG, "Using adaptive streaming for ${durationSeconds}s video ($capInfo)")
                if (userQualityCapHeight != null) {
                    android.util.Log.w(TAG, "Quality cap must be applied via DefaultTrackSelector by caller")
                }
                return adaptiveSource to true
            }
            android.util.Log.d(TAG, "Adaptive streaming not available, falling back to progressive")
        }

        // Progressive streaming: select best track under quality cap
        val videoTrack = if (userQualityCapHeight != null) {
            // When quality cap is set, respect it: use lowest available if none under cap
            findBestTrackUnderCap(resolved.videoTracks, userQualityCapHeight)
                ?: resolved.videoTracks.minByOrNull { it.height ?: Int.MAX_VALUE }
        } else {
            selectedQuality ?: resolved.videoTracks.maxByOrNull { it.height ?: 0 }
        }

        if (videoTrack == null) {
            throw IllegalArgumentException("No video tracks available for playback")
        }

        android.util.Log.d(TAG, "Using progressive streaming: ${videoTrack.qualityLabel} (${videoTrack.height}p)")
        return createVideoMediaSource(videoTrack, resolved) to false
    }

    /**
     * Find the best video track that respects the given height cap.
     * Prefers muxed streams over video-only for reliability.
     */
    private fun findBestTrackUnderCap(tracks: List<VideoTrack>, capHeight: Int): VideoTrack? {
        return tracks
            .filter { it.height != null && it.height <= capHeight }
            .sortedWith(
                compareByDescending<VideoTrack> { it.height ?: 0 }
                    .thenBy { it.isVideoOnly } // prefer muxed
                    .thenByDescending { it.bitrate ?: 0 }
            )
            .firstOrNull()
    }

    /**
     * Try to create an adaptive streaming MediaSource (HLS or DASH).
     * Returns null if no adaptive streams are available.
     */
    private fun tryCreateAdaptiveSource(resolved: ResolvedStreams): MediaSource? {
        // Prefer HLS over DASH for better compatibility
        resolved.hlsUrl?.let { hlsUrl ->
            return try {
                val mediaItem = MediaItem.Builder()
                    .setUri(hlsUrl)
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build()
                HlsMediaSource.Factory(dataSourceFactory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(mediaItem)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to create HLS source: ${e.message}")
                null
            }
        }

        resolved.dashUrl?.let { dashUrl ->
            return try {
                val mediaItem = MediaItem.Builder()
                    .setUri(dashUrl)
                    .setMimeType(MimeTypes.APPLICATION_MPD)
                    .build()
                DashMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(mediaItem)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to create DASH source: ${e.message}")
                null
            }
        }

        return null
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

        // Only merge when the chosen stream is video-only. Muxed streams already contain audio.
        if (videoTrack.isVideoOnly && resolved.audioTracks.isNotEmpty()) {
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
