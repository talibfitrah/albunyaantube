package com.albunyaan.tube.data.extractor

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.albunyaan.tube.data.me.MeRefreshTelemetry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.wheneverBlocking
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Tests for [RateLimitedDownloader] (spec §4.4).
 *
 * Covers the gating behaviour:
 *  - Player priority bypasses both rate-limit and cooldown gates (spec D1).
 *  - Non-player priorities check the cooldown first, then acquire a token.
 *  - HTTP 429 trips the cooldown.
 *  - ReCaptchaException trips the cooldown (and rethrows the original).
 *  - Rate-limiter timeout surfaces as IOException with no delegate call.
 *
 * Approach:
 *  - The delegate is a hand-rolled fake [Downloader] subclass — production
 *    binding is [OkHttpDownloader] (final class), but the wrapper only needs
 *    the abstract base type for the [Downloader.execute] contract. The
 *    wrapper's constructor is typed as `Downloader` for testability.
 *  - The rate limiter is a Mockito mock so we can stub `acquire(...)` without
 *    waiting on virtual time. Mockito 5's inline mock maker handles final
 *    Kotlin classes by default since 5.0.
 *  - The cooldown is a real [CooldownState] backed by a JVM-friendly
 *    DataStore (Robolectric provides the [android.content.Context]-free
 *    DataStore environment). We assert against persisted state directly
 *    rather than mocking — Mockito cannot mock a class with a non-null
 *    `() -> Long` field cleanly because the `$default` synthetic for
 *    `isTrippedSync` dereferences `this.now`, which mocks zero out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class RateLimitedDownloaderTest {

    private class FakeDownloader : Downloader() {
        var callCount: Int = 0
        var nextResponse: Response? = null
        var nextThrowable: Throwable? = null

        override fun execute(request: Request): Response {
            callCount++
            nextThrowable?.let {
                when (it) {
                    is IOException -> throw it
                    is ReCaptchaException -> throw it
                    is RuntimeException -> throw it
                    else -> throw RuntimeException("FakeDownloader unexpected", it)
                }
            }
            return nextResponse ?: throw IllegalStateException("FakeDownloader: no response set")
        }
    }

    @get:Rule val tmp = TemporaryFolder().also { it.create() }

    private lateinit var delegate: FakeDownloader
    private lateinit var limiter: GlobalNewPipeRateLimiter
    private lateinit var cooldown: CooldownState
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var clock: AtomicLong

    @Before
    fun setUp() {
        clock = AtomicLong(1_000_000_000L)
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(tmp.root, "rld.preferences_pb") }
        )
        cooldown = CooldownState(dataStore, { clock.get() }, MeRefreshTelemetry())
        delegate = FakeDownloader()
        limiter = mock()
        wheneverBlocking { limiter.acquire(any(), any()) }.doReturn(true)
    }

    @After
    fun tearDown() {
        // DataStore closes on GC; tmp folder cleans up.
    }

    private fun newRequest(): Request =
        Request.newBuilder()
            .httpMethod("GET")
            .url("https://example.com/api")
            .build()

    private fun ok(): Response =
        Response(200, "OK", emptyMap(), "", "https://example.com/api")

    @Test
    fun catches_ReCaptchaException_and_trips_cooldown() = runTest {
        delegate.nextThrowable = ReCaptchaException("captcha", "https://example.com/api")
        val sut = RateLimitedDownloader(delegate, limiter, cooldown)

        NewPipePriorityContext.with(Priority.USER_FOREGROUND) {
            assertThrows(ReCaptchaException::class.java) {
                sut.execute(newRequest())
            }
        }

        // Cooldown was tripped — untilMs is now set to clock + 1h (first trip).
        val until = runBlocking { cooldown.untilMs() }
        assertNotNull(until)
        assertEquals(clock.get() + 60L * 60_000L, until)
    }

    @Test
    fun observes_HTTP_429_and_trips_cooldown() = runTest {
        delegate.nextResponse = Response(429, "Too Many Requests", emptyMap(), "", "https://example.com/api")
        val sut = RateLimitedDownloader(delegate, limiter, cooldown)

        NewPipePriorityContext.with(Priority.USER_FOREGROUND) {
            assertThrows(IOException::class.java) {
                sut.execute(newRequest())
            }
        }

        val until = runBlocking { cooldown.untilMs() }
        assertNotNull(until)
        assertEquals(clock.get() + 60L * 60_000L, until)
    }

    @Test
    fun player_priority_skips_rate_limiter_and_cooldown() = runTest {
        // Pre-trip the cooldown so a non-player call would be blocked.
        cooldown.trip(IOException("preexisting"))
        delegate.nextResponse = ok()
        val sut = RateLimitedDownloader(delegate, limiter, cooldown)

        NewPipePriorityContext.with(Priority.PLAYER) {
            val resp = sut.execute(newRequest())
            assertEquals(200, resp.responseCode())
        }

        verifyBlocking(limiter, { never() }) { acquire(any(), any()) }
        // Delegate was reached.
        assertEquals(1, delegate.callCount)
    }

    @Test
    fun cooldown_active_blocks_non_player_calls() = runTest {
        cooldown.trip(IOException("preexisting"))
        val sut = RateLimitedDownloader(delegate, limiter, cooldown)

        NewPipePriorityContext.with(Priority.USER_FOREGROUND) {
            assertThrows(IOException::class.java) {
                sut.execute(newRequest())
            }
        }

        // Delegate must not be reached when cooldown is tripped.
        assertEquals(0, delegate.callCount)
        // Rate limiter must not be consulted when cooldown is tripped.
        verifyBlocking(limiter, { never() }) { acquire(any(), any()) }
    }

    @Test
    fun visible_interactive_priority_uses_rate_limiter_and_reaches_delegate() = runTest {
        delegate.nextResponse = ok()
        val sut = RateLimitedDownloader(delegate, limiter, cooldown)

        NewPipePriorityContext.with(Priority.VISIBLE_INTERACTIVE) {
            val resp = sut.execute(newRequest())
            assertEquals(200, resp.responseCode())
        }

        verifyBlocking(limiter) {
            acquire(
                Priority.VISIBLE_INTERACTIVE,
                GlobalNewPipeRateLimiter.DEFAULT_ACQUIRE_TIMEOUT_MS,
            )
        }
        assertEquals(1, delegate.callCount)
    }

    @Test
    fun player_priority_does_NOT_trip_cooldown_on_429() = runTest {
        // Spec D1 invariant: Player NEVER trips cooldown, even on 429.
        // A regression that drops the `priority != Priority.PLAYER` guard in
        // RateLimitedDownloader.execute would make this test fail.
        delegate.nextResponse = Response(429, "Too Many Requests", emptyMap(), "", "https://example.com/api")
        val sut = RateLimitedDownloader(delegate, limiter, cooldown)

        NewPipePriorityContext.with(Priority.PLAYER) {
            // Player gets the raw response (including 429) — no IOException, no cooldown trip.
            val resp = sut.execute(newRequest())
            assertEquals(429, resp.responseCode())
        }

        // Spec D1: cooldown must NOT be tripped by a player-priority 429.
        assertNull(runBlocking { cooldown.untilMs() })
        // And the rate limiter was bypassed for the player call.
        verifyBlocking(limiter, { never() }) { acquire(any(), any()) }
    }

    @Test
    fun player_priority_does_NOT_trip_cooldown_on_ReCaptcha() = runTest {
        // Spec D1 invariant: Player NEVER trips cooldown, even on ReCaptchaException.
        // A regression that drops the `priority != Priority.PLAYER` guard in
        // RateLimitedDownloader.execute around the catch-block would make this test fail.
        delegate.nextThrowable = ReCaptchaException("captcha", "https://example.com/api")
        val sut = RateLimitedDownloader(delegate, limiter, cooldown)

        NewPipePriorityContext.with(Priority.PLAYER) {
            // The exception still propagates — player isn't shielded from the error,
            // only from the cooldown side-effect.
            assertThrows(ReCaptchaException::class.java) {
                sut.execute(newRequest())
            }
        }

        // Spec D1: cooldown must NOT be tripped by a player-priority captcha.
        assertNull(runBlocking { cooldown.untilMs() })
        // And the rate limiter was bypassed for the player call.
        verifyBlocking(limiter, { never() }) { acquire(any(), any()) }
    }

    @Test
    fun rate_limiter_timeout_throws_IOException() = runTest {
        // Override the default stub so acquire returns false (timeout).
        wheneverBlocking { limiter.acquire(any(), any()) }.doReturn(false)
        val sut = RateLimitedDownloader(delegate, limiter, cooldown)

        NewPipePriorityContext.with(Priority.USER_FOREGROUND) {
            assertThrows(IOException::class.java) {
                sut.execute(newRequest())
            }
        }

        // Delegate must not be reached when the rate limiter times out.
        assertEquals(0, delegate.callCount)
        // No cooldown trip recorded (rate-limit timeout is not a 429 / captcha).
        assertNull(runBlocking { cooldown.untilMs() })
    }

    /**
     * ANDROID-PERSONAL-02 [Bug 2]: TOCTOU race between the initial cooldown
     * check and the rate-limiter acquire return.
     *
     * Sequence under test:
     *  1. Caller sees `isTrippedSync()=false` at the top of `execute()`.
     *  2. Caller suspends in `rateLimiter.acquire(priority)`.
     *  3. WHILE suspended, another path calls `cooldownState.trip(...)`
     *     (here simulated by tripping the same `cooldown` instance from
     *     inside the mock's `acquire` answer).
     *  4. `acquire` returns `true`.
     *  5. *Without* the fix, `delegate.execute(...)` would now be called
     *     and a request would hit YouTube during an active cooldown.
     *  6. *With* the fix, the second `isTrippedSync()` check after acquire
     *     observes the trip and throws an [IOException] — no delegate call.
     *
     * Implementation note: we stub `limiter.acquire(...)` with a side-effect
     * that trips the cooldown before returning `true`. This deterministically
     * places the trip in the window between the two checks without needing
     * a real second thread.
     */
    @Test
    fun cooldown_tripped_during_rate_limiter_acquire_short_circuits_delegate() = runTest {
        // The mock's acquire trips the cooldown then returns true,
        // simulating a concurrent caller that observed a 429 during the
        // first caller's acquire wait.
        wheneverBlocking { limiter.acquire(any(), any()) }.thenAnswer {
            runBlocking { cooldown.trip(IOException("concurrent 429")) }
            true
        }
        delegate.nextResponse = ok()
        val sut = RateLimitedDownloader(delegate, limiter, cooldown)

        // The execute call must throw because the cooldown was tripped while
        // we were suspended in acquire.
        val thrown = NewPipePriorityContext.with(Priority.USER_FOREGROUND) {
            assertThrows(IOException::class.java) {
                sut.execute(newRequest())
            }
        }
        // The error message must clearly indicate the TOCTOU branch fired,
        // not the initial-check branch — so a regression that drops the
        // second isTrippedSync check would surface a different message.
        assertTrue(
            "expected post-acquire cooldown error, got: ${thrown.message}",
            thrown.message?.contains("while awaiting rate limiter token") == true,
        )
        // Critical: the delegate must NOT have been called even though
        // acquire returned true — the second isTrippedSync gate stopped us.
        assertEquals(
            "delegate must not be reached when cooldown trips during acquire",
            0,
            delegate.callCount,
        )
        // The cooldown is recorded (the simulated concurrent 429 took effect).
        assertNotNull(runBlocking { cooldown.untilMs() })
    }
}
