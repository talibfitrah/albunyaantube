package com.albunyaan.tube.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection

/**
 * Custom track selector that provides better quality selection for discrete video streams.
 *
 * This extends DefaultTrackSelector to:
 * 1. Select appropriate default quality based on screen size
 * 2. Enable manual quality selection via ExoPlayer settings menu
 * 3. Show quality labels in the settings UI
 */
@OptIn(UnstableApi::class)
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
