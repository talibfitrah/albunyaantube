package com.albunyaan.tube.download

import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * P4-T3: Download expiry policy - single source of truth for expiry rules.
 *
 * Downloads are automatically deleted after [ttlDays] days from completion.
 * This policy is used by both the repository's init cleanup and the periodic worker.
 */
@Singleton
class DownloadExpiryPolicy @Inject constructor(
    private val clock: Clock
) {
    /**
     * Content TTL in days. Downloads older than this are deleted.
     */
    val ttlDays: Long = 30L

    /**
     * Grace period in hours to handle clock skew or file system anomalies.
     */
    val gracePeriodHours: Long = 1L

    /**
     * Returns the cutoff instant for expiry checks.
     * Downloads with completedAt before this instant are considered expired.
     */
    fun cutoffInstant(): Instant {
        return clock.instant()
            .minus(ttlDays, ChronoUnit.DAYS)
            .minus(gracePeriodHours, ChronoUnit.HOURS)
    }

    /**
     * Returns the cutoff timestamp in milliseconds since epoch.
     */
    fun cutoffMillis(): Long {
        return cutoffInstant().toEpochMilli()
    }

    /**
     * Check if a download is expired based on its completion timestamp.
     *
     * @param completedAtMillis The completion timestamp in millis since epoch
     * @return true if the download is expired and should be deleted
     */
    fun isExpired(completedAtMillis: Long): Boolean {
        return completedAtMillis < cutoffMillis()
    }

    /**
     * Calculate remaining days until expiry.
     *
     * @param completedAtMillis The completion timestamp in millis since epoch
     * @return Days remaining, or 0 if already expired
     */
    fun daysUntilExpiry(completedAtMillis: Long): Long {
        val expiryMillis = completedAtMillis + TimeUnit.DAYS.toMillis(ttlDays)
        val remainingMillis = expiryMillis - clock.instant().toEpochMilli()
        return maxOf(0, TimeUnit.MILLISECONDS.toDays(remainingMillis))
    }
}
