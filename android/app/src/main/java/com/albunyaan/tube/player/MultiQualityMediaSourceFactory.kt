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
import com.albunyaan.tube.data.extractor.QualitySelectionOrigin
import com.albunyaan.tube.util.HttpConstants

/**
 * Result of creating a MediaSource. Contains the source, type info, and actual URL used.
 */
data class MediaSourceResult(
    val source: MediaSource,
    val isAdaptive: Boolean,
    /** The actual manifest/video/audio URL used (for identity tracking). May be null for audio-only mode. */
    val actualSourceUrl: String?,
    /** Which adaptive type was used, if any */
    val adaptiveType: AdaptiveType = AdaptiveType.NONE,
    /**
     * The video track actually selected by the factory for progressive/synthetic DASH sources.
     * Used by proactive downshift to know the true "current" quality (may differ from selection.video
     * when factory applies cold-start quality selection in AUTO mode).
     * Null for adaptive HLS/DASH (ABR handles quality) or audio-only mode.
     */
    val selectedVideoTrack: VideoTrack? = null
) {
    enum class AdaptiveType { NONE, HLS, DASH, SYNTHETIC_DASH, SYNTH_ADAPTIVE }
}

/**
 * Factory for creating MediaSources from NewPipe extractor data.
 *
 * Streaming strategy (Phase 2 update):
 * 1. Prefer HLS/DASH adaptive streaming whenever available (smooth ABR playback, better seeks).
 * 2. Try SYNTH_ADAPTIVE (multi-rep synthetic DASH) when 2+ video-only tracks exist (ABR capable).
 * 3. Try single-rep synthetic DASH for video-only + audio progressive streams (improved seeks).
 * 4. Fall back to raw progressive only when all above fail.
 *
 * Phase 1B: HLS Poison Gate
 * Before selecting HLS, checks HlsPoisonRegistry to skip videos with known HLS failures.
 * This prevents repeatedly attempting HLS when it's known to 403 for specific videos.
 *
 * Phase 2: SYNTH_ADAPTIVE
 * Multi-representation synthetic DASH enables quality switching without true HLS/DASH manifests.
 * ExoPlayer's ABR logic can switch between progressive streams bundled in a single MPD.
 *
 * NOTE: Media3's quality selection in settings menu ONLY works with adaptive streams (HLS/DASH).
 * For progressive streams, quality selection is handled via custom UI.
 */
