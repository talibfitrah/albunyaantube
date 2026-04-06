package com.albunyaan.tube.player

import androidx.annotation.VisibleForTesting
import androidx.media3.ui.AspectRatioFrameLayout

/**
 * Determines the optimal resize mode for fullscreen video playback based on
 * the actual video dimensions and the device viewport.
 *
 * Instead of a static screen-ratio threshold, this computes the exact crop percentage
 * that ZOOM would produce for the current video on the current screen, and chooses
 * ZOOM only when the crop is within the allowed budget.
 *
 * The crop budget is configurable per device tier:
 * - [DEFAULT_CROP_BUDGET] (5%): barely perceptible crop, used for most devices
 * - [GENEROUS_CROP_BUDGET] (20%): allows more crop for devices where users prefer
 *   filling the screen (e.g., Samsung S25 Ultra with 19.5:9 playing 16:9 content)
 *
 * This class is a pure function with no state, making it trivially unit-testable.
 */
object AspectPolicy {

    /** Default crop budget: 5% total crop — barely perceptible */
    const val DEFAULT_CROP_BUDGET = 0.05f

    /**
     * Higher crop budget for devices where filling the screen is preferred.
     *
     * At 20%: 16:9 video on S25 Ultra (19.5:9) → 18% crop < 20% → ZOOM (no bars)
     * At 20%: 4:3 video on S25 Ultra → 38% crop > 20% → FIT (protects content/subtitles)
     */
    const val GENEROUS_CROP_BUDGET = 0.20f

    /**
     * Compute the optimal resize mode for a given video on a given viewport.
     *
     * @param viewportWidth  Width of the player viewport in pixels (screen width in fullscreen)
     * @param viewportHeight Height of the player viewport in pixels (screen height in fullscreen)
     * @param videoWidth     Width of the video content in pixels
     * @param videoHeight    Height of the video content in pixels
     * @param pixelWidthHeightRatio Pixel aspect ratio from [androidx.media3.common.VideoSize] (1.0 for square pixels)
     * @param cropBudget     Maximum fraction of video content that may be cropped (0.0–1.0)
     * @return [AspectRatioFrameLayout.RESIZE_MODE_ZOOM] if crop is within budget,
     *         [AspectRatioFrameLayout.RESIZE_MODE_FIT] otherwise
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun computeResizeMode(
        viewportWidth: Int,
        viewportHeight: Int,
        videoWidth: Int,
        videoHeight: Int,
        pixelWidthHeightRatio: Float = 1f,
        cropBudget: Float = DEFAULT_CROP_BUDGET
    ): Int {
        if (videoWidth <= 0 || videoHeight <= 0 || viewportWidth <= 0 || viewportHeight <= 0) {
            return AspectRatioFrameLayout.RESIZE_MODE_FIT
        }

        val videoAspect = (videoWidth.toFloat() * pixelWidthHeightRatio) / videoHeight.toFloat()
        val viewportAspect = viewportWidth.toFloat() / viewportHeight.toFloat()

        val cropPercent = computeCropPercent(videoAspect, viewportAspect)

        return if (cropPercent <= cropBudget) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    /**
     * Compute the percentage of video content that would be cropped if ZOOM is used.
     *
     * When viewport is wider than video: ZOOM crops top/bottom of the video.
     * When viewport is taller than video: ZOOM crops left/right of the video.
     * When aspects match exactly: 0% crop.
     */
    @VisibleForTesting
    fun computeCropPercent(videoAspect: Float, viewportAspect: Float): Float {
        if (videoAspect <= 0f || viewportAspect <= 0f) return 1f
        return 1f - minOf(videoAspect, viewportAspect) / maxOf(videoAspect, viewportAspect)
    }
}
