package com.albunyaan.tube.player

import com.albunyaan.tube.data.extractor.QualitySelectionOrigin
import com.albunyaan.tube.data.extractor.VideoTrack
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for SYNTH_ADAPTIVE cache-hit logic using PRODUCTION code.
 *
 * These tests exercise the actual [CacheHitDecider] production component that
 * [PlayerFragment.checkCacheHit] delegates to. This ensures that:
 * 1. Tests fail if production logic regresses
 * 2. No "mirror logic" that can silently diverge from production
 *
 * Background: SYNTH_ADAPTIVE uses multi-representation DASH (ABR-capable).
 * Unlike SYNTHETIC_DASH (single-rep, factory pre-selects track), SYNTH_ADAPTIVE
 * relies on track selector constraints (CAP/LOCK mode) for quality enforcement.
 *
 * Key invariant: For SYNTH_ADAPTIVE, quality cap changes should NOT cause
 * MediaSource rebuild because the MPD contains ALL quality levels. Quality
 * switching is handled entirely by track selector, not MediaSource rebuilds.
 *
 * BLOCKER fix (PR6.2): Previously, checkCacheHit() required factorySelectedVideoTrack
 * for MANUAL/AUTO_RECOVERY origins, but SYNTH_ADAPTIVE intentionally sets this to null
 * (ABR selects at playback time). This caused perpetual cache misses → rebuild loops.
 *
 * Fixed by separating SYNTH_ADAPTIVE handling: cache-hit depends ONLY on
 * (streamId, audioOnly) for SYNTH_ADAPTIVE, NOT on track comparison.
 *
 * @see CacheHitDecider
 * @see PlayerFragment.checkCacheHit
 */
class SynthAdaptiveCacheHitLogicTest {

    // --- Helper to create prepared state for SYNTH_ADAPTIVE ---

    private fun synthAdaptivePreparedState(
        videoId: String = "test123",
        qualityCapHeight: Int? = null
    ) = CacheHitDecider.PreparedState(
        streamKey = videoId to false, // (streamId, audioOnly=false)
        streamUrl = "syntheticdash://$videoId",
        adaptiveType = MediaSourceResult.AdaptiveType.SYNTH_ADAPTIVE,
        qualityCapHeight = qualityCapHeight,
        factorySelectedVideoTrack = null // Intentionally null for ABR
    )

    private fun synthAdaptiveRequestedState(
        videoId: String = "test123",
        qualityCapHeight: Int? = null,
        selectionOrigin: QualitySelectionOrigin = QualitySelectionOrigin.AUTO
    ) = CacheHitDecider.RequestedState(
        streamKey = videoId to false,
        audioOnly = false,
        forceProgressive = false,
        qualityCapHeight = qualityCapHeight,
        selectionOrigin = selectionOrigin,
        requestedVideoTrack = null, // Not used for SYNTH_ADAPTIVE
        wouldUseAdaptive = true,
        adaptiveUrl = null
    )

    // --- Helper for SYNTHETIC_DASH (single-rep) ---

    private fun videoTrack(height: Int, bitrate: Int = height * 1000) = VideoTrack(
        url = "https://example.com/video/$height",
        mimeType = "video/webm",
        width = height * 16 / 9,
        height = height,
        bitrate = bitrate,
        qualityLabel = "${height}p",
        fps = 30,
        isVideoOnly = true,
        syntheticDashMetadata = null
    )

    private fun syntheticDashPreparedState(
        videoId: String = "test123",
        qualityCapHeight: Int? = 720,
        factorySelectedTrack: VideoTrack = videoTrack(720)
    ) = CacheHitDecider.PreparedState(
        streamKey = videoId to false,
        streamUrl = "syntheticdash://$videoId",
        adaptiveType = MediaSourceResult.AdaptiveType.SYNTHETIC_DASH,
        qualityCapHeight = qualityCapHeight,
        factorySelectedVideoTrack = factorySelectedTrack
    )

