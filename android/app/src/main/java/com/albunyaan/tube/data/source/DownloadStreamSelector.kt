package com.albunyaan.tube.data.source

import androidx.annotation.VisibleForTesting
import com.albunyaan.tube.data.model.api.models.DownloadManifestDto
import com.albunyaan.tube.data.model.api.models.StreamOption

/**
 * Stream selection logic for download quality preferences.
 *
 * This class encapsulates the algorithm for selecting the appropriate stream
 * from a download manifest based on user preferences (audio-only, target height).
 *
 * Extracted from RetrofitDownloadService to enable direct unit testing without
 * mocking network dependencies.
 */
object DownloadStreamSelector {

    /**
     * Select the appropriate stream from a download manifest.
     *
     * Selection algorithm:
     * - If audioOnly: selects best audio stream by bitrate
     * - If targetHeight specified (>0): selects video stream closest to (but not exceeding) target
     * - Otherwise: selects best video stream by bitrate (highest quality)
     *
     * @param manifest The download manifest containing available streams
     * @param audioOnly Whether to select audio-only stream
     * @param targetHeight Target video height for quality selection (null or 0 = best available)
     * @return The selected stream option
     * @throws IllegalStateException if no suitable streams are available
     */
    fun selectStream(
        manifest: DownloadManifestDto,
        audioOnly: Boolean,
        targetHeight: Int?
    ): StreamOption {
        val effectiveHeight = targetHeight?.takeIf { it > 0 }

        return if (audioOnly) {
            selectBestAudioStream(manifest)
        } else if (effectiveHeight != null) {
            selectVideoStreamByHeight(manifest, effectiveHeight)
        } else {
            selectBestVideoStream(manifest)
        }
    }

    /**
     * Select best audio stream by bitrate (highest quality audio).
     */
    private fun selectBestAudioStream(manifest: DownloadManifestDto): StreamOption {
        return manifest.audioStreams
            ?.sortedByDescending { it.bitrate ?: 0 }
            ?.firstOrNull()
            ?: throw IllegalStateException("No audio streams available")
    }

    /**
     * Select video stream based on target height preference.
     *
     * Algorithm:
     * 1. Parse height from quality labels (e.g., "720p" -> 720)
     * 2. Find stream closest to (but not exceeding) target height
     * 3. If no stream at or below target, fall back to lowest available
     */
    private fun selectVideoStreamByHeight(
        manifest: DownloadManifestDto,
        targetHeight: Int
    ): StreamOption {
        val videoStreams = manifest.videoStreams
            ?: throw IllegalStateException("No video streams available")

        // Parse height from qualityLabel (e.g., "720p" -> 720, "1080p60" -> 1080)
        val streamsWithHeight = videoStreams.mapNotNull { stream ->
            val height = stream.qualityLabel?.let { parseHeightFromQualityLabel(it) }
            if (height != null) stream to height else null
        }

        // Find stream closest to (but not exceeding) target height
        val matchingStream = streamsWithHeight
            .filter { (_, height) -> height <= targetHeight }
            .maxByOrNull { (_, height) -> height }
            ?.first

        // Fallback to lowest available if no stream at or below target
        return matchingStream ?: streamsWithHeight
            .minByOrNull { (_, height) -> height }
            ?.first
            ?: videoStreams.firstOrNull()
            ?: throw IllegalStateException("No video streams available")
    }

    /**
     * Select best video stream by bitrate (highest quality).
     */
    private fun selectBestVideoStream(manifest: DownloadManifestDto): StreamOption {
        return manifest.videoStreams
            ?.sortedByDescending { it.bitrate ?: 0 }
            ?.firstOrNull()
            ?: throw IllegalStateException("No video streams available")
    }

    /**
     * Parse height from quality label string.
     *
     * Examples:
     * - "720p" -> 720
     * - "1080p60" -> 1080
     * - "480p30" -> 480
     * - "invalid" -> null
     *
     * @param label The quality label to parse
     * @return The parsed height, or null if parsing fails
     */
    @VisibleForTesting
    internal fun parseHeightFromQualityLabel(label: String): Int? {
        // Match patterns like "720p", "1080p60", "480p30"
        val match = Regex("""(\d+)p""").find(label)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
}
