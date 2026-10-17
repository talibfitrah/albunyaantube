package com.albunyaan.tube.data.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec §9.2(a): refreshAt = elapsed_at_resolve + clamp(expire − wall_at_resolve, 30 min, 6 h) − 10 min. */
class StreamModelsLiveRefreshTest {

    private val elapsed = 1_000_000L
    private val wall = 1_792_200_000_000L
    private val minute = 60_000L

    private fun live(hlsUrl: String?) = ResolvedStreams(
        streamId = "Rs7St51oDDc", videoTracks = emptyList(), audioTracks = emptyList(), durationSeconds = 0,
        hlsUrl = hlsUrl, isLive = true, urlGeneratedAt = elapsed, urlGeneratedAtWallMs = wall,
    )

    private fun hls(expireSeconds: Long) =
        "https://manifest.googlevideo.com/api/manifest/hls_variant/expire/$expireSeconds/ei/x/ip/203.0.113.7/id/Rs7St51oDDc.1/file/index.m3u8"

    @Test fun `refreshes ten minutes before the urls expire`() {
        val expire = wall / 1000 + 21_586                                  // F11: ~5 h 59 m 46 s
        assertEquals(elapsed + 21_586_000L - 10 * minute, live(hls(expire)).liveRefreshAtElapsedMs())
    }

    @Test fun `a lifetime under thirty minutes is clamped up (device clock skew, K9)`() {
        assertEquals(elapsed + 30 * minute - 10 * minute, live(hls(wall / 1000 + 600)).liveRefreshAtElapsedMs())
    }

    @Test fun `a device clock ahead of the expire is clamped up, never scheduled in the past (K9)`() {
        assertEquals(elapsed + 30 * minute - 10 * minute, live(hls(wall / 1000 - 3_600)).liveRefreshAtElapsedMs())
    }

    @Test fun `a lifetime over six hours is clamped down`() {
        assertEquals(elapsed + 360 * minute - 10 * minute, live(hls(wall / 1000 + 86_400)).liveRefreshAtElapsedMs())
    }

    @Test fun `without an expire it refreshes at five hours fifty`() {
        assertEquals(elapsed + 350 * minute, live("https://example.test/live.m3u8").liveRefreshAtElapsedMs())
    }

    @Test fun `a vod stream has no live refresh`() {
        val vod = live(hls(wall / 1000 + 21_586)).copy(isLive = false)
        assertNull(vod.liveRefreshAtElapsedMs())
        assertNull(vod.timeUntilLiveRefreshMs(elapsed))
    }

    @Test fun `the time until it is never negative`() {
        val streams = live(hls(wall / 1000 + 21_586))
        assertEquals(0L, streams.timeUntilLiveRefreshMs(elapsed + 7 * 60 * minute))
        assertEquals(21_586_000L - 10 * minute, streams.timeUntilLiveRefreshMs(elapsed))
    }

    @Test fun `the debug override is floored at thirty seconds`() {
        assertEquals(120_000L, ResolvedStreams.debugLiveRefreshOverrideMs("120000"))
        assertEquals(30_000L, ResolvedStreams.debugLiveRefreshOverrideMs("120"))   // seconds-for-ms typo
        assertEquals(30_000L, ResolvedStreams.debugLiveRefreshOverrideMs("0"))
        assertEquals(30_000L, ResolvedStreams.debugLiveRefreshOverrideMs("-5"))
        assertNull(ResolvedStreams.debugLiveRefreshOverrideMs(null))
        assertNull(ResolvedStreams.debugLiveRefreshOverrideMs("soon"))
    }
}
