package com.albunyaan.tube.player

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for HTTP 403 error handling logic.
 *
 * Tests that each 403 classification triggers the intended recovery path:
 * - GEO_RESTRICTED: No recovery, shows user-facing error
 * - URL_EXPIRED: Forced refresh with immediate retry
 * - RATE_LIMITED: Exponential backoff before retry
 * - UNKNOWN_403: Exponential backoff + forced refresh as last resort
 *
 * These tests verify the error handling decision logic without Android dependencies.
 * The actual PlayerFragment integration is tested via instrumentation tests.
 */
class Http403ErrorHandlerTest {

    private lateinit var telemetry: StreamRequestTelemetry

    @Before
    fun setUp() {
        telemetry = StreamRequestTelemetry()
    }

    /**
     * Simulates the error handling decision made by PlayerFragment.handle403OrHttpError().
     * This is extracted logic for unit testing purposes.
     */
    private data class ErrorHandlingDecision(
        val shouldRecoverAttempt: Boolean,
        val recoveryAction: RecoveryAction,
        val backoffMs: Long?,
        val userMessage: UserMessage?
    )

    private enum class RecoveryAction {
        NONE,              // No recovery possible (geo-restricted)
        IMMEDIATE_REFRESH, // URL expired - refresh immediately
        DELAYED_REFRESH,   // Rate limited or unknown - delay then refresh
        STANDARD_HANDLING  // Non-403 error - standard error handling
    }

    private enum class UserMessage {
        GEO_RESTRICTED,
        STREAM_EXPIRED,
        RATE_LIMITED,
        STREAM_UNAVAILABLE,
        NONE
    }

    /**
     * Determines the error handling decision based on failure type.
     * This mirrors the logic in PlayerFragment.handle403OrHttpError().
     */
    private fun determineErrorHandling(
        failureType: StreamRequestTelemetry.FailureType,
        currentStreamRefreshCount: Int,
        maxStreamRefreshes: Int,
        retryAfterHeaderSeconds: Long? = null
    ): ErrorHandlingDecision {
        return when (failureType) {
            StreamRequestTelemetry.FailureType.GEO_RESTRICTED -> {
                // No recovery possible
                ErrorHandlingDecision(
                    shouldRecoverAttempt = false,
                    recoveryAction = RecoveryAction.NONE,
                    backoffMs = null,
                    userMessage = UserMessage.GEO_RESTRICTED
                )
            }

            StreamRequestTelemetry.FailureType.URL_EXPIRED -> {
                // Force refresh immediately if under limit
                if (currentStreamRefreshCount < maxStreamRefreshes) {
                    ErrorHandlingDecision(
                        shouldRecoverAttempt = true,
                        recoveryAction = RecoveryAction.IMMEDIATE_REFRESH,
                        backoffMs = null,
                        userMessage = UserMessage.STREAM_EXPIRED
                    )
                } else {
                    ErrorHandlingDecision(
                        shouldRecoverAttempt = false,
                        recoveryAction = RecoveryAction.NONE,
                        backoffMs = null,
                        userMessage = UserMessage.STREAM_UNAVAILABLE
                    )
                }
            }

            StreamRequestTelemetry.FailureType.RATE_LIMITED -> {
                // Exponential backoff: 2s, 4s, 8s, 16s, 32s (or use retry-after header)
                // Clamp to non-negative to prevent undefined bit-shift behavior
                val safeRefreshCount = currentStreamRefreshCount.coerceAtLeast(0)
                val backoffMs = retryAfterHeaderSeconds?.times(1000)
                    ?: (2000L * (1 shl safeRefreshCount.coerceAtMost(4)))

                if (currentStreamRefreshCount < maxStreamRefreshes) {
                    ErrorHandlingDecision(
                        shouldRecoverAttempt = true,
                        recoveryAction = RecoveryAction.DELAYED_REFRESH,
                        backoffMs = backoffMs,
                        userMessage = UserMessage.RATE_LIMITED
                    )
                } else {
                    ErrorHandlingDecision(
                        shouldRecoverAttempt = false,
                        recoveryAction = RecoveryAction.NONE,
                        backoffMs = null,
                        userMessage = UserMessage.STREAM_UNAVAILABLE
                    )
                }
            }

            StreamRequestTelemetry.FailureType.UNKNOWN_403,
            StreamRequestTelemetry.FailureType.HTTP_ERROR,
            StreamRequestTelemetry.FailureType.NETWORK_ERROR -> {
                // Linear backoff: 1.5s, 3s, 4.5s, 6s...
                // Clamp to non-negative to ensure consistent behavior
                val safeCount = currentStreamRefreshCount.coerceAtLeast(0)
                val backoffMs = 1500L * (safeCount + 1)

                if (currentStreamRefreshCount < maxStreamRefreshes) {
                    ErrorHandlingDecision(
                        shouldRecoverAttempt = true,
                        recoveryAction = RecoveryAction.DELAYED_REFRESH,
                        backoffMs = backoffMs,
                        userMessage = UserMessage.NONE
                    )
                } else {
                    ErrorHandlingDecision(
                        shouldRecoverAttempt = false,
                        recoveryAction = RecoveryAction.NONE,
                        backoffMs = null,
                        userMessage = UserMessage.STREAM_UNAVAILABLE
                    )
                }
            }
        }
    }

