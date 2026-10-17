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
    ): Triple<PlayerViewModel, FakePlayerRepository, C> {
        val repository = FakePlayerRepository().apply { resolvedStreams = resolved }
        val vm = playerViewModel(
            repository = repository,
            contentService = content,
            prefetchService = FakePrefetchService(prefetched),
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

    @Test fun `an end never advances the queue and asks the backend`() = runTest(dispatcher) {
        val (vm, _, content) = setup(streams("Rs7St51oDDc", isLive = true))
        vm.cancelAfter {
            content.channelLives[channel] = ChannelLives(channel, "LIVE", listOf(LiveItem("Rs7St51oDDc", "Sunnah")))
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
            content.channelLives[channel] = ChannelLives(channel, "LIVE", listOf(LiveItem("newSunnah01", "Sunnah")))
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

    /** §9.1 step 3: a failure the backend could not answer still gets the same-id recovery once it answers. */
    @Test fun `a failure retries as a failure`() = runTest(dispatcher) {
        val (vm, repository, content) = setup(streams("Rs7St51oDDc", isLive = true))
        vm.cancelAfter {
            vm.loadVideo(videoId = "Rs7St51oDDc", sourceChannelId = channel, liveChannelId = channel)
            advanceTimeBy(1_000); runCurrent()

            assertTrue(vm.forceRefreshForAutoRecovery())
            runCurrent()
            assertEquals(1, repository.resolveCallCount)                          // the backend is unreachable: wait
            content.channelLives[channel] = ChannelLives(channel, "LIVE", listOf(LiveItem("Rs7St51oDDc", "Sunnah")))

            advanceClocks(15_000)

            assertEquals(2, repository.resolveCallCount)
            assertTrue(vm.state.value.streamState is StreamState.Ready)
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
