package com.albunyaan.tube.player

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.VideoTrack
import com.albunyaan.tube.util.HttpConstants
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 2C: Factory for creating multi-representation synthetic DASH MediaSources.
 *
 * This factory creates DASH MediaSources with multiple video quality representations,
 * enabling ExoPlayer's ABR (Adaptive Bitrate) logic to switch between qualities
 * based on network conditions.
 *
 * **Key difference from SyntheticDashMediaSourceFactory:**
 * - Original: Single-representation DASH (no quality switching within stream)
 * - This: Multi-representation DASH with ABR capability
 *
 * **Architecture:**
 * 1. MultiRepresentationMpdGenerator creates MPD XML with multiple Representations
 * 2. SyntheticDashMpdRegistry stores the MPD in memory
 * 3. SyntheticDashDataSource.Factory serves the MPD via syntheticdash:// scheme
 * 4. DashMediaSource.Factory creates the actual ExoPlayer source
 *
 * **When to use this vs standard HLS/DASH:**
 * - Use HLS/DASH when available (true adaptive streaming from YouTube)
 * - Use SYNTH_ADAPTIVE when only progressive streams exist but multiple qualities are available
 * - Fall back to single-rep synthetic DASH when only 1 eligible quality exists
 * - Fall back to raw progressive when no synthetic DASH eligibility
 */
@OptIn(UnstableApi::class)
@Singleton
class MultiRepSyntheticDashMediaSourceFactory @Inject constructor(
    private val mpdGenerator: MultiRepresentationMpdGenerator,
    private val mpdRegistry: SyntheticDashMpdRegistry
) {

    companion object {
        private const val TAG = "MultiRepSynthDash"
    }

    /**
     * Result of creating a multi-rep synthetic DASH source.
     */
    sealed class Result {
        /**
         * Successfully created multi-rep synthetic DASH source.
         * @param source The MediaSource for ExoPlayer
         * @param videoTracks The video tracks included in the MPD (ordered by height desc)
         * @param audioTrack The audio track included
         * @param codecFamily The codec family used (e.g., "H264", "VP9")
         * @param videoId The video ID (for registry cleanup)
         */
        data class Success(
            val source: MediaSource,
            val videoTracks: List<VideoTrack>,
            val audioTrack: AudioTrack,
            val codecFamily: String,
            val videoId: String
        ) : Result()

        /**
         * Failed to create multi-rep source.
         * @param reason Machine-readable failure reason
         */
        data class Failure(val reason: String) : Result()
    }

    /**
     * Create a multi-representation synthetic DASH MediaSource.
     *
     * Phase 5: Checks if MPD with metadata was pre-generated during prefetch.
     * - If found WITH metadata AND fresh (not expired): TRUE cache hit (no regeneration).
     * - Otherwise: Generates MPD fresh (always with full ladder, no cap filtering).
     *
     * The main benefit of pre-generation is that both the MPD and metadata are already
     * in the registry when playback starts, completely avoiding generation latency.
     *
     * **Quality Cap Architecture (Phase 3):**
     * The MPD always includes ALL quality levels (full ladder). Quality cap is enforced
     * ONLY via track selector constraints (CAP/LOCK mode), NOT by filtering the MPD.
     * This enables instant quality switching without MediaSource rebuild.
     *
     * @param resolved The resolved streams from NewPipe
     * @param videoId The video ID (for registry and logging)
     * @param context Android context for DataSource creation
     * @param qualityCapHeight DEPRECATED: No longer used. Quality cap is enforced via track selector.
     *                         Parameter kept for API compatibility with existing call sites.
     * @return Result.Success with MediaSource, or Result.Failure with reason
     */
    @Suppress("UNUSED_PARAMETER")
    fun createMediaSource(
        resolved: ResolvedStreams,
        videoId: String,
        context: Context,
        qualityCapHeight: Int? = null
    ): Result {
        // Phase 5: Check if MPD WITH METADATA was pre-generated during prefetch.
        // Pre-generated MPD includes ALL quality levels (no cap), enabling instant
        // quality switching via track selector constraints without MPD regeneration.
        //
        // Key insight: qualityCapHeight is now ONLY enforced via track selector,
        // NOT by filtering tracks in the MPD. This enables seamless quality switching
        // without MediaSource rebuild (fixing the MAJOR "no reload on resolution switch" issue).
        //
        // Must have metadata AND be fresh to be a true cache hit (avoid stale signed URLs).
        // TTL check prevents using cached MPDs with expired signed URLs.
        val cachedEntry = mpdRegistry.getFreshEntry(videoId)
        val trueCacheHit = cachedEntry != null

        val videoTracks: List<VideoTrack>
        val audioTrack: AudioTrack
        val codecFamily: String

        if (trueCacheHit) {
            // TRUE cache hit: MPD and metadata already in registry from prefetch.
            // No need to call mpdGenerator.generateMpd() at all!
            Log.d(TAG, "MPD TRUE cache hit for $videoId (pre-generated with metadata)")
            // Defensive null checks - hasMetadata() guarantees non-null, but be safe
            val entry = cachedEntry ?: run {
                Log.e(TAG, "Cache hit but entry is null for $videoId")
                return Result.Failure("CACHE_ENTRY_NULL")
            }
            videoTracks = entry.videoTracks ?: run {
                Log.e(TAG, "Cache hit but videoTracks is null for $videoId")
                return Result.Failure("CACHE_METADATA_INCOMPLETE")
            }
            audioTrack = entry.audioTrack ?: run {
                Log.e(TAG, "Cache hit but audioTrack is null for $videoId")
                return Result.Failure("CACHE_METADATA_INCOMPLETE")
            }
            codecFamily = entry.codecFamily ?: run {
                Log.e(TAG, "Cache hit but codecFamily is null for $videoId")
                return Result.Failure("CACHE_METADATA_INCOMPLETE")
            }
            // MPD is already registered, no re-registration needed
        } else {
            // Generate MPD fresh without quality cap (full ladder).
            // Quality cap is enforced via track selector, not MPD filtering.
            // This enables instant quality switching without MediaSource rebuild.
            val mpdResult = mpdGenerator.generateMpd(resolved, qualityCapHeight = null)
            if (mpdResult is MultiRepresentationMpdGenerator.Result.Failure) {
                return Result.Failure(mpdResult.reason)
            }
            val success = mpdResult as MultiRepresentationMpdGenerator.Result.Success
            videoTracks = success.videoTracks
            audioTrack = success.audioTrack
            codecFamily = success.codecFamily
            // Register MPD with metadata in registry
            mpdRegistry.registerWithMetadata(
                videoId = videoId,
                mpdXml = success.mpdXml,
                videoTracks = success.videoTracks,
                audioTrack = success.audioTrack,
                codecFamily = success.codecFamily
            )
        }

        // Create data source factories
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(HttpConstants.YOUTUBE_USER_AGENT)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
            .setAllowCrossProtocolRedirects(true)

        val upstreamFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

        // Create composite data source factory that handles both syntheticdash:// and http(s)://
        val compositeFactory = CompositeDataSourceFactory(
            syntheticDashFactory = SyntheticDashDataSource.Factory(mpdRegistry),
            upstreamFactory = upstreamFactory
        )

        // Create DASH MediaSource using syntheticdash:// URI
        val mpdUri = "${SyntheticDashDataSource.SCHEME}://$videoId"
        val mediaItem = MediaItem.Builder()
            .setUri(mpdUri)
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .build()

        val source = DashMediaSource.Factory(compositeFactory)
            .createMediaSource(mediaItem)

        Log.d(TAG, "Created multi-rep DASH: ${videoTracks.size} reps ($codecFamily)")

        return Result.Success(
            source = source,
            videoTracks = videoTracks,
            audioTrack = audioTrack,
            codecFamily = codecFamily,
            videoId = videoId
        )
    }

    /**
     * Check eligibility for multi-rep synthetic DASH.
     *
     * @param resolved The resolved streams
     * @return Pair of (eligible, reason)
     */
    fun checkEligibility(resolved: ResolvedStreams): Pair<Boolean, String> {
        return mpdGenerator.checkEligibility(resolved)
    }

    /**
     * Clean up MPD registration for a video.
     * Call when playback completes or player is released.
     */
    fun cleanup(videoId: String) {
        mpdRegistry.unregister(videoId)
    }

    /**
     * Clear all MPD registrations.
     * Call on app background or destroy.
     */
    fun clearAll() {
        mpdRegistry.clearAll()
    }
}

