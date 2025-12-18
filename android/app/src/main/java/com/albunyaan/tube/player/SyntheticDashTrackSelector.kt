package com.albunyaan.tube.player

import com.albunyaan.tube.data.extractor.VideoTrack

/**
 * Helper for synthetic DASH track selection decisions.
 *
 * Used by [MultiQualityMediaSourceFactory] to determine whether to use synthetic DASH
 * or fall back to raw progressive playback.
 *
 * Key decision: Skip synthetic DASH when a higher-resolution muxed progressive
 * track exists under the same quality cap. Synthetic DASH only works with
 * video-only tracks, but muxed progressives may offer better quality.
 */
object SyntheticDashTrackSelector {

    /**
     * Find the best video-only track under the quality cap (for synthetic DASH).
     * Only considers video-only tracks with valid SyntheticDashMetadata.
     */
    fun findBestVideoOnlyTrackUnderCap(tracks: List<VideoTrack>, capHeight: Int): VideoTrack? {
        return tracks
            .filter { track ->
                val height = track.height
                track.isVideoOnly &&
                height != null &&
                height <= capHeight &&
                track.syntheticDashMetadata?.hasValidRanges() == true
            }
            .maxByOrNull { it.height ?: 0 }
    }

    /**
     * Find the best muxed (non-video-only) track under the quality cap.
     * Used to compare against synthetic DASH selection.
     */
    fun findBestMuxedTrackUnderCap(tracks: List<VideoTrack>, capHeight: Int): VideoTrack? {
        return tracks
            .filter { track ->
                val height = track.height
                !track.isVideoOnly &&
                height != null &&
                height <= capHeight
            }
            .maxByOrNull { it.height ?: 0 }
    }

    /**
     * Determine if synthetic DASH should be skipped in favor of raw progressive.
     *
     * @param videoOnlyTrack The best video-only track found for synthetic DASH
     * @param allTracks All available video tracks
     * @param effectiveCap The quality cap to apply (user cap or default)
     * @return true if synthetic DASH should be skipped (muxed is higher res)
     */
    fun shouldSkipSyntheticDash(
        videoOnlyTrack: VideoTrack?,
        allTracks: List<VideoTrack>,
        effectiveCap: Int
    ): Boolean {
        if (videoOnlyTrack == null) return true

        val bestMuxedTrack = findBestMuxedTrackUnderCap(allTracks, effectiveCap)

        // Skip synthetic DASH if muxed is higher resolution
        return bestMuxedTrack != null && (bestMuxedTrack.height ?: 0) > (videoOnlyTrack.height ?: 0)
    }
}
