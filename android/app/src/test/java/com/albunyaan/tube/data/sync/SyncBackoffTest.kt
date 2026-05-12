package com.albunyaan.tube.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncBackoffTest {

    @Test
    fun firstFailureWaits1Second() {
        val b = SyncBackoff()
        assertEquals(1_000L, b.next())
    }

    @Test
    fun doublesUpToCap() {
        val b = SyncBackoff()
        assertEquals(1_000L,  b.next())
        assertEquals(2_000L,  b.next())
        assertEquals(4_000L,  b.next())
        assertEquals(8_000L,  b.next())
        assertEquals(16_000L, b.next())
        assertEquals(32_000L, b.next())
        assertEquals(60_000L, b.next())   // capped
        assertEquals(60_000L, b.next())   // capped forever
    }

    @Test
    fun resetReturnsToOneSecond() {
        val b = SyncBackoff()
        repeat(5) { b.next() }
        b.reset()
        assertEquals(1_000L, b.next())
    }
}
