package com.albunyaan.tube.ui.player

import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.ExtractionClient
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.VideoTrack
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.player.LivePlaylistRedirector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** Spec §9.2(a): the refresh swaps in place with no player event; a refused swap falls back to today's rebuild. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PlayerViewModelLiveRefreshTest {

    private val dispatcher = StandardTestDispatcher()
    // Relative to the (Robolectric) wall clock the streams stamp, so the lifetimes differ: 2 h and 5 h.
    private val e1 by lazy { System.currentTimeMillis() / 1000 + 2 * 3600 }
    private val e2 by lazy { System.currentTimeMillis() / 1000 + 5 * 3600 }
    private val minute = 60_000L

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun playlist(itag: Int, expire: Long) =
        "https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/$expire/ei/x/ip/203.0.113.7/id/Rs7St51oDDc.1/itag/$itag/file/index.m3u8"

    private fun master(expire: Long) = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n${playlist(230, expire)}\n"

    /**
     * Virtual time and Robolectric's monotonic clock move together, a second at a time, so each refresh pass reads
     * the clock it runs at (the retry deadline is on that clock).
     */
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

    /**
     * runTest drains the scheduler after the body (a failed assertion included) with Robolectric's clock standing
     * still, so a refresh job left behind would retry forever: the virtual-time tests cancel the VM's scope.
     */
    private inline fun PlayerViewModel.cancelAfter(block: () -> Unit) = try { block() } finally { viewModelScope.cancel() }

    // Two audio languages keep maybeEnumerateDubs (a real MWEB request for an 11-character id) out of a JVM test.
    private fun live(expire: Long, client: ExtractionClient = ExtractionClient.NEWPIPE_IOS) = ResolvedStreams(
        streamId = "Rs7St51oDDc",
        videoTracks = listOf(VideoTrack("https://v.test/360", "video/mp4", 640, 360, 1, "360p", 30, isVideoOnly = false)),
        audioTracks = listOf(
            AudioTrack("https://a.test/a", "audio/mp4", 128_000, "mp4a.40.2"),
            AudioTrack("https://a.test/ar", "audio/mp4", 48_000, "mp4a.40.5", language = "ar"),
        ),
        durationSeconds = 0,
        hlsUrl = "https://manifest.googlevideo.com/api/manifest/hls_variant/expire/$expire/file/index.m3u8",
        isLive = true, extractionClient = client,
    )

    /** Review Focus 5: with the fragment's view gone (background audio), the swap still lands: it needs no event. */
    @Test fun `a refresh with no view swaps in the redirector and emits nothing`() = runTest(dispatcher) {
        val repository = FakePlayerRepository().apply { resolvedStreams = live(e1) }
        val redirector = LivePlaylistRedirector { _, _ -> master(e2) }
        val vm = playerViewModel(repository = repository, livePlaylistRedirector = redirector)
        vm.loadVideo(videoId = "Rs7St51oDDc")
        advanceUntilIdle()
        val ready = vm.state.value.streamState as StreamState.Ready
        val events = mutableListOf<PlayerUiEvent>()
        // Unconfined: subscribed before the refresh emits (the SharedFlow drops what nobody collects) and run
        // without advanceUntilIdle, which never runs backgroundScope work on its own.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiEvents.collect { events += it } }

        repository.resolvedStreams = live(e2)
        vm.performLiveStreamRefresh("Rs7St51oDDc", ready.selection)
        advanceUntilIdle()

        assertTrue("no rebuild event", events.none { it is PlayerUiEvent.LiveStreamRefreshReady })
        assertEquals(playlist(230, e2), redirector.rewrite("Rs7St51oDDc", playlist(230, e1)))
        assertEquals("the player keeps its source", ready, vm.state.value.streamState)
    }

    /** RV I10b + Review Focus 3: another client's URLs take today's rebuild (which never seeks a live stream). */
    @Test fun `a client mismatch falls back to the rebuild event`() = runTest(dispatcher) {
        val repository = FakePlayerRepository().apply { resolvedStreams = live(e1, ExtractionClient.NEWPIPE_IOS) }
        val vm = playerViewModel(repository = repository, livePlaylistRedirector = LivePlaylistRedirector { _, _ -> master(e2) })
        vm.loadVideo(videoId = "Rs7St51oDDc")
        advanceUntilIdle()
        val ready = vm.state.value.streamState as StreamState.Ready
        val events = mutableListOf<PlayerUiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiEvents.collect { events += it } }

        val fresh = live(e2, ExtractionClient.NEWPIPE_ANDROID)
        repository.resolvedStreams = fresh
        vm.performLiveStreamRefresh("Rs7St51oDDc", ready.selection)
        advanceUntilIdle()

        assertTrue(events.any { it is PlayerUiEvent.LiveStreamRefreshReady })
        // Fix round 1: Ready follows the rebuilt source, or the fragment's next state pass rebuilds from e1.
        assertEquals(fresh, (vm.state.value.streamState as StreamState.Ready).selection.resolved)
    }

    /** An audio-only player plays a progressive audio URL the redirector never sees: only the rebuild refreshes it. */
    @Test fun `an audio-only player takes the rebuild`() = runTest(dispatcher) {
        val repository = FakePlayerRepository().apply { resolvedStreams = live(e1) }
        val redirector = LivePlaylistRedirector { _, _ -> master(e2) }
        val vm = playerViewModel(repository = repository, livePlaylistRedirector = redirector)
        vm.loadVideo(videoId = "Rs7St51oDDc")
        advanceUntilIdle()
        vm.setAudioOnly(true)
        val ready = vm.state.value.streamState as StreamState.Ready
        val events = mutableListOf<PlayerUiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiEvents.collect { events += it } }

        val fresh = live(e2)
        repository.resolvedStreams = fresh
        vm.performLiveStreamRefresh("Rs7St51oDDc", ready.selection)
        advanceUntilIdle()

        assertTrue(events.any { it is PlayerUiEvent.LiveStreamRefreshReady })
        assertNull("no swap behind an audio-only source", redirector.rewrite("Rs7St51oDDc", playlist(230, e1)))
        assertEquals(fresh, (vm.state.value.streamState as StreamState.Ready).selection.resolved)
    }

    /**
     * Hand-off: after a swap Ready keeps the URLs the player was built from (so it never rebuilds). The audio-only
     * toggle rebuilds from Ready, so it must start from the swapped-in URLs, not from a master that dies at e1.
     */
    @Test fun `the audio-only rebuild after a swap starts from the fresh urls`() = runTest(dispatcher) {
        val repository = FakePlayerRepository().apply { resolvedStreams = live(e1) }
        val vm = playerViewModel(repository = repository, livePlaylistRedirector = LivePlaylistRedirector { _, _ -> master(e2) })
        vm.loadVideo(videoId = "Rs7St51oDDc")
        advanceUntilIdle()
        val ready = vm.state.value.streamState as StreamState.Ready
        val fresh = live(e2)
        repository.resolvedStreams = fresh

        vm.performLiveStreamRefresh("Rs7St51oDDc", ready.selection)
        assertEquals("the swap itself rebuilds nothing", ready, vm.state.value.streamState)
        vm.setAudioOnly(true)
        assertEquals(fresh, (vm.state.value.streamState as StreamState.Ready).selection.resolved)
        vm.setAudioOnly(false)
        assertEquals(fresh.hlsUrl, (vm.state.value.streamState as StreamState.Ready).selection.resolved.hlsUrl)
    }

    /** Fix round 1: an audio-language change rebuilds from Ready too, so after a swap it starts from the fresh URLs. */
    @Test fun `an audio-language change after a swap starts from the fresh urls`() = runTest(dispatcher) {
        val repository = FakePlayerRepository().apply { resolvedStreams = live(e1) }
        val vm = playerViewModel(repository = repository, livePlaylistRedirector = LivePlaylistRedirector { _, _ -> master(e2) })
        vm.loadVideo(videoId = "Rs7St51oDDc")
        advanceUntilIdle()
        val ready = vm.state.value.streamState as StreamState.Ready
        val fresh = live(e2)
        repository.resolvedStreams = fresh

        vm.performLiveStreamRefresh("Rs7St51oDDc", ready.selection)
        vm.selectAudioTrack(ready.selection.resolved.audioTracks.first { it.language == "ar" })

        val selection = (vm.state.value.streamState as StreamState.Ready).selection
        assertEquals(fresh, selection.resolved)
        assertEquals("ar", selection.audio.language)
    }

    /** Hand-off: one transient failure must not mean a certain 403 at expiry. Retries share the one job slot. */
    @Test fun `a failed refresh retries every two minutes until the urls expire`() = runTest(dispatcher) {
        val first = live(e1)
        val repository = FakePlayerRepository().apply { resolvedStreams = first }
        val vm = playerViewModel(
            repository = repository,
            livePlaylistRedirector = LivePlaylistRedirector { _, _ -> master(e2) },
            ioDispatcher = dispatcher,
        )
        vm.cancelAfter {
            vm.loadVideo(videoId = "Rs7St51oDDc")
            runCurrent()
            val resolves = repository.resolveCallCount
            repository.alwaysFail = true

            advanceClocks(first.timeUntilLiveRefreshMs()!!)
            assertEquals("the scheduled attempt", resolves + 1, repository.resolveCallCount)
            advanceClocks(8 * minute)
            assertEquals("retries at +2, +4, +6, +8 min", resolves + 5, repository.resolveCallCount)
            advanceClocks(60 * minute)
            assertEquals("the lead window is spent: the 403 at expiry takes the rebuild", resolves + 5, repository.resolveCallCount)
        }
    }

    /** Fix round 1: the deadline is the URLs' expiry on the clock, so slow resolves cannot start an attempt past it. */
    @Test fun `slow resolves never start a retry past the expiry`() = runTest(dispatcher) {
        val first = live(e1)
        val repository = FakePlayerRepository().apply { resolvedStreams = first }
        val vm = playerViewModel(
            repository = repository,
            livePlaylistRedirector = LivePlaylistRedirector { _, _ -> master(e2) },
            ioDispatcher = dispatcher,
        )
        vm.cancelAfter {
            vm.loadVideo(videoId = "Rs7St51oDDc")
            runCurrent()
            val resolves = repository.resolveCallCount
            repository.alwaysFail = true
            repository.resolveDelayMs = 90_000                 // each failed attempt takes 1.5 min

            advanceClocks(first.timeUntilLiveRefreshMs()!! + 20 * minute)
            assertEquals("attempts at +0, +3.5 and +7 min; +10.5 is past the expiry", resolves + 3, repository.resolveCallCount)
        }
    }

    @Test fun `a retry that succeeds swaps and stops retrying`() = runTest(dispatcher) {
        val first = live(e1)
        val repository = FakePlayerRepository().apply { resolvedStreams = first }
        val redirector = LivePlaylistRedirector { _, _ -> master(e2) }
        val vm = playerViewModel(repository = repository, livePlaylistRedirector = redirector, ioDispatcher = dispatcher)
        vm.cancelAfter {
            vm.loadVideo(videoId = "Rs7St51oDDc")
            runCurrent()
            val resolves = repository.resolveCallCount
            repository.failNextResolve = true                  // the next three resolves fail, the fourth returns e2
            repository.resolvedStreamsAfterFailure = live(e2)

            advanceClocks(first.timeUntilLiveRefreshMs()!! + 6 * minute)
            assertEquals(resolves + 4, repository.resolveCallCount)
            assertEquals(playlist(230, e2), redirector.rewrite("Rs7St51oDDc", playlist(230, e1)))
            advanceClocks(10 * minute)
            assertEquals("no retry after the swap", resolves + 4, repository.resolveCallCount)
        }
    }

    /** Fix round 1: after a swap the next refresh is due at the fresh URLs' expire − 10 min, not e1's (due now). */
    @Test fun `a swap schedules the next refresh from the fresh expiry`() = runTest(dispatcher) {
        assertNextRefreshFromFreshExpiry(ExtractionClient.NEWPIPE_IOS)
    }

    /** Fix round 1: the fallback rebuild schedules from the fresh expiry too. */
    @Test fun `a fallback schedules the next refresh from the fresh expiry`() = runTest(dispatcher) {
        assertNextRefreshFromFreshExpiry(ExtractionClient.NEWPIPE_ANDROID)
    }

    private fun TestScope.assertNextRefreshFromFreshExpiry(freshClient: ExtractionClient) {
        val first = live(e1)
        val repository = FakePlayerRepository().apply { resolvedStreams = first }
        var fetches = 0                                    // one good master: a second swap cannot loop the test
        val redirector = LivePlaylistRedirector { _, _ -> if (fetches++ == 0) master(e2) else null }
        val vm = playerViewModel(repository = repository, livePlaylistRedirector = redirector, ioDispatcher = dispatcher)
        vm.cancelAfter {
            vm.loadVideo(videoId = "Rs7St51oDDc")
            runCurrent()
            val resolves = repository.resolveCallCount
            val fresh = live(e2, freshClient)
            repository.resolvedStreams = fresh

            advanceClocks(first.timeUntilLiveRefreshMs()!!)
            assertEquals("the refresh at e1's lead", resolves + 1, repository.resolveCallCount)
            repository.alwaysFail = true
            val untilNext = fresh.timeUntilLiveRefreshMs()!!
            advanceClocks(untilNext - minute)
            assertEquals("nothing before the fresh lead (e1's is already due)", resolves + 1, repository.resolveCallCount)
            advanceClocks(minute)
            assertEquals("the next refresh at the fresh lead", resolves + 2, repository.resolveCallCount)
        }
    }
}
