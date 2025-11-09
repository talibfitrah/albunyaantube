package com.albunyaan.tube.player

import android.content.Context
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.trackselection.ExoTrackSelection
import com.google.android.exoplayer2.trackselection.TrackSelection
import com.google.android.exoplayer2.upstream.BandwidthMeter

/**
 * Custom track selector that provides better quality selection for discrete video streams.
 *
 * This extends DefaultTrackSelector to:
 * 1. Select appropriate default quality based on screen size
 * 2. Enable manual quality selection via ExoPlayer settings menu
 * 3. Show quality labels in the settings UI
 */
class QualityTrackSelector(
    context: Context,
    trackSelectionFactory: ExoTrackSelection.Factory = AdaptiveTrackSelection.Factory()
) : DefaultTrackSelector(context, trackSelectionFactory) {

    init {
        // Configure parameters for better quality selection
        parameters = buildUponParameters()
            // Allow quality selection override
            .setAllowVideoMixedMimeTypeAdaptiveness(true)
            .setAllowVideoNonSeamlessAdaptiveness(true)
            .setAllowAudioMixedMimeTypeAdaptiveness(true)
            // Prefer higher quality when bandwidth allows
            .setForceHighestSupportedBitrate(false)
            .setForceLowestBitrate(false)
            // Max video size constraints (will be overridden by user selection)
            .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
            .setMaxVideoBitrate(Int.MAX_VALUE)
            .build()
    }

    /**
     * Update parameters to select a specific quality.
     *
     * @param height The desired video height (e.g., 720 for 720p)
     */
    fun selectQuality(height: Int) {
        parameters = buildUponParameters()
            .setMaxVideoSize(Int.MAX_VALUE, height)
            .setMinVideoSize(0, height)
            .build()
    }

    /**
     * Reset to automatic quality selection based on bandwidth.
     */
    fun selectAutoQuality() {
        parameters = buildUponParameters()
            .clearVideoSizeConstraints()
            .setForceHighestSupportedBitrate(false)
            .build()
    }

    /**
     * Gets a human-readable label for a video format.
     */
    fun getQualityLabel(format: Format): String {
        // Check for custom tag from our MediaSource factory
        val tag = format.metadata

        val height = format.height
        val width = format.width
        val bitrate = format.bitrate

        return when {
            height > 0 -> "${height}p"
            width > 0 -> "${width}w"
            bitrate > 0 -> "${bitrate / 1000}kbps"
            else -> "Unknown"
        }
    }

    companion object {
        /**
         * Creates a track selector configured for discrete quality selection.
         */
        fun createForDiscreteQualities(context: Context): QualityTrackSelector {
            return QualityTrackSelector(context)
        }
    }
}

