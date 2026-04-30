package com.albunyaan.tube.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.source.MediaSource
import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.SyntheticDashMetadata
import com.albunyaan.tube.data.extractor.VideoTrack
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.CreationException
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator

/**
 * Factory for creating synthetic DASH MediaSources from progressive streams.
 *
 * PR6.1 Implementation: Wraps eligible progressive streams in a synthetic DASH manifest
 * using NewPipe's YoutubeProgressiveDashManifestCreator. This improves seek/restart behavior
 * by using byte-range requests and a structured container index.
 *
 * **What this does:**
 * - Converts video-only and audio-only progressive streams to DASH format
 * - Enables efficient byte-range seeking instead of downloading from start
 * - Uses the same underlying URLs (no extra network calls)
 *
 * **What this does NOT do:**
 * - Does not provide ABR (adaptive bitrate) - progressive remains single-bitrate
 * - Does not work with muxed streams (video+audio combined)
 * - Does not make any network calls during manifest generation
 *
 * **Fallback behavior:**
 * If synthetic DASH creation fails for any reason, callers should fall back to
 * standard ProgressiveMediaSource. This factory returns Result.Failure on failure.
 */
@OptIn(UnstableApi::class)
class SyntheticDashMediaSourceFactory(
    private val dataSourceFactory: DataSource.Factory,
    private val mpdRegistry: SyntheticDashMpdRegistry? = null
) {
    companion object {
        private const val TAG = "SyntheticDashFactory"
    }

    /**
     * Result of synthetic DASH creation attempt.
     */
    sealed class Result {
        data class Success(val source: MediaSource, val mpdUrl: String) : Result()
        data class Failure(val reason: String) : Result()
    }

    /**
     * Attempt to create a synthetic DASH MediaSource for a video-only track.
     *
     * @param track The video track (must be video-only with valid SyntheticDashMetadata)
     * @param durationSeconds Fallback duration in seconds (from StreamInfo)
     * @param videoId The video ID used to register the MPD in the registry (when registry is set)
     * @return Result.Success with MediaSource, or Result.Failure with reason
     */
    fun createVideoSource(track: VideoTrack, durationSeconds: Long?, videoId: String? = null): Result {
        // Validate preconditions
        if (!track.isVideoOnly) {
            return Result.Failure("NOT_VIDEO_ONLY")
        }

        val metadata = track.syntheticDashMetadata
            ?: return Result.Failure("NO_METADATA")

        if (!metadata.hasValidRanges()) {
            return Result.Failure("INVALID_RANGES")
        }

        // Build ItagItem for manifest creation
        val itagItem = buildItagItem(metadata, isVideo = true)
            ?: return Result.Failure("ITAG_BUILD_FAILED")

        // Calculate duration with fallback
        val duration = calculateDuration(durationSeconds, metadata.approxDurationMs)
        if (duration <= 0) {
            return Result.Failure("NO_DURATION")
        }

        return generateDashSource(track.url, itagItem, duration, "video", videoId)
    }

    /**
     * Attempt to create a synthetic DASH MediaSource for an audio track.
     *
     * @param track The audio track (must have valid SyntheticDashMetadata)
     * @param durationSeconds Fallback duration in seconds (from StreamInfo)
     * @param videoId The video ID used to register the MPD in the registry (when registry is set)
     * @return Result.Success with MediaSource, or Result.Failure with reason
     */
    fun createAudioSource(track: AudioTrack, durationSeconds: Long?, videoId: String? = null): Result {
        val metadata = track.syntheticDashMetadata
            ?: return Result.Failure("NO_METADATA")

        if (!metadata.hasValidRanges()) {
            return Result.Failure("INVALID_RANGES")
        }

        // Build ItagItem for manifest creation
        val itagItem = buildItagItem(metadata, isVideo = false)
            ?: return Result.Failure("ITAG_BUILD_FAILED")

        // Calculate duration with fallback
        val duration = calculateDuration(durationSeconds, metadata.approxDurationMs)
        if (duration <= 0) {
            return Result.Failure("NO_DURATION")
        }

        return generateDashSource(track.url, itagItem, duration, "audio", videoId)
    }

    /**
     * Build an ItagItem from our stored metadata.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun buildItagItem(metadata: SyntheticDashMetadata, isVideo: Boolean): ItagItem? {
        return try {
            // ItagItem requires the itag value to look up codec/format info
            val itagItem = ItagItem.getItag(metadata.itag)
            // Set the byte range information
            itagItem.setInitStart(metadata.initStart.toInt())
            itagItem.setInitEnd(metadata.initEnd.toInt())
            itagItem.setIndexStart(metadata.indexStart.toInt())
            itagItem.setIndexEnd(metadata.indexEnd.toInt())
            // Set approximate duration if available
            metadata.approxDurationMs?.let { itagItem.setApproxDurationMs(it) }
            itagItem
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build ItagItem for itag ${metadata.itag}: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Calculate duration with fallback logic.
     * Uses ceiling division for approxDurationMs to avoid truncating short videos to 0.
     */
    private fun calculateDuration(durationSeconds: Long?, approxDurationMs: Long?): Long {
        return when {
            durationSeconds != null && durationSeconds > 0 -> durationSeconds
            approxDurationMs != null && approxDurationMs > 0 -> (approxDurationMs + 999) / 1000
            else -> 0
        }
    }

    /**
     * Generate DASH MediaSource using NewPipe's manifest creator.
     *
     * When [mpdRegistry] and [videoId] are both available, registers the MPD content
     * under [videoId] and uses a `syntheticdash://<videoId>` URI served by
     * [SyntheticDashDataSource]. Otherwise falls back to the legacy `data:` URI approach.
     */
    private fun generateDashSource(
        streamUrl: String,
        itagItem: ItagItem,
        durationSeconds: Long,
        streamType: String,
        videoId: String? = null
    ): Result {
        return try {
            // Generate MPD manifest (no network call - purely local XML generation)
            val mpdManifest = YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(
                streamUrl,
                itagItem,
                durationSeconds
            )

            if (mpdManifest.isNullOrBlank()) {
                return Result.Failure("EMPTY_MPD")
            }

            // Use registry-based syntheticdash:// URI when registry and videoId are available,
            // otherwise fall back to legacy data: URI to preserve backward compatibility.
            val registry = mpdRegistry
            if (registry != null && videoId != null) {
                // Register MPD in the registry so SyntheticDashDataSource can serve it
                registry.register(videoId, mpdManifest)

                val mpdUri = Uri.parse("syntheticdash://$videoId")
                val mediaItem = MediaItem.Builder()
                    .setUri(mpdUri)
                    .setMimeType(MimeTypes.APPLICATION_MPD)
                    .build()

                // Use SyntheticDashDataSource.Factory for manifest delivery;
                // segment fetching uses the regular dataSourceFactory via DefaultDashChunkSource.
                val source = DashMediaSource.Factory(
                    DefaultDashChunkSource.Factory(dataSourceFactory), // segment data source
                    SyntheticDashDataSource.Factory(registry)          // manifest data source
                ).createMediaSource(mediaItem)

                Log.d(TAG, "Created synthetic DASH $streamType source via registry (videoId=$videoId, itag=${itagItem.id}, dur=${durationSeconds}s)")
                Result.Success(source, mpdUri.toString())
            } else {
                // Legacy path: embed MPD in a data: URI
                val mpdDataUri = "data:application/dash+xml;charset=utf-8," +
                    java.net.URLEncoder.encode(mpdManifest, "UTF-8")

                val mediaItem = MediaItem.Builder()
                    .setUri(mpdDataUri)
                    .setMimeType(MimeTypes.APPLICATION_MPD)
                    .build()

                val source = DashMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(mediaItem)

                Log.d(TAG, "Created synthetic DASH $streamType source via data: URI (itag=${itagItem.id}, dur=${durationSeconds}s)")
                Result.Success(source, mpdDataUri)
            }

        } catch (e: CreationException) {
            Log.w(TAG, "Synthetic DASH creation failed for $streamType: CreationException")
            Result.Failure("CREATION_EXCEPTION")
        } catch (e: Exception) {
            Log.w(TAG, "Synthetic DASH creation failed for $streamType: ${e.javaClass.simpleName}")
            Result.Failure("UNEXPECTED:${e.javaClass.simpleName}")
        }
    }
}
