package com.albunyaan.tube.data.extractor

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException

/**
 * NewPipe [Downloader] wrapper that enforces the global rate limit
 * ([GlobalNewPipeRateLimiter]) and the cooldown gate ([CooldownState]) before
 * delegating to [OkHttpDownloader] (spec §4.4).
 *
 * Behaviour by [Priority]:
 *  - [Priority.PLAYER]  — bypasses both gates entirely. Playback must never
 *    block on a refresh-thread bucket because the user is actively watching;
 *    the player path is also the only path that *must* survive a tripped
 *    cooldown so a previously cached / mid-stream playback can recover. See
 *    spec D1.
 *  - All other priorities — check [CooldownState.isTrippedSync] (synchronous
 *    bridge with internal [runBlocking]); if tripped, throw [IOException]
 *    without consulting the rate limiter or delegate. Otherwise acquire from
 *    the bucket via [GlobalNewPipeRateLimiter.acquire] and only delegate to
 *    [OkHttpDownloader] on success. After the acquire returns we **re-check**
 *    the cooldown state — between the initial check and the acquire, another
 *    caller may have observed a 429 and tripped the cooldown; without this
 *    second read we would race past a fresh trip and hit YouTube during an
 *    active cooldown (ANDROID-PERSONAL-02 [Bug 2]).
 *
 * Trip triggers (non-Player only):
 *  - [ReCaptchaException] from the delegate → [CooldownState.trip] then
 *    rethrow so NewPipe still sees the original exception.
 *  - HTTP 429 from the delegate → [CooldownState.trip] then convert to
 *    [IOException] so callers fall through to retry / backoff.
 *
 * The priority is read from [NewPipePriorityContext.currentOrDefault] —
 * callers wrap each NewPipe entry point in
 * `NewPipePriorityContext.with(priority) { ... }` (see callers in `player`,
 * `data/channel`, `data/playlist`, `data/me`).
 *
 * Thread model: NewPipe's [Downloader.execute] is synchronous. The class uses
 * `runBlocking` to bridge into the suspending [GlobalNewPipeRateLimiter] and
 * [CooldownState.trip] APIs — this is acceptable because the caller is
 * already on `Dispatchers.IO` (NewPipe extraction is wrapped in
 * `withContext(Dispatchers.IO) { ... }` at every call site).
 */
@Singleton
class RateLimitedDownloader @Inject constructor(
    // Typed as the abstract [Downloader] base so unit tests can substitute a
    // fake. Hilt is wired to provide the production [OkHttpDownloader]
    // instance via [com.albunyaan.tube.di.DataModule.provideRateLimitedDownloader].
    private val delegate: Downloader,
    private val rateLimiter: GlobalNewPipeRateLimiter,
    private val cooldownState: CooldownState,
) : Downloader() {

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val priority = NewPipePriorityContext.currentOrDefault()

        if (priority != Priority.PLAYER) {
            if (cooldownState.isTrippedSync()) {
                throw IOException(
                    "NewPipe cooldown active until ${runBlocking { cooldownState.untilMs() }}"
                )
            }
            val acquired = runBlocking { rateLimiter.acquire(priority) }
            if (!acquired) {
                throw IOException("NewPipe rate limiter timeout")
            }
            // ANDROID-PERSONAL-02 [Bug 2]: TOCTOU re-check after acquiring
            // a token. Caller A may have read isTrippedSync()=false at the
            // top, then suspended in `rateLimiter.acquire(priority)`. While
            // suspended, Caller B observed an HTTP 429 and called
            // `cooldownState.trip(...)`. When Caller A's acquire returns,
            // its request would otherwise hit YouTube during an active
            // cooldown — exactly what spec D1 / spec §4.6 forbid.
            //
            // The same `cooldownState` instance is consulted, so this
            // re-check sees Caller B's trip immediately. We use
            // `isTrippedSync()` (not the suspending variant) to keep the
            // synchronous Downloader.execute contract — runBlocking is
            // already in use throughout this class for the same reason.
            if (cooldownState.isTrippedSync()) {
                throw IOException(
                    "NewPipe cooldown tripped while awaiting rate limiter token; " +
                        "active until ${runBlocking { cooldownState.untilMs() }}"
                )
            }
        }

        try {
            val response = delegate.execute(request)
            if (priority != Priority.PLAYER && response.responseCode() == 429) {
                runBlocking { cooldownState.trip(IOException("HTTP 429")) }
                throw IOException("HTTP 429 — cooldown tripped")
            }
            return response
        } catch (e: ReCaptchaException) {
            if (priority != Priority.PLAYER) {
                runBlocking { cooldownState.trip(e) }
            }
            throw e
        }
    }
}