    private fun syntheticDashRequestedState(
        videoId: String = "test123",
        qualityCapHeight: Int? = 720,
        selectionOrigin: QualitySelectionOrigin = QualitySelectionOrigin.MANUAL,
        requestedTrack: VideoTrack = videoTrack(720)
    ) = CacheHitDecider.RequestedState(
        streamKey = videoId to false,
        audioOnly = false,
        forceProgressive = false,
        qualityCapHeight = qualityCapHeight,
        selectionOrigin = selectionOrigin,
        requestedVideoTrack = requestedTrack,
        wouldUseAdaptive = true,
        adaptiveUrl = null
    )

    // --- Core invariant: SYNTH_ADAPTIVE + MANUAL cap is idempotent (no rebuild loops) ---

    @Test
    fun `SYNTH_ADAPTIVE with same quality cap returns Hit`() {
        val prepared = synthAdaptivePreparedState(qualityCapHeight = 720)
        val requested = synthAdaptiveRequestedState(qualityCapHeight = 720)

        val result = CacheHitDecider.evaluate(prepared, requested)

        assertTrue("SYNTH_ADAPTIVE with same cap should be Hit", result is CacheHitDecider.Result.Hit)
        assertEquals("Cap should be preserved", 720, (result as CacheHitDecider.Result.Hit).qualityCapToApply)
    }

    @Test
    fun `SYNTH_ADAPTIVE with different quality cap returns Hit with new cap`() {
        val prepared = synthAdaptivePreparedState(qualityCapHeight = 720)
        val requested = synthAdaptiveRequestedState(qualityCapHeight = 1080) // User selected different quality

        val result = CacheHitDecider.evaluate(prepared, requested)

        assertTrue("SYNTH_ADAPTIVE with changed cap should still be Hit", result is CacheHitDecider.Result.Hit)
        assertEquals("New cap should be applied", 1080, (result as CacheHitDecider.Result.Hit).qualityCapToApply)
    }

    @Test
    fun `SYNTH_ADAPTIVE with null factorySelectedVideoTrack returns Hit (BLOCKER fix)`() {
        // This is the BLOCKER case: SYNTH_ADAPTIVE intentionally has null factorySelectedVideoTrack
        // because ABR (Adaptive Bitrate) handles quality selection at playback time.
        // Before the fix, this caused Miss → rebuild loops for MANUAL selection.
        val prepared = synthAdaptivePreparedState(qualityCapHeight = 480)
        val requested = synthAdaptiveRequestedState(
            qualityCapHeight = 480,
            selectionOrigin = QualitySelectionOrigin.MANUAL // MANUAL with null track was the bug
        )

        val result = CacheHitDecider.evaluate(prepared, requested)

        assertTrue("SYNTH_ADAPTIVE with null factorySelectedVideoTrack should be Hit", result is CacheHitDecider.Result.Hit)
    }

    @Test
    fun `SYNTH_ADAPTIVE removing quality cap returns Hit with null cap`() {
        val prepared = synthAdaptivePreparedState(qualityCapHeight = 720)
        val requested = synthAdaptiveRequestedState(qualityCapHeight = null) // Removing cap (e.g., "Auto" quality)

        val result = CacheHitDecider.evaluate(prepared, requested)

        assertTrue("Removing cap should be Hit", result is CacheHitDecider.Result.Hit)
        assertNull("Cap should be null", (result as CacheHitDecider.Result.Hit).qualityCapToApply)
    }

    @Test
    fun `SYNTH_ADAPTIVE adding quality cap returns Hit with new cap`() {
        val prepared = synthAdaptivePreparedState(qualityCapHeight = null) // No cap initially
        val requested = synthAdaptiveRequestedState(qualityCapHeight = 480) // Adding cap

        val result = CacheHitDecider.evaluate(prepared, requested)

        assertTrue("Adding cap should be Hit", result is CacheHitDecider.Result.Hit)
        assertEquals("New cap should be applied", 480, (result as CacheHitDecider.Result.Hit).qualityCapToApply)
    }

