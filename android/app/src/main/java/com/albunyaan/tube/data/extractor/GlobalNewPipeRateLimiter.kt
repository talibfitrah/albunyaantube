package com.albunyaan.tube.data.extractor

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Global token-bucket rate limiter for NewPipe HTTP paths (spec §4.5,
 * post-beta.5 fix).
 *
 * Scope: the bucket throttles **autonomous/background traffic only**
 * ([Priority.BACKGROUND_REFRESH]). User-initiated paths — [Priority.PLAYER],
 * [Priority.VISIBLE_INTERACTIVE], [Priority.USER_FOREGROUND] — bypass the
 * bucket entirely. User gestures are self-rate-limited by human tap cadence,
 * and real abuse signals from YouTube (HTTP 429 / ReCaptcha) still **trip**
 * the cooldown via [RateLimitedDownloader] — but the resulting cooldown
 * read is also scoped to BACKGROUND_REFRESH, so a stale persisted trip
 * cannot lock the user out of channel/detail taps on subsequent app starts
 * (the beta.4/beta.5 channel-detail regression). A static token clock is
 * the wrong tool for user-facing flows: a 20-token / 30 s-refill bucket
 * gives ~2 tokens/min steady state, which silently blocks a user who
 * casually browses several channels in a row.
 *
 * Bucket parameters (production, BACKGROUND_REFRESH only):
 * - Capacity: 20 tokens
 * - Refill: 1 token / 30 s
 * - Foreground reserve (5 tokens) protects future user-foreground callers
 *   if any are ever migrated back into the bucket
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
        // Only BACKGROUND_REFRESH consumes from the bucket. Every other
        // priority is user-initiated (or the player) and must not be gated by
        // a static token clock — see class-level KDoc for the rationale.
        if (priority != Priority.BACKGROUND_REFRESH) return true

        val effectiveTimeoutMs = if (timeoutMs == DEFAULT_ACQUIRE_TIMEOUT_MS) {
            DEFAULT_BACKGROUND_ACQUIRE_TIMEOUT_MS
        } else {
            timeoutMs
        }
        val deadline = now() + effectiveTimeoutMs
        while (true) {
            mutex.withLock {
                refillLocked()
                val reserve = if (priority == Priority.BACKGROUND_REFRESH) {
                    BACKGROUND_FOREGROUND_RESERVE_TOKENS
                } else {
                    0
                }
                if (tokens > reserve) {
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
        const val DEFAULT_ACQUIRE_TIMEOUT_MS: Long = 30_000L
        private const val DEFAULT_BACKGROUND_ACQUIRE_TIMEOUT_MS: Long = 0L
        private const val BACKGROUND_FOREGROUND_RESERVE_TOKENS: Int = 5

        /**
         * Floor for the per-iteration wait so a tight loop with tiny remaining
         * budgets cannot busy-spin. Small enough not to materially alter the
         * wait behaviour — the deadline check on the next iteration still
         * honours [acquire]'s contract.
         */
        private const val MIN_WAIT_MS: Long = 50L
    }
}
