package com.albunyaan.tube.data.me

import com.albunyaan.tube.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * ANDROID-PERSONAL-03 / T3: ISO-week bucket math.
 *
 * Cubic R-final7 — boundaries are ISO weeks (Monday-Sunday) per the
 * 2026-05-16 weekly-grouping design. Tests pin `now` to a Monday so the
 * math is obvious, and recompute expected boundaries via the same
 * TemporalAdjusters used by the production code (relative to
 * ZoneId.systemDefault() — same zone the impl uses).
 */
class WeekBucketTest {

    /** 2026-04-27 12:00:00 UTC — a Monday. */
    private val NOW = 1761566400000L

    private fun expectedMondayMillis(weeksBack: Long): Long {
        val zone = ZoneId.systemDefault()
        val nowDate = Instant.ofEpochMilli(NOW).atZone(zone).toLocalDate()
        val thisMonday = nowDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return thisMonday.minusWeeks(weeksBack).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    @Test
    fun `weekIndex 0 spans this ISO Monday to next Monday`() {
        val b = WeekBucket.forIndex(weekIndex = 0, now = NOW)
        assertEquals(0, b.weekIndex)
        assertEquals(expectedMondayMillis(0), b.startMs)
        assertEquals(expectedMondayMillis(-1), b.endMs)
    }

    @Test
    fun `weekIndex 1 is the prior ISO week`() {
        val b = WeekBucket.forIndex(weekIndex = 1, now = NOW)
        assertEquals(1, b.weekIndex)
        assertEquals(expectedMondayMillis(1), b.startMs)
        assertEquals(expectedMondayMillis(0), b.endMs)
    }

    @Test
    fun `weekIndex N spans weeksBack=N+1 to weeksBack=N`() {
        for (i in 0..52) {
            val b = WeekBucket.forIndex(weekIndex = i, now = NOW)
            assertEquals(expectedMondayMillis(i.toLong()), b.startMs)
            assertEquals(expectedMondayMillis(i.toLong() - 1L), b.endMs)
        }
    }

    @Test
    fun `successive weeks have no overlap and no gap`() {
        val a = WeekBucket.forIndex(weekIndex = 3, now = NOW)
        val b = WeekBucket.forIndex(weekIndex = 4, now = NOW)
        // Adjacent: a.startMs == b.endMs (half-open ranges).
        assertEquals(a.startMs, b.endMs)
    }

    @Test
    fun `negative weekIndex throws`() {
        try {
            WeekBucket.forIndex(weekIndex = -1, now = NOW)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun `headerLabel maps to expected string resource ids`() {
        assertEquals(R.string.me_week_this, WeekBucket.headerLabel(0))
        assertEquals(R.string.me_week_last, WeekBucket.headerLabel(1))
        assertEquals(R.string.me_week_n_ago, WeekBucket.headerLabel(2))
        assertEquals(R.string.me_week_n_ago, WeekBucket.headerLabel(7))
        assertEquals(R.string.me_week_n_ago, WeekBucket.headerLabel(51))
    }

    @Test
    fun `WeekContent isEmpty when both lists empty`() {
        val empty = WeekContent(0, 0L, 1L, emptyList(), emptyList())
        assertTrue(empty.isEmpty)
    }

    @Test
    fun `WeekContent not empty when only shorts present`() {
        val onlyShorts = WeekContent(
            0, 0L, 1L,
            shorts = listOf(MeFeedVideo("v", "c", "n", "t", null, null, null, 1L, true)),
            videos = emptyList(),
        )
        assertFalse(onlyShorts.isEmpty)
    }

    @Test
    fun `WeekContent not empty when only videos present`() {
        val onlyVideos = WeekContent(
            0, 0L, 1L,
            shorts = emptyList(),
            videos = listOf(MeFeedVideo("v", "c", "n", "t", null, null, null, 1L, false)),
        )
        assertFalse(onlyVideos.isEmpty)
    }
}