    @Test
    fun `SYNTH_ADAPTIVE with MANUAL origin and cap change returns Hit`() {
        // Critical regression test: MANUAL origin should NOT cause Miss for SYNTH_ADAPTIVE
        val prepared = synthAdaptivePreparedState(qualityCapHeight = 720)
        val requested = synthAdaptiveRequestedState(
            qualityCapHeight = 480,
            selectionOrigin = QualitySelectionOrigin.MANUAL
        )

        val result = CacheHitDecider.evaluate(prepared, requested)

        assertTrue("SYNTH_ADAPTIVE MANUAL cap change should be Hit", result is CacheHitDecider.Result.Hit)
        assertEquals("New cap should be applied", 480, (result as CacheHitDecider.Result.Hit).qualityCapToApply)
    }

    @Test
    fun `SYNTH_ADAPTIVE with AUTO_RECOVERY origin returns Hit`() {
        val prepared = synthAdaptivePreparedState(qualityCapHeight = 720)
        val requested = synthAdaptiveRequestedState(
            qualityCapHeight = 480,
            selectionOrigin = QualitySelectionOrigin.AUTO_RECOVERY
        )

        val result = CacheHitDecider.evaluate(prepared, requested)

        assertTrue("SYNTH_ADAPTIVE AUTO_RECOVERY should be Hit", result is CacheHitDecider.Result.Hit)
    }

    // --- Edge cases: stream ID mismatch should Miss ---

    @Test
    fun `SYNTH_ADAPTIVE with different streamId returns Miss`() {
        val prepared = synthAdaptivePreparedState(videoId = "video1", qualityCapHeight = 720)
        val requested = synthAdaptiveRequestedState(videoId = "video2", qualityCapHeight = 720)

        val result = CacheHitDecider.evaluate(prepared, requested)

        assertTrue("Different streamId should be Miss", result is CacheHitDecider.Result.Miss)
    }

    @Test
    fun `SYNTH_ADAPTIVE with null preparedStreamUrl returns Miss`() {
        val prepared = CacheHitDecider.PreparedState(
            streamKey = "test123" to false,
            streamUrl = null, // Not prepared yet
            adaptiveType = MediaSourceResult.AdaptiveType.SYNTH_ADAPTIVE,
            qualityCapHeight = 720,
            factorySelectedVideoTrack = null
        )
        val requested = synthAdaptiveRequestedState(qualityCapHeight = 720)

        val result = CacheHitDecider.evaluate(prepared, requested)

        assertTrue("Null streamUrl should be Miss", result is CacheHitDecider.Result.Miss)
    }

    // --- SYNTHETIC_DASH (single-rep) should still require track comparison ---

    @Test
    fun `SYNTHETIC_DASH with same track returns Hit with null cap`() {
        // MAJOR fix validation: SYNTHETIC_DASH is single-rep, so track selector constraints
        // should NOT be applied. Returns Hit(null) to prevent constraint application.
        val track = videoTrack(720)
        val prepared = syntheticDashPreparedState(factorySelectedTrack = track)
        val requested = syntheticDashRequestedState(requestedTrack = track)

        val result = CacheHitDecider.evaluate(prepared, requested)

        assertTrue("SYNTHETIC_DASH with same track should be Hit", result is CacheHitDecider.Result.Hit)
        // Critical: qualityCapToApply must be null for single-rep sources
        assertNull("SYNTHETIC_DASH Hit should have null cap (no track selector constraints)",
            (result as CacheHitDecider.Result.Hit).qualityCapToApply)
    }

    @Test
    fun `SYNTHETIC_DASH with different height returns Miss for MANUAL`() {
        val prepared = syntheticDashPreparedState(
            factorySelectedTrack = videoTrack(720)
        )
        val requested = syntheticDashRequestedState(
            selectionOrigin = QualitySelectionOrigin.MANUAL,
            requestedTrack = videoTrack(480) // Different height
        )

        val result = CacheHitDecider.evaluate(prepared, requested)

        assertTrue("SYNTHETIC_DASH with different height should be Miss", result is CacheHitDecider.Result.Miss)
    }

