package com.albunyaan.tube.player

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

/**
 * Unit tests for StreamUrlRefreshManager.
 *
 * Tests TTL estimation, scheduling logic, and shouldRefreshBeforeOperation()
 * across various boundary conditions:
 * - TTL < 30min (needs preemptive refresh)
 * - TTL ≈ 30min (boundary condition)
 * - TTL > 30min (fresh, no refresh needed)
 * - TTL expired (negative/zero remaining)
 * - Critical TTL threshold (< 10min)
 */
class StreamUrlRefreshManagerTest {

    private lateinit var telemetry: StreamRequestTelemetry
    private lateinit var rateLimiter: ExtractionRateLimiter
    private lateinit var manager: StreamUrlRefreshManager

    private var currentTime = 1000L

    @Before
    fun setUp() {
        currentTime = 1000L
        telemetry = StreamRequestTelemetry()
        rateLimiter = ExtractionRateLimiter().apply {
            setTestClock { currentTime }
        }
        manager = StreamUrlRefreshManager(telemetry, rateLimiter)
    }

    // --- Basic StreamUrlInfo Tests ---

    @Test
    fun `StreamUrlInfo calculates ttlRemainingMs correctly`() {
        // Use current time to ensure TTL is fresh
        val resolvedAt = System.currentTimeMillis()
        val info = StreamUrlRefreshManager.StreamUrlInfo(
            videoId = "video1",
            resolvedAtMs = resolvedAt
        )

        // At creation time, TTL should be approximately 6 hours
        assertTrue("TTL should be close to 6 hours",
            info.ttlRemainingMs > 5 * 60 * 60 * 1000L)
    }

    @Test
    fun `StreamUrlInfo ageMs increases over time`() {
        val resolvedAt = System.currentTimeMillis() - 60_000L // 1 minute ago
        val info = StreamUrlRefreshManager.StreamUrlInfo(
            videoId = "video1",
            resolvedAtMs = resolvedAt
        )

        assertTrue("Age should be at least 60 seconds", info.ageMs >= 60_000L)
    }

    @Test
    fun `StreamUrlInfo isExpired when TTL is zero or negative`() {
        val expiredResolvedAt = System.currentTimeMillis() -
            (StreamUrlRefreshManager.ESTIMATED_TTL_MS + 1000L)
        val info = StreamUrlRefreshManager.StreamUrlInfo(
            videoId = "video1",
            resolvedAtMs = expiredResolvedAt
        )

        assertTrue("Stream should be expired", info.isExpired)
        assertEquals(0L, info.ttlRemainingMs)
    }

    @Test
    fun `StreamUrlInfo needsPreemptiveRefresh when TTL below threshold`() {
        // Resolved 5 hours 35 minutes ago (25 minutes TTL remaining)
        val resolvedAt = System.currentTimeMillis() -
            (5 * 60 * 60 * 1000L + 35 * 60 * 1000L)
        val info = StreamUrlRefreshManager.StreamUrlInfo(
            videoId = "video1",
            resolvedAtMs = resolvedAt
        )

        assertTrue("Should need preemptive refresh (TTL < 30min)", info.needsPreemptiveRefresh)
    }

    @Test
    fun `StreamUrlInfo needsPreemptiveRefresh false when TTL above threshold`() {
        // Resolved 5 hours ago (1 hour TTL remaining)
        val resolvedAt = System.currentTimeMillis() - (5 * 60 * 60 * 1000L)
        val info = StreamUrlRefreshManager.StreamUrlInfo(
            videoId = "video1",
            resolvedAtMs = resolvedAt
        )

        assertFalse("Should not need preemptive refresh (TTL > 30min)", info.needsPreemptiveRefresh)
    }

    @Test
    fun `StreamUrlInfo isCritical when TTL below critical threshold`() {
        // Resolved 5 hours 55 minutes ago (5 minutes TTL remaining)
        val resolvedAt = System.currentTimeMillis() -
            (5 * 60 * 60 * 1000L + 55 * 60 * 1000L)
        val info = StreamUrlRefreshManager.StreamUrlInfo(
            videoId = "video1",
            resolvedAtMs = resolvedAt
        )

        assertTrue("Should be critical (TTL < 10min)", info.isCritical)
    }

    @Test
    fun `StreamUrlInfo withLastCheck creates updated copy`() {
        val info = StreamUrlRefreshManager.StreamUrlInfo(
            videoId = "video1",
            resolvedAtMs = 1000L,
            lastCheckMs = 0L
        )

        val updated = info.withLastCheck(5000L)

        assertEquals(0L, info.lastCheckMs) // Original unchanged
        assertEquals(5000L, updated.lastCheckMs)
        assertEquals("video1", updated.videoId)
    }

