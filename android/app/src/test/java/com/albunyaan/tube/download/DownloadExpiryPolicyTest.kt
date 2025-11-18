package com.albunyaan.tube.download

import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/**
 * Unit tests for DownloadExpiryPolicy.
 *
 * Tests the expiry calculation logic using a fixed clock for deterministic results.
 */
class DownloadExpiryPolicyTest {

    private fun createPolicy(fixedInstant: Instant): DownloadExpiryPolicy {
        val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
        return DownloadExpiryPolicy(clock)
    }

    @Test
    fun `isExpired returns false for recent download`() {
        val now = Instant.parse("2025-11-18T12:00:00Z")
        val policy = createPolicy(now)

        // Download completed 1 day ago
        val completedAt = now.minusSeconds(TimeUnit.DAYS.toSeconds(1))
        assertFalse(policy.isExpired(completedAt.toEpochMilli()))
    }

    @Test
    fun `isExpired returns false for download at exactly 30 days`() {
        val now = Instant.parse("2025-11-18T12:00:00Z")
        val policy = createPolicy(now)

        // Download completed exactly 30 days ago (within grace period)
        val completedAt = now.minusSeconds(TimeUnit.DAYS.toSeconds(30))
        assertFalse(policy.isExpired(completedAt.toEpochMilli()))
    }

    @Test
    fun `isExpired returns true for download older than 30 days plus grace`() {
        val now = Instant.parse("2025-11-18T12:00:00Z")
        val policy = createPolicy(now)

        // Download completed 31 days and 2 hours ago (beyond grace period)
        val completedAt = now
            .minusSeconds(TimeUnit.DAYS.toSeconds(31))
            .minusSeconds(TimeUnit.HOURS.toSeconds(2))
        assertTrue(policy.isExpired(completedAt.toEpochMilli()))
    }

    @Test
    fun `isExpired returns false during grace period`() {
        val now = Instant.parse("2025-11-18T12:00:00Z")
        val policy = createPolicy(now)

        // Download completed 30 days and 30 minutes ago (within 1-hour grace)
        val completedAt = now
            .minusSeconds(TimeUnit.DAYS.toSeconds(30))
            .minusSeconds(TimeUnit.MINUTES.toSeconds(30))
        assertFalse(policy.isExpired(completedAt.toEpochMilli()))
    }

    @Test
    fun `cutoffMillis returns correct value`() {
        val now = Instant.parse("2025-11-18T12:00:00Z")
        val policy = createPolicy(now)

        val expected = now
            .minusSeconds(TimeUnit.DAYS.toSeconds(30))
            .minusSeconds(TimeUnit.HOURS.toSeconds(1))
            .toEpochMilli()

        assertEquals(expected, policy.cutoffMillis())
    }

    @Test
    fun `daysUntilExpiry returns correct days for recent download`() {
        val now = Instant.parse("2025-11-18T12:00:00Z")
        val policy = createPolicy(now)

        // Download completed 5 days ago
        val completedAt = now.minusSeconds(TimeUnit.DAYS.toSeconds(5))
        val daysRemaining = policy.daysUntilExpiry(completedAt.toEpochMilli())

        assertEquals(25L, daysRemaining)
    }

    @Test
    fun `daysUntilExpiry returns 0 for expired download`() {
        val now = Instant.parse("2025-11-18T12:00:00Z")
        val policy = createPolicy(now)

        // Download completed 60 days ago (expired)
        val completedAt = now.minusSeconds(TimeUnit.DAYS.toSeconds(60))
        val daysRemaining = policy.daysUntilExpiry(completedAt.toEpochMilli())

        assertEquals(0L, daysRemaining)
    }

    @Test
    fun `daysUntilExpiry returns 30 for just completed download`() {
        val now = Instant.parse("2025-11-18T12:00:00Z")
        val policy = createPolicy(now)

        // Download just completed
        val completedAt = now
        val daysRemaining = policy.daysUntilExpiry(completedAt.toEpochMilli())

        assertEquals(30L, daysRemaining)
    }

    @Test
    fun `ttlDays is 30`() {
        val policy = createPolicy(Instant.now())
        assertEquals(30L, policy.ttlDays)
    }

    @Test
    fun `gracePeriodHours is 1`() {
        val policy = createPolicy(Instant.now())
        assertEquals(1L, policy.gracePeriodHours)
    }
}
