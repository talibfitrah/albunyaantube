package com.albunyaan.tube.data.extractor

/**
 * Priority levels for the global NewPipe rate limiter (spec §4.5).
 *
 * - [PLAYER]: bypasses the bucket entirely — playback must never block
 *   on a refresh-thread bucket because the user is actively watching.
 * - [USER_FOREGROUND]: Home / Search / paged grids the user is looking at.
 *   Acquires from the bucket with a foreground timeout.
 * - [BACKGROUND_REFRESH]: Reserved for any future background NewPipe path
 *   (the Me tab itself uses ATOM, so it does not consume the bucket).
 *   Uses the same bucket as USER_FOREGROUND, but only opportunistically so it
 *   cannot drain the foreground reserve.
 *
 * Declaration order is not significant — no compareTo/ordinal usage.
 */
enum class Priority {
    PLAYER,
    USER_FOREGROUND,
    BACKGROUND_REFRESH,
}

/**
 * Thread-local priority hint used by `RateLimitedDownloader` (T7) to decide
 * which bucket lane an outbound NewPipe HTTP call belongs to.
 *
 * NewPipe's `Downloader` API is synchronous and does not give us an obvious
 * place to thread call-site context, so we stash the priority in a
 * `ThreadLocal` set by the caller via [with]. The block-scoped helper
 * guarantees the prior value is restored on the calling thread, even when
 * the block throws. LIFO-stack semantics: nested [with] calls are
 * well-defined.
 *
 * Usage:
 * ```
 * NewPipePriorityContext.with(Priority.USER_FOREGROUND) {
 *     val info = StreamInfo.getInfo(url) // Downloader reads currentOrDefault()
 * }
 * ```
 *
 * Coroutine note: NewPipe extraction is wrapped in `withContext(Dispatchers.IO)`,
 * so the priority must be set inside that block — `ThreadLocal` is per-thread,
 * not per-coroutine. A future migration to
 * [kotlinx.coroutines.ThreadContextElement] / `ThreadLocal.asContextElement()`
 * would let the priority ride with the coroutine; deferred to whenever T7
 * adds the [RateLimitedDownloader] consumer.
 */
object NewPipePriorityContext {
    /**
     * Underlying thread-local holding the current priority. Internal +
     * [PublishedApi] so the inline [with] helper can reference it from any
     * call site, while preventing arbitrary `current.set(...)` from outside
     * (which would defeat the LIFO-stack invariant [with] enforces).
     *
     * Read via [currentOrDefault] from consumers.
     */
    @PublishedApi
    internal val current: ThreadLocal<Priority?> = ThreadLocal<Priority?>()

    /**
     * Returns the current priority on this thread, or [Priority.USER_FOREGROUND]
     * if no priority is set. The fallback is deliberately the *non-bypassing*
     * choice: an unset priority must NEVER silently skip the rate-limit /
     * cooldown gates.
     */
    fun currentOrDefault(): Priority = current.get() ?: Priority.USER_FOREGROUND

    /**
     * Run [block] with [priority] as the current thread's priority, restoring
     * the prior value on exit (even if [block] throws). LIFO-stack semantics:
     * nested [with] calls are well-defined.
     */
    inline fun <T> with(priority: Priority, block: () -> T): T {
        val prior = current.get()
        current.set(priority)
        try {
            return block()
        } finally {
            current.set(prior)
        }
    }
}