    @Test
    fun `SYNTHETIC_DASH with cap change returns Miss`() {
        val prepared = syntheticDashPreparedState(qualityCapHeight = 720)
        val requested = syntheticDashRequestedState(qualityCapHeight = 480) // Cap changed

        val result = CacheHitDecider.evaluate(prepared, requested)

        assertTrue("SYNTHETIC_DASH cap change should be Miss", result is CacheHitDecider.Result.Miss)
    }

    @Test
    fun `SYNTHETIC_DASH with same height but different bitrate returns Miss for AUTO_RECOVERY`() {
        val prepared = syntheticDashPreparedState(
            factorySelectedTrack = videoTrack(720, bitrate = 2000000)
        )
        val requested = syntheticDashRequestedState(
            selectionOrigin = QualitySelectionOrigin.AUTO_RECOVERY,
            requestedTrack = videoTrack(720, bitrate = 1000000) // Same height, lower bitrate
        )

        val result = CacheHitDecider.evaluate(prepared, requested)

        assertTrue("SYNTHETIC_DASH same height different bitrate should be Miss for AUTO_RECOVERY",
            result is CacheHitDecider.Result.Miss)
    }

    @Test
    fun `SYNTHETIC_DASH with same height but different bitrate returns Hit with null cap for MANUAL`() {
        // MANUAL only compares height, not bitrate (muxed vs video-only have different bitrates)
        val prepared = syntheticDashPreparedState(
            factorySelectedTrack = videoTrack(720, bitrate = 2000000)
        )
        val requested = syntheticDashRequestedState(
            selectionOrigin = QualitySelectionOrigin.MANUAL,
            requestedTrack = videoTrack(720, bitrate = 1000000) // Same height, different bitrate
        )

        val result = CacheHitDecider.evaluate(prepared, requested)

        assertTrue("SYNTHETIC_DASH same height different bitrate should be Hit for MANUAL",
            result is CacheHitDecider.Result.Hit)
        // SYNTHETIC_DASH is single-rep: must return null cap
        assertNull("SYNTHETIC_DASH Hit should have null cap",
            (result as CacheHitDecider.Result.Hit).qualityCapToApply)
    }

    // --- Edge case: audioOnly and forceProgressive bypass synthetic logic ---

    @Test
    fun `SYNTH_ADAPTIVE with audioOnly follows standard logic`() {
        val prepared = CacheHitDecider.PreparedState(
            streamKey = "test123" to true, // audioOnly = true
            streamUrl = "https://audio.mp4",
            adaptiveType = MediaSourceResult.AdaptiveType.SYNTH_ADAPTIVE,
            qualityCapHeight = null,
            factorySelectedVideoTrack = null
        )
        val requested = CacheHitDecider.RequestedState(
            streamKey = "test123" to true,
            audioOnly = true,
            forceProgressive = false,
            qualityCapHeight = null,
            selectionOrigin = QualitySelectionOrigin.AUTO,
            requestedVideoTrack = null,
            wouldUseAdaptive = false, // Audio is progressive
            adaptiveUrl = null
        )

        // Should follow standard (non-synthetic) logic path
        val result = CacheHitDecider.evaluate(prepared, requested)

        // audioOnly with matching keys should be Hit (standard path)
        assertTrue("audioOnly should follow standard logic", result is CacheHitDecider.Result.Hit)
    }

