package com.albunyaan.tube.player

import com.albunyaan.tube.data.extractor.VideoTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QualityStepDownHelperTest {

    private fun track(
        url: String,
        height: Int,
        bitrate: Int,
        isVideoOnly: Boolean = false,
        fps: Int? = 30,
        qualityLabel: String? = "${height}p",
        mimeType: String? = "video/mp4"
    ) = VideoTrack(
        url = url,
        mimeType = mimeType,
        width = (height * 16 / 9), // Approximate 16:9 aspect ratio
        height = height,
        bitrate = bitrate,
        qualityLabel = qualityLabel,
        fps = fps,
        isVideoOnly = isVideoOnly
    )

    @Test
    fun `returns null when current is null`() {
        val available = listOf(track("url1", 720, 2000000))
        assertNull(QualityStepDownHelper.findNextLowerQualityTrack(null, available))
    }

    @Test
    fun `returns null when available is empty`() {
        val current = track("url1", 720, 2000000)
        assertNull(QualityStepDownHelper.findNextLowerQualityTrack(current, emptyList()))
    }

    @Test
    fun `returns null when current is only available track`() {
        val current = track("url1", 720, 2000000)
        assertNull(QualityStepDownHelper.findNextLowerQualityTrack(current, listOf(current)))
    }

    @Test
    fun `step 1 - video-only switches to muxed at same resolution`() {
        val videoOnly720 = track("url1", 720, 2500000, isVideoOnly = true)
        val muxed720 = track("url2", 720, 2000000, isVideoOnly = false)
        val muxed480 = track("url3", 480, 1000000, isVideoOnly = false)

        val result = QualityStepDownHelper.findNextLowerQualityTrack(
            videoOnly720,
            listOf(videoOnly720, muxed720, muxed480)
        )

        assertEquals(muxed720, result)
    }

    @Test
    fun `step 1 - muxed does not switch to video-only at same resolution`() {
        val muxed720 = track("url1", 720, 2000000, isVideoOnly = false)
        val videoOnly720 = track("url2", 720, 2500000, isVideoOnly = true)
        val muxed480 = track("url3", 480, 1000000, isVideoOnly = false)

        val result = QualityStepDownHelper.findNextLowerQualityTrack(
            muxed720,
            listOf(muxed720, videoOnly720, muxed480)
        )

        // Should skip video-only and go to lower resolution
        assertEquals(muxed480, result)
    }

    @Test
    fun `step 2 - lower bitrate at same resolution`() {
        val high720 = track("url1", 720, 2500000)
        val medium720 = track("url2", 720, 2000000)
        val low720 = track("url3", 720, 1500000)
        val muxed480 = track("url4", 480, 1000000)

        val result = QualityStepDownHelper.findNextLowerQualityTrack(
            high720,
            listOf(high720, medium720, low720, muxed480)
        )

        // Should pick medium (highest of lower bitrates at same resolution)
        assertEquals(medium720, result)
    }

    @Test
    fun `step 2 - picks highest of lower bitrates`() {
        val high720 = track("url1", 720, 3000000)
        val medium720 = track("url2", 720, 2000000)
        val low720 = track("url3", 720, 1000000)

        val result = QualityStepDownHelper.findNextLowerQualityTrack(
            high720,
            listOf(high720, medium720, low720)
        )

        // Should pick medium (2000000) not low (1000000)
        assertEquals(medium720, result)
    }

    @Test
    fun `step 3 - drops to lower resolution when no same-res alternatives`() {
        val only720 = track("url1", 720, 2000000)
        val muxed480 = track("url2", 480, 1000000)
        val muxed360 = track("url3", 360, 500000)

        val result = QualityStepDownHelper.findNextLowerQualityTrack(
            only720,
            listOf(only720, muxed480, muxed360)
        )

        // Should pick 480p (next lower resolution)
        assertEquals(muxed480, result)
    }

    @Test
    fun `step 3 - prefers muxed over video-only at lower resolution`() {
        val current1080 = track("url1", 1080, 4000000)
        val videoOnly720 = track("url2", 720, 2500000, isVideoOnly = true)
        val muxed720 = track("url3", 720, 2000000, isVideoOnly = false)

        val result = QualityStepDownHelper.findNextLowerQualityTrack(
            current1080,
            listOf(current1080, videoOnly720, muxed720)
        )

        // Should prefer muxed720 over videoOnly720
        assertEquals(muxed720, result)
    }

    @Test
    fun `step 3 - prefers higher bitrate when both muxed at same resolution`() {
        val current1080 = track("url1", 1080, 4000000)
        val muxed720High = track("url2", 720, 2500000, isVideoOnly = false)
        val muxed720Low = track("url3", 720, 1500000, isVideoOnly = false)
        val muxed480 = track("url4", 480, 1000000, isVideoOnly = false)

        val result = QualityStepDownHelper.findNextLowerQualityTrack(
            current1080,
            listOf(current1080, muxed720Low, muxed720High, muxed480)
        )

        // Should prefer higher bitrate muxed at 720p
        assertEquals(muxed720High, result)
    }

    @Test
    fun `full step-down sequence - 1080p to 720p muxed to 480p`() {
        val track1080 = track("url1", 1080, 4000000)
        val track720Muxed = track("url2", 720, 2000000, isVideoOnly = false)
        val track720VideoOnly = track("url3", 720, 2500000, isVideoOnly = true)
        val track480 = track("url4", 480, 1000000)
        val track360 = track("url5", 360, 500000)

        val available = listOf(track1080, track720Muxed, track720VideoOnly, track480, track360)

        // From 1080p -> should go to 720p muxed (preferred over video-only)
        val step1 = QualityStepDownHelper.findNextLowerQualityTrack(track1080, available)
        assertEquals(track720Muxed, step1)

        // From 720p muxed -> should go to 480p (no lower bitrate at 720p for muxed)
        val step2 = QualityStepDownHelper.findNextLowerQualityTrack(track720Muxed, available)
        assertEquals(track480, step2)

        // From 480p -> should go to 360p
        val step3 = QualityStepDownHelper.findNextLowerQualityTrack(track480, available)
        assertEquals(track360, step3)

        // From 360p -> no lower, should be null
        val step4 = QualityStepDownHelper.findNextLowerQualityTrack(track360, available)
        assertNull(step4)
    }

    @Test
    fun `video-only to muxed sequence at each resolution`() {
        val track720VideoOnly = track("url1", 720, 2500000, isVideoOnly = true)
        val track720Muxed = track("url2", 720, 2000000, isVideoOnly = false)
        val track480VideoOnly = track("url3", 480, 1200000, isVideoOnly = true)
        val track480Muxed = track("url4", 480, 1000000, isVideoOnly = false)

        val available = listOf(track720VideoOnly, track720Muxed, track480VideoOnly, track480Muxed)

        // From 720p video-only -> should go to 720p muxed
        val step1 = QualityStepDownHelper.findNextLowerQualityTrack(track720VideoOnly, available)
        assertEquals(track720Muxed, step1)

        // From 720p muxed -> should go to 480p muxed (preferred over video-only)
        val step2 = QualityStepDownHelper.findNextLowerQualityTrack(track720Muxed, available)
        assertEquals(track480Muxed, step2)
    }

    @Test
    fun `step 2 - muxed stays muxed even when video-only has lower bitrate`() {
        // Scenario: Currently on muxed 720p @ 2.5Mbps
        // Available: video-only 720p @ 2.0Mbps (lower bitrate), muxed 720p @ 1.5Mbps (lower bitrate)
        // Should pick muxed 1.5Mbps, NOT video-only 2.0Mbps
        val currentMuxed720 = track("url1", 720, 2500000, isVideoOnly = false)
        val videoOnly720Lower = track("url2", 720, 2000000, isVideoOnly = true)
        val muxed720Lower = track("url3", 720, 1500000, isVideoOnly = false)
        val muxed480 = track("url4", 480, 1000000, isVideoOnly = false)

        val available = listOf(currentMuxed720, videoOnly720Lower, muxed720Lower, muxed480)

        val result = QualityStepDownHelper.findNextLowerQualityTrack(currentMuxed720, available)

        // Must stay on muxed, not switch to video-only even though it has higher bitrate among lower options
        assertEquals(muxed720Lower, result)
    }

    @Test
    fun `step 1 - video-only switches to muxed even with multiple video-only options`() {
        // Scenario: Currently on video-only 720p @ 3.0Mbps
        // Available: video-only 720p @ 2.5Mbps, video-only 720p @ 2.0Mbps, muxed 720p @ 1.8Mbps
        // Step 1 priority: switch to muxed at same resolution first, regardless of bitrate
        val currentVideoOnly720 = track("url1", 720, 3000000, isVideoOnly = true)
        val videoOnly720Mid = track("url2", 720, 2500000, isVideoOnly = true)
        val videoOnly720Low = track("url3", 720, 2000000, isVideoOnly = true)
        val muxed720 = track("url4", 720, 1800000, isVideoOnly = false)

        val available = listOf(currentVideoOnly720, videoOnly720Mid, videoOnly720Low, muxed720)

        // Step 1 should switch to muxed at same resolution
        val result = QualityStepDownHelper.findNextLowerQualityTrack(currentVideoOnly720, available)
        assertEquals(muxed720, result)
    }
}
