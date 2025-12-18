package com.albunyaan.tube.player

import com.albunyaan.tube.data.extractor.SyntheticDashMetadata
import com.albunyaan.tube.data.extractor.VideoTrack
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for synthetic DASH track selection logic.
 *
 * These tests verify the production logic in [SyntheticDashTrackSelector] that determines
 * whether synthetic DASH should be used or skipped in favor of raw progressive when a
 * higher-res muxed track is available.
 */
class SyntheticDashTrackSelectionTest {

    // Helper to create valid SyntheticDashMetadata
    private fun validDashMetadata(itag: Int = 247) = SyntheticDashMetadata(
        itag = itag,
        initStart = 0,
        initEnd = 100,
        indexStart = 101,
        indexEnd = 200,
        approxDurationMs = 60000,
        codec = "vp9"
    )

    // Helper to create a video-only track (eligible for synthetic DASH)
    private fun videoOnlyTrack(
        height: Int,
        itag: Int = 247
    ) = VideoTrack(
        url = "https://example.com/video/$height",
        mimeType = "video/webm",
        width = height * 16 / 9,
        height = height,
        bitrate = height * 1000,
        qualityLabel = "${height}p",
        fps = 30,
        isVideoOnly = true,
        syntheticDashMetadata = validDashMetadata(itag)
    )

    // Helper to create a muxed track (not eligible for synthetic DASH)
    private fun muxedTrack(height: Int) = VideoTrack(
        url = "https://example.com/muxed/$height",
        mimeType = "video/mp4",
        width = height * 16 / 9,
        height = height,
        bitrate = height * 800,
        qualityLabel = "${height}p",
        fps = 30,
        isVideoOnly = false,
        syntheticDashMetadata = null // Muxed tracks don't have DASH metadata
    )

    // --- Core Skip Logic Tests (using production SyntheticDashTrackSelector) ---

    @Test
    fun `skip synthetic DASH when muxed 720p exists but video-only only has 480p`() {
        val tracks = listOf(
            videoOnlyTrack(480),  // Best video-only
            muxedTrack(720)       // Higher-res muxed
        )
        val cap = 720

        val bestVideoOnly = SyntheticDashTrackSelector.findBestVideoOnlyTrackUnderCap(tracks, cap)
        val shouldSkip = SyntheticDashTrackSelector.shouldSkipSyntheticDash(bestVideoOnly, tracks, cap)

        assertTrue("Should skip synthetic DASH when muxed is higher res", shouldSkip)
    }

    @Test
    fun `use synthetic DASH when video-only 720p equals muxed 720p`() {
        val tracks = listOf(
            videoOnlyTrack(720),  // Video-only at same res
            muxedTrack(720)       // Muxed at same res
        )
        val cap = 720

        val bestVideoOnly = SyntheticDashTrackSelector.findBestVideoOnlyTrackUnderCap(tracks, cap)
        val shouldSkip = SyntheticDashTrackSelector.shouldSkipSyntheticDash(bestVideoOnly, tracks, cap)

        assertFalse("Should use synthetic DASH when resolutions are equal", shouldSkip)
    }

    @Test
    fun `use synthetic DASH when video-only 1080p is higher than muxed 720p`() {
        val tracks = listOf(
            videoOnlyTrack(1080),  // Higher-res video-only
            muxedTrack(720)        // Lower-res muxed
        )
        val cap = 1080

        val bestVideoOnly = SyntheticDashTrackSelector.findBestVideoOnlyTrackUnderCap(tracks, cap)
        val shouldSkip = SyntheticDashTrackSelector.shouldSkipSyntheticDash(bestVideoOnly, tracks, cap)

        assertFalse("Should use synthetic DASH when video-only is higher res", shouldSkip)
    }

    @Test
    fun `use synthetic DASH when no muxed tracks available`() {
        val tracks = listOf(
            videoOnlyTrack(720),
            videoOnlyTrack(480)
        )
        val cap = 720

        val bestVideoOnly = SyntheticDashTrackSelector.findBestVideoOnlyTrackUnderCap(tracks, cap)
        val shouldSkip = SyntheticDashTrackSelector.shouldSkipSyntheticDash(bestVideoOnly, tracks, cap)

        assertFalse("Should use synthetic DASH when no muxed tracks", shouldSkip)
    }

