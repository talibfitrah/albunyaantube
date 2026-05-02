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
 * ## Gating scope (post-beta.5 fix)
 *
 * Both gates apply **only** to [Priority.BACKGROUND_REFRESH]. User-initiated
 * paths — [Priority.PLAYER], [Priority.VISIBLE_INTERACTIVE],
 * [Priority.USER_FOREGROUND] — bypass the bucket and the cooldown read. A
 * static token clock + persisted cooldown is the wrong tool for user-facing
 * flows: in beta.4/beta.5 a single 429 from any path could persist a 1–24 h
 * cooldown that then locked the user out of every channel/detail tap on
 * subsequent app starts, even though human tap cadence is self-rate-limiting
 * and YouTube's transient 429 is usually long gone by the time the user
 * comes back. Symmetric to the bucket bypass in [GlobalNewPipeRateLimiter] —
 * see that class's KDoc for the full rationale.
 *
 * ### Trip side effect (still applies to user paths)
 *
 * A 429 / [ReCaptchaException] from a non-Player path **still trips** the
 * cooldown via [CooldownState.trip]. The trip itself is a one-way signal:
 * it gates *future* [Priority.BACKGROUND_REFRESH] (which respects the
 * cooldown read) but does not retro-block the user gesture that observed
 * the 429. This preserves the original spec §4.6 protection for autonomous
 * traffic while letting user gestures fail-naturally on real abuse signals
 * instead of preemptively locking out on yesterday's persisted trip.
 *
 * ### Player exception (spec D1)
 *
 * Player still NEVER trips cooldown — the player path must survive a
 * concurrent trip so a previously cached / mid-stream playback can recover,
 * and a player 429 typically means the stream URL aged out, not that the
 * service is being abused.
 *
 * ## Behaviour by [Priority]
 *
 *  - [Priority.PLAYER] — bypasses both gates and never trips cooldown
 *    (spec D1).
 *  - [Priority.VISIBLE_INTERACTIVE], [Priority.USER_FOREGROUND] — bypass
 *    both gates. On 429 / [ReCaptchaException] from the delegate, *trip*
 *    cooldown (so future [Priority.BACKGROUND_REFRESH] gets gated) and
 *    surface the error to the caller.
 *  - [Priority.BACKGROUND_REFRESH] — full gate: check
 *    [CooldownState.isTrippedSync] first; if tripped, throw [IOException]
 *    without consulting the rate limiter or delegate. Otherwise acquire
 *    from the bucket via [GlobalNewPipeRateLimiter.acquire]. After the
 *    acquire returns we **re-check** the cooldown state — between the
 *    initial check and the acquire, another caller may have observed a
 *    429 and tripped the cooldown; without this second read we would race
 *    past a fresh trip and hit YouTube during an active cooldown
 *    (ANDROID-PERSONAL-02 [Bug 2]).
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
 *
 * ## Residual TOCTOU (ANDROID-PERSONAL-02 round 2 [Bug C])
 *
 * A tiny window remains between the post-acquire cooldown re-check and
 * [delegate.execute] inside the BACKGROUND_REFRESH branch. If a concurrent
 * caller observes a 429 and trips the cooldown in this window, this
 * caller still issues exactly one request that races past the trip and
 * hits YouTube while the cooldown is technically active. We accept this
 * because closing the window fully would require holding the cooldown's
 * mutex across the entire HTTP call, which would serialize all NewPipe
 * traffic and defeat the rate-limiter's parallelism. Practical impact:
 * at most one request per cooldown trip slips through the moment cooldown
 * trips — bounded behaviour, well below the failure threshold the
 * cooldown is designed to dampen.
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

        // Gate scope: only BACKGROUND_REFRESH respects the cooldown / bucket
        // gates. User-initiated and Player paths bypass both — see class
        // KDoc for the rationale (beta.5 channel-detail regression: a stale
        // persisted cooldown locked the user out of every channel tap on
        // subsequent app starts).
        if (priority == Priority.BACKGROUND_REFRESH) {
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
            // Trip-on-429 still applies to every non-Player priority. The
            // trip is a one-way signal: it gates *future* BACKGROUND_REFRESH
            // (which reads the cooldown above) but does not retro-block the
            // user gesture that observed this 429. The user's own request
            // surfaces the error normally so they can retry; only autonomous
            // traffic is throttled by the resulting cooldown.
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
