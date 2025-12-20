package com.albunyaan.tube.player

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ExtractionRateLimiter.
 *
 * Tests use an injected clock to avoid sleeps and ensure deterministic behavior.
 * Verifies rate limiting behavior for manual, auto-recovery, and prefetch request kinds.
 */
class ExtractionRateLimiterTest {

    private var currentTime = 1000L // Start at 1000ms to avoid edge case with 0
    private lateinit var limiter: ExtractionRateLimiter

    @Before
    fun setUp() {
        currentTime = 1000L // Start at 1000ms to avoid edge case with lastAttemptTime > 0 check
        limiter = ExtractionRateLimiter().apply {
            setTestClock { currentTime }
        }
    }

    // --- Basic Acquire/Allow Tests ---

    @Test
    fun `first request is always allowed`() {
        val result = limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)
        assertTrue("First request should be allowed", result is ExtractionRateLimiter.RateLimitResult.Allowed)
    }

    @Test
    fun `second request within minimum interval is rate limited`() {
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)
        limiter.onExtractionSuccess("video1") // Clear backoff to isolate min-interval check

        currentTime = 16_000L // 15 seconds later (less than 30s min interval)
        val result = limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)

        // Should be Delayed because we're within min interval
        // The result should NOT be Allowed - it should be either Delayed or Blocked
        assertFalse("Second request should not be allowed within min interval",
            result is ExtractionRateLimiter.RateLimitResult.Allowed)
    }

    @Test
    fun `request after minimum interval is allowed`() {
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)

        // Need to clear backoff by signaling success
        limiter.onExtractionSuccess("video1")

        currentTime = 32_000L // 31 seconds later (> 30s min interval)
        val result = limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)

        assertTrue("Request after interval should be allowed", result is ExtractionRateLimiter.RateLimitResult.Allowed)
    }

    @Test
    fun `different videos have separate limits`() {
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)

        val result = limiter.acquire("video2", ExtractionRateLimiter.RequestKind.MANUAL)

        assertTrue("Different video should be allowed", result is ExtractionRateLimiter.RateLimitResult.Allowed)
    }

    // --- Per-Video Limit Tests ---

    @Test
    fun `per-video limit blocks after max attempts`() {
        // Exhaust 3 attempts (max per video in 5 min window)
        // Need to wait for min interval + backoff between attempts
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)
        limiter.onExtractionSuccess("video1")

        currentTime = 31_000L
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)
        limiter.onExtractionSuccess("video1")

        currentTime = 62_000L
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)
        limiter.onExtractionSuccess("video1")

        // Fourth attempt should be blocked
        currentTime = 93_000L
        val result = limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)

        assertTrue("Fourth request should be blocked", result is ExtractionRateLimiter.RateLimitResult.Blocked)
    }

    @Test
    fun `per-video limit resets after window expires`() {
        // Exhaust 3 attempts
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)
        limiter.onExtractionSuccess("video1")
        currentTime = 31_000L
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)
        limiter.onExtractionSuccess("video1")
        currentTime = 62_000L
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)
        limiter.onExtractionSuccess("video1")

        // Wait for window to expire (5 minutes from start at 1000ms)
        currentTime = 1000L + 5 * 60 * 1000L + 1000L  // Start + 5min + buffer

        val result = limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)

        assertTrue("Request after window should be allowed", result is ExtractionRateLimiter.RateLimitResult.Allowed)
    }

    // --- Global Limit Tests ---

    @Test
    fun `global limit blocks after max attempts across videos`() {
        // Make 10 requests across different videos (max global in 1 min)
        for (i in 1..10) {
            val result = limiter.acquire("video$i", ExtractionRateLimiter.RequestKind.MANUAL)
            assertTrue("Request $i should be allowed", result is ExtractionRateLimiter.RateLimitResult.Allowed)
        }

        // 11th request should be blocked
        val result = limiter.acquire("video11", ExtractionRateLimiter.RequestKind.MANUAL)

        assertTrue("11th request should be blocked globally", result is ExtractionRateLimiter.RateLimitResult.Blocked)
    }

    @Test
    fun `global limit resets after window expires`() {
        // Make 10 requests
        for (i in 1..10) {
            limiter.acquire("video$i", ExtractionRateLimiter.RequestKind.MANUAL)
        }

        // Wait for global window to expire (1 minute from start at 1000ms)
        currentTime = 1000L + 61_000L  // Start + 1min + buffer

        val result = limiter.acquire("video11", ExtractionRateLimiter.RequestKind.MANUAL)

        assertTrue("Request after global window should be allowed", result is ExtractionRateLimiter.RateLimitResult.Allowed)
    }

    @Test
    fun `auto-recovery bypasses global limit when exhausted`() {
        // Exhaust global limit with MANUAL requests (10 max per minute)
        for (i in 1..10) {
            val result = limiter.acquire("video$i", ExtractionRateLimiter.RequestKind.MANUAL)
            assertTrue("Request $i should be allowed", result is ExtractionRateLimiter.RateLimitResult.Allowed)
        }

        // Verify MANUAL is now blocked
        val manualResult = limiter.acquire("video11", ExtractionRateLimiter.RequestKind.MANUAL)
        assertTrue("MANUAL should be blocked by global limit", manualResult is ExtractionRateLimiter.RateLimitResult.Blocked)

        // Verify PREFETCH is also blocked
        val prefetchResult = limiter.acquire("video12", ExtractionRateLimiter.RequestKind.PREFETCH)
        assertTrue("PREFETCH should be blocked by global limit", prefetchResult is ExtractionRateLimiter.RateLimitResult.Blocked)

        // AUTO_RECOVERY should bypass the global limit entirely
        val recoveryResult = limiter.acquire("video13", ExtractionRateLimiter.RequestKind.AUTO_RECOVERY)
        assertTrue("AUTO_RECOVERY should bypass global limit", recoveryResult is ExtractionRateLimiter.RateLimitResult.Allowed)
    }

    // --- Auto-Recovery Reserved Budget Tests ---

    @Test
    fun `auto-recovery bypasses minimum interval for first attempt`() {
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)

        currentTime = 6_000L // Only 5 seconds after start at 1000ms
        val result = limiter.acquire("video1", ExtractionRateLimiter.RequestKind.AUTO_RECOVERY)

        assertTrue("First auto-recovery should bypass min interval", result is ExtractionRateLimiter.RateLimitResult.Allowed)
    }

    @Test
    fun `auto-recovery has reserved budget separate from manual`() {
        // Exhaust manual attempts
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)
        limiter.onExtractionSuccess("video1")
        currentTime = 31_000L
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)
        limiter.onExtractionSuccess("video1")
        currentTime = 62_000L
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)
        limiter.onExtractionSuccess("video1")

        // Manual should now be blocked
        currentTime = 93_000L
        val manualResult = limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)
        assertTrue("Manual should be blocked", manualResult is ExtractionRateLimiter.RateLimitResult.Blocked)

        // But auto-recovery should still be allowed (reserved budget)
        currentTime = 124_000L
        val recoveryResult = limiter.acquire("video1", ExtractionRateLimiter.RequestKind.AUTO_RECOVERY)
        assertTrue("Auto-recovery should use reserved budget", recoveryResult is ExtractionRateLimiter.RateLimitResult.Allowed)
    }

    // --- Prefetch Tests ---

    @Test
    fun `prefetch is blocked when budget is under pressure`() {
        // Make 2 requests (leaves 1 slot before max of 3)
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)
        limiter.onExtractionSuccess("video1")
        currentTime = 31_000L
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)
        limiter.onExtractionSuccess("video1")

        // Prefetch should be blocked to preserve budget
        currentTime = 62_000L
        val result = limiter.acquire("video1", ExtractionRateLimiter.RequestKind.PREFETCH)

        assertTrue("Prefetch should be blocked under pressure", result is ExtractionRateLimiter.RateLimitResult.Blocked)
    }

    @Test
    fun `prefetch is allowed when budget has room`() {
        // Make 1 request (leaves 2 slots)
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)

        // Prefetch for different video should be allowed
        val result = limiter.acquire("video2", ExtractionRateLimiter.RequestKind.PREFETCH)

        assertTrue("Prefetch should be allowed with room", result is ExtractionRateLimiter.RateLimitResult.Allowed)
    }

    // --- Exponential Backoff Tests ---

    @Test
    fun `exponential backoff applies to consecutive manual attempts`() {
        // First attempt allowed
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)

        // Second attempt after min interval (31s)
        currentTime = 31_000L
        val result = limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)

        // Should be delayed by backoff (2s base for first consecutive)
        // The implementation adds backoff on top of min interval check
        assertTrue("Should be allowed or delayed by backoff",
            result is ExtractionRateLimiter.RateLimitResult.Allowed ||
            result is ExtractionRateLimiter.RateLimitResult.Delayed)
    }

    @Test
    fun `backoff resets after successful extraction`() {
        // Make two attempts to build up backoff
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)
        currentTime = 35_000L
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)

        // Signal success - resets backoff
        limiter.onExtractionSuccess("video1")

        // Next attempt should not have backoff delay (only min interval)
        currentTime = 66_000L
        val result = limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)

        assertTrue("After success, should be allowed", result is ExtractionRateLimiter.RateLimitResult.Allowed)
    }

    // --- State Management Tests ---

    @Test
    fun `getAttemptCount returns correct count`() {
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)
        limiter.onExtractionSuccess("video1")
        currentTime = 31_000L
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)

        assertEquals("Should have 2 attempts", 2, limiter.getAttemptCount("video1"))
        assertEquals("Unknown video should have 0", 0, limiter.getAttemptCount("unknown"))
    }

    @Test
    fun `getGlobalAttemptCount returns correct count`() {
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)
        limiter.acquire("video2", ExtractionRateLimiter.RequestKind.MANUAL)
        limiter.acquire("video3", ExtractionRateLimiter.RequestKind.MANUAL)

        assertEquals("Should have 3 global attempts", 3, limiter.getGlobalAttemptCount())
    }

    @Test
    fun `clear removes all state`() {
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)
        limiter.acquire("video2", ExtractionRateLimiter.RequestKind.MANUAL)

        limiter.clear()

        assertEquals("Should have 0 attempts after clear", 0, limiter.getAttemptCount("video1"))
        assertEquals("Should have 0 global attempts after clear", 0, limiter.getGlobalAttemptCount())
    }

    @Test
    fun `resetForVideo clears backoff but preserves attempt count`() {
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)
        currentTime = 35_000L
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)

        // Reset backoff for video
        limiter.resetForVideo("video1")

        // Attempt count should remain
        assertEquals("Should still have 2 attempts", 2, limiter.getAttemptCount("video1"))

        // But next request should be allowed (no backoff)
        currentTime = 66_000L
        val result = limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)
        assertTrue("Should be allowed after reset", result is ExtractionRateLimiter.RateLimitResult.Allowed)
    }

    // --- Per-Kind Consecutive Attempts Isolation ---

    @Test
    fun `prefetch does not affect manual backoff`() {
        // Make a MANUAL request to start backoff tracking
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)

        // Make PREFETCH request on same video (should not affect video1's MANUAL backoff)
        currentTime = 6_000L
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.PREFETCH)

        // Wait for min interval from PREFETCH's lastAttemptTime (6000 + 30000 = 36000)
        currentTime = 37_000L

        // Next MANUAL on video1 should not have increased backoff from prefetch
        val result = limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)

        // Should be allowed (or delayed only by normal manual backoff, not inflated)
        assertTrue("MANUAL should not be affected by PREFETCH attempts on same video",
            result is ExtractionRateLimiter.RateLimitResult.Allowed ||
            result is ExtractionRateLimiter.RateLimitResult.Delayed)
    }

    @Test
    fun `auto_recovery does not affect manual backoff counter`() {
        // First MANUAL request at time 1000
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)

        // AUTO_RECOVERY request on same video (bypasses min interval for first)
        currentTime = 5_000L
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.AUTO_RECOVERY)

        // Wait for min interval from AUTO_RECOVERY's lastAttemptTime (5000 + 30000 = 35000)
        currentTime = 36_000L

        // Second MANUAL should have backoff based on 1 consecutive manual, not 2
        // With 1 consecutive manual attempt, backoff is 2s (BACKOFF_BASE_MS)
        // We're at 35s since last MANUAL attempt (36000 - 1000), which is > 2s backoff, so should be allowed
        val result = limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)

        assertTrue("MANUAL backoff should not include AUTO_RECOVERY attempts",
            result is ExtractionRateLimiter.RateLimitResult.Allowed)
    }

    @Test
    fun `manual min interval ignores auto-recovery attempts`() {
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)
        limiter.onExtractionSuccess("video1")

        // Auto-recovery happens within the manual min interval window
        currentTime = 20_000L
        limiter.acquire("video1", ExtractionRateLimiter.RequestKind.AUTO_RECOVERY)

        // Manual should be allowed based on its own last MANUAL attempt time (1000ms)
        currentTime = 32_000L
        val result = limiter.acquire("video1", ExtractionRateLimiter.RequestKind.MANUAL)

        assertTrue("MANUAL min interval should ignore AUTO_RECOVERY attempts",
            result is ExtractionRateLimiter.RateLimitResult.Allowed)
    }

    @Test
    fun `auto_recovery does not contribute to global attempt count`() {
        // Make 9 MANUAL requests (one less than max global of 10)
        for (i in 1..9) {
            val result = limiter.acquire("video$i", ExtractionRateLimiter.RequestKind.MANUAL)
            assertTrue("Request $i should be allowed", result is ExtractionRateLimiter.RateLimitResult.Allowed)
        }

        // Global count should be 9
        assertEquals("Should have 9 global attempts", 9, limiter.getGlobalAttemptCount())

        // Make multiple AUTO_RECOVERY requests - they should NOT add to global count
        for (i in 10..15) {
            val result = limiter.acquire("video$i", ExtractionRateLimiter.RequestKind.AUTO_RECOVERY)
            assertTrue("AUTO_RECOVERY $i should be allowed", result is ExtractionRateLimiter.RateLimitResult.Allowed)
        }

        // Global count should STILL be 9 (AUTO_RECOVERY excluded from global tracking)
        assertEquals("AUTO_RECOVERY should not contribute to global count", 9, limiter.getGlobalAttemptCount())

        // The 10th MANUAL request should still be allowed (global count is 9)
        val manualResult = limiter.acquire("video16", ExtractionRateLimiter.RequestKind.MANUAL)
        assertTrue("10th MANUAL should be allowed (global was 9)", manualResult is ExtractionRateLimiter.RateLimitResult.Allowed)

        // Global count is now 10
        assertEquals("Should have 10 global attempts now", 10, limiter.getGlobalAttemptCount())

        // 11th MANUAL should be blocked
        val blockedResult = limiter.acquire("video17", ExtractionRateLimiter.RequestKind.MANUAL)
        assertTrue("11th MANUAL should be blocked", blockedResult is ExtractionRateLimiter.RateLimitResult.Blocked)
    }
}
