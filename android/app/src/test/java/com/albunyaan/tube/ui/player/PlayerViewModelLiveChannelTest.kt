package com.albunyaan.tube.ui.player

import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.R
import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.VideoTrack
import com.albunyaan.tube.data.source.ContentService
import com.albunyaan.tube.data.source.FakeContentService
import com.albunyaan.tube.data.source.api.ChannelLives
import com.albunyaan.tube.data.source.api.LiveItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doAnswer
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import retrofit2.HttpException
import retrofit2.Response
import java.time.Duration

/** Spec §9.1: an end, a not-live resolve or a failure asks the backend; the queue never advances. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PlayerViewModelLiveChannelTest {

    private val dispatcher = StandardTestDispatcher()
    private val channel = "UCROKYPep-UuODNwyipe6JMw"

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    // Two audio languages keep maybeEnumerateDubs (a real MWEB request for an 11-character id) out of a JVM test.
    private fun streams(id: String, isLive: Boolean) = ResolvedStreams(
        streamId = id,
        videoTracks = listOf(VideoTrack("https://v.test/$id", "video/mp4", 640, 360, 1, "360p", 30, isVideoOnly = false)),
        audioTracks = listOf(
            AudioTrack("https://a.test/$id", "audio/mp4", 128_000, "mp4a.40.2"),
            AudioTrack("https://a.test/$id/ar", "audio/mp4", 48_000, "mp4a.40.5", language = "ar"),
        ),
        durationSeconds = 0, hlsUrl = "https://h.test/$id.m3u8", isLive = isLive,
    )

    private fun <C : ContentService> setup(
        resolved: ResolvedStreams,
        content: C,
        prefetched: ResolvedStreams? = null,
        rateLimiter: com.albunyaan.tube.player.ExtractionRateLimiter = com.albunyaan.tube.player.ExtractionRateLimiter(),
    ): Triple<PlayerViewModel, FakePlayerRepository, C> {
        val repository = FakePlayerRepository().apply { resolvedStreams = resolved }
        val vm = playerViewModel(
            repository = repository,
            contentService = content,
            prefetchService = FakePrefetchService(prefetched),
            rateLimiter = rateLimiter,
            ioDispatcher = dispatcher,
        )
        return Triple(vm, repository, content)
    }

    private fun setup(resolved: ResolvedStreams) = setup(resolved, FakeContentService())

    /** The incident's ten minutes run on the monotonic clock: virtual time and Robolectric's clock move together. */
    private fun TestScope.advanceClocks(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(1_000L, left)
            ShadowSystemClock.advanceBy(Duration.ofMillis(step))
            advanceTimeBy(step)
            left -= step
        }
        runCurrent()
    }

    /** A wait loop left behind (a failed assertion included) would retry forever while runTest drains: cancel it. */
    private inline fun PlayerViewModel.cancelAfter(block: () -> Unit) = try { block() } finally { viewModelScope.cancel() }

    /** The backend's answers, one per call (the last repeats). */
    private class FailingLives(private vararg val errors: Exception) : ContentService by FakeContentService() {
        var calls = 0
        override suspend fun fetchChannelLives(channelId: String): ChannelLives = throw errors[minOf(calls++, errors.lastIndex)]
    }

    private fun http(code: Int) = HttpException(Response.error<Any>(code, "".toResponseBody(null)))

    /** A backend answer that arrives only when the test completes it. */
    private fun later(answer: kotlinx.coroutines.CompletableDeferred<ChannelLives>) = object : ContentService by FakeContentService() {
        override suspend fun fetchChannelLives(channelId: String): ChannelLives = answer.await()
    }

    /** A resolve that fails on the network [failures] times, then answers [live]. */
    private class Offline(private val live: ResolvedStreams, private var failures: Int) :
        com.albunyaan.tube.player.PlayerRepository by FakePlayerRepository() {
        var calls = 0
        override suspend fun resolveStreams(
            videoId: String, forceRefresh: Boolean, priority: com.albunyaan.tube.data.extractor.Priority, sourceChannelId: String?,
        ): ResolvedStreams? {
            calls++
            if (failures > 0) { failures--; throw java.io.IOException("offline") }
            return live
        }
    }

    /** Stage 9 r3 (Cubic): a resolve that fails on the network is no answer, not an end: the listed id is re-resolved. */
    @Test fun `a network failure re-resolves the listed id`() = runTest(dispatcher) {
        val repository = Offline(streams("Rs7St51oDDc", isLive = true), failures = 3)
        val answer = kotlinx.coroutines.CompletableDeferred<ChannelLives>()
        val vm = playerViewModel(repository = repository, contentService = later(answer), ioDispatcher = dispatcher)
        vm.cancelAfter {
            vm.loadVideo(videoId = "Rs7St51oDDc", sourceChannelId = channel, liveChannelId = channel)
            advanceTimeBy(4_000); runCurrent()                                       // 3 attempts fail; the backend is asked
            assertEquals(3, repository.calls)

            answer.complete(ChannelLives(listOf(LiveItem("Rs7St51oDDc", "Sunnah"))))  // still listed
            runCurrent()

            assertEquals("the same id again", 4, repository.calls)
            assertTrue(vm.state.value.streamState is StreamState.Ready)
        }
    }

    /** ...within the same-id budget (AUTO_RECOVERY permits): once it is spent, step 4 waits and never loops on the id. */
    @Test fun `network failures retry within the same-id budget, then step 4`() = runTest(dispatcher) {
        val repository = Offline(streams("Rs7St51oDDc", isLive = true), failures = Int.MAX_VALUE)
        val content = FakeContentService().apply { channelLives[channel] = ChannelLives(listOf(LiveItem("Rs7St51oDDc", "Sunnah"))) }
        val vm = playerViewModel(repository = repository, contentService = content, ioDispatcher = dispatcher)
        vm.cancelAfter {
            vm.loadVideo(videoId = "Rs7St51oDDc", sourceChannelId = channel, liveChannelId = channel)
            advanceClocks(3 * 60_000L)
            val spent = repository.calls
            assertTrue("same-id re-resolves ran ($spent)", spent > 3)

            advanceClocks(5 * 60_000L)
            assertEquals("a spent budget never re-resolves", spent, repository.calls)
            assertEquals(StreamState.Loading, vm.state.value.streamState)

            advanceClocks(3 * 60_000L)
            assertTrue(vm.state.value.streamState is StreamState.Error)
        }
    }

    /** Stage 9 r3 (Cubic): the reconnect overlay reads the state flow, so an incident over an unchanged Loading reaches it. */
    @Test fun `a reconnect reaches the state flow while the backend is asked`() = runTest(dispatcher) {
        val answer = kotlinx.coroutines.CompletableDeferred<ChannelLives>()
        val (vm, _, _) = setup(streams("pqC-1yfFvu8", isLive = false), later(answer))
        vm.cancelAfter {
            vm.loadVideo(videoId = "pqC-1yfFvu8", sourceChannelId = channel, liveChannelId = channel)
            assertFalse("a first load", vm.state.value.liveReconnecting)
            runCurrent()                                                             // an ended id opened cold: the backend is asked
            assertEquals(StreamState.Loading, vm.state.value.streamState)
            assertTrue(vm.state.value.liveReconnecting)

            answer.complete(ChannelLives(listOf(LiveItem("pqC-1yfFvu8", "Sunnah"))))  // still listed: step 4 waits
            runCurrent()
            assertEquals(StreamState.Loading, vm.state.value.streamState)
            assertTrue(vm.state.value.liveReconnecting)
        }
    }

    @Test fun `an end never advances the queue and asks the backend`() = runTest(dispatcher) {
        val (vm, _, content) = setup(streams("Rs7St51oDDc", isLive = true))
        vm.cancelAfter {
            content.channelLives[channel] = ChannelLives(listOf(LiveItem("Rs7St51oDDc", "Sunnah")))
            vm.loadVideo(videoId = "Rs7St51oDDc", sourceChannelId = channel, liveChannelId = channel)
            advanceTimeBy(1_000); runCurrent()

            assertFalse(vm.markCurrentComplete())
            advanceTimeBy(1_000); runCurrent()

            assertEquals(listOf(channel), content.channelLivesCalls)
            assertEquals(StreamState.Loading, vm.state.value.streamState)        // step 4: the existing reconnecting state
        }
    }

    @Test fun `a rotated stream is switched in place`() = runTest(dispatcher) {
        val (vm, repository, content) = setup(streams("Rs7St51oDDc", isLive = true))
        vm.cancelAfter {
            vm.loadVideo(videoId = "Rs7St51oDDc", sourceChannelId = channel, liveChannelId = channel)
            advanceTimeBy(1_000); runCurrent()
            content.channelLives[channel] = ChannelLives(listOf(LiveItem("newSunnah01", "Sunnah")))
            repository.resolvedStreams = streams("newSunnah01", isLive = true)

            vm.markCurrentComplete()
            advanceTimeBy(1_000); runCurrent()

            assertEquals("newSunnah01", vm.state.value.currentItem?.streamId)
            assertEquals(channel, vm.state.value.currentItem?.liveChannelId)
        }
    }

    /** RV I11: a resolve that is not live now is never played in live-channel mode (no September recording). */
    @Test fun `a not-live resolve never plays the recording`() = runTest(dispatcher) {
        val (vm, _, content) = setup(streams("pqC-1yfFvu8", isLive = false))
        vm.cancelAfter {
            vm.loadVideo(videoId = "pqC-1yfFvu8", sourceChannelId = channel, liveChannelId = channel)
            advanceTimeBy(1_000); runCurrent()

            assertFalse(vm.state.value.streamState is StreamState.Ready)
            assertTrue(content.channelLivesCalls.isNotEmpty())
        }
    }

    /** RV I11, first resolve: Home's tap prefetch must not slip a recording past the live check. */
    @Test fun `a tap-prefetched recording is never played either`() = runTest(dispatcher) {
        val recording = streams("pqC-1yfFvu8", isLive = false)
        val (vm, _, content) = setup(recording, FakeContentService(), prefetched = recording)
        vm.cancelAfter {
            vm.loadVideo(videoId = "pqC-1yfFvu8", sourceChannelId = channel, liveChannelId = channel)
            advanceTimeBy(1_000); runCurrent()

            assertFalse(vm.state.value.streamState is StreamState.Ready)
            assertTrue(content.channelLivesCalls.isNotEmpty())
        }
    }

    /** Regression pin (passes before this task too): live-channel mode never touches an ordinary video's end. */
    @Test fun `an ordinary video keeps today's end behaviour`() = runTest(dispatcher) {
        val (vm, _, content) = setup(streams("Rs7St51oDDc", isLive = true))
        vm.cancelAfter {
            vm.loadVideo(videoId = "Rs7St51oDDc")
            advanceTimeBy(1_000); runCurrent()

            vm.markCurrentComplete()
            advanceTimeBy(1_000); runCurrent()

            assertTrue(content.channelLivesCalls.isEmpty())
        }
    }

    /** A 404 says the channel is not a servable live channel: the error state at once, not ten minutes of retries. */
    @Test fun `a 404 gives up at once`() = runTest(dispatcher) {
        val (vm, _, content) = setup(streams("Rs7St51oDDc", isLive = true), FailingLives(http(404)))
        vm.cancelAfter {
            vm.loadVideo(videoId = "Rs7St51oDDc", sourceChannelId = channel, liveChannelId = channel)
            advanceTimeBy(1_000); runCurrent()

            vm.markCurrentComplete()
            runCurrent()
            assertEquals(StreamState.Error(R.string.player_stream_unavailable), vm.state.value.streamState)

            advanceClocks(60_000)
            assertEquals(1, content.calls)
        }
    }

    /** No connection, a server error or a bad body is no answer: keep reconnecting and ask again. */
    @Test fun `any other failure keeps retrying`() = runTest(dispatcher) {
        val failures = FailingLives(java.io.IOException("offline"), http(500), com.squareup.moshi.JsonDataException("bad body"))
        val (vm, _, content) = setup(streams("Rs7St51oDDc", isLive = true), failures)
        vm.cancelAfter {
            vm.loadVideo(videoId = "Rs7St51oDDc", sourceChannelId = channel, liveChannelId = channel)
            advanceTimeBy(1_000); runCurrent()

            vm.markCurrentComplete()
            runCurrent()
            assertEquals(1, content.calls)
            assertEquals(StreamState.Loading, vm.state.value.streamState)

            advanceClocks(15_000)
            assertEquals(2, content.calls)
            advanceClocks(30_000)
            assertEquals(3, content.calls)
            assertEquals(StreamState.Loading, vm.state.value.streamState)
        }
    }

    /** §9.1 step 3: a failure that waits (nothing live yet) still gets the same-id recovery once the id is listed. */
    @Test fun `a failure retries as a failure`() = runTest(dispatcher) {
        val (vm, repository, content) = setup(streams("Rs7St51oDDc", isLive = true))
        vm.cancelAfter {
            vm.loadVideo(videoId = "Rs7St51oDDc", sourceChannelId = channel, liveChannelId = channel)
            advanceTimeBy(1_000); runCurrent()
            content.channelLives[channel] = ChannelLives(emptyList())

            assertTrue(vm.forceRefreshForAutoRecovery())
            runCurrent()
            assertEquals(1, repository.resolveCallCount)                          // nothing listed: wait
            content.channelLives[channel] = ChannelLives(listOf(LiveItem("Rs7St51oDDc", "Sunnah")))

            advanceClocks(15_000)

            assertEquals(2, repository.resolveCallCount)
            assertTrue(vm.state.value.streamState is StreamState.Ready)
        }
    }

    /** Ruling (fix round 1): a backend outage must not turn the 6 h expiry's 403 into 10 min of waiting and an error. */
    @Test fun `an unreachable backend still gets one same-id refresh`() = runTest(dispatcher) {
        val (vm, repository, content) = setup(streams("Rs7St51oDDc", isLive = true))
        vm.cancelAfter {
            vm.loadVideo(videoId = "Rs7St51oDDc", sourceChannelId = channel, liveChannelId = channel)
            advanceTimeBy(1_000); runCurrent()

            assertTrue(vm.forceRefreshForAutoRecovery())
            runCurrent()

            assertEquals(listOf(channel), content.channelLivesCalls)
            assertEquals(2, repository.resolveCallCount)
            assertTrue(vm.state.value.streamState is StreamState.Ready)
        }
    }

    /** §9.1: a failure acts at once when the backend answers: the same id is recovered, a new id is switched to. */
    @Test fun `a failure recovers the same id or switches at once`() = runTest(dispatcher) {
        val (vm, repository, content) = setup(streams("Rs7St51oDDc", isLive = true))
        vm.cancelAfter {
            vm.loadVideo(videoId = "Rs7St51oDDc", sourceChannelId = channel, liveChannelId = channel)
            advanceTimeBy(1_000); runCurrent()
            content.channelLives[channel] = ChannelLives(listOf(LiveItem("Rs7St51oDDc", "Sunnah")))

            vm.forceRefreshForAutoRecovery()
            runCurrent()
            assertEquals(2, repository.resolveCallCount)
            assertEquals("Rs7St51oDDc", (vm.state.value.streamState as StreamState.Ready).streamId)

            content.channelLives[channel] = ChannelLives(listOf(LiveItem("newSunnah01", "Sunnah")))
            repository.resolvedStreams = streams("newSunnah01", isLive = true)
            vm.forceRefreshForAutoRecovery()
            runCurrent()
            assertEquals("newSunnah01", (vm.state.value.streamState as StreamState.Ready).streamId)
        }
    }

    /** Stage 9 r2: a delayed refresh whose second acquire is still refused is a spent budget (step 4), never a Loading with no way out. */
    @Test fun `a delayed refresh still refused after the wait goes to step 4 and ends in the error state`() = runTest(dispatcher) {
        var autoRecoveryCalls = 0
        val limiter = org.mockito.kotlin.mock<com.albunyaan.tube.player.ExtractionRateLimiter> {
            on { acquire(org.mockito.kotlin.any(), org.mockito.kotlin.any()) } doAnswer { inv ->
                val kind = inv.arguments[1] as com.albunyaan.tube.player.ExtractionRateLimiter.RequestKind
                when {
                    kind != com.albunyaan.tube.player.ExtractionRateLimiter.RequestKind.AUTO_RECOVERY ->
                        com.albunyaan.tube.player.ExtractionRateLimiter.RateLimitResult.Allowed
                    autoRecoveryCalls++ == 0 -> com.albunyaan.tube.player.ExtractionRateLimiter.RateLimitResult.Delayed(1_000, "minimum interval")
                    else -> com.albunyaan.tube.player.ExtractionRateLimiter.RateLimitResult.Blocked("limit", 60_000)
                }
            }
        }
        val (vm, _, content) = setup(streams("Rs7St51oDDc", isLive = true), FakeContentService(), rateLimiter = limiter)
        vm.cancelAfter {
            content.channelLives[channel] = ChannelLives(listOf(LiveItem("Rs7St51oDDc", "Sunnah")))
            vm.loadVideo(videoId = "Rs7St51oDDc", sourceChannelId = channel, liveChannelId = channel)
            advanceTimeBy(1_000); runCurrent()

            assertTrue(vm.forceRefreshForAutoRecovery())
            advanceClocks(2_000)                                                  // the delay lapses, the second acquire is refused
            assertEquals(StreamState.Loading, vm.state.value.streamState)         // step 4's reconnecting state, with retries running
            val asked = content.channelLivesCalls.size

            advanceClocks(11 * 60_000L)

            assertTrue("step 4 must keep asking the backend", content.channelLivesCalls.size > asked)
            assertTrue(vm.state.value.streamState is StreamState.Error)
        }
    }

    /** Extra 3: a refresh the limiter refuses (4 cycles 31 s apart) goes to step 4, never a stopped player left Ready. */
    @Test fun `a refused same-id refresh goes to the step 4 retries`() = runTest(dispatcher) {
        val (vm, repository, content) = setup(streams("Rs7St51oDDc", isLive = true))
        vm.cancelAfter {
            content.channelLives[channel] = ChannelLives(listOf(LiveItem("Rs7St51oDDc", "Sunnah")))
            vm.loadVideo(videoId = "Rs7St51oDDc", sourceChannelId = channel, liveChannelId = channel)
            advanceTimeBy(1_000); runCurrent()

            repeat(3) {
                assertTrue(vm.forceRefreshForAutoRecovery())
                runCurrent()
                assertTrue(vm.state.value.streamState is StreamState.Ready)
                advanceClocks(31_000)
            }
            assertEquals(4, repository.resolveCallCount)

            vm.forceRefreshForAutoRecovery()
            runCurrent()

            assertEquals(4, repository.resolveCallCount)
            assertEquals(StreamState.Loading, vm.state.value.streamState)
        }
    }

    /** §9.1 step 3: when the fragment's same-id budget runs out, the step 4 retries run, not the manual-retry overlay. */
    @Test fun `an exhausted recovery goes to the step 4 retries`() = runTest(dispatcher) {
        val (vm, _, content) = setup(streams("Rs7St51oDDc", isLive = true))
        vm.cancelAfter {
            content.channelLives[channel] = ChannelLives(listOf(LiveItem("Rs7St51oDDc", "Sunnah")))
            vm.loadVideo(videoId = "Rs7St51oDDc", sourceChannelId = channel, liveChannelId = channel)
            advanceTimeBy(1_000); runCurrent()

            vm.setRecoveryExhaustedState()
            runCurrent()

            assertEquals(listOf(channel), content.channelLivesCalls)
            assertEquals(StreamState.Loading, vm.state.value.streamState)
        }
    }

    /** Important 1: a resolve that fails (F3: an ended 24/7 id) asks the backend, which lists the new live. */
    @Test fun `a failed resolve asks the backend and switches`() = runTest(dispatcher) {
        val repository = FakePlayerRepository().apply {
            failNextResolve = true                                                   // 3 nulls, then the next call
            resolvedStreamsAfterFailure = streams("newSunnah01", isLive = true)
        }
        val content = FakeContentService()
        val vm = playerViewModel(repository = repository, contentService = content, ioDispatcher = dispatcher)
        vm.cancelAfter {
            content.channelLives[channel] = ChannelLives(listOf(LiveItem("newSunnah01", "Sunnah")))

            vm.loadVideo(videoId = "Rs7St51oDDc", sourceChannelId = channel, liveChannelId = channel)
            advanceTimeBy(4_000); runCurrent()

            assertEquals(listOf("Rs7St51oDDc", "Rs7St51oDDc", "Rs7St51oDDc", "newSunnah01"), repository.resolvedVideoIds)
            assertEquals("newSunnah01", (vm.state.value.streamState as StreamState.Ready).streamId)
        }
    }

    /** Important 2: a wait that outlives its incident never puts a recovered stream back into reconnecting. */
    @Test fun `a recovered stream stays playing`() = runTest(dispatcher) {
        val (vm, _, content) = setup(streams("Rs7St51oDDc", isLive = true))
        vm.cancelAfter {
            vm.loadVideo(videoId = "Rs7St51oDDc", sourceChannelId = channel, liveChannelId = channel)
            advanceTimeBy(1_000); runCurrent()
            content.channelLives[channel] = ChannelLives(emptyList())
            vm.markCurrentComplete()
            runCurrent()
            assertEquals(StreamState.Loading, vm.state.value.streamState)

            vm.retryCurrentStream()                                                  // live again
            runCurrent()
            assertTrue(vm.state.value.streamState is StreamState.Ready)

            advanceClocks(15_000)

            assertTrue(vm.state.value.streamState is StreamState.Ready)
            assertEquals(1, content.channelLivesCalls.size)
        }
    }

    /** Minor 3: the DEBUG force-end belongs to the item it was armed for. */
    @Test fun `the debug force-end follows the item`() = runTest(dispatcher) {
        val (vm, _, content) = setup(streams("Rs7St51oDDc", isLive = true))
        vm.cancelAfter {
            content.channelLives[channel] = ChannelLives(listOf(LiveItem("Rs7St51oDDc", "Sunnah")))
            vm.debugLiveForceEndMs = 60_000
            vm.loadVideo(videoId = "Rs7St51oDDc", sourceChannelId = channel, liveChannelId = channel)
            advanceTimeBy(30_000); runCurrent()
            vm.loadVideo(videoId = "Rs7St51oDDc", sourceChannelId = channel, liveChannelId = channel)   // reopened
            runCurrent()

            advanceTimeBy(31_000); runCurrent()
            assertTrue("the first arming was cancelled", content.channelLivesCalls.isEmpty())
            advanceTimeBy(30_000); runCurrent()
            assertEquals(listOf(channel), content.channelLivesCalls)
        }
    }

    /** Task 28: step 4 shows the fragment's reconnecting overlay; a first load and an ordinary video never do. */
    @Test fun `only a reconnect shows the reconnecting state`() = runTest(dispatcher) {
        val (vm, _, content) = setup(streams("pqC-1yfFvu8", isLive = false))
        vm.cancelAfter {
            vm.loadVideo(videoId = "pqC-1yfFvu8")                                    // an ordinary video plays first
            advanceTimeBy(1_000); runCurrent()
            assertTrue(vm.state.value.streamState is StreamState.Ready)
            assertFalse(vm.state.value.liveReconnecting)

            content.channelLives[channel] = ChannelLives(emptyList())
            vm.loadVideo(videoId = "pqC-1yfFvu8", sourceChannelId = channel, liveChannelId = channel)
            assertEquals(StreamState.Loading, vm.state.value.streamState)            // the first load: as it was
            assertFalse(vm.state.value.liveReconnecting)

            advanceTimeBy(1_000); runCurrent()                                       // an ended id (a recording): step 4 waits
            assertEquals(StreamState.Loading, vm.state.value.streamState)
            assertTrue(vm.state.value.liveReconnecting)                                        // with nothing ever played
        }
    }

    /** Fix round 1: a Retry over a played live is a reconnect; a new live load after it is a first load again. */
    @Test fun `a new live load after other playback is a first load`() = runTest(dispatcher) {
        val (vm, _, _) = setup(streams("Rs7St51oDDc", isLive = true))
        vm.cancelAfter {
            vm.loadVideo(videoId = "Rs7St51oDDc", sourceChannelId = channel, liveChannelId = channel)
            advanceTimeBy(1_000); runCurrent()
            assertTrue(vm.state.value.streamState is StreamState.Ready)

            vm.retryCurrentStream()                                                  // no incident open
            assertEquals(StreamState.Loading, vm.state.value.streamState)
            assertTrue(vm.state.value.liveReconnecting)

            vm.loadVideo(videoId = "Rs7St51oDDc", sourceChannelId = channel, liveChannelId = channel)
            assertEquals(StreamState.Loading, vm.state.value.streamState)
            assertFalse(vm.state.value.liveReconnecting)
        }
    }

    /** Fix round 1: the switch's resolve (the incident is closed) keeps the reconnecting state. */
    @Test fun `a switch keeps the reconnecting state`() = runTest(dispatcher) {
        val (vm, repository, content) = setup(streams("Rs7St51oDDc", isLive = true))
        vm.cancelAfter {
            vm.loadVideo(videoId = "Rs7St51oDDc", sourceChannelId = channel, liveChannelId = channel)
            advanceTimeBy(1_000); runCurrent()
            content.channelLives[channel] = ChannelLives(listOf(LiveItem("newSunnah01", "Sunnah")))
            repository.resolvedStreams = streams("newSunnah01", isLive = true)
            repository.resolveDelayMs = 5_000                                        // hold the switch's resolve

            vm.markCurrentComplete()
            runCurrent()

            assertEquals("newSunnah01", vm.state.value.currentItem?.streamId)
            assertEquals(StreamState.Loading, vm.state.value.streamState)
            assertTrue(vm.state.value.liveReconnecting)
        }
    }

    /**
     * Stage 3 I1: the fragment stops the player right after a failure's refresh is accepted. Left Ready(old) while the
     * backend is asked, the stopped player's video-size reset re-prepared the failed source and spent the budget twice.
     */
    @Test fun `a live failure is Loading until the backend answers`() = runTest(dispatcher) {
        val answer = kotlinx.coroutines.CompletableDeferred<ChannelLives>()
        val later = object : ContentService by FakeContentService() {
            override suspend fun fetchChannelLives(channelId: String): ChannelLives = answer.await()
        }
        val (vm, repository, _) = setup(streams("Rs7St51oDDc", isLive = true), later)
        vm.cancelAfter {
            vm.loadVideo(videoId = "Rs7St51oDDc", sourceChannelId = channel, liveChannelId = channel)
            advanceTimeBy(1_000); runCurrent()
            assertTrue(vm.state.value.streamState is StreamState.Ready)

            assertTrue(vm.forceRefreshForAutoRecovery())
            assertEquals("no Ready(old) for the stopped player to rebuild", StreamState.Loading, vm.state.value.streamState)
            runCurrent()
            assertEquals("still no answer", StreamState.Loading, vm.state.value.streamState)
            assertEquals(1, repository.resolveCallCount)

            answer.complete(ChannelLives(listOf(LiveItem("Rs7St51oDDc", "Sunnah"))))
            runCurrent()

            assertEquals("one same-id refresh", 2, repository.resolveCallCount)
            assertTrue(vm.state.value.streamState is StreamState.Ready)
        }
    }

    /** Stage 3 M4: a rotated live with no title keeps a title on screen and drops the old broadcast's details. */
    @Test fun `a switch to an untitled live keeps the title and drops the old details`() = runTest(dispatcher) {
        val (vm, repository, content) = setup(streams("Rs7St51oDDc", isLive = true))
        vm.cancelAfter {
            vm.loadVideo(
                videoId = "Rs7St51oDDc", title = "Sunnah", channelName = "Lecturer", description = "Old broadcast",
                viewCount = 1234, sourceChannelId = channel, liveChannelId = channel,
            )
            advanceTimeBy(1_000); runCurrent()
            content.channelLives[channel] = ChannelLives(listOf(LiveItem("newSunnah01", "")))   // the backend's null title
            repository.resolvedStreams = streams("newSunnah01", isLive = true)

            vm.markCurrentComplete()
            runCurrent()

            val item = vm.state.value.currentItem!!
            assertEquals("newSunnah01", item.streamId)
            assertEquals("Sunnah", item.title)
            assertEquals(null, item.description)
            assertEquals(null, item.viewCount)
        }
    }

    /** Codex C2: NewPipe's title may replace the display title; D12 recovery still matches the backend's. */
    @Test fun `hydration never changes which broadcast recovery picks`() = runTest(dispatcher) {
        val translated = object : com.albunyaan.tube.data.extractor.ExtractorClient by FakeExtractorClient() {
            override suspend fun fetchVideoMetadata(ids: List<String>) =
                ids.associateWith { com.albunyaan.tube.data.extractor.VideoMetadata(title = "Sunnah (translated)") }
        }
        val repository = FakePlayerRepository().apply { resolvedStreams = streams("Rs7St51oDDc", isLive = true) }
        val content = FakeContentService()
        val vm = playerViewModel(repository = repository, contentService = content, extractorClient = translated, ioDispatcher = dispatcher)
        vm.cancelAfter {
            vm.loadVideo(videoId = "Rs7St51oDDc", title = "Sunnah", sourceChannelId = channel, liveChannelId = channel)
            advanceTimeBy(1_000); runCurrent()
            assertEquals("display metadata hydrates", "Sunnah (translated)", vm.state.value.currentItem?.title)
            content.channelLives[channel] = ChannelLives(listOf(LiveItem("otherShow01", "Other"), LiveItem("newSunnah01", "Sunnah")))
            repository.resolvedStreams = streams("newSunnah01", isLive = true)

            vm.markCurrentComplete()
            runCurrent()

            assertEquals("D12 matches the backend's title at once", "newSunnah01", vm.state.value.currentItem?.streamId)
        }
    }

    /** Task 26's retry runs on a failed refresh; a refresh that is no longer live is the trigger's, not a retry. */
    @Test fun `a refresh that is no longer live asks the backend`() = runTest(dispatcher) {
        val (vm, repository, content) = setup(streams("Rs7St51oDDc", isLive = true))
        vm.cancelAfter {
            vm.loadVideo(videoId = "Rs7St51oDDc", sourceChannelId = channel, liveChannelId = channel)
            advanceTimeBy(1_000); runCurrent()
            val ready = vm.state.value.streamState as StreamState.Ready

            repository.resolvedStreams = null
            assertFalse(vm.performLiveStreamRefresh("Rs7St51oDDc", ready.selection))
            assertTrue(content.channelLivesCalls.isEmpty())

            repository.resolvedStreams = streams("Rs7St51oDDc", isLive = false)
            assertTrue(vm.performLiveStreamRefresh("Rs7St51oDDc", ready.selection))
            runCurrent()
            assertEquals(listOf(channel), content.channelLivesCalls)
        }
    }
}