@OptIn(UnstableApi::class)
class MultiQualityMediaSourceFactory(
    private val context: Context,
    private val hlsPoisonRegistry: HlsPoisonRegistry? = null,
    private val multiRepFactory: MultiRepSyntheticDashMediaSourceFactory? = null,
    private val coldStartQualityChooser: ColdStartQualityChooser? = null,
    private val featureFlags: PlaybackFeatureFlags? = null
) {

    // Standard data source for progressive/DASH (Android User-Agent)
    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(HttpConstants.YOUTUBE_USER_AGENT)
        // Timeouts balanced for reliability and responsiveness
        .setConnectTimeoutMs(15000)  // 15s connect timeout
        .setReadTimeoutMs(20000)     // 20s read timeout (balances reliability with responsiveness)
        .setAllowCrossProtocolRedirects(true)  // Allow HTTP -> HTTPS redirects

    /**
     * HLS-specific data source with iOS User-Agent.
     *
     * **Design Decision: Always use iOS UA for HLS**
     *
     * When iOS client fetch is enabled (BuildConfig.ENABLE_NPE_IOS_FETCH), HLS manifest URLs
     * are returned from YouTube's iOS endpoint. These HLS segment URLs expect iOS-like headers;
     * using an Android User-Agent causes HTTP 403 errors.
     *
     * We always use iOS UA for HLS rather than conditionally checking the flag because:
     * 1. It's harmless for non-iOS HLS URLs (YouTube has not been observed to validate UA for HLS manifests;
     *    if an edge case surfaces where non-iOS HLS fails with iOS UA, we can add conditional logic)
     * 2. It simplifies the code (no runtime flag checking in data source selection)
     * 3. It future-proofs against enabling the iOS fetch flag later (no code changes needed here)
     * 4. The HLS URLs we receive when iOS fetch is OFF are rare (most VODs lack HLS without iOS)
     *
     * The coupling is intentional: NPE's iOS client fetch returns URLs that expect this UA.
     * See: HttpConstants.YOUTUBE_IOS_USER_AGENT, NewPipeExtractorClient.initializeNewPipe()
     */
    private val hlsHttpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(HttpConstants.YOUTUBE_IOS_USER_AGENT)
        .setConnectTimeoutMs(15000)
        .setReadTimeoutMs(20000)
        .setAllowCrossProtocolRedirects(true)

    private val dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(
        context,
        httpDataSourceFactory
    )

    private val hlsDataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(
        context,
        hlsHttpDataSourceFactory
    )

    // Cache factory to enable HTTP response caching for faster subsequent loads
    private val cacheDataSourceFactory: DataSource.Factory = CacheDataSource.Factory()
        .setCache(getOrCreateCache(context))
        .setUpstreamDataSourceFactory(dataSourceFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    // HLS-specific cache factory with iOS User-Agent
    private val hlsCacheDataSourceFactory: DataSource.Factory = CacheDataSource.Factory()
        .setCache(getOrCreateCache(context))
        .setUpstreamDataSourceFactory(hlsDataSourceFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    // PR6.1: Synthetic DASH factory for progressive stream wrapping
    private val syntheticDashFactory by lazy {
        SyntheticDashMediaSourceFactory(cacheDataSourceFactory)
    }

    companion object {
        private const val TAG = "MultiQualityMediaSource"

        /**
         * Fallback initial quality ceiling when ColdStartQualityChooser is unavailable.
         * 720p offers a good balance of quality and bandwidth for most connections.
         *
         * For progressive/synthetic DASH streams (no ABR), if throughput can't keep up:
         * - BufferHealthMonitor will detect declining buffer and trigger proactive downshift
         * - Early-stall exception handles critical situations during grace period
         * - Predictive downshift catches gradual depletion before stall
         *
         * Phase 3: Prefer using ColdStartQualityChooser for context-aware initial quality.
         */
        private const val FALLBACK_INITIAL_QUALITY_HEIGHT = 720

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
     * Phase 3: Get the effective initial quality height for AUTO mode.
     *
     * Uses ColdStartQualityChooser if available for context-aware selection
     * (network type, screen size, persisted hints). Falls back to 720p.
     */
    private fun getInitialQualityHeight(): Int {
        return try {
            coldStartQualityChooser?.chooseInitialQuality(context)?.recommendedHeight
                ?: FALLBACK_INITIAL_QUALITY_HEIGHT
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to get initial quality from chooser: ${e.message}")
            FALLBACK_INITIAL_QUALITY_HEIGHT
        }
    }

    /**
     * Creates a MediaSource for the selected quality.
     *
     * Prefers HLS/DASH adaptive streaming whenever available for:
     * - Better seek performance (no re-download from start)
     * - Automatic quality adaptation based on network
     * - More reliable playback on variable connections
     *
     * Falls back to progressive streaming when adaptive is unavailable or explicitly forced.
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
        return createMediaSourceWithType(
            resolved = resolved,
            audioOnly = audioOnly,
            selectedQuality = selectedQuality,
            userQualityCapHeight = qualityCap,
            forceProgressive = forceProgressive
        ).source
    }

    /**
     * Creates a MediaSource and returns full result with source identity tracking.
     *
     * Strategy:
     * - Prefer HLS/DASH adaptive streaming whenever available (ABR can drop below cap, not above).
     * - When adaptive is unavailable (or explicitly forced off): Use progressive streaming.
     *   Quality cap selects the best track under the cap.
     *
     * **IMPORTANT**: For adaptive sources (when isAdaptive=true is returned), this factory does NOT
     * apply the quality cap to the MediaSource itself. Callers MUST configure the player's
     * DefaultTrackSelector to enforce the quality cap. Example:
     * ```
     * val result = factory.createMediaSourceWithType(resolved, false, null, 720)
     * if (result.isAdaptive && userQualityCapHeight != null) {
     *     trackSelector.setParameters(
     *         trackSelector.buildUponParameters()
     *             .setMaxVideoSize(Int.MAX_VALUE, userQualityCapHeight)
     *             .build()
     *     )
     * }
     * ```
     *
     * Phase 1B: Pass videoId to check HLS poison status before attempting HLS.
     *
     * @param resolved The resolved streams from NewPipe extractor
     * @param audioOnly Whether to create audio-only MediaSource
     * @param selectedQuality The video track to use (for progressive) or reference quality
     * @param userQualityCapHeight User's quality cap height (null = AUTO, ABR chooses freely).
     *        For progressive sources, this selects the best track under the cap.
     *        For adaptive sources, caller must apply via DefaultTrackSelector.
     * @param selectionOrigin Origin of the selection (used to respect AUTO_RECOVERY step-down).
     * @param forceProgressive If true, skips adaptive selection and always returns a progressive source.
     * @param videoId The video ID, used to check HLS poison registry (Phase 1B).
     * @return MediaSourceResult with source, isAdaptive flag, actual URL used, and adaptive type
     */
    fun createMediaSourceWithType(
        resolved: ResolvedStreams,
        audioOnly: Boolean,
        selectedQuality: VideoTrack? = null,
        userQualityCapHeight: Int? = null,
        selectionOrigin: QualitySelectionOrigin = QualitySelectionOrigin.AUTO,
        forceProgressive: Boolean = false,
        videoId: String? = null
    ): MediaSourceResult {
        if (audioOnly) {
            val audioTrack = resolved.audioTracks.maxByOrNull { it.bitrate ?: 0 }
                ?: resolved.audioTracks.firstOrNull()
            return MediaSourceResult(
                source = createAudioOnlySource(resolved),
                isAdaptive = false,
                actualSourceUrl = audioTrack?.url,
                adaptiveType = MediaSourceResult.AdaptiveType.NONE
            )
        }

        // Prefer HLS/DASH adaptive streaming unless explicitly forced to progressive.
        // Quality cap is applied via track selector, NOT by forcing progressive.
        if (!forceProgressive) {
            val adaptiveResult = tryCreateAdaptiveSource(resolved, videoId)
            if (adaptiveResult != null) {
                val capInfo = userQualityCapHeight?.let { "${it}p cap" } ?: "no cap (ABR free)"
                android.util.Log.d(TAG, "Using ${adaptiveResult.type} adaptive streaming ($capInfo)")
                if (userQualityCapHeight != null) {
                    android.util.Log.w(TAG, "Quality cap must be applied via DefaultTrackSelector by caller")
                }
                return MediaSourceResult(
                    source = adaptiveResult.source,
                    isAdaptive = true,
                    actualSourceUrl = adaptiveResult.url,
                    adaptiveType = adaptiveResult.type
                )
            }
            android.util.Log.d(TAG, "Adaptive streaming not available, trying SYNTH_ADAPTIVE")

            // Phase 2: Try multi-rep synthetic DASH (SYNTH_ADAPTIVE) for ABR capability
            if (multiRepFactory != null && videoId != null) {
                val multiRepResult = tryCreateMultiRepSynthAdaptive(resolved, videoId, userQualityCapHeight)
                if (multiRepResult != null) {
                    val capInfo = userQualityCapHeight?.let { "${it}p cap" } ?: "no cap (ABR free)"
                    android.util.Log.d(TAG, "Using SYNTH_ADAPTIVE streaming ($capInfo)")
                    if (userQualityCapHeight != null) {
                        android.util.Log.w(TAG, "Quality cap must be applied via DefaultTrackSelector by caller")
                    }
                    return MediaSourceResult(
                        source = multiRepResult.source,
                        isAdaptive = true, // Multi-rep enables ABR quality switching
                        actualSourceUrl = multiRepResult.url,
                        adaptiveType = MediaSourceResult.AdaptiveType.SYNTH_ADAPTIVE
                    )
                }
            }
            android.util.Log.d(TAG, "SYNTH_ADAPTIVE not available, trying single-rep synthetic DASH")

            // PR6.1: Try single-rep synthetic DASH for video-only + audio progressive streams
            val syntheticResult = tryCreateSyntheticDashSource(resolved, userQualityCapHeight, selectedQuality, selectionOrigin)
            if (syntheticResult != null) {
                val capInfo = userQualityCapHeight?.let { "${it}p cap" } ?: "no cap"
                android.util.Log.d(TAG, "Using single-rep synthetic DASH streaming ($capInfo)")
                return MediaSourceResult(
                    source = syntheticResult.source,
                    isAdaptive = false, // Not true ABR - still single bitrate
                    actualSourceUrl = syntheticResult.url,
                    adaptiveType = MediaSourceResult.AdaptiveType.SYNTHETIC_DASH,
                    selectedVideoTrack = syntheticResult.selectedVideoTrack
                )
            }
            android.util.Log.d(TAG, "Synthetic DASH not available, falling back to raw progressive")
        } else {
            android.util.Log.d(TAG, "Adaptive streaming skipped (forceProgressive=true)")
        }

        // Raw progressive streaming: select best track under quality cap
        val videoTrack = when {
            // AUTO_RECOVERY should use the explicit stepped-down track even if a user cap exists.
            selectionOrigin == QualitySelectionOrigin.AUTO_RECOVERY && selectedQuality != null -> selectedQuality
            userQualityCapHeight != null -> {
                // When quality cap is set, respect it: use lowest available if none under cap
                findBestTrackUnderCap(resolved.videoTracks, userQualityCapHeight)
                    ?: resolved.videoTracks.minByOrNull { it.height ?: Int.MAX_VALUE }
            }
            else -> {
                // No user cap (AUTO mode): use cold-start quality selection.
                // BufferHealthMonitor handles quality adjustments via predictive/early-stall downshift.
                val initialHeight = getInitialQualityHeight()
                // Fallback chain: 1) ≤initialHeight, 2) lowest with height, 3) first available (null heights)
                findBestTrackUnderCap(resolved.videoTracks, initialHeight)
                    ?: resolved.videoTracks.filter { it.height != null }.minByOrNull { it.height!! }
                    ?: resolved.videoTracks.firstOrNull()
            }
        }

        if (videoTrack == null) {
            throw IllegalArgumentException("No video tracks available for playback")
        }

        android.util.Log.d(TAG, "Using raw progressive streaming: ${videoTrack.qualityLabel} (${videoTrack.height}p)")
        return MediaSourceResult(
            source = createVideoMediaSource(videoTrack, resolved),
            isAdaptive = false,
            actualSourceUrl = videoTrack.url,
            adaptiveType = MediaSourceResult.AdaptiveType.NONE,
            selectedVideoTrack = videoTrack
        )
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
     * Result of adaptive source creation attempt.
     */
    private data class AdaptiveSourceResult(
        val source: MediaSource,
        val url: String,
        val type: MediaSourceResult.AdaptiveType,
        /** For synthetic DASH, the video track that was selected. Null for true adaptive (HLS/DASH). */
        val selectedVideoTrack: VideoTrack? = null
    )

    /**
     * Phase 2: Try to create multi-rep synthetic DASH (SYNTH_ADAPTIVE).
     *
     * This creates a DASH MPD with multiple video representations, enabling
     * ExoPlayer's ABR logic to switch between qualities based on network conditions.
     *
     * Note: The MPD always includes ALL quality levels (full ladder). Quality cap is
     * enforced via track selector constraints, not MPD filtering. This enables instant
     * quality switching without MediaSource rebuild.
     *
     * @param resolved The resolved streams
     * @param videoId The video ID for MPD registry
     * @param userQualityCapHeight Passed for API compatibility but no longer used for MPD filtering.
     *        Quality cap is now enforced via QualityTrackSelector.
     * @return AdaptiveSourceResult if successful, null otherwise
     */
    private fun tryCreateMultiRepSynthAdaptive(
        resolved: ResolvedStreams,
        videoId: String,
        userQualityCapHeight: Int?
    ): AdaptiveSourceResult? {
        // Phase 6: Runtime feature flag for synthetic adaptive DASH
        val synthAdaptiveEnabled = featureFlags?.isSynthAdaptiveEnabled
            ?: com.albunyaan.tube.BuildConfig.ENABLE_SYNTH_ADAPTIVE
        if (!synthAdaptiveEnabled) {
            android.util.Log.d(TAG, "SYNTH_ADAPTIVE disabled via feature flag")
            return null
        }

        val factory = multiRepFactory ?: return null

        // Check eligibility first
        val (eligible, reason) = factory.checkEligibility(resolved)
        if (!eligible) {
            android.util.Log.d(TAG, "SYNTH_ADAPTIVE ineligible: $reason")
            return null
        }

        // Create multi-rep source
        val result = factory.createMediaSource(
            resolved = resolved,
            videoId = videoId,
            context = context,
            qualityCapHeight = userQualityCapHeight
        )

        return when (result) {
            is MultiRepSyntheticDashMediaSourceFactory.Result.Success -> {
                android.util.Log.d(TAG, "SYNTH_ADAPTIVE created: ${result.videoTracks.size} reps (${result.codecFamily})")
                AdaptiveSourceResult(
                    source = result.source,
                    url = "${SyntheticDashDataSource.SCHEME}://$videoId",
                    type = MediaSourceResult.AdaptiveType.SYNTH_ADAPTIVE
                )
            }
            is MultiRepSyntheticDashMediaSourceFactory.Result.Failure -> {
                android.util.Log.d(TAG, "SYNTH_ADAPTIVE failed: ${result.reason}")
                null
            }
        }
    }

    /**
     * Try to create an adaptive streaming MediaSource (HLS or DASH).
     * Returns null if no adaptive streams are available.
     *
     * Fallback order: HLS → DASH → null
     * If HLS creation fails, attempts DASH before giving up.
     *
     * Phase 1B: Checks HlsPoisonRegistry before attempting HLS. If the video is
     * poisoned (prior 403 failure), HLS is skipped and DASH is attempted directly.
     *
     * For live streams, uses non-caching DataSource to prevent stale manifest issues.
     * For VOD, uses caching DataSource for faster subsequent loads.
     *
     * NOTE: HLS sources use iOS User-Agent because HLS manifests come from YouTube's iOS endpoint
     * (when setFetchIosClient=true). Using Android User-Agent causes HTTP 403 on HLS segments.
     *
     * @param resolved The resolved streams
     * @param videoId Optional video ID for HLS poison check
     */
    private fun tryCreateAdaptiveSource(resolved: ResolvedStreams, videoId: String? = null): AdaptiveSourceResult? {
        // Use non-caching factory for live streams to prevent stale manifests
        // that cause playback to stop after a few seconds
        val dashDataSourceFactory = if (resolved.isLive) {
            android.util.Log.d(TAG, "Using non-caching data source for live stream")
            dataSourceFactory
        } else {
            cacheDataSourceFactory
        }

        // HLS requires iOS User-Agent (manifest comes from iOS endpoint)
        val hlsSourceFactory = if (resolved.isLive) {
            hlsDataSourceFactory
        } else {
            hlsCacheDataSourceFactory
        }

        // Phase 1B: Check HLS poison registry before attempting HLS
        val hlsPoisoned = videoId != null && hlsPoisonRegistry?.isHlsPoisoned(videoId) == true
        if (hlsPoisoned) {
            android.util.Log.d(TAG, "Skipping HLS for $videoId (poisoned), trying DASH directly")
        }

        // Try HLS first (better compatibility) unless poisoned
        val hlsResult = if (!hlsPoisoned) {
            resolved.hlsUrl?.let { hlsUrl ->
                try {
                    val mediaItem = MediaItem.Builder()
                        .setUri(hlsUrl)
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()
                    // Use iOS User-Agent for HLS to match the client that fetched the manifest
                    val source = HlsMediaSource.Factory(hlsSourceFactory)
                        .setAllowChunklessPreparation(true)
                        .createMediaSource(mediaItem)
                    AdaptiveSourceResult(source, hlsUrl, MediaSourceResult.AdaptiveType.HLS)
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Failed to create HLS source: ${e.message}")
                    null
                }
            }
        } else {
            null
        }
        if (hlsResult != null) {
            android.util.Log.d(TAG, "Using HLS adaptive streaming (isLive=${resolved.isLive}, iOS UA)")
            return hlsResult
        }

        // Fall back to DASH if HLS unavailable or failed (uses Android User-Agent)
        val dashResult = resolved.dashUrl?.let { dashUrl ->
            try {
                val mediaItem = MediaItem.Builder()
                    .setUri(dashUrl)
                    .setMimeType(MimeTypes.APPLICATION_MPD)
                    .build()
                val source = DashMediaSource.Factory(dashDataSourceFactory)
                    .createMediaSource(mediaItem)
                AdaptiveSourceResult(source, dashUrl, MediaSourceResult.AdaptiveType.DASH)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to create DASH source: ${e.message}")
                null
            }
        }
        if (dashResult != null) {
            android.util.Log.d(TAG, "Using DASH adaptive streaming (HLS unavailable or failed, isLive=${resolved.isLive})")
            return dashResult
        }

        return null
    }

    /**
     * PR6.1: Try to create a synthetic DASH MediaSource from progressive streams.
     *
     * This wraps video-only + audio progressive streams in synthetic DASH manifests
     * for improved seek behavior while maintaining the same underlying URLs.
     *
     * Requirements for synthetic DASH:
     * - Video track must be video-only (not muxed)
     * - Both video and audio must have valid SyntheticDashMetadata
     * - Duration must be available
     *
     * Falls back to null if any step fails (caller should use raw progressive).
     */
    private fun tryCreateSyntheticDashSource(
        resolved: ResolvedStreams,
        userQualityCapHeight: Int?,
        selectedQuality: VideoTrack?,
        selectionOrigin: QualitySelectionOrigin
    ): AdaptiveSourceResult? {
        // Select video track using same logic as progressive fallback
        val videoTrack = when {
            selectionOrigin == QualitySelectionOrigin.AUTO_RECOVERY && selectedQuality != null -> selectedQuality
            userQualityCapHeight != null -> {
                // For synthetic DASH, prefer video-only tracks (muxed can't be wrapped)
                SyntheticDashTrackSelector.findBestVideoOnlyTrackUnderCap(resolved.videoTracks, userQualityCapHeight)
            }
            else -> {
                // No user cap (AUTO mode): use cold-start quality selection.
                // BufferHealthMonitor handles quality adjustments via predictive/early-stall downshift.
                val initialHeight = getInitialQualityHeight()
                // Fallback chain: 1) ≤initialHeight video-only, 2) lowest with height, 3) first eligible
                SyntheticDashTrackSelector.findBestVideoOnlyTrackUnderCap(resolved.videoTracks, initialHeight)
                    ?: resolved.videoTracks
                        .filter { it.isVideoOnly && it.height != null && it.syntheticDashMetadata?.hasValidRanges() == true }
                        .minByOrNull { it.height!! }
                    ?: resolved.videoTracks
                        .firstOrNull { it.isVideoOnly && it.syntheticDashMetadata?.hasValidRanges() == true }
            }
        }

        // Video track must be video-only with valid metadata
        if (videoTrack == null || !videoTrack.isVideoOnly) {
            android.util.Log.d(TAG, "Synthetic DASH: no eligible video-only track")
            return null
        }

        if (videoTrack.syntheticDashMetadata?.hasValidRanges() != true) {
            android.util.Log.d(TAG, "Synthetic DASH: video track missing valid metadata")
            return null
        }

        // Check if there's a higher-resolution muxed progressive track available.
        // Synthetic DASH can only use video-only tracks, but if a better muxed option exists,
        // we should fall back to raw progressive to use that higher-quality muxed stream.
        val effectiveCap = userQualityCapHeight ?: getInitialQualityHeight()
        if (SyntheticDashTrackSelector.shouldSkipSyntheticDash(videoTrack, resolved.videoTracks, effectiveCap)) {
            val bestMuxedTrack = SyntheticDashTrackSelector.findBestMuxedTrackUnderCap(resolved.videoTracks, effectiveCap)
            android.util.Log.d(TAG, "Synthetic DASH: muxed ${bestMuxedTrack?.height}p available, higher than video-only ${videoTrack.height}p - prefer raw progressive")
            return null
        }

        // Select best audio track with valid metadata
        val audioTrack = resolved.audioTracks
            .filter { it.syntheticDashMetadata?.hasValidRanges() == true }
            .maxByOrNull { it.bitrate ?: 0 }

        if (audioTrack == null) {
            android.util.Log.d(TAG, "Synthetic DASH: no audio track with valid metadata")
            return null
        }

        val durationSeconds = resolved.durationSeconds?.toLong()

        // Create synthetic DASH video source
        val videoResult = syntheticDashFactory.createVideoSource(videoTrack, durationSeconds)
        if (videoResult is SyntheticDashMediaSourceFactory.Result.Failure) {
            android.util.Log.d(TAG, "Synthetic DASH video failed: ${videoResult.reason}")
            return null
        }
        val videoSource = (videoResult as SyntheticDashMediaSourceFactory.Result.Success).source

        // Create synthetic DASH audio source
        val audioResult = syntheticDashFactory.createAudioSource(audioTrack, durationSeconds)
        if (audioResult is SyntheticDashMediaSourceFactory.Result.Failure) {
            android.util.Log.d(TAG, "Synthetic DASH audio failed: ${audioResult.reason}")
            return null
        }
        val audioSource = (audioResult as SyntheticDashMediaSourceFactory.Result.Success).source

        // Merge video and audio synthetic DASH sources
        val mergedSource = MergingMediaSource(videoSource, audioSource)

        android.util.Log.d(TAG, "Synthetic DASH created: ${videoTrack.qualityLabel} + ${audioTrack.codec ?: "audio"}")
        return AdaptiveSourceResult(
            source = mergedSource,
            url = videoTrack.url, // Use video URL for identity tracking
            type = MediaSourceResult.AdaptiveType.SYNTHETIC_DASH,
            selectedVideoTrack = videoTrack
        )
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
