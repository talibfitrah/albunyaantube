package com.albunyaan.tube.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.Call

/**
 * Registers a cancellation hook on the current coroutine's Job that calls
 * [Call.cancel] when the coroutine is cancelled. Returns the same [Call] so
 * the caller can chain `.execute().use { ... }` immediately.
 *
 * Without this hook, `withTimeoutOrNull` in [UpdatePromptFlow.checkForUpdate]
 * times the splash gate out but leaves the underlying OkHttp socket blocked
 * until OkHttp's much-longer (default 10 s connect, 10 s read) timeout fires —
 * holding an IO dispatcher thread for tens of seconds per stalled cold start.
 */
internal suspend fun Call.cancelWhenCoroutineCancels(): Call = this.also { call ->
    // NOTE: fires on job COMPLETION, so it frees the socket once a call returns or
    // throws — it does NOT bound a call that is already blocked. Coroutine
    // cancellation cannot unwind a thread parked in Call.execute(), and the
    // onCancelling variant of invokeOnCompletion is internal kotlinx API. So a
    // withTimeoutOrNull wrapped around one of these calls is best-effort: it returns
    // promptly only once the underlying call unblocks. Bounding a stalled socket
    // needs an OkHttp callTimeout on the client, which the shared NetworkModule
    // client does not set (connect 15 s / read 20 s, callTimeout disabled).
    currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
        if (cause != null) call.cancel()
    }
}

/**
 * Like [runCatching], but re-throws [CancellationException] so coroutine
 * cancellation propagates correctly instead of being captured as
 * `Result.failure(CancellationException)` — a long-standing Kotlin pitfall
 * (cubic R5 P2). Use this everywhere we runCatching inside a coroutine that
 * may be cancelled (splash-gate timeout, lifecycle scope tear-down).
 *
 * The block is `suspend` so callers can chain real suspending APIs
 * (`withContext`, `delay`, `await`) inside without silently losing
 * cancellation semantics through a non-suspend lambda boundary (final
 * bloat-audit H1).
 */
internal suspend inline fun <R> runCatchingCoroutine(block: suspend () -> R): Result<R> = try {
    Result.success(block())
} catch (ce: CancellationException) {
    throw ce
} catch (t: Throwable) {
    Result.failure(t)
}
