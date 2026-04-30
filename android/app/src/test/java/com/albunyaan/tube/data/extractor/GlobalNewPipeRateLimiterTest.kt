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
        val limiter = GlobalNewPipeRateLimiter(
            initialTokens = 5,
            capacity = 5,
            refillPeriodMs = 30_000L,
            now = { currentTime },
        )
        repeat(5) {
            assertTrue(limiter.acquire(Priority.USER_FOREGROUND, timeoutMs = 0L))
        }
    }

    @Test
    fun blocks_when_bucket_empty_then_unblocks_after_refill() = runTest {
        val limiter = GlobalNewPipeRateLimiter(
            initialTokens = 1,
            capacity = 1,
            refillPeriodMs = 30_000L,
            now = { currentTime },
        )
        assertTrue(limiter.acquire(Priority.USER_FOREGROUND, timeoutMs = 0L))
        // bucket empty
        val deferred = async {
            limiter.acquire(Priority.USER_FOREGROUND, timeoutMs = 60_000L)
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
        assertFalse(limiter.acquire(Priority.USER_FOREGROUND, timeoutMs = 100L))
    }

    @Test
    fun visible_interactive_default_timeout_is_fast() = runTest {
        val limiter = GlobalNewPipeRateLimiter(
            initialTokens = 0,
            capacity = 0,
            refillPeriodMs = 30_000L,
            now = { currentTime },
        )

        assertFalse(limiter.acquire(Priority.VISIBLE_INTERACTIVE))
        assertEquals(GlobalNewPipeRateLimiter.DEFAULT_VISIBLE_INTERACTIVE_ACQUIRE_TIMEOUT_MS, currentTime)
    }
}