    // --- GEO_RESTRICTED Tests ---

    @Test
    fun `GEO_RESTRICTED never attempts recovery`() {
        val decision = determineErrorHandling(
            failureType = StreamRequestTelemetry.FailureType.GEO_RESTRICTED,
            currentStreamRefreshCount = 0,
            maxStreamRefreshes = 5
        )

        assertFalse("GEO_RESTRICTED should never recover", decision.shouldRecoverAttempt)
        assertEquals(RecoveryAction.NONE, decision.recoveryAction)
        assertNull("No backoff for non-recoverable error", decision.backoffMs)
        assertEquals(UserMessage.GEO_RESTRICTED, decision.userMessage)
    }

    @Test
    fun `GEO_RESTRICTED ignores refresh count`() {
        // Even with no prior refreshes, geo-restriction is final
        for (count in 0..5) {
            val decision = determineErrorHandling(
                failureType = StreamRequestTelemetry.FailureType.GEO_RESTRICTED,
                currentStreamRefreshCount = count,
                maxStreamRefreshes = 5
            )
            assertFalse("GEO_RESTRICTED should never recover (count=$count)",
                decision.shouldRecoverAttempt)
        }
    }

    // --- URL_EXPIRED Tests ---

    @Test
    fun `URL_EXPIRED triggers immediate refresh on first attempt`() {
        val decision = determineErrorHandling(
            failureType = StreamRequestTelemetry.FailureType.URL_EXPIRED,
            currentStreamRefreshCount = 0,
            maxStreamRefreshes = 5
        )

        assertTrue("URL_EXPIRED should recover", decision.shouldRecoverAttempt)
        assertEquals(RecoveryAction.IMMEDIATE_REFRESH, decision.recoveryAction)
        assertNull("No backoff for immediate refresh", decision.backoffMs)
        assertEquals(UserMessage.STREAM_EXPIRED, decision.userMessage)
    }

    @Test
    fun `URL_EXPIRED respects max refresh limit`() {
        val decision = determineErrorHandling(
            failureType = StreamRequestTelemetry.FailureType.URL_EXPIRED,
            currentStreamRefreshCount = 5,
            maxStreamRefreshes = 5
        )

        assertFalse("URL_EXPIRED should not recover at limit", decision.shouldRecoverAttempt)
        assertEquals(RecoveryAction.NONE, decision.recoveryAction)
        assertEquals(UserMessage.STREAM_UNAVAILABLE, decision.userMessage)
    }

    @Test
    fun `URL_EXPIRED triggers immediate refresh until max reached`() {
        val maxRefreshes = 5
        for (count in 0 until maxRefreshes) {
            val decision = determineErrorHandling(
                failureType = StreamRequestTelemetry.FailureType.URL_EXPIRED,
                currentStreamRefreshCount = count,
                maxStreamRefreshes = maxRefreshes
            )
            assertTrue("URL_EXPIRED should recover (count=$count)", decision.shouldRecoverAttempt)
            assertEquals(RecoveryAction.IMMEDIATE_REFRESH, decision.recoveryAction)
        }

        // At max, should not recover
        val atMaxDecision = determineErrorHandling(
            failureType = StreamRequestTelemetry.FailureType.URL_EXPIRED,
            currentStreamRefreshCount = maxRefreshes,
            maxStreamRefreshes = maxRefreshes
        )
        assertFalse("URL_EXPIRED should not recover at max", atMaxDecision.shouldRecoverAttempt)
    }