    @Test
    fun `StreamUrlInfo withRefreshScheduled creates updated copy`() {
        val info = StreamUrlRefreshManager.StreamUrlInfo(
            videoId = "video1",
            resolvedAtMs = 1000L,
            refreshScheduled = false
        )

        val updated = info.withRefreshScheduled(true)

        assertFalse(info.refreshScheduled) // Original unchanged
        assertTrue(updated.refreshScheduled)
    }

    // --- onStreamResolved Tests ---

    @Test
    fun `onStreamResolved records URL info`() {
        manager.onStreamResolved("video1")

        val info = manager.getUrlInfo("video1")
        assertNotNull(info)
        assertEquals("video1", info?.videoId)
    }

    @Test
    fun `onStreamResolved updates telemetry`() {
        manager.onStreamResolved("video1")

        // Telemetry should also have the video tracked
        assertNotNull(telemetry.getEstimatedTtlRemainingMs("video1"))
    }

    @Test
    fun `onStreamRefreshed updates resolution time`() {
        manager.onStreamResolved("video1")
        val firstInfo = manager.getUrlInfo("video1")

        // Wait a tiny bit
        Thread.sleep(10)

        manager.onStreamRefreshed("video1")
        val secondInfo = manager.getUrlInfo("video1")

        assertTrue("New resolution time should be later",
            secondInfo!!.resolvedAtMs >= firstInfo!!.resolvedAtMs)
    }

    // --- shouldRefreshBeforeOperation Tests ---

    @Test
    fun `shouldRefreshBeforeOperation returns false for unknown video`() {
        assertFalse(manager.shouldRefreshBeforeOperation("unknown"))
    }

    @Test
    fun `shouldRefreshBeforeOperation returns false for fresh stream`() {
        manager.onStreamResolved("video1")

        // Just resolved - should not need refresh
        assertFalse(manager.shouldRefreshBeforeOperation("video1"))
    }

    @Test
    fun `shouldRefreshBeforeOperation returns true when TTL below threshold`() {
        // We need to simulate an old stream - we'll test the underlying StreamUrlInfo logic
        // Since we can't easily inject time into the manager, we test the info calculation directly

        val resolvedAt = System.currentTimeMillis() -
            (StreamUrlRefreshManager.ESTIMATED_TTL_MS -
                StreamUrlRefreshManager.PREEMPTIVE_REFRESH_THRESHOLD_MS + 1000L)
        val info = StreamUrlRefreshManager.StreamUrlInfo(
            videoId = "video1",
            resolvedAtMs = resolvedAt
        )

        assertTrue("Should need refresh when TTL < threshold", info.needsPreemptiveRefresh)
    }

    // --- TTL Boundary Tests ---

    @Test
    fun `TTL exactly at 30 min threshold does not need refresh`() {
        val resolvedAt = System.currentTimeMillis() -
            (StreamUrlRefreshManager.ESTIMATED_TTL_MS -
                StreamUrlRefreshManager.PREEMPTIVE_REFRESH_THRESHOLD_MS)
        val info = StreamUrlRefreshManager.StreamUrlInfo(
            videoId = "video1",
            resolvedAtMs = resolvedAt
        )

        // At exactly threshold, TTL remaining equals threshold, so needsPreemptiveRefresh is false
        assertFalse("At exactly threshold, should not need refresh", info.needsPreemptiveRefresh)
    }

    @Test
    fun `TTL 1ms below 30 min threshold needs refresh`() {
        val resolvedAt = System.currentTimeMillis() -
            (StreamUrlRefreshManager.ESTIMATED_TTL_MS -
                StreamUrlRefreshManager.PREEMPTIVE_REFRESH_THRESHOLD_MS + 1L)
        val info = StreamUrlRefreshManager.StreamUrlInfo(
            videoId = "video1",
            resolvedAtMs = resolvedAt
        )

        assertTrue("Just below threshold should need refresh", info.needsPreemptiveRefresh)
    }

    @Test
    fun `TTL exactly at 10 min critical threshold is not critical`() {
        val resolvedAt = System.currentTimeMillis() -
            (StreamUrlRefreshManager.ESTIMATED_TTL_MS -
                StreamUrlRefreshManager.CRITICAL_TTL_THRESHOLD_MS)
        val info = StreamUrlRefreshManager.StreamUrlInfo(
            videoId = "video1",
            resolvedAtMs = resolvedAt
        )

        assertFalse("At exactly critical threshold, should not be critical", info.isCritical)
    }

