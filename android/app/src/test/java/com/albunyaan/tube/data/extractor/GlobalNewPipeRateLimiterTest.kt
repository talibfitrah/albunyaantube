package com.albunyaan.tube.data.extractor

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [GlobalNewPipeRateLimiter] (spec §4.5).
 *
 * Bucket scope: only [Priority.BACKGROUND_REFRESH] consumes tokens. The other
 * priorities bypass the bucket immediately. Tests use BACKGROUND_REFRESH for
 * any assertion that exercises the token clock; user-gesture priorities are
 * covered by [user_gesture_priorities_bypass_bucket].
 *
 * Uses kotlinx-coroutines-test virtual time so the 30 s refill period
 * does not require real wall-clock waits. The limiter's `now: () -> Long`
 * seam is bound to the test scheduler's `currentTime` lambda — every refill
 * check reads the live virtual clock, so `advanceTimeBy` triggers refills
 * on the next acquire attempt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlobalNewPipeRateLimiterTest {

    @Test
    fun acquires_immediately_when_tokens_available() = runTest {
        // BACKGROUND_REFRESH can only consume tokens above the foreground
        // reserve (5). With capacity=10, five acquires bring tokens 10→5.
        val limiter = GlobalNewPipeRateLimiter(
            initialTokens = 10,
            capacity = 10,
            refillPeriodMs = 30_000L,
            now = { currentTime },
        )
        repeat(5) {
            assertTrue(limiter.acquire(Priority.BACKGROUND_REFRESH, timeoutMs = 0L))
        }
    }

    @Test
    fun blocks_when_bucket_empty_then_unblocks_after_refill() = runTest {
        // capacity=6 so the first BACKGROUND_REFRESH acquire (6>5) succeeds,
        // bringing tokens to 5 — at the reserve floor. The next acquire must
        // wait for a refill (+1 → 6, then succeeds).
        val limiter = GlobalNewPipeRateLimiter(
            initialTokens = 6,
            capacity = 6,
            refillPeriodMs = 30_000L,
            now = { currentTime },
        )
        assertTrue(limiter.acquire(Priority.BACKGROUND_REFRESH, timeoutMs = 0L))
        // BACKGROUND_REFRESH bucket effectively empty (tokens at reserve floor)
        val deferred = async {
            limiter.acquire(Priority.BACKGROUND_REFRESH, timeoutMs = 60_000L)
        }
        advanceTimeBy(30_001L)
        assertTrue(deferred.await())
    }

    @Test
    fun player_priority_bypasses_bucket() = runTest {
        val limiter = GlobalNewPipeRateLimiter(
            initialTokens = 0,
            capacity = 0,
            refillPeriodMs = 30_000L,
            now = { currentTime },
        )
        assertTrue(limiter.acquire(Priority.PLAYER, timeoutMs = 0L))
    }

    @Test
    fun acquire_returns_false_on_timeout() = runTest {
        val limiter = GlobalNewPipeRateLimiter(
            initialTokens = 0,
            capacity = 0,
            refillPeriodMs = 30_000L,
            now = { currentTime },
        )
        assertFalse(limiter.acquire(Priority.BACKGROUND_REFRESH, timeoutMs = 100L))
    }

    @Test
    fun user_gesture_priorities_bypass_bucket() = runTest {
        // Empty bucket so any path that consumes a token would fail/block.
        val limiter = GlobalNewPipeRateLimiter(
            initialTokens = 0,
            capacity = 0,
            refillPeriodMs = 30_000L,
            now = { currentTime },
        )
        // Both user-initiated priorities must succeed immediately even with
        // no tokens — the bucket is reserved for BACKGROUND_REFRESH. Virtual
        // clock should not advance (no internal delay).
        val before = currentTime
        assertTrue(limiter.acquire(Priority.VISIBLE_INTERACTIVE, timeoutMs = 0L))
        assertTrue(limiter.acquire(Priority.USER_FOREGROUND, timeoutMs = 0L))
        assertEquals(before, currentTime)
    }
}