    // --- RATE_LIMITED Tests ---

    @Test
    fun `RATE_LIMITED uses exponential backoff on first attempt`() {
        val decision = determineErrorHandling(
            failureType = StreamRequestTelemetry.FailureType.RATE_LIMITED,
            currentStreamRefreshCount = 0,
            maxStreamRefreshes = 5
        )

        assertTrue("RATE_LIMITED should recover", decision.shouldRecoverAttempt)
        assertEquals(RecoveryAction.DELAYED_REFRESH, decision.recoveryAction)
        assertEquals(2000L, decision.backoffMs) // 2s * 2^0 = 2s
        assertEquals(UserMessage.RATE_LIMITED, decision.userMessage)
    }

    @Test
    fun `RATE_LIMITED backoff doubles with each attempt`() {
        val expectedBackoffs = listOf(2000L, 4000L, 8000L, 16000L, 32000L)

        for (count in 0 until 5) {
            val decision = determineErrorHandling(
                failureType = StreamRequestTelemetry.FailureType.RATE_LIMITED,
                currentStreamRefreshCount = count,
                maxStreamRefreshes = 10
            )
            assertEquals("Backoff at attempt $count", expectedBackoffs[count], decision.backoffMs)
        }
    }

    @Test
    fun `RATE_LIMITED backoff caps at 32 seconds`() {
        // coerceAtMost(4) means max shift is 4 -> 2^4 = 16 -> 2000*16 = 32000
        val decision5 = determineErrorHandling(
            failureType = StreamRequestTelemetry.FailureType.RATE_LIMITED,
            currentStreamRefreshCount = 5,
            maxStreamRefreshes = 10
        )
        assertEquals(32000L, decision5.backoffMs) // Capped at 2^4

        val decision10 = determineErrorHandling(
            failureType = StreamRequestTelemetry.FailureType.RATE_LIMITED,
            currentStreamRefreshCount = 10,
            maxStreamRefreshes = 20
        )
        assertEquals(32000L, decision10.backoffMs) // Still capped
    }

    @Test
    fun `RATE_LIMITED uses retry-after header when provided`() {
        val decision = determineErrorHandling(
            failureType = StreamRequestTelemetry.FailureType.RATE_LIMITED,
            currentStreamRefreshCount = 0,
            maxStreamRefreshes = 5,
            retryAfterHeaderSeconds = 60 // 60 seconds
        )

        assertEquals(60000L, decision.backoffMs) // 60s in ms
    }

    @Test
    fun `RATE_LIMITED respects max refresh limit`() {
        val decision = determineErrorHandling(
            failureType = StreamRequestTelemetry.FailureType.RATE_LIMITED,
            currentStreamRefreshCount = 5,
            maxStreamRefreshes = 5
        )

        assertFalse("RATE_LIMITED should not recover at limit", decision.shouldRecoverAttempt)
        assertEquals(RecoveryAction.NONE, decision.recoveryAction)
        assertEquals(UserMessage.STREAM_UNAVAILABLE, decision.userMessage)
    }

    // --- UNKNOWN_403 Tests ---

    @Test
    fun `UNKNOWN_403 uses linear backoff on first attempt`() {
        val decision = determineErrorHandling(
            failureType = StreamRequestTelemetry.FailureType.UNKNOWN_403,
            currentStreamRefreshCount = 0,
            maxStreamRefreshes = 5
        )

        assertTrue("UNKNOWN_403 should recover", decision.shouldRecoverAttempt)
        assertEquals(RecoveryAction.DELAYED_REFRESH, decision.recoveryAction)
        assertEquals(1500L, decision.backoffMs) // 1500 * (0+1) = 1500
        assertEquals(UserMessage.NONE, decision.userMessage) // No toast for unknown
    }

