package com.albunyaan.tube.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SyncBackoffTest {

    /**
     * Cubic R7 P2 introduced equal-jitter — each `next()` returns a value in
     * `[base/2, base]`. Tests now assert the doubling-shape via the range and
     * use a fixed-seed Random for reproducibility.
     */

    @Test
    fun firstFailureWaitsWithin500to1000Ms() {
        val b = SyncBackoff(random = Random(42))
        val wait = b.next()
        assertTrue("wait=$wait should be in [500,1000]", wait in 500..1_000)
    }

    @Test
    fun doublesUpToCapWithJitter() {
        val b = SyncBackoff(random = Random(42))
        assertTrue(b.next() in 500..1_000)
        assertTrue(b.next() in 1_000..2_000)
        assertTrue(b.next() in 2_000..4_000)
        assertTrue(b.next() in 4_000..8_000)
        assertTrue(b.next() in 8_000..16_000)
        assertTrue(b.next() in 16_000..32_000)
        assertTrue(b.next() in 30_000..60_000)   // capped base=60s, wait ∈ [30s,60s]
        assertTrue(b.next() in 30_000..60_000)   // capped forever
    }

    @Test
    fun resetReturnsToInitialRange() {
        val b = SyncBackoff(random = Random(7))
        repeat(5) { b.next() }
        b.reset()
        val wait = b.next()
        assertTrue("wait=$wait should be in [500,1000] after reset", wait in 500..1_000)
    }

    @Test
    fun zeroJitterRandomYieldsHalfOfBase() {
        // A Random whose nextLong(from, until) always returns from=0 means
        // wait = half + 0 = base/2. Pins the lower bound of equal-jitter.
        val zero = object : Random() {
            override fun nextBits(bitCount: Int): Int = 0
            override fun nextLong(from: Long, until: Long): Long = from
        }
        val b = SyncBackoff(random = zero)
        assertEquals(500L, b.next())
        assertEquals(1_000L, b.next())
    }
}
