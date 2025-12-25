package com.albunyaan.tube.ui.detail.adapters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Unit tests for ChannelLiveAdapter utility functions.
 */
class ChannelLiveAdapterTest {

    // ========== safeQuantityForPlural tests ==========

    @Test
    fun `safeQuantityForPlural returns same value for small counts`() {
        assertEquals(0, ChannelLiveAdapter.safeQuantityForPlural(0L))
        assertEquals(1, ChannelLiveAdapter.safeQuantityForPlural(1L))
        assertEquals(100, ChannelLiveAdapter.safeQuantityForPlural(100L))
        assertEquals(1000, ChannelLiveAdapter.safeQuantityForPlural(1000L))
    }

    @Test
    fun `safeQuantityForPlural returns same value for millions`() {
        assertEquals(1_000_000, ChannelLiveAdapter.safeQuantityForPlural(1_000_000L))
        assertEquals(100_000_000, ChannelLiveAdapter.safeQuantityForPlural(100_000_000L))
    }

    @Test
    fun `safeQuantityForPlural clamps billions to Int MAX_VALUE`() {
        // 3 billion exceeds Int.MAX_VALUE (2,147,483,647)
        val threeBillion = 3_000_000_000L
        assertEquals(Int.MAX_VALUE, ChannelLiveAdapter.safeQuantityForPlural(threeBillion))
    }

    @Test
    fun `safeQuantityForPlural handles exactly Int MAX_VALUE`() {
        assertEquals(Int.MAX_VALUE, ChannelLiveAdapter.safeQuantityForPlural(Int.MAX_VALUE.toLong()))
    }

    @Test
    fun `safeQuantityForPlural clamps Long MAX_VALUE`() {
        assertEquals(Int.MAX_VALUE, ChannelLiveAdapter.safeQuantityForPlural(Long.MAX_VALUE))
    }

    @Test
    fun `safeQuantityForPlural handles value just above Int MAX_VALUE`() {
        val justAbove = Int.MAX_VALUE.toLong() + 1
        assertEquals(Int.MAX_VALUE, ChannelLiveAdapter.safeQuantityForPlural(justAbove))
    }

    // ========== formatDuration tests ==========

    @Test
    fun `formatDuration formats seconds only`() {
        assertEquals("0:00", ChannelLiveAdapter.formatDuration(0))
        assertEquals("0:30", ChannelLiveAdapter.formatDuration(30))
        assertEquals("0:59", ChannelLiveAdapter.formatDuration(59))
    }

    @Test
    fun `formatDuration formats minutes and seconds`() {
        assertEquals("1:00", ChannelLiveAdapter.formatDuration(60))
        assertEquals("1:30", ChannelLiveAdapter.formatDuration(90))
        assertEquals("5:05", ChannelLiveAdapter.formatDuration(305))
        assertEquals("59:59", ChannelLiveAdapter.formatDuration(3599))
    }

    @Test
    fun `formatDuration formats hours minutes and seconds`() {
        assertEquals("1:00:00", ChannelLiveAdapter.formatDuration(3600))
        assertEquals("1:30:00", ChannelLiveAdapter.formatDuration(5400))
        assertEquals("2:05:30", ChannelLiveAdapter.formatDuration(7530))
        assertEquals("10:00:00", ChannelLiveAdapter.formatDuration(36000))
    }

    @Test
    fun `formatDuration handles very long durations`() {
        // 100 hours
        assertEquals("100:00:00", ChannelLiveAdapter.formatDuration(360000))
    }

    // ========== formatScheduledTime tests ==========

    @Test
    fun `formatScheduledTime returns non-empty string`() {
        val date = Date(1735142400000L) // Dec 25, 2024
        val result = ChannelLiveAdapter.formatScheduledTime(date)
        // Just verify it returns something non-empty (actual format depends on locale)
        assertTrue("Expected non-empty formatted date string", result.isNotEmpty())
    }

    @Test
    fun `formatScheduledTime produces consistent output for same date`() {
        val date = Date(1735142400000L)
        val result1 = ChannelLiveAdapter.formatScheduledTime(date)
        val result2 = ChannelLiveAdapter.formatScheduledTime(date)
        assertEquals(result1, result2)
    }

    @Test
    fun `formatScheduledTime uses provided locale for formatting`() {
        val date = Date(1735142400000L) // Dec 25, 2024 12:00:00 UTC
        val usResult = ChannelLiveAdapter.formatScheduledTime(date, Locale.US)
        val germanResult = ChannelLiveAdapter.formatScheduledTime(date, Locale.GERMANY)

        // Both should be non-empty
        assertTrue("US result should be non-empty", usResult.isNotEmpty())
        assertTrue("German result should be non-empty", germanResult.isNotEmpty())

        // CRITICAL: Verify the locale parameter is actually used.
        // If formatScheduledTime() ignored the locale and always used Locale.getDefault(),
        // this assertion would fail when the test runs on a machine with a different default.
        // We verify by comparing against DateFormat output for each locale directly.
        val expectedUsFormat = DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM, DateFormat.SHORT, Locale.US
        ).format(date)
        val expectedGermanFormat = DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM, DateFormat.SHORT, Locale.GERMANY
        ).format(date)

        assertEquals(
            "formatScheduledTime should use Locale.US for formatting",
            expectedUsFormat,
            usResult
        )
        assertEquals(
            "formatScheduledTime should use Locale.GERMANY for formatting",
            expectedGermanFormat,
            germanResult
        )
    }

    @Test
    fun `formatScheduledTime with same locale produces consistent output`() {
        val date = Date(1735142400000L)
        val result1 = ChannelLiveAdapter.formatScheduledTime(date, Locale.FRANCE)
        val result2 = ChannelLiveAdapter.formatScheduledTime(date, Locale.FRANCE)
        assertEquals("Same locale should produce identical output", result1, result2)
    }
}
