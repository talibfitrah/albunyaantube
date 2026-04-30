package com.albunyaan.tube.data.me

import com.albunyaan.tube.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ANDROID-PERSONAL-03 / T3: rolling 7-day bucket math.
 *
 * Anchored at a fixed `now` so behaviour is deterministic across runs and
 * across "today is Sunday" edge cases (rolling buckets ignore the day of
 * the week — only the timestamp matters).
 */
class WeekBucketTest {

    /** 2026-04-27 12:00:00 UTC (a Monday — picked to make the math obvious). */
    private val NOW = 1761566400000L
    private val WEEK = WeekBucket.WEEK_MS

    @Test
    fun `weekIndex 0 covers the most recent 7 days ending at now`() {
        val b = WeekBucket.forIndex(weekIndex = 0, now = NOW)
        assertEquals(0, b.weekIndex)
        assertEquals(NOW - WEEK, b.startMs)
        assertEquals(NOW, b.endMs)
    }

    @Test
    fun `weekIndex 1 is the prior week`() {
        val b = WeekBucket.forIndex(weekIndex = 1, now = NOW)
        assertEquals(1, b.weekIndex)
        assertEquals(NOW - 2L * WEEK, b.startMs)
        assertEquals(NOW - WEEK, b.endMs)
    }

    @Test
    fun `weekIndex N covers now-(N+1)w to now-Nw`() {
        for (i in 0..52) {
            val b = WeekBucket.forIndex(weekIndex = i, now = NOW)
            assertEquals(NOW - (i + 1L) * WEEK, b.startMs)
            assertEquals(NOW - i.toLong() * WEEK, b.endMs)
            // Always exactly one week wide.
            assertEquals(WEEK, b.endMs - b.startMs)
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