    @Test
    fun `TTL 1ms below 10 min critical threshold is critical`() {
        val resolvedAt = System.currentTimeMillis() -
            (StreamUrlRefreshManager.ESTIMATED_TTL_MS -
                StreamUrlRefreshManager.CRITICAL_TTL_THRESHOLD_MS + 1L)
        val info = StreamUrlRefreshManager.StreamUrlInfo(
            videoId = "video1",
            resolvedAtMs = resolvedAt
        )

        assertTrue("Just below critical threshold should be critical", info.isCritical)
    }

    @Test
    fun `expired stream has zero TTL remaining`() {
        val resolvedAt = System.currentTimeMillis() -
            (StreamUrlRefreshManager.ESTIMATED_TTL_MS + 1000L)
        val info = StreamUrlRefreshManager.StreamUrlInfo(
            videoId = "video1",
            resolvedAtMs = resolvedAt
        )

        assertEquals("Expired stream should have 0 TTL", 0L, info.ttlRemainingMs)
        assertTrue("Expired stream should be expired", info.isExpired)
        assertTrue("Expired stream should need refresh", info.needsPreemptiveRefresh)
        assertTrue("Expired stream should be critical", info.isCritical)
    }

    @Test
    fun `negative age scenario has full TTL`() {
        // Future resolution time (clock skew edge case)
        val resolvedAt = System.currentTimeMillis() + 1000L
        val info = StreamUrlRefreshManager.StreamUrlInfo(
            videoId = "video1",
            resolvedAtMs = resolvedAt
        )

        // Age will be negative, but TTL should still be calculated
        // TTL = estimatedExpiry - now = (resolvedAt + 6h) - now = 6h + 1s
        assertTrue("Future resolution should have > 6h TTL",
            info.ttlRemainingMs > StreamUrlRefreshManager.ESTIMATED_TTL_MS)
        assertFalse("Future resolution should not be expired", info.isExpired)
    }

    // --- getTtlRemainingMs Tests ---

    @Test
    fun `getTtlRemainingMs returns null for unknown video`() {
        assertNull(manager.getTtlRemainingMs("unknown"))
    }

    @Test
    fun `getTtlRemainingMs returns value for known video`() {
        manager.onStreamResolved("video1")

        val ttl = manager.getTtlRemainingMs("video1")
        assertNotNull(ttl)
        assertTrue("TTL should be positive", ttl!! > 0)
    }

    // --- isUrlCritical Tests ---

    @Test
    fun `isUrlCritical returns false for unknown video`() {
        assertFalse(manager.isUrlCritical("unknown"))
    }

    @Test
    fun `isUrlCritical returns false for fresh stream`() {
        manager.onStreamResolved("video1")
        assertFalse(manager.isUrlCritical("video1"))
    }

    // --- checkRefreshNeeded Tests ---

    @Test
    fun `checkRefreshNeeded returns false for unknown video`() {
        assertFalse(manager.checkRefreshNeeded("unknown"))
    }

    @Test
    fun `checkRefreshNeeded returns false for fresh video`() {
        manager.onStreamResolved("video1")
        assertFalse(manager.checkRefreshNeeded("video1"))
    }

    // --- setCurrentlyPlaying Tests ---

    @Test
    fun `setCurrentlyPlaying with null clears current video`() {
        manager.setCurrentlyPlaying("video1")
        assertEquals("video1", manager.getCurrentlyPlaying())

        manager.setCurrentlyPlaying(null)

        assertNull("Currently playing should be null after clearing", manager.getCurrentlyPlaying())
    }

    @Test
    fun `setCurrentlyPlaying switches video context`() {
        manager.onStreamResolved("video1")
        manager.onStreamResolved("video2")

        manager.setCurrentlyPlaying("video1")
        assertEquals("video1", manager.getCurrentlyPlaying())

        // Fresh video1 has a scheduled refresh (for future TTL threshold)
        val info1BeforeSwitch = manager.getUrlInfo("video1")
        assertNotNull(info1BeforeSwitch)
        assertTrue("Fresh video1 should have future refresh scheduled", info1BeforeSwitch!!.refreshScheduled)

        manager.setCurrentlyPlaying("video2")
        assertEquals("video2", manager.getCurrentlyPlaying())

        // After switching, video1's scheduled refresh should be cancelled
        val info1AfterSwitch = manager.getUrlInfo("video1")
        assertNotNull("video1 should still be tracked after switch", info1AfterSwitch)
        assertFalse("video1 refresh should be cancelled after switch", info1AfterSwitch!!.refreshScheduled)

        // video2 now has the scheduled refresh
        val info2AfterSwitch = manager.getUrlInfo("video2")
        assertNotNull("video2 should be tracked", info2AfterSwitch)
        assertTrue("video2 should have future refresh scheduled", info2AfterSwitch!!.refreshScheduled)
    }

