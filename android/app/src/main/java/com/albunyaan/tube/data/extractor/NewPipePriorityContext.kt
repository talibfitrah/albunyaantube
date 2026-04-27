package com.albunyaan.tube.data.extractor

/**
 * Priority levels for the global NewPipe rate limiter (spec §4.5).
 *
 * - [PLAYER]: bypasses the bucket entirely — playback must never block
 *   on a refresh-thread bucket because the user is actively watching.
 * - [USER_FOREGROUND]: Home / Search / paged grids the user is looking at.
 *   Acquires from the bucket with a 10 s timeout.
 * - [BACKGROUND_REFRESH]: Reserved for any future background NewPipe path
 *   (the Me tab itself uses ATOM, so it does not consume the bucket).
 *   Same bucket as USER_FOREGROUND but distinct so callers can be tagged
 *   in telemetry.
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
 * the block throws.
 *
 * Usage:
 * ```
 * NewPipePriorityContext.with(Priority.USER_FOREGROUND) {
 *     val info = StreamInfo.getInfo(url) // Downloader reads current.get()
 * }
 * ```
 *
 * Coroutine note: NewPipe extraction is wrapped in `withContext(Dispatchers.IO)`,
 * so the priority must be set inside that block — `ThreadLocal` is per-thread,
 * not per-coroutine. (`asContextElement()` is a future enhancement if call
 * sites grow.)
 */
object NewPipePriorityContext {
    val current: ThreadLocal<Priority?> = ThreadLocal<Priority?>()

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
