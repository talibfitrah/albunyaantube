package com.albunyaan.tube.player

import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.VideoTrack
import com.albunyaan.tube.player.Forbidden403Step.EXHAUSTED
import com.albunyaan.tube.player.Forbidden403Step.PLAY_MUXED
import com.albunyaan.tube.player.Forbidden403Step.REFRESH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Forbidden403RecoveryTest {
    @Test fun `first 403 refreshes`() = assertEquals(REFRESH, next403Step(0, 2, playingMuxed = false, hasMuxed = true))
    @Test fun `repeat 403 on adaptive plays muxed`() = assertEquals(PLAY_MUXED, next403Step(1, 2, false, true))
    @Test fun `repeat 403 at the budget edge still plays muxed before exhausting`() = assertEquals(PLAY_MUXED, next403Step(2, 2, false, true))
    @Test fun `403 while muxed refreshes then exhausts`() { assertEquals(REFRESH, next403Step(1, 2, true, true)); assertEquals(EXHAUSTED, next403Step(2, 2, true, true)) }
    @Test fun `no muxed track keeps the refresh ladder`() { assertEquals(REFRESH, next403Step(1, 2, false, false)); assertEquals(EXHAUSTED, next403Step(2, 2, false, false)) }

    private fun track(videoOnly: Boolean) = VideoTrack(
        url = "https://example.com/v", mimeType = "video/mp4", width = 640, height = 360,
        bitrate = 500_000, qualityLabel = "360p", fps = 30, isVideoOnly = videoOnly
    )
    private fun streams(isLive: Boolean, vararg tracks: VideoTrack) = ResolvedStreams(
        streamId = "V2Brp_esIVI", videoTracks = tracks.toList(), audioTracks = emptyList(),
        durationSeconds = 60, isLive = isLive
    )

    @Test fun `VOD with a muxed track has a muxed fallback`() = assertTrue(hasMuxedFallback(streams(false, track(true), track(false))))
    @Test fun `VOD with only video-only tracks has none`() = assertFalse(hasMuxedFallback(streams(false, track(true))))
    // Live ignores forceProgressive (DashSourceBuilder.decide), so PLAY_MUXED would rebuild the same live source.
    @Test fun `live never has a muxed fallback`() = assertFalse(hasMuxedFallback(streams(true, track(false))))
    @Test fun `no streams has none`() = assertFalse(hasMuxedFallback(null))
}
