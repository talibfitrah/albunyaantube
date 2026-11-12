package com.albunyaan.tube.player

import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.VideoTrack
import com.albunyaan.tube.player.Forbidden403Step.EXHAUSTED
import com.albunyaan.tube.player.Forbidden403Step.PLAY_MUXED
import com.albunyaan.tube.player.Forbidden403Step.REFRESH
import com.albunyaan.tube.player.StreamRequestTelemetry.FailureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

class Forbidden403RecoveryTest {
    // next403Step(403s on this stream incl. this one, refreshes this episode, max, playingMuxed, hasMuxed)
    @Test fun `first 403 refreshes`() = assertEquals(REFRESH, next403Step(1, 0, 2, playingMuxed = false, hasMuxed = true))
    @Test fun `repeat 403 on adaptive plays muxed`() = assertEquals(PLAY_MUXED, next403Step(2, 1, 2, false, true))
    @Test fun `repeat 403 at the budget edge still plays muxed before exhausting`() = assertEquals(PLAY_MUXED, next403Step(2, 2, 2, false, true))
    @Test fun `403 while muxed refreshes then exhausts`() { assertEquals(REFRESH, next403Step(2, 1, 2, true, true)); assertEquals(EXHAUSTED, next403Step(3, 2, 2, true, true)) }
    @Test fun `no muxed track keeps the refresh ladder`() { assertEquals(REFRESH, next403Step(2, 1, 2, false, false)); assertEquals(EXHAUSTED, next403Step(3, 2, 2, false, false)) }

    // The field pattern: play ~40 s, 403, refresh, play again (resume resets the episode
    // budget to 0), 403 again. The per-stream count says this is the second 403.
    @Test fun `second 403 on the same stream after a resume plays muxed`() = assertEquals(PLAY_MUXED, next403Step(2, 0, 2, false, true))
    @Test fun `no muxed after a resume keeps the ladder`() = assertEquals(REFRESH, next403Step(2, 0, 2, false, false))
    @Test fun `live never plays muxed after a resume`() = assertEquals(REFRESH, next403Step(2, 0, 2, false, hasMuxedFallback(streams(true, track(false)))))
    @Test fun `first 403 after another path spent a refresh still refreshes`() = assertEquals(REFRESH, next403Step(1, 1, 2, false, true))

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

    // counts403: only a 403 that fresh URLs should have fixed is evidence for PLAY_MUXED.
    @Test fun `fresh-URL 403 is counted`() = assertTrue(counts403(FailureType.UNKNOWN_403, inSeekWindow = false))
    @Test fun `seek-window 403 is not counted`() = assertFalse(counts403(FailureType.UNKNOWN_403, inSeekWindow = true))
    @Test fun `URL expiry is not counted`() = assertFalse(counts403(FailureType.URL_EXPIRED, inSeekWindow = false))
    @Test fun `rate-limited 403 is not counted`() = assertFalse(counts403(FailureType.RATE_LIMITED, inSeekWindow = false))
    @Test fun `geo-restricted 403 is not counted`() = assertFalse(counts403(FailureType.GEO_RESTRICTED, inSeekWindow = false))

    // next403Count: a repeat is a 403 soon after the last counted one (the field repeat comes ~50 s after a resume).
    @Test fun `the first counted 403 is one`() = assertEquals(1, next403Count(0, lastAtMs = 0, nowMs = 10_000))
    @Test fun `a 403 a minute after the last one is a repeat`() = assertEquals(2, next403Count(1, lastAtMs = 0, nowMs = 60_000))
    // Two network switches an hour apart in a long lecture: each refresh worked, so neither pins 360p.
    @Test fun `a 403 an hour after the last one starts over`() = assertEquals(1, next403Count(1, lastAtMs = 0, nowMs = 3_600_000))

    // afterBackoff: a delayed 403 recovery acts only on the video that 403'd.
    @Test fun `a backoff recovery runs while the 403'd video is still current`() = runTest {
        var ran = false
        afterBackoff(1_500, "V2Brp_esIVI", { "V2Brp_esIVI" }) { ran = true }
        assertTrue(ran)
    }
    @Test fun `a backoff recovery is dropped when the user switched video meanwhile`() = runTest {
        var current = "V2Brp_esIVI"
        var ran = false
        launch { afterBackoff(8_000, "V2Brp_esIVI", { current }) { ran = true } }
        advanceTimeBy(1_000)
        current = "xc7keR2piUM"
        advanceUntilIdle()
        assertFalse(ran)
    }
}