/**
 * Composite DataSource.Factory that routes requests based on URI scheme.
 *
 * - syntheticdash:// → SyntheticDashDataSource (serves MPD from memory)
 * - http(s):// → upstream factory (fetches actual video/audio segments)
 */
@OptIn(UnstableApi::class)
private class CompositeDataSourceFactory(
    private val syntheticDashFactory: DataSource.Factory,
    private val upstreamFactory: DataSource.Factory
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        return CompositeDataSource(
            syntheticDashDataSource = syntheticDashFactory.createDataSource(),
            upstreamDataSource = upstreamFactory.createDataSource()
        )
    }
}

/**
 * Composite DataSource that routes open() calls based on URI scheme.
 */
@OptIn(UnstableApi::class)
private class CompositeDataSource(
    private val syntheticDashDataSource: DataSource,
    private val upstreamDataSource: DataSource
) : DataSource {

    private var activeSource: DataSource? = null

    override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
        val scheme = dataSpec.uri.scheme

        val chosenSource = if (scheme == SyntheticDashDataSource.SCHEME) {
            syntheticDashDataSource
        } else {
            upstreamDataSource
        }

        // Call open() first, only set activeSource after successful open to avoid
        // pointing to an unopened source if open() throws an exception
        val result = chosenSource.open(dataSpec)
        activeSource = chosenSource
        return result
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return activeSource?.read(buffer, offset, length)
            ?: throw IllegalStateException("DataSource not opened")
    }

    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
        syntheticDashDataSource.addTransferListener(transferListener)
        upstreamDataSource.addTransferListener(transferListener)
    }

    override fun getUri(): android.net.Uri? {
        return activeSource?.uri
    }

    override fun getResponseHeaders(): Map<String, List<String>> {
        return activeSource?.responseHeaders ?: emptyMap()
    }

    override fun close() {
        activeSource?.close()
        activeSource = null
    }
}