    @Test
    fun `skip synthetic DASH when muxed 480p exists but cap limits video-only to 360p`() {
        val tracks = listOf(
            videoOnlyTrack(720),   // Above cap
            videoOnlyTrack(360),   // Best under cap
            muxedTrack(480)        // Higher than best video-only under cap
        )
        val cap = 480  // Limits video-only to 360p

        val bestVideoOnly = SyntheticDashTrackSelector.findBestVideoOnlyTrackUnderCap(tracks, cap)
        assertEquals(360, bestVideoOnly?.height)

        val shouldSkip = SyntheticDashTrackSelector.shouldSkipSyntheticDash(bestVideoOnly, tracks, cap)
        assertTrue("Should skip when cap makes muxed higher", shouldSkip)
    }

    // --- Edge Cases ---

    @Test
    fun `skip synthetic DASH when no video-only tracks available`() {
        val tracks = listOf(
            muxedTrack(720),
            muxedTrack(480)
        )
        val cap = 720

        val bestVideoOnly = SyntheticDashTrackSelector.findBestVideoOnlyTrackUnderCap(tracks, cap)
        assertNull(bestVideoOnly)

        val shouldSkip = SyntheticDashTrackSelector.shouldSkipSyntheticDash(bestVideoOnly, tracks, cap)
        assertTrue("Should skip when no video-only tracks", shouldSkip)
    }

    @Test
    fun `compare correctly when video-only has null height`() {
        val nullHeightVideoOnly = VideoTrack(
            url = "https://example.com/video",
            mimeType = "video/webm",
            width = null,
            height = null,  // No height info
            bitrate = 1000000,
            qualityLabel = "unknown",
            fps = 30,
            isVideoOnly = true,
            syntheticDashMetadata = validDashMetadata()
        )
        val tracks = listOf(
            nullHeightVideoOnly,
            muxedTrack(480)
        )
        val cap = 720

        // Video-only with null height shouldn't match the filter
        val bestVideoOnly = SyntheticDashTrackSelector.findBestVideoOnlyTrackUnderCap(tracks, cap)
        assertNull(bestVideoOnly)

        val shouldSkip = SyntheticDashTrackSelector.shouldSkipSyntheticDash(bestVideoOnly, tracks, cap)
        assertTrue("Should skip when video-only has null height", shouldSkip)
    }

    @Test
    fun `muxed track above cap is ignored`() {
        val tracks = listOf(
            videoOnlyTrack(480),
            muxedTrack(1080)  // Above cap, should be ignored
        )
        val cap = 720

        val bestVideoOnly = SyntheticDashTrackSelector.findBestVideoOnlyTrackUnderCap(tracks, cap)
        val shouldSkip = SyntheticDashTrackSelector.shouldSkipSyntheticDash(bestVideoOnly, tracks, cap)

        assertFalse("Muxed above cap should be ignored", shouldSkip)
    }

    // --- Real-World Scenarios ---

    @Test
    fun `typical YouTube scenario - video-only 1080p, 720p, 480p + muxed 720p, 360p`() {
        val tracks = listOf(
            videoOnlyTrack(1080, itag = 248),
            videoOnlyTrack(720, itag = 247),
            videoOnlyTrack(480, itag = 244),
            muxedTrack(720),
            muxedTrack(360)
        )

        // At 720p cap: video-only 720p = muxed 720p, use synthetic DASH
        val bestAt720 = SyntheticDashTrackSelector.findBestVideoOnlyTrackUnderCap(tracks, 720)
        assertFalse(SyntheticDashTrackSelector.shouldSkipSyntheticDash(bestAt720, tracks, 720))

        // At 480p cap: video-only 480p > muxed 360p, use synthetic DASH
        val bestAt480 = SyntheticDashTrackSelector.findBestVideoOnlyTrackUnderCap(tracks, 480)
        assertFalse(SyntheticDashTrackSelector.shouldSkipSyntheticDash(bestAt480, tracks, 480))
    }

    @Test
    fun `scenario where only low-res video-only exists`() {
        // Some videos have video-only only at very low resolutions
        val tracks = listOf(
            videoOnlyTrack(240),   // Only video-only available
            muxedTrack(720),
            muxedTrack(480),
            muxedTrack(360)
        )
        val cap = 720

        val bestVideoOnly = SyntheticDashTrackSelector.findBestVideoOnlyTrackUnderCap(tracks, cap)
        assertEquals(240, bestVideoOnly?.height)

        val shouldSkip = SyntheticDashTrackSelector.shouldSkipSyntheticDash(bestVideoOnly, tracks, cap)
        assertTrue("Should prefer 720p muxed over 240p synthetic DASH", shouldSkip)
    }
}
