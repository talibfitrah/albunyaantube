package com.albunyaan.tube.ui.player

import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.VideoTrack
import com.albunyaan.tube.data.source.ContentService
import com.albunyaan.tube.data.source.FakeContentService
import com.albunyaan.tube.data.source.api.ChannelLives
import com.albunyaan.tube.data.source.api.LiveItem
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import kotlinx.coroutines.CoroutineScope
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response

/** Owner requirement: an admin removal reaches a phone already playing a live-channel stream within a minute. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PlayerViewModelLiveHeartbeatTest {

    private val dispatcher = StandardTestDispatcher()
    private val channel = "UCROKYPep-UuODNwyipe6JMw"
    private val live = "Rs7St51oDDc"
    private val CAST_URL = "https://cast.example/ours"   // the URL this app loaded to the receiver

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    // Two audio languages keep maybeEnumerateDubs (a real MWEB request) out of a JVM test.
    private fun streams(id: String) = ResolvedStreams(
        streamId = id,
        videoTracks = listOf(VideoTrack("https://v.test/$id", "video/mp4", 640, 360, 1, "360p", 30, isVideoOnly = false)),
        audioTracks = listOf(
            AudioTrack("https://a.test/$id", "audio/mp4", 128_000, "mp4a.40.2"),
            AudioTrack("https://a.test/$id/ar", "audio/mp4", 48_000, "mp4a.40.5", language = "ar"),
        ),
        durationSeconds = 0, hlsUrl = "https://h.test/$id.m3u8", isLive = true,
    )

    private fun http(code: Int) = HttpException(Response.error<Any>(code, "".toResponseBody(null)))

    /** The backend: [answer] per `live` param; [asked] records each param (null = a recovery ask with no page live). */
    private class Backend(var answer: (live: String?) -> ChannelLives) : ContentService by FakeContentService() {
        val asked = mutableListOf<String?>()
        override suspend fun fetchChannelLives(channelId: String, live: String?): ChannelLives { asked += live; return answer(live) }
        /** Exact live links: [link] answers GET /live/links/{id} (returns = 204, throws = anything else). */
        var link: () -> Unit = {}
        val linkAsked = mutableListOf<String>()
        override suspend fun checkLiveLink(videoId: String) { linkAsked += videoId; link() }
    }

    private fun listed(vararg ids: String) = ChannelLives(ids.map { LiveItem(it, "Sunnah") })

    private fun TestScope.playing(
        backend: Backend,
        castWatch: CastRemovalWatch = CastRemovalWatch(CoroutineScope(dispatcher)) { null },
    ): Pair<PlayerViewModel, FakePlayerRepository> {
        val repository = FakePlayerRepository().apply { resolvedStreams = streams(live) }
        val vm = playerViewModel(repository = repository, contentService = backend, ioDispatcher = dispatcher, castWatch = castWatch)
        vm.loadVideo(videoId = live, sourceChannelId = channel, liveChannelId = channel)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(vm.state.value.streamState is StreamState.Ready)
        return vm to repository
    }

    private fun TestScope.playingLink(
        backend: Backend,
        castWatch: CastRemovalWatch = CastRemovalWatch(CoroutineScope(dispatcher)) { null },
        repository: FakePlayerRepository = FakePlayerRepository(),
    ): PlayerViewModel {
        repository.resolvedStreams = streams(live)
        val vm = playerViewModel(repository = repository, contentService = backend, ioDispatcher = dispatcher, castWatch = castWatch)
        vm.loadVideo(videoId = live, liveLink = true)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(vm.state.value.streamState is StreamState.Ready)
        return vm
    }

    private fun TestScope.tick() { advanceTimeBy(60_000); runCurrent() }

    /** A wait loop left behind would run forever while runTest drains: cancel it. */
    private inline fun PlayerViewModel.cancelAfter(block: () -> Unit) = try { block() } finally { viewModelScope.cancel() }

    @Test fun `a 404 tick stops playback as unavailable`() = runTest(dispatcher) {
        val backend = Backend { throw http(404) }
        val (vm, repository) = playing(backend)
        vm.cancelAfter {
            tick()

            assertEquals(listOf<String?>(live), backend.asked)
            assertEquals(StreamState.ContentUnavailable, vm.state.value.streamState)
            tick()
            assertEquals("removal, not an end: no recovery, no more ticks", 1, backend.asked.size)
            assertEquals(1, repository.resolveCallCount)
        }
    }

    @Test fun `an empty tick stops playback as unavailable`() = runTest(dispatcher) {
        val backend = Backend { listed() }
        val (vm, repository) = playing(backend)
        vm.cancelAfter {
            tick()

            assertEquals(StreamState.ContentUnavailable, vm.state.value.streamState)
            tick()
            assertEquals(1, backend.asked.size)
            assertEquals(1, repository.resolveCallCount)
        }
    }

    @Test fun `a tick that lists the current id keeps playing`() = runTest(dispatcher) {
        val backend = Backend { listed(live) }
        val (vm, _) = playing(backend)
        vm.cancelAfter {
            advanceTimeBy(57_000); runCurrent()                  // Ready came at ~0 s; playing() spent 1 s
            assertTrue("not before 60 s", backend.asked.isEmpty())
            advanceTimeBy(2_000); runCurrent()
            tick(); tick()

            assertEquals(listOf<String?>(live, live, live), backend.asked)
            assertTrue(vm.state.value.streamState is StreamState.Ready)
        }
    }

    /** Cubic r8: a re-resolve of the same live keeps the running tick, so refreshes under 60 s apart never starve it. */
    @Test fun `a re-resolve of the same live keeps the tick on time`() = runTest(dispatcher) {
        val backend = Backend { listed(live) }
        val (vm, repository) = playing(backend)
        vm.cancelAfter {
            advanceTimeBy(40_000); runCurrent()
            assertTrue(vm.forceRefreshCurrentStream())
            advanceTimeBy(1_000); runCurrent()
            assertEquals("re-resolved", 2, repository.resolveCallCount)
            assertTrue(vm.state.value.streamState is StreamState.Ready)

            advanceTimeBy(20_000); runCurrent()

            assertEquals(listOf<String?>(live), backend.asked)
        }
    }

    @Test fun `an unreachable or busy backend keeps playing`() = runTest(dispatcher) {
        val failures = ArrayDeque(listOf(http(503), java.io.IOException("offline"), http(500), http(400)))
        val backend = Backend { throw failures.removeFirst() }
        val (vm, _) = playing(backend)
        vm.cancelAfter {
            repeat(4) { tick() }

            assertEquals("asked again at every tick", 4, backend.asked.size)
            assertTrue(vm.state.value.streamState is StreamState.Ready)
        }
    }

    @Test fun `no tick while a recovery incident is in progress`() = runTest(dispatcher) {
        val backend = Backend { listed() }                       // nothing live: the incident waits (15, 30, 60, 120 s)
        val (vm, _) = playing(backend)
        vm.cancelAfter {
            vm.markCurrentComplete()
            runCurrent()
            repeat(4) { tick() }

            assertTrue("only the incident's asks: ${backend.asked}", backend.asked.isNotEmpty() && backend.asked.all { it == null })
        }
    }

    /** ...including while the incident's own ask is still out and the old Ready is still up. */
    @Test fun `no tick while the incident's ask is out`() = runTest(dispatcher) {
        val asked = mutableListOf<String?>()
        val backend = object : ContentService by FakeContentService() {
            override suspend fun fetchChannelLives(channelId: String, live: String?): ChannelLives {
                asked += live
                if (live == null) kotlinx.coroutines.awaitCancellation()   // the incident's ask never answers
                return listed()                                             // a tick would stop playback
            }
        }
        val repository = FakePlayerRepository().apply { resolvedStreams = streams(live) }
        val vm = playerViewModel(repository = repository, contentService = backend, ioDispatcher = dispatcher)
        vm.cancelAfter {
            vm.loadVideo(videoId = live, sourceChannelId = channel, liveChannelId = channel)
            advanceTimeBy(1_000); runCurrent()
            vm.markCurrentComplete()
            runCurrent()
            assertTrue(vm.state.value.streamState is StreamState.Ready)
            repeat(3) { tick() }

            assertEquals(listOf<String?>(null), asked)
            assertTrue(vm.state.value.streamState is StreamState.Ready)
        }
    }

    @Test fun `after a switch the tick asks for the new id`() = runTest(dispatcher) {
        val backend = Backend { listed("newSunnah01") }
        val (vm, repository) = playing(backend)
        vm.cancelAfter {
            repository.resolvedStreams = streams("newSunnah01")
            vm.markCurrentComplete()                             // the incident's ask (live = null) switches
            runCurrent()
            assertEquals("newSunnah01", (vm.state.value.streamState as StreamState.Ready).streamId)
            backend.asked.clear()

            tick()

            assertEquals(listOf<String?>("newSunnah01"), backend.asked)
            assertTrue(vm.state.value.streamState is StreamState.Ready)
        }
    }

    @Test fun `no tick after leaving live-channel mode`() = runTest(dispatcher) {
        val backend = Backend { listed(live) }
        val (vm, _) = playing(backend)
        vm.cancelAfter {
            vm.loadVideo(videoId = live)                         // the same id as an ordinary video
            advanceTimeBy(1_000); runCurrent()
            repeat(3) { tick() }

            assertTrue(backend.asked.isEmpty())
        }
    }

    // Cubic K4: the TV keeps playing a cast live after the player screen closes (its ViewModel cleared too), so the
    // removal heartbeat moves to the app-lifetime cast watch until the cast ends.

    private fun receiver(playing: Boolean = true): RemoteMediaClient {
        val ours = media(CAST_URL)                               // built first: Mockito forbids stubbing inside a stubbing
        return mock<RemoteMediaClient>().also { client ->
            whenever(client.hasMediaSession()).thenReturn(playing)
            whenever(client.mediaInfo).thenReturn(ours)
        }
    }

    private fun media(url: String) = mock<MediaInfo>().also { whenever(it.contentId).thenReturn(url) }

    /** The cast watch on the test clock, claiming [castId]; cancelled after [block] so its loop never outlives the test. */
    private inline fun watching(client: RemoteMediaClient?, castId: String?, block: (CastRemovalWatch) -> Unit) {
        val scope = CoroutineScope(dispatcher)
        try { block(CastRemovalWatch(scope) { client }.apply { loaded = castId?.let { it to CAST_URL } }) } finally { scope.cancel() }
    }

    @Test fun `a closed player keeps watching its cast live and a removal stops the receiver`() = runTest(dispatcher) {
        var answer = listed(live)
        val backend = Backend { answer }
        val client = receiver()
        watching(client, live) { watch ->
            val (vm, _) = playing(backend, watch)
            vm.onPlayerViewDestroyed()
            vm.viewModelScope.cancel()                           // a popped player clears its ViewModel too

            tick()
            assertEquals(listOf<String?>(live), backend.asked)
            verify(client, never()).stop()

            answer = listed()                                    // the admin removed it
            tick()
            verify(client).stop()
            tick()
            assertEquals("no ticks after the stop", 2, backend.asked.size)
        }
    }

    @Test fun `a 404 tick for a closed player's cast live stops the receiver`() = runTest(dispatcher) {
        val backend = Backend { throw http(404) }
        val client = receiver()
        watching(client, live) { watch ->
            val (vm, _) = playing(backend, watch)
            vm.cancelAfter {
                vm.onPlayerViewDestroyed()
                tick()

                verify(client).stop()
            }
        }
    }

    @Test fun `the cast watch ends when the cast ends or the receiver gets another video`() = runTest(dispatcher) {
        for (end in listOf<(CastRemovalWatch, RemoteMediaClient) -> Unit>(
            { _, client -> whenever(client.hasMediaSession()).thenReturn(false) },
            { watch, _ -> watch.loaded = "lec00000001" to CAST_URL },
            { _, client -> media("https://cast.example/another-sender").let { whenever(client.mediaInfo).thenReturn(it) } },   // Codex r3
        )) {
            val backend = Backend { listed() }
            val client = receiver()
            watching(client, live) { watch ->
                val (vm, _) = playing(backend, watch)
                vm.cancelAfter {
                    vm.onPlayerViewDestroyed()
                    end(watch, client)
                    repeat(3) { tick() }

                    assertTrue(backend.asked.isEmpty())
                    verify(client, never()).stop()
                }
            }
        }
    }

    /** Cubic r11: the cast session ended with the player closed: the watch ends (it never asks again), not a gap. */
    @Test fun `the closed player's watch ends with the cast session`() = runTest(dispatcher) {
        val backend = Backend { listed(live) }
        val client = receiver()
        var current: RemoteMediaClient? = client
        var up = true
        val scope = CoroutineScope(dispatcher)
        try {
            val watch = CastRemovalWatch(scope, sessionUp = { up }) { current }.apply { loaded = live to CAST_URL }
            val (vm, _) = playing(backend, watch)
            vm.onPlayerViewDestroyed()
            vm.viewModelScope.cancel()
            tick()
            assertEquals(listOf<String?>(live), backend.asked)

            current = null; up = false                           // the session ended
            tick()
            current = client; up = true                          // a gap would resume asking here; an end never does
            repeat(2) { tick() }

            assertEquals(listOf<String?>(live), backend.asked)
        } finally { scope.cancel() }
    }

    /** Codex r4: the screen closed while the receiver had not yet reported this app's load; it plays it by the tick. */
    @Test fun `a close during a pending cast load still hands the watch over`() = runTest(dispatcher) {
        var answer = listed(live)
        val backend = Backend { answer }
        val client = receiver()
        val ours = client.mediaInfo
        whenever(client.mediaInfo).thenReturn(null)                // the load is out, no media status yet
        whenever(client.hasMediaSession()).thenReturn(false)
        watching(client, live) { watch ->
            val (vm, _) = playing(backend, watch)
            vm.onPlayerViewDestroyed()
            vm.viewModelScope.cancel()

            whenever(client.mediaInfo).thenReturn(ours)            // the receiver took it
            whenever(client.hasMediaSession()).thenReturn(true)
            answer = listed()                                      // and the admin removed it
            tick()

            verify(client).stop()
        }
    }

    /** Codex r5: a gap in the receiver's status (a reconnect) neither ends the watch nor stops the TV. */
    @Test fun `a receiver status gap keeps the closed player's watch`() = runTest(dispatcher) {
        var answer = listed(live)
        val backend = Backend { answer }
        val client = receiver()
        val ours = client.mediaInfo
        watching(client, live) { watch ->
            val (vm, _) = playing(backend, watch)
            vm.onPlayerViewDestroyed()
            vm.viewModelScope.cancel()

            whenever(client.mediaInfo).thenReturn(null)            // no status for a tick
            tick()
            verify(client, never()).stop()

            whenever(client.mediaInfo).thenReturn(ours)            // back, and the admin removed it
            answer = listed()
            tick()
            verify(client).stop()
        }
    }

    /** Codex r6: the screen closed while the cast client was briefly unavailable (a reconnect); it returns by the tick. */
    @Test fun `a close during a client gap still hands the watch over`() = runTest(dispatcher) {
        val backend = Backend { listed() }                       // the admin removed it
        val client = receiver()
        var current: RemoteMediaClient? = null                   // a reconnect: no client yet
        val scope = CoroutineScope(dispatcher)
        try {
            val watch = CastRemovalWatch(scope) { current }.apply { loaded = live to CAST_URL }
            val (vm, _) = playing(backend, watch)
            vm.onPlayerViewDestroyed()                           // the handover, with no client right now
            vm.viewModelScope.cancel()

            current = client                                     // back
            tick()

            verify(client).stop()
        } finally { scope.cancel() }
    }

    /** Codex r5: a phone recovery in progress does not pause the check of the live the TV plays. */
    @Test fun `a phone recovery keeps checking the live the TV plays`() = runTest(dispatcher) {
        val backend = Backend { listed() }                       // nothing live: the incident waits; a tick is a removal
        val client = receiver()
        watching(client, live) { watch ->
            val (vm, _) = playing(backend, watch)
            vm.cancelAfter {
                vm.markCurrentComplete()                         // the phone's recovery incident starts
                runCurrent()
                tick()

                assertTrue("the tick asked for the TV's live: ${backend.asked}", live in backend.asked)
                assertEquals(StreamState.ContentUnavailable, vm.state.value.streamState)
                val asked = backend.asked.size
                repeat(3) { tick() }
                assertEquals("the removal ended the incident too", asked, backend.asked.size)
            }
        }
    }

    /** Codex r4: a phone re-resolve that failed leaves the TV playing this app's load; the tick still checks it. */
    @Test fun `a failed phone resolve keeps checking a link the TV still plays`() = runTest(dispatcher) {
        val backend = Backend { listed() }
        val client = receiver()
        val repository = FakePlayerRepository()
        watching(client, live) { watch ->
            val vm = playingLink(backend, watch, repository)
            vm.cancelAfter {
                repository.alwaysFail = true
                vm.forceRefreshCurrentStream()
                advanceTimeBy(30_000); runCurrent()
                assertTrue("phone failed: ${vm.state.value.streamState}", vm.state.value.streamState is StreamState.Error)

                backend.link = { throw http(404) }                 // the admin removed it
                tick(); tick()

                assertEquals(StreamState.ContentUnavailable, vm.state.value.streamState)
            }
        }
    }

    @Test fun `a closed player with nothing of its own cast stops the heartbeat as today`() = runTest(dispatcher) {
        for ((client, castId) in listOf(receiver() to null, receiver() to "lec00000001", receiver(playing = false) to live, null to live)) {
            val backend = Backend { listed() }
            watching(client, castId) { watch ->
                val (vm, _) = playing(backend, watch)
                vm.cancelAfter {
                    vm.onPlayerViewDestroyed()
                    repeat(3) { tick() }

                    assertTrue(backend.asked.isEmpty())
                    client?.let { verify(it, never()).stop() }
                }
            }
        }
    }

    /** The player reopened on the same live watches it again itself: one ask per tick, not two. */
    @Test fun `a reopened player takes the watch back`() = runTest(dispatcher) {
        val backend = Backend { listed(live) }
        watching(receiver(), live) { watch ->
            val (vm, _) = playing(backend, watch)
            vm.cancelAfter {
                vm.onPlayerViewDestroyed()
                vm.loadVideo(videoId = live, sourceChannelId = channel, liveChannelId = channel)
                advanceTimeBy(1_000); runCurrent()
                backend.asked.clear()
                tick()

                assertEquals(listOf<String?>(live), backend.asked)
            }
        }
    }

    // Owner ruling: an exact live link (a VIDEO item of a LIVE category, liveLink = true) stops too when removed.

    @Test fun `a served link (204) keeps playing`() = runTest(dispatcher) {
        val backend = Backend { error("no channel asks for a link") }
        val vm = playingLink(backend)
        vm.cancelAfter {
            tick(); tick()

            assertEquals(listOf(live, live), backend.linkAsked)
            assertTrue(backend.asked.isEmpty())
            assertTrue(vm.state.value.streamState is StreamState.Ready)
        }
    }

    @Test fun `a removed link (404) stops playback as unavailable`() = runTest(dispatcher) {
        val backend = Backend { error("no channel asks for a link") }.apply { link = { throw http(404) } }
        val vm = playingLink(backend)
        vm.cancelAfter {
            tick()

            assertEquals(StreamState.ContentUnavailable, vm.state.value.streamState)
            tick()
            assertEquals("no more ticks after the stop", listOf(live), backend.linkAsked)
        }
    }

    @Test fun `a busy, unreachable or refusing backend keeps a link playing`() = runTest(dispatcher) {
        val failures = ArrayDeque(listOf(http(503), java.io.IOException("offline"), http(500), http(400)))
        val backend = Backend { error("no channel asks for a link") }.apply { link = { throw failures.removeFirst() } }
        val vm = playingLink(backend)
        vm.cancelAfter {
            repeat(4) { tick() }

            assertEquals(4, backend.linkAsked.size)
            assertTrue(vm.state.value.streamState is StreamState.Ready)
        }
    }

    @Test fun `no link ticks for an item without liveLink`() = runTest(dispatcher) {
        val backend = Backend { listed(live) }
        val (vm, _) = playing(backend)                           // a live-channel item asks its channel, never the link
        vm.cancelAfter {
            tick()
            vm.loadVideo(videoId = live)                         // an ordinary video
            advanceTimeBy(1_000); runCurrent()
            repeat(3) { tick() }

            assertTrue(backend.linkAsked.isEmpty())
        }
    }

    @Test fun `no link ticks after the item changes`() = runTest(dispatcher) {
        val backend = Backend { error("no channel asks for a link") }
        val vm = playingLink(backend)
        vm.cancelAfter {
            vm.loadVideo(videoId = "lec00000001")
            advanceTimeBy(1_000); runCurrent()
            repeat(3) { tick() }

            assertTrue(backend.linkAsked.isEmpty())
        }
    }
}
