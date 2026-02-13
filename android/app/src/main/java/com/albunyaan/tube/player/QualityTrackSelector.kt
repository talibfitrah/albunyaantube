package com.albunyaan.tube.player

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import com.albunyaan.tube.data.extractor.QualityConstraintMode

/**
 * Custom track selector that provides better quality selection for discrete video streams.
 *
 * This extends DefaultTrackSelector to:
 * 1. Select appropriate default quality based on screen size
 * 2. Enable manual quality selection via ExoPlayer settings menu
 * 3. Show quality labels in the settings UI
 *
 * Phase 3: Supports both CAP and LOCK modes:
 * - CAP: Maximum ceiling; ABR can go lower but not higher
 * - LOCK: Fixed height with forced highest bitrate; locks to specified resolution
 */
@OptIn(UnstableApi::class)
class QualityTrackSelector(
    context: Context,
    trackSelectionFactory: ExoTrackSelection.Factory = AdaptiveTrackSelection.Factory()
) : DefaultTrackSelector(context, trackSelectionFactory) {

    companion object {
        private const val TAG = "QualityTrackSelector"

        /**
         * Creates a track selector configured for discrete quality selection.
         */
        fun createForDiscreteQualities(context: Context): QualityTrackSelector {
            return QualityTrackSelector(context)
        }
    }

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
     * Update parameters to select a specific quality (legacy - uses LOCK semantics).
     *
     * @param height The desired video height (e.g., 720 for 720p)
     * @deprecated Use [applyQualityConstraint] with explicit mode instead
     */
    @Deprecated("Use applyQualityConstraint with explicit mode", ReplaceWith("applyQualityConstraint(height, QualityConstraintMode.LOCK)"))
    fun selectQuality(height: Int) {
        applyQualityConstraint(height, QualityConstraintMode.LOCK)
    }

    /**
     * Phase 3: Apply quality constraint with explicit mode.
     *
     * LOCK mode has been deprecated in favor of CAP_STRICT to prevent audio-only fallback.
     * When a user selects a specific resolution, we now use CAP_STRICT which:
     * - Sets the selected resolution as maximum
     * - Forces highest bitrate at or below that resolution
     * - Allows ABR to select lower resolutions if needed (prevents audio-only)
     *
     * @param height The desired video height (e.g., 720 for 720p). Must be positive.
     * @param mode CAP (ceiling only) or LOCK (now behaves as CAP_STRICT to prevent audio-only)
     * @throws IllegalArgumentException if height is not positive
     */
    fun applyQualityConstraint(height: Int, mode: QualityConstraintMode) {
        require(height > 0) { "Height must be positive, got: $height" }
        parameters = when (mode) {
            QualityConstraintMode.CAP -> {
                // Cap mode: Set maximum only, allow ABR to drop below
                // Min set to 0 (no minimum constraint) so ABR can select any lower quality
                // Reset forceHighestSupportedBitrate to allow ABR to adapt bitrate
                Log.d(TAG, "Applying quality CAP: max ${height}p, ABR enabled below cap")
                buildUponParameters()
                    .setMaxVideoSize(Int.MAX_VALUE, height)
                    .setMinVideoSize(0, 0) // No minimum constraint
                    .setForceHighestSupportedBitrate(false) // Allow ABR to adapt bitrate
                    .build()
            }
            QualityConstraintMode.LOCK -> {
                // LOCK mode has been changed to CAP_STRICT to prevent audio-only fallback.
                // Previously, LOCK set a minimum height which could result in NO video track
                // being selected if that exact resolution wasn't available.
                //
                // CAP_STRICT behavior:
                // - Sets the selected resolution as the maximum ceiling
                // - Forces highest bitrate to prefer the requested quality when available
                // - Does NOT set a minimum, allowing fallback to lower resolutions
                // - This ensures video playback continues even if the exact resolution isn't available
                Log.d(TAG, "Applying quality CAP_STRICT (was LOCK): max ${height}p, forcing highest bitrate, no minimum")
                buildUponParameters()
                    .setMaxVideoSize(Int.MAX_VALUE, height)
                    .setMinVideoSize(0, 0) // No minimum - prevents audio-only fallback
                    .setForceHighestSupportedBitrate(true) // Prefer highest available at/below cap
                    .build()
            }
        }
    }

    /**
     * Reset to automatic quality selection based on bandwidth.
     */
    fun selectAutoQuality() {
        Log.d(TAG, "Selecting AUTO quality: ABR enabled, no constraints")
        parameters = buildUponParameters()
            .clearVideoSizeConstraints()
            .setForceHighestSupportedBitrate(false)
            .build()
    }

    /**
     * Gets a human-readable label for a video format.
     */
    fun getQualityLabel(format: Format): String {
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
}
