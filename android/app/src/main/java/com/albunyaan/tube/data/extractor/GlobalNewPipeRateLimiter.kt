package com.albunyaan.tube.data.extractor

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Global token-bucket rate limiter for NewPipe HTTP paths (spec §4.5).
 *
 * Bucket parameters (production):
 * - Capacity: 20 tokens
 * - Refill: 1 token / 30 s
 * - Player priority: bypasses bucket entirely (playback must never block)
 * - Acquire timeout: 10 s for Home / Search / paged grids
 *
 * Smaller bucket than v1 because the Me-tab refresh no longer consumes from
 * it (T2 swapped Me-feed away from NewPipe to ATOM). The bucket now exists
 * primarily to throttle Home / Search burst load.
 *
 * Thread safety: a single suspend [Mutex] serialises refill + decrement,
 * so concurrent callers see consistent token counts. The mutex is only
 * held for the bucket arithmetic, not across [delay], so blocked callers
 * do not stall others.
 *
 * Test seam: the [VisibleForTesting] primary constructor lets unit tests
 * inject a virtual-time `now: () -> Long` lambda (e.g. `{ currentTime }`
 * inside `runTest`). The lambda is invoked on every refill / deadline
 * check — never cached at construction — so virtual-time advances are
 * observed.
 */
@Singleton
class GlobalNewPipeRateLimiter @VisibleForTesting internal constructor(
    initialTokens: Int,
    private val capacity: Int,
    private val refillPeriodMs: Long,
    private val now: () -> Long,
) {

    @Inject
    constructor() : this(
        initialTokens = DEFAULT_TOKENS,
        capacity = DEFAULT_TOKENS,
        refillPeriodMs = DEFAULT_REFILL_MS,
        now = { System.currentTimeMillis() },
    )

    private val mutex = Mutex()
    private var tokens: Int = initialTokens.coerceAtMost(capacity)
    private var lastRefillAt: Long = now()

    /**
     * Try to acquire a token for the given [priority], waiting up to
     * [timeoutMs] (using suspendable [delay] — virtual-time friendly).
     *
     * Returns `true` if a token was consumed (or `priority == PLAYER`,
     * which always bypasses the bucket), `false` if the deadline elapsed
     * before refill made tokens available.
     *
     * A [timeoutMs] of `0L` is "non-blocking" — return immediately based
     * on whether a token is available right now.
     *
     * Wait granularity: the per-iteration delay is floored at [MIN_WAIT_MS]
     * (50 ms) to avoid sub-tick busy-spinning. This means a tight [timeoutMs]
     * under 50 ms may take up to ~50 ms to return `false`; tight deadlines
     * are best-effort, not exact.
     */
    suspend fun acquire(
        priority: Priority,
        timeoutMs: Long = DEFAULT_ACQUIRE_TIMEOUT_MS,
    ): Boolean {
        if (priority == Priority.PLAYER) return true

        val deadline = now() + timeoutMs
        while (true) {
            mutex.withLock {
                refillLocked()
                if (tokens > 0) {
                    tokens--
                    return true
                }
            }
            val nowMs = now()
            if (nowMs >= deadline) return false
            // Wait for next refill or until the deadline, whichever comes first.
            val timeUntilNextRefill = refillPeriodMs - (nowMs - lastRefillAt)
            val timeUntilDeadline = deadline - nowMs
            val waitMs = minOf(timeUntilNextRefill, timeUntilDeadline).coerceAtLeast(MIN_WAIT_MS)
            delay(waitMs)
        }
    }

    /**
     * Refill the bucket if at least one [refillPeriodMs] has elapsed since
     * the last refill marker. Bounded by [capacity] so long idle periods
     * cannot stockpile tokens beyond bucket size.
     *
     * Caller must hold [mutex].
     */
    private fun refillLocked() {
        val nowMs = now()
        val elapsed = nowMs - lastRefillAt
        if (elapsed >= refillPeriodMs) {
            val refills = (elapsed / refillPeriodMs).toInt()
            tokens = (tokens + refills).coerceAtMost(capacity)
            lastRefillAt += refills * refillPeriodMs
        }
    }

    companion object {
        const val DEFAULT_TOKENS: Int = 20
        const val DEFAULT_REFILL_MS: Long = 30_000L
        const val DEFAULT_ACQUIRE_TIMEOUT_MS: Long = 10_000L

        /**
         * Floor for the per-iteration wait so a tight loop with tiny remaining
         * budgets cannot busy-spin. Small enough not to materially alter the
         * wait behaviour — the deadline check on the next iteration still
         * honours [acquire]'s contract.
         */
        private const val MIN_WAIT_MS: Long = 50L
    }
}
