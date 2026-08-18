package com.albunyaan.tube.data.extractor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live resolves must never be served from the stream cache.
 *
 * A cached live manifest is the cause of the "Resolving stream…" interruption users reported on
 * live channels: the manifest is only usable at the live edge, so a few minutes later its first
 * segment fetch 403s and the player force-re-resolves in front of the user. Measured on-device
 * (2026-08-18): a 5-minute-old live manifest 403'd 3.7s into playback; the refreshed one played.
 *
 * VOD is unaffected — its URLs really do last hours, which is what the 30-minute TTL is for.
 */
class StreamCacheLiveTest {

    private val fresh = 60_000L          // a minute old: comfortably inside the TTL
    private val stale = 31 * 60_000L     // past the 30-minute TTL

    @Test
    fun `live is never reused, however fresh the entry`() {
        for (age in listOf(0L, 1_000L, fresh)) {
            assertFalse(
                "a live manifest must be re-resolved (age=${age}ms)",
                NewPipeExtractorClient.isStreamCacheUsable(
                    isLive = true, ageMillis = age, timebaseMatches = true,
                ),
            )
        }
    }

    @Test
    fun `fresh VOD is still reused — the cache keeps working`() {
        assertTrue(
            NewPipeExtractorClient.isStreamCacheUsable(
                isLive = false, ageMillis = fresh, timebaseMatches = true,
            ),
        )
    }

    @Test
    fun `VOD past the TTL is not reused`() {
        assertFalse(
            NewPipeExtractorClient.isStreamCacheUsable(
                isLive = false, ageMillis = stale, timebaseMatches = true,
            ),
        )
    }

    @Test
    fun `a timebase change invalidates the entry`() {
        assertFalse(
            NewPipeExtractorClient.isStreamCacheUsable(
                isLive = false, ageMillis = fresh, timebaseMatches = false,
            ),
        )
    }
}