    @Test
    fun `SYNTH_ADAPTIVE with forceProgressive bypasses synthetic logic and returns Hit`() {
        // forceProgressive=true skips synthetic handling (goes to standard path)
        // Standard path: wouldUseAdaptive=false, so it's a progressive request
        // Since keys match and it's progressive, standard logic returns Hit(null)
        val prepared = synthAdaptivePreparedState(qualityCapHeight = 720)
        val requested = CacheHitDecider.RequestedState(
            streamKey = "test123" to false,
            audioOnly = false,
            forceProgressive = true, // Progressive fallback
            qualityCapHeight = 720,
            selectionOrigin = QualitySelectionOrigin.AUTO,
            requestedVideoTrack = null,
            wouldUseAdaptive = false, // Progressive, not adaptive
            adaptiveUrl = null
        )

        val result = CacheHitDecider.evaluate(prepared, requested)

        // Standard path for progressive: keys match → Hit(null)
        assertTrue("forceProgressive should return Hit via standard logic",
            result is CacheHitDecider.Result.Hit)
        assertNull("forceProgressive Hit should have null cap",
            (result as CacheHitDecider.Result.Hit).qualityCapToApply)
    }

    // --- Bitrate null/0 handling for AUTO_RECOVERY ---

    @Test
    fun `SYNTHETIC_DASH with null bitrate returns Hit for AUTO_RECOVERY (no unnecessary rebuild)`() {
        // MINOR fix validation: If either bitrate is null, treat as match to avoid unnecessary rebuilds.
        // This prevents rebuild loops when bitrate info is missing.
        val prepared = syntheticDashPreparedState(
            factorySelectedTrack = videoTrack(720, bitrate = 2000000)
        )
        // Create track with null bitrate
        val trackWithNullBitrate = VideoTrack(
            url = "https://example.com/video/720",
            mimeType = "video/webm",
            width = 1280,
            height = 720,
            bitrate = null, // Missing bitrate info
            qualityLabel = "720p",
            fps = 30,
            isVideoOnly = true,
            syntheticDashMetadata = null
        )
        val requested = syntheticDashRequestedState(
            selectionOrigin = QualitySelectionOrigin.AUTO_RECOVERY,
            requestedTrack = trackWithNullBitrate
        )

        val result = CacheHitDecider.evaluate(prepared, requested)

        // Heights match, bitrate comparison skipped due to null → Hit
        assertTrue("Null bitrate should not cause Miss for AUTO_RECOVERY",
            result is CacheHitDecider.Result.Hit)
    }

    @Test
    fun `SYNTHETIC_DASH with zero bitrate returns Hit for AUTO_RECOVERY (no unnecessary rebuild)`() {
        // MINOR fix validation: If either bitrate is 0, treat as match (0 means unknown).
        val trackWithZeroBitrate = VideoTrack(
            url = "https://example.com/video/720",
            mimeType = "video/webm",
            width = 1280,
            height = 720,
            bitrate = 0, // Zero/unknown bitrate
            qualityLabel = "720p",
            fps = 30,
            isVideoOnly = true,
            syntheticDashMetadata = null
        )
        val prepared = syntheticDashPreparedState(
            factorySelectedTrack = trackWithZeroBitrate
        )
        val requested = syntheticDashRequestedState(
            selectionOrigin = QualitySelectionOrigin.AUTO_RECOVERY,
            requestedTrack = videoTrack(720, bitrate = 1000000)
        )

        val result = CacheHitDecider.evaluate(prepared, requested)

        // Heights match, bitrate comparison skipped due to zero → Hit
        assertTrue("Zero bitrate should not cause Miss for AUTO_RECOVERY",
            result is CacheHitDecider.Result.Hit)
    }

    @Test
    fun `SYNTHETIC_DASH with valid different bitrates returns Miss for AUTO_RECOVERY`() {
        // Ensure that when BOTH bitrates are valid (>0) and different, we still get Miss.
        // This is the expected behavior for AUTO_RECOVERY step-down detection.
        val prepared = syntheticDashPreparedState(
            factorySelectedTrack = videoTrack(720, bitrate = 2000000)
        )
        val requested = syntheticDashRequestedState(
            selectionOrigin = QualitySelectionOrigin.AUTO_RECOVERY,
            requestedTrack = videoTrack(720, bitrate = 1000000) // Valid, different bitrate
        )

        val result = CacheHitDecider.evaluate(prepared, requested)

        assertTrue("Valid different bitrates should cause Miss for AUTO_RECOVERY",
            result is CacheHitDecider.Result.Miss)
    }