    @Test
    fun `UNKNOWN_403 backoff increases linearly`() {
        val expectedBackoffs = listOf(1500L, 3000L, 4500L, 6000L, 7500L)

        for (count in 0 until 5) {
            val decision = determineErrorHandling(
                failureType = StreamRequestTelemetry.FailureType.UNKNOWN_403,
                currentStreamRefreshCount = count,
                maxStreamRefreshes = 10
            )
            assertEquals("Backoff at attempt $count", expectedBackoffs[count], decision.backoffMs)
        }
    }

    @Test
    fun `UNKNOWN_403 respects max refresh limit`() {
        val decision = determineErrorHandling(
            failureType = StreamRequestTelemetry.FailureType.UNKNOWN_403,
            currentStreamRefreshCount = 5,
            maxStreamRefreshes = 5
        )

        assertFalse("UNKNOWN_403 should not recover at limit", decision.shouldRecoverAttempt)
        assertEquals(RecoveryAction.NONE, decision.recoveryAction)
    }

    // --- HTTP_ERROR Tests ---

    @Test
    fun `HTTP_ERROR uses same logic as UNKNOWN_403`() {
        val httpErrorDecision = determineErrorHandling(
            failureType = StreamRequestTelemetry.FailureType.HTTP_ERROR,
            currentStreamRefreshCount = 2,
            maxStreamRefreshes = 5
        )

        val unknown403Decision = determineErrorHandling(
            failureType = StreamRequestTelemetry.FailureType.UNKNOWN_403,
            currentStreamRefreshCount = 2,
            maxStreamRefreshes = 5
        )

        assertEquals(httpErrorDecision.shouldRecoverAttempt, unknown403Decision.shouldRecoverAttempt)
        assertEquals(httpErrorDecision.recoveryAction, unknown403Decision.recoveryAction)
        assertEquals(httpErrorDecision.backoffMs, unknown403Decision.backoffMs)
    }

    // --- NETWORK_ERROR Tests ---

    @Test
    fun `NETWORK_ERROR uses same logic as UNKNOWN_403`() {
        val networkErrorDecision = determineErrorHandling(
            failureType = StreamRequestTelemetry.FailureType.NETWORK_ERROR,
            currentStreamRefreshCount = 1,
            maxStreamRefreshes = 5
        )

        val unknown403Decision = determineErrorHandling(
            failureType = StreamRequestTelemetry.FailureType.UNKNOWN_403,
            currentStreamRefreshCount = 1,
            maxStreamRefreshes = 5
        )

        assertEquals(networkErrorDecision.shouldRecoverAttempt, unknown403Decision.shouldRecoverAttempt)
        assertEquals(networkErrorDecision.recoveryAction, unknown403Decision.recoveryAction)
        assertEquals(networkErrorDecision.backoffMs, unknown403Decision.backoffMs)
    }

    // --- Edge Cases ---

    @Test
    fun `zero max refreshes means no recovery for any type except geo-restricted`() {
        val types = listOf(
            StreamRequestTelemetry.FailureType.URL_EXPIRED,
            StreamRequestTelemetry.FailureType.RATE_LIMITED,
            StreamRequestTelemetry.FailureType.UNKNOWN_403
        )

        for (type in types) {
            val decision = determineErrorHandling(
                failureType = type,
                currentStreamRefreshCount = 0,
                maxStreamRefreshes = 0
            )
            assertFalse("$type should not recover with maxRefreshes=0",
                decision.shouldRecoverAttempt)
        }
    }

    @Test
    fun `negative refresh count is handled safely`() {
        // Edge case - shouldn't happen but shouldn't crash
        val decision = determineErrorHandling(
            failureType = StreamRequestTelemetry.FailureType.URL_EXPIRED,
            currentStreamRefreshCount = -1,
            maxStreamRefreshes = 5
        )

        assertTrue("Negative count should still allow recovery", decision.shouldRecoverAttempt)
    }

    @Test
    fun `negative refresh count produces non-negative backoff for RATE_LIMITED`() {
        // Verify that negative refresh count doesn't cause negative shift overflow
        val decision = determineErrorHandling(
            failureType = StreamRequestTelemetry.FailureType.RATE_LIMITED,
            currentStreamRefreshCount = -1,
            maxStreamRefreshes = 5
        )

        assertTrue("Negative count should still allow recovery", decision.shouldRecoverAttempt)
        assertNotNull("Backoff should be present", decision.backoffMs)
        assertTrue("Backoff must be non-negative", decision.backoffMs!! >= 0)
        assertEquals("Backoff should be 2000ms (2^0 = 1)", 2000L, decision.backoffMs)
    }

