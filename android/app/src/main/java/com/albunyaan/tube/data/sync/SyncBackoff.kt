package com.albunyaan.tube.data.sync

/**
 * Plan D — per-row exponential backoff for sync push retries.
 * Schedule: 1s → 2s → 4s → 8s → 16s → 32s → 60s (capped).
 * Single-threaded — caller owns the instance per row, no concurrency.
 */
class SyncBackoff(
    private val initialMs: Long = 1_000L,
    private val capMs:     Long = 60_000L,
) {
    private var current: Long = 0L

    /** Returns the wait this attempt, then doubles for next attempt (capped). */
    fun next(): Long {
        val wait = if (current == 0L) initialMs else (current * 2L).coerceAtMost(capMs)
        current = wait
        return wait
    }

    fun reset() { current = 0L }
}