    // --- Standard adaptive (HLS/DASH) URL comparison ---

    @Test
    fun `Standard adaptive with matching URLs returns Hit`() {
        // When prepared adaptiveType is NOT synthetic and wouldUseAdaptive=true,
        // cache-hit is determined by URL comparison.
        val hlsUrl = "https://manifest.googlevideo.com/api/manifest/hls/..."
        val prepared = CacheHitDecider.PreparedState(
            streamKey = "test123" to false,
            streamUrl = hlsUrl,
            adaptiveType = null, // Standard HLS/DASH (not synthetic)
            qualityCapHeight = null,
            factorySelectedVideoTrack = null
        )
        val requested = CacheHitDecider.RequestedState(
            streamKey = "test123" to false,
            audioOnly = false,
            forceProgressive = false,
            qualityCapHeight = null,
            selectionOrigin = QualitySelectionOrigin.AUTO,
            requestedVideoTrack = null,
            wouldUseAdaptive = true,
            adaptiveUrl = hlsUrl // Same URL
        )

        val result = CacheHitDecider.evaluate(prepared, requested)

        assertTrue("Matching adaptive URLs should be Hit", result is CacheHitDecider.Result.Hit)
        assertNull("Standard adaptive Hit should have null cap",
            (result as CacheHitDecider.Result.Hit).qualityCapToApply)
    }

    @Test
    fun `Standard adaptive with mismatched URLs returns Miss`() {
        // When the prepared streamUrl differs from the requested adaptiveUrl,
        // this indicates a different stream source (e.g., HLS→DASH switch) and must rebuild.
        val prepared = CacheHitDecider.PreparedState(
            streamKey = "test123" to false,
            streamUrl = "https://manifest.googlevideo.com/hls/old-manifest",
            adaptiveType = null, // Standard HLS/DASH
            qualityCapHeight = null,
            factorySelectedVideoTrack = null
        )
        val requested = CacheHitDecider.RequestedState(
            streamKey = "test123" to false,
            audioOnly = false,
            forceProgressive = false,
            qualityCapHeight = null,
            selectionOrigin = QualitySelectionOrigin.AUTO,
            requestedVideoTrack = null,
            wouldUseAdaptive = true,
            adaptiveUrl = "https://manifest.googlevideo.com/dash/new-manifest" // Different URL
        )

        val result = CacheHitDecider.evaluate(prepared, requested)

        assertTrue("Mismatched adaptive URLs should be Miss", result is CacheHitDecider.Result.Miss)
    }

    @Test
    fun `Progressive request with matching keys returns Hit regardless of URL`() {
        // When forceProgressive=true or wouldUseAdaptive=false, cache-hit is based on
        // key match only (not URL comparison). This is the standard progressive path.
        val prepared = CacheHitDecider.PreparedState(
            streamKey = "test123" to false,
            streamUrl = "https://progressive.example.com/video.mp4",
            adaptiveType = null, // Could be anything when using progressive
            qualityCapHeight = null,
            factorySelectedVideoTrack = null
        )
        val requested = CacheHitDecider.RequestedState(
            streamKey = "test123" to false,
            audioOnly = false,
            forceProgressive = false,
            qualityCapHeight = null,
            selectionOrigin = QualitySelectionOrigin.AUTO,
            requestedVideoTrack = null,
            wouldUseAdaptive = false, // Progressive request
            adaptiveUrl = null // No adaptive URL for progressive
        )

        val result = CacheHitDecider.evaluate(prepared, requested)

        // Progressive path: keys match → Hit(null), no URL comparison needed
        assertTrue("Progressive request with matching keys should be Hit",
            result is CacheHitDecider.Result.Hit)
        assertNull("Progressive Hit should have null cap",
            (result as CacheHitDecider.Result.Hit).qualityCapToApply)
    }
}
