package com.albunyaan.tube.player

import androidx.media3.ui.AspectRatioFrameLayout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [AspectPolicy] — verifies crop-budget-based resize mode computation
 * across various device/video aspect ratio combinations.
 */
class AspectPolicyTest {

    @Test
    fun `16x9 video on 16x9 screen with 5 pct budget - ZOOM (zero crop)`() {
        val result = AspectPolicy.computeResizeMode(
            viewportWidth = 1920, viewportHeight = 1080,
            videoWidth = 1920, videoHeight = 1080,
            cropBudget = AspectPolicy.DEFAULT_CROP_BUDGET
        )
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, result)
    }

    @Test
    fun `16x9 video on 19_5x9 screen with 5 pct budget - FIT (18 pct crop exceeds 5 pct)`() {
        // Samsung S25 Ultra: 2340x1080 landscape = 2.167:1
        val result = AspectPolicy.computeResizeMode(
            viewportWidth = 2340, viewportHeight = 1080,
            videoWidth = 1920, videoHeight = 1080,
            cropBudget = AspectPolicy.DEFAULT_CROP_BUDGET
        )
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, result)
    }

    @Test
    fun `16x9 video on 19_5x9 screen with 20 pct budget - ZOOM (18 pct crop within 20 pct)`() {
        // S25 Ultra with generous crop budget
        val result = AspectPolicy.computeResizeMode(
            viewportWidth = 2340, viewportHeight = 1080,
            videoWidth = 1920, videoHeight = 1080,
            cropBudget = AspectPolicy.GENEROUS_CROP_BUDGET
        )
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, result)
    }

    @Test
    fun `4x3 video on 19_5x9 screen with 20 pct budget - FIT (38 pct crop exceeds 20 pct)`() {
        // Even on S25 Ultra with generous budget, 4:3 content is too different
        val result = AspectPolicy.computeResizeMode(
            viewportWidth = 2340, viewportHeight = 1080,
            videoWidth = 640, videoHeight = 480,
            cropBudget = AspectPolicy.GENEROUS_CROP_BUDGET
        )
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, result)
    }

    @Test
    fun `ultra-wide 2_35x1 video on 16x9 screen - FIT (24 pct crop exceeds budget)`() {
        // 2.35:1 cinematic on 16:9 TV — video is wider than screen
        // crop = 1 - 1.778/2.35 = 24.3% > 5 pct → FIT
        // But with generous budget: 24.3% > 20 pct → still FIT
        val result5 = AspectPolicy.computeResizeMode(
            viewportWidth = 1920, viewportHeight = 1080,
            videoWidth = 2350, videoHeight = 1000,
            cropBudget = AspectPolicy.DEFAULT_CROP_BUDGET
        )
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, result5)
    }

    @Test
    fun `nearly matching aspect ratios exceeding 5 pct budget - FIT`() {
        // 16:10 video (1.6) on 16:9 screen (1.778): crop = 1 - 1.6/1.778 = 10% > 5 pct → FIT
        val result = AspectPolicy.computeResizeMode(
            viewportWidth = 1920, viewportHeight = 1080,
            videoWidth = 1600, videoHeight = 1000,
            cropBudget = AspectPolicy.DEFAULT_CROP_BUDGET
        )
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, result)

        // 1920x1080 video on 1920x1020 screen: very close
        // Screen aspect = 1.882, video = 1.778 → crop = 5.5 pct > 5 pct → FIT
        val result2 = AspectPolicy.computeResizeMode(
            viewportWidth = 1920, viewportHeight = 1020,
            videoWidth = 1920, videoHeight = 1080,
            cropBudget = AspectPolicy.DEFAULT_CROP_BUDGET
        )
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, result2)
    }

    @Test
    fun `zero dimensions - FIT safe default`() {
        assertEquals(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectPolicy.computeResizeMode(0, 0, 1920, 1080)
        )
        assertEquals(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectPolicy.computeResizeMode(1920, 1080, 0, 0)
        )
        assertEquals(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectPolicy.computeResizeMode(1920, 1080, -1, 1080)
        )
    }

    @Test
    fun `pixel ratio != 1 handled correctly`() {
        // Video is 1440x1080 with pixel ratio 1.333 → effective aspect = 1440*1.333/1080 = 1.778 (16:9)
        // On 16:9 screen → zero crop → ZOOM
        val result = AspectPolicy.computeResizeMode(
            viewportWidth = 1920, viewportHeight = 1080,
            videoWidth = 1440, videoHeight = 1080,
            pixelWidthHeightRatio = 1.333f,
            cropBudget = AspectPolicy.DEFAULT_CROP_BUDGET
        )
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, result)
    }

    @Test
    fun `exact aspect match - ZOOM (zero crop)`() {
        val result = AspectPolicy.computeResizeMode(
            viewportWidth = 2560, viewportHeight = 1440,
            videoWidth = 1280, videoHeight = 720,
            cropBudget = AspectPolicy.DEFAULT_CROP_BUDGET
        )
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, result)
    }

    @Test
    fun `crop at exactly 5 pct budget threshold - ZOOM`() {
        // Need: cropPercent = exactly 0.05
        // 1 - min/max = 0.05 → min/max = 0.95
        // videoAspect = 1.0, viewportAspect = 1.0/0.95 ≈ 1.0526
        val result = AspectPolicy.computeResizeMode(
            viewportWidth = 10526, viewportHeight = 10000,
            videoWidth = 10000, videoHeight = 10000,
            cropBudget = 0.05f
        )
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, result)
    }

    @Test
    fun `crop just over 5 pct budget - FIT`() {
        // viewportAspect = 1.06, videoAspect = 1.0 → crop = 1 - 1/1.06 = 5.66 pct > 5 pct
        val result = AspectPolicy.computeResizeMode(
            viewportWidth = 10600, viewportHeight = 10000,
            videoWidth = 10000, videoHeight = 10000,
            cropBudget = 0.05f
        )
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, result)
    }

    @Test
    fun `portrait 9x16 video on landscape 16x9 screen - FIT (huge crop)`() {
        val result = AspectPolicy.computeResizeMode(
            viewportWidth = 1920, viewportHeight = 1080,
            videoWidth = 1080, videoHeight = 1920,
            cropBudget = AspectPolicy.GENEROUS_CROP_BUDGET
        )
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, result)
    }

    @Test
    fun `zero crop budget - only exact match gets ZOOM`() {
        // Exact match → zero crop → ZOOM
        assertEquals(
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectPolicy.computeResizeMode(1920, 1080, 1920, 1080, cropBudget = 0f)
        )
        // Even tiny mismatch → FIT
        assertEquals(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectPolicy.computeResizeMode(1921, 1080, 1920, 1080, cropBudget = 0f)
        )
    }

    @Test
    fun `negative pixelWidthHeightRatio treated as FIT`() {
        // Should not crash or return ZOOM for nonsensical pixel ratio
        val result = AspectPolicy.computeResizeMode(
            viewportWidth = 1920, viewportHeight = 1080,
            videoWidth = 1920, videoHeight = 1080,
            pixelWidthHeightRatio = -1f,
            cropBudget = AspectPolicy.DEFAULT_CROP_BUDGET
        )
        // Negative pixel ratio produces negative videoAspect → computeCropPercent guards → FIT
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, result)
    }

    @Test
    fun `computeCropPercent with matching aspects`() {
        assertEquals(0f, AspectPolicy.computeCropPercent(1.778f, 1.778f), 0.001f)
    }

    @Test
    fun `computeCropPercent with S25 Ultra and 16x9`() {
        // S25 Ultra landscape: 2.167, 16:9 video: 1.778
        val crop = AspectPolicy.computeCropPercent(1.778f, 2.167f)
        // Expected: 1 - 1.778/2.167 ≈ 0.179
        assertEquals(0.179f, crop, 0.01f)
    }
}
