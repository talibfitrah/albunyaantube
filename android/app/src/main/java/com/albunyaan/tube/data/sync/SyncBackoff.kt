package com.albunyaan.tube.data.sync

import kotlin.random.Random

/**
 * Plan D — per-row exponential backoff for sync push retries.
 * Schedule: 1s → 2s → 4s → 8s → 16s → 32s → 60s (capped).
 * Single-threaded — caller owns the instance per row, no concurrency.
 *
 * Cubic R7 P2 — added equal-jitter on the returned wait. Pre-fix the
 * schedule was deterministic, so a fleet-wide outage produced
 * synchronized reconnections — every client whose push failed at the
 * same wall-clock waited the same 1s/2s/4s and re-hit the backend
 * concurrently, amplifying the original outage into a thundering herd.
 * Equal-jitter randomises in [base/2, base] so clients fan out across
 * the window while preserving the exponential shape.
 */
class SyncBackoff(
    private val initialMs: Long = 1_000L,
    private val capMs:     Long = 60_000L,
    private val random:    Random = Random.Default,
) {
    private var current: Long = 0L

    /** Returns the wait this attempt, then doubles for next attempt (capped). */
    fun next(): Long {
        val base = if (current == 0L) initialMs else (current * 2L).coerceAtMost(capMs)
        current = base
        // Equal-jitter: wait ∈ [base/2, base]. Floor at 1ms to keep nonzero.
        val half = (base / 2L).coerceAtLeast(1L)
        return half + random.nextLong(0, half + 1)
    }

    fun reset() { current = 0L }
}