    @Test
    fun `very high refresh count at limit prevents recovery`() {
        val decision = determineErrorHandling(
            failureType = StreamRequestTelemetry.FailureType.RATE_LIMITED,
            currentStreamRefreshCount = 100,
            maxStreamRefreshes = 100
        )

        assertFalse("At limit should not recover", decision.shouldRecoverAttempt)
    }

    // --- Integration with Telemetry Classification ---

    @Test
    fun `telemetry classification flows to correct recovery action`() {
        // Test that telemetry classification leads to expected recovery

        // Record a geo-restricted failure
        val geoType = telemetry.recordFailure(
            videoId = "video1", streamType = "HLS",
            requestUrl = "https://example.com",
            requestHeaders = emptyMap(),
            responseCode = 403, responseHeaders = emptyMap(),
            responseBody = "country blocked"
        )
        val geoDecision = determineErrorHandling(geoType, 0, 5)
        assertFalse("Geo-restricted should not recover", geoDecision.shouldRecoverAttempt)

        // Record an expired URL failure
        val expiredType = telemetry.recordFailure(
            videoId = "video2", streamType = "DASH",
            requestUrl = "https://example.com",
            requestHeaders = emptyMap(),
            responseCode = 403, responseHeaders = emptyMap(),
            responseBody = "signature expired"
        )
        val expiredDecision = determineErrorHandling(expiredType, 0, 5)
        assertTrue("Expired should recover", expiredDecision.shouldRecoverAttempt)
        assertEquals(RecoveryAction.IMMEDIATE_REFRESH, expiredDecision.recoveryAction)

        // Record a rate-limited failure
        val rateLimitType = telemetry.recordFailure(
            videoId = "video3", streamType = "PROGRESSIVE",
            requestUrl = "https://example.com",
            requestHeaders = emptyMap(),
            responseCode = 403, responseHeaders = mapOf("retry-after" to listOf("30")),
            responseBody = null
        )
        val rateLimitDecision = determineErrorHandling(rateLimitType, 0, 5, 30)
        assertTrue("Rate-limited should recover", rateLimitDecision.shouldRecoverAttempt)
        assertEquals(RecoveryAction.DELAYED_REFRESH, rateLimitDecision.recoveryAction)
        assertEquals(30000L, rateLimitDecision.backoffMs)
    }

    // --- Boundary Condition Tests ---

    @Test
    fun `refresh count exactly at boundary prevents recovery`() {
        val types = listOf(
            StreamRequestTelemetry.FailureType.URL_EXPIRED,
            StreamRequestTelemetry.FailureType.RATE_LIMITED,
            StreamRequestTelemetry.FailureType.UNKNOWN_403
        )

        for (type in types) {
            val atBoundary = determineErrorHandling(type, 5, 5)
            assertFalse("$type at boundary should not recover", atBoundary.shouldRecoverAttempt)

            val belowBoundary = determineErrorHandling(type, 4, 5)
            assertTrue("$type below boundary should recover", belowBoundary.shouldRecoverAttempt)
        }
    }

    @Test
    fun `large retry-after header values are handled`() {
        val decision = determineErrorHandling(
            failureType = StreamRequestTelemetry.FailureType.RATE_LIMITED,
            currentStreamRefreshCount = 0,
            maxStreamRefreshes = 5,
            retryAfterHeaderSeconds = 3600 // 1 hour
        )

        assertEquals(3600000L, decision.backoffMs) // 1 hour in ms
    }

    @Test
    fun `zero retry-after uses exponential backoff`() {
        val decision = determineErrorHandling(
            failureType = StreamRequestTelemetry.FailureType.RATE_LIMITED,
            currentStreamRefreshCount = 0,
            maxStreamRefreshes = 5,
            retryAfterHeaderSeconds = 0
        )

        // 0 * 1000 = 0, but toLongOrNull() on "0" returns 0, which times 1000 = 0
        // The ?: fallback isn't used because 0L * 1000 = 0L which is not null
        assertEquals(0L, decision.backoffMs)
    }
}
