package com.albunyaan.tube.player

import com.albunyaan.tube.data.extractor.VideoTrack

/**
 * Helper for quality step-down logic during playback stalls.
 * Extracted to enable unit testing.
 */
object QualityStepDownHelper {

    /**
     * Find the next lower quality track to step down to.
     * Step-down priority:
     * 1. If current is video-only, try muxed at same resolution (avoids merge failures)
     * 2. If same resolution has lower bitrate available, try that
     * 3. Otherwise, drop to the next lower resolution
     *
     * @param current The currently playing video track
     * @param available All available video tracks
     * @return The next lower quality track, or null if none available
     */
    fun findNextLowerQualityTrack(
        current: VideoTrack?,
        available: List<VideoTrack>
    ): VideoTrack? {
        if (current == null || available.isEmpty()) return null

        val currentHeight = current.height ?: 0
        val currentBitrate = current.bitrate?.takeIf { it > 0 } // null if invalid
        val currentUrl = current.url

        // Step 1: If currently on video-only, try muxed at same resolution
        if (current.isVideoOnly) {
            val muxedSameRes = available.firstOrNull {
                !it.isVideoOnly && (it.height ?: 0) == currentHeight && it.url != currentUrl
            }
            if (muxedSameRes != null) {
                return muxedSameRes
            }
        }

        // Step 2: Try lower bitrate at the same resolution (prefer same stream type: muxed stays muxed)
        // Only compare bitrates when both current and candidate have valid (non-null, > 0) bitrates
        val lowerBitrateSameRes = if (currentBitrate != null) {
            available
                .filter { (it.height ?: 0) == currentHeight && it.url != currentUrl }
                .filter { candidate ->
                    val candidateBitrate = candidate.bitrate?.takeIf { it > 0 }
                    candidateBitrate != null && candidateBitrate < currentBitrate
                }
                .filter { it.isVideoOnly == current.isVideoOnly } // stay within same stream type
                .maxByOrNull { it.bitrate ?: 0 } // highest of the lower bitrates
        } else null
        if (lowerBitrateSameRes != null) {
            return lowerBitrateSameRes
        }

        // Note: No Step 2b needed - if current is video-only and we reach here, Step 1 already
        // checked for muxed at same resolution and found none. Proceed to lower resolution.

        // Step 3: Drop to next lower resolution (prefer muxed)
        val lowerResolutions = available
            .filter { (it.height ?: 0) < currentHeight }
            .sortedWith(
                compareByDescending<VideoTrack> { it.height ?: 0 }
                    .thenBy { it.isVideoOnly } // prefer muxed
                    .thenByDescending { it.bitrate ?: 0 }
            )

        return lowerResolutions.firstOrNull()
    }
}
