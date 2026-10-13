package com.albunyaan.tube.ui.bootstrap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The backend's under-13 rejection is permanent by design: it revokes tokens, disables
 * the Firebase account and tombstones it, so the age gate cannot be retried around. That
 * makes a mistyped year unrecoverable, which is why the client must catch it first.
 *
 * These tests pin the boundary. If [ProfileBootstrapViewModel.isUnderMinimumAge] ever
 * stops rejecting an under-13 date, an accidental tap silently destroys a real account
 * again -- and for a Play reviewer, the test credentials they were given.
 */
class MinimumAgeTest {

    private val clock = Clock.fixed(Instant.parse("2026-08-25T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `exactly thirteen today is allowed`() {
        assertFalse(
            ProfileBootstrapViewModel.isUnderMinimumAge(LocalDate.of(2013, 8, 25), clock)
        )
    }

    @Test
    fun `one day short of thirteen is rejected`() {
        assertTrue(
            ProfileBootstrapViewModel.isUnderMinimumAge(LocalDate.of(2013, 8, 26), clock)
        )
    }

    @Test
    fun `comfortably older than thirteen is allowed`() {
        assertFalse(
            ProfileBootstrapViewModel.isUnderMinimumAge(LocalDate.of(1990, 1, 1), clock)
        )
    }

    @Test
    fun `a date in the current year is rejected`() {
        // The exact failure the date picker used to invite: it opened on the current
        // month, so the nearest tappable day produced an age of 0.
        assertTrue(
            ProfileBootstrapViewModel.isUnderMinimumAge(LocalDate.of(2026, 8, 1), clock)
        )
    }

    @Test
    fun `east of UTC before UTC midnight the server's earlier date decides`() {
        // The server measures age on the UTC date (ClockConfig: Clock.systemUTC()). At 08:30 on
        // 2025-03-01 in Tokyo it is still 2025-02-28 in UTC, so this DOB is 12 to the server.
        val tokyo = Clock.fixed(Instant.parse("2025-02-28T23:30:00Z"), ZoneId.of("Asia/Tokyo"))
        assertTrue(
            ProfileBootstrapViewModel.isUnderMinimumAge(LocalDate.of(2012, 2, 29), tokyo)
        )
    }

    @Test
    fun `client boundary matches the backend's MIN_AGE`() {
        // AccountProfileService.MIN_AGE is 13. If the server ever moves, this must move
        // with it or the client starts submitting dates the server will destroy accounts
        // over.
        assertTrue(ProfileBootstrapViewModel.MIN_AGE_YEARS == 13)
    }
}