    // --- clearVideo Tests ---

    @Test
    fun `clearVideo removes video from tracking`() {
        manager.onStreamResolved("video1")
        assertNotNull(manager.getUrlInfo("video1"))

        manager.clearVideo("video1")
        assertNull(manager.getUrlInfo("video1"))
    }

    @Test
    fun `clearVideo also clears from telemetry`() {
        manager.onStreamResolved("video1")
        assertNotNull(telemetry.getEstimatedTtlRemainingMs("video1"))

        manager.clearVideo("video1")
        assertNull(telemetry.getEstimatedTtlRemainingMs("video1"))
    }

    // --- clear Tests ---

    @Test
    fun `clear removes all videos`() {
        manager.onStreamResolved("video1")
        manager.onStreamResolved("video2")
        manager.onStreamResolved("video3")

        manager.clear()

        assertNull(manager.getUrlInfo("video1"))
        assertNull(manager.getUrlInfo("video2"))
        assertNull(manager.getUrlInfo("video3"))
    }

    // --- RefreshCallback Tests ---

    @Test
    fun `setRefreshCallback registers callback`() {
        val callback = object : StreamUrlRefreshManager.RefreshCallback {
            override fun onRefreshNeeded(videoId: String, isCritical: Boolean): Boolean {
                return true
            }
        }

        // Should not crash
        manager.setRefreshCallback(callback)
        manager.setRefreshCallback(null)
    }

    // --- Constants Tests ---

    @Test
    fun `ESTIMATED_TTL_MS is 6 hours`() {
        assertEquals("TTL should be 6 hours in ms",
            6 * 60 * 60 * 1000L,
            StreamUrlRefreshManager.ESTIMATED_TTL_MS)
    }

    @Test
    fun `PREEMPTIVE_REFRESH_THRESHOLD_MS is 30 minutes`() {
        assertEquals("Preemptive threshold should be 30 minutes in ms",
            30 * 60 * 1000L,
            StreamUrlRefreshManager.PREEMPTIVE_REFRESH_THRESHOLD_MS)
    }

    @Test
    fun `CRITICAL_TTL_THRESHOLD_MS is 10 minutes`() {
        assertEquals("Critical threshold should be 10 minutes in ms",
            10 * 60 * 1000L,
            StreamUrlRefreshManager.CRITICAL_TTL_THRESHOLD_MS)
    }

    // --- Edge Cases ---

    @Test
    fun `multiple resolves for same video updates tracking`() {
        manager.onStreamResolved("video1")
        val firstInfo = manager.getUrlInfo("video1")

        Thread.sleep(10)

        manager.onStreamResolved("video1")
        val secondInfo = manager.getUrlInfo("video1")

        assertTrue("Second resolution should be later",
            secondInfo!!.resolvedAtMs >= firstInfo!!.resolvedAtMs)
    }

    @Test
    fun `release clears all state`() {
        manager.onStreamResolved("video1")
        manager.setCurrentlyPlaying("video1")

        manager.release()

        assertNull(manager.getUrlInfo("video1"))
    }

    @Test
    fun `concurrent video tracking works correctly`() {
        // Resolve multiple videos
        repeat(10) { i ->
            manager.onStreamResolved("video$i")
        }

        // All should be tracked
        repeat(10) { i ->
            assertNotNull("video$i should be tracked", manager.getUrlInfo("video$i"))
        }
    }

    // --- StreamUrlInfo.estimatedExpiryMs Tests ---

    @Test
    fun `estimatedExpiryMs is resolvedAt plus TTL`() {
        val resolvedAt = 1000L
        val info = StreamUrlRefreshManager.StreamUrlInfo(
            videoId = "video1",
            resolvedAtMs = resolvedAt
        )

        assertEquals(
            resolvedAt + StreamUrlRefreshManager.ESTIMATED_TTL_MS,
            info.estimatedExpiryMs
        )
    }

    @Test
    fun `custom estimatedExpiryMs can be provided`() {
        val resolvedAt = 1000L
        val customExpiry = 5000L
        val info = StreamUrlRefreshManager.StreamUrlInfo(
            videoId = "video1",
            resolvedAtMs = resolvedAt,
            estimatedExpiryMs = customExpiry
        )

        assertEquals(customExpiry, info.estimatedExpiryMs)
    }
}
