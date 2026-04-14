package com.albunyaan.tube.ui.shorts

import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.VideoTrack
import com.albunyaan.tube.player.PlayerRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Unit tests for [PlayerBinder].
 *
 * Primary goal: prove the P0 rapid-swipe race is fixed.
 *
 * A slow resolve of video A followed by a fast resolve of video B must NOT
 * cause A's MediaSource to be applied on top of B. The binder's generation
 * token must short-circuit the stale resolve.
 *
 * ### Test isolation
 *
 * Media3's real [androidx.media3.exoplayer.ExoPlayer] can't be instantiated
 * in a JVM unit test (its static init requires Android framework state), so
 * PlayerBinder exposes an internal [PlayerBinder.PlayerOps] seam that we
 * substitute with a recording fake. The PlayerView attach path is likewise
 * routed through [PlayerBinder.PlayerViewAttach] so we don't depend on a
 * real PlayerView (Robolectric is used only to make the mocked type
 * loadable — we never construct one).
 *
 * Dispatchers.Main is set to an [UnconfinedTestDispatcher] so PlayerBinder's
 * internal scope (Dispatchers.Main.immediate) is driven by the test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PlayerBinderTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * A [PlayerRepository] whose resolves suspend on externally-controlled
     * [CompletableDeferred]s, letting the test complete resolutions out of
     * order to simulate a network race.
     */
    private class TestPlayerRepository : PlayerRepository {
        private val deferred = mutableMapOf<String, CompletableDeferred<ResolvedStreams?>>()

        override suspend fun resolveStreams(
            videoId: String,
            forceRefresh: Boolean
        ): ResolvedStreams? = deferred.getOrPut(videoId) { CompletableDeferred() }.await()

        fun complete(videoId: String, result: ResolvedStreams?) {
            deferred.getOrPut(videoId) { CompletableDeferred() }.complete(result)
        }
    }

    /**
     * Records every [PlayerBinder.PlayerOps] call so tests can assert which
     * player mutations happened and in what order.
     */
    private class RecordingPlayerOps : PlayerBinder.PlayerOps {
        val calls = mutableListOf<String>()
        val mediaSources = mutableListOf<MediaSource>()
        private var playWhenReadyState: Boolean = false

        override fun stop() { calls += "stop" }
        override fun clearMediaItems() { calls += "clearMediaItems" }
        override fun setMediaSource(source: MediaSource) {
            calls += "setMediaSource"
            mediaSources += source
        }
        override fun setRepeatModeOne() { calls += "setRepeatModeOne" }
        override fun prepare() { calls += "prepare" }
        override fun setPlayWhenReady(value: Boolean) {
            calls += "setPlayWhenReady=$value"
            playWhenReadyState = value
        }
        override fun getPlayWhenReady(): Boolean = playWhenReadyState
        override fun release() { calls += "release" }
    }

    private class RecordingAttach : PlayerBinder.PlayerViewAttach {
        data class Event(val view: PlayerView, val attached: Boolean)
        val events = mutableListOf<Event>()
        override fun attach(view: PlayerView, attached: Boolean) {
            events += Event(view, attached)
        }
    }

    /** Build a resolved-streams payload whose [buildProgressiveSource] will succeed. */
    private fun resolved(id: String): ResolvedStreams = ResolvedStreams(
        streamId = id,
        videoTracks = listOf(
            VideoTrack(
                url = "https://example.test/$id.mp4",
                mimeType = "video/mp4",
                width = 720,
                height = 1280,
                bitrate = 1_000_000,
                qualityLabel = "720p",
                fps = 30,
                isVideoOnly = false
            )
        ),
        audioTracks = emptyList(),
        durationSeconds = 30
    )

    private fun newBinder(
        repo: PlayerRepository,
        ops: PlayerBinder.PlayerOps = RecordingPlayerOps(),
        attach: PlayerBinder.PlayerViewAttach = RecordingAttach()
    ) = PlayerBinder(repo, ops, attach)

    @Test
    fun rapidBind_dropsStaleResolveWithoutTouchingPlayer() = runTest(dispatcher) {
        val ops = RecordingPlayerOps()
        val attach = RecordingAttach()
        val repo = TestPlayerRepository()
        val binder = newBinder(repo, ops, attach)

        // PlayerView is never touched by our test doubles — but the type is
        // required by bind()'s signature. Robolectric makes the class loadable
        // so Mockito's inline mock maker can produce one.
        val viewA: PlayerView = org.mockito.kotlin.mock()
        val viewB: PlayerView = org.mockito.kotlin.mock()

        // Bind A — resolution suspends.
        binder.bind(viewA, "A")
        // Bind B before A completes — generation bumps to 2; A is now stale.
        binder.bind(viewB, "B")

        // Complete A AFTER B (the race: A's slow resolve returns LAST).
        repo.complete("A", resolved("A"))
        repo.complete("B", resolved("B"))

        dispatcher.scheduler.advanceUntilIdle()

        // Exactly ONE setMediaSource — B's. A's stale resolve was dropped.
        assertEquals(
            "Only B's source must be applied; A was stale",
            1, ops.calls.count { it == "setMediaSource" }
        )
        assertEquals("prepare must be called once — for B", 1, ops.calls.count { it == "prepare" })

        // Attach lifecycle: viewA attached, then detached when B bound, then B attached.
        val detachedA = attach.events.any { it.view === viewA && !it.attached }
        val attachedB = attach.events.any { it.view === viewB && it.attached }
        assertTrue("viewA must be detached on rebind", detachedA)
        assertTrue("viewB must be attached on rebind", attachedB)
    }

    @Test
    fun bindPerformsSynchronousViewAttachAndPlayerReset() = runTest(dispatcher) {
        val ops = RecordingPlayerOps()
        val attach = RecordingAttach()
        val repo = TestPlayerRepository()
        val binder = newBinder(repo, ops, attach)

        val view: PlayerView = org.mockito.kotlin.mock()
        // Never complete the resolve — we're only asserting the sync path.
        binder.bind(view, "X")

        assertTrue("view must be attached synchronously", attach.events.any {
            it.view === view && it.attached
        })
        assertTrue("stop must fire synchronously", ops.calls.contains("stop"))
        assertTrue("clearMediaItems must fire synchronously", ops.calls.contains("clearMediaItems"))
        assertFalse(
            "setMediaSource must NOT fire until resolve completes",
            ops.calls.contains("setMediaSource")
        )
    }

    @Test
    fun release_cancelsPendingResolveAndSkipsPlayerApply() = runTest(dispatcher) {
        val ops = RecordingPlayerOps()
        val attach = RecordingAttach()
        val repo = TestPlayerRepository()
        val binder = newBinder(repo, ops, attach)

        val view: PlayerView = org.mockito.kotlin.mock()
        binder.bind(view, "Y")
        binder.release()

        // Complete AFTER release — the cancelled scope must swallow the result.
        repo.complete("Y", resolved("Y"))
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(
            "No setMediaSource after release",
            ops.calls.contains("setMediaSource")
        )
        assertFalse("No prepare after release", ops.calls.contains("prepare"))
        assertTrue("release must be forwarded to PlayerOps", ops.calls.contains("release"))
    }

    @Test
    fun resolveReturningNull_doesNotTouchPlayerMediaSource() = runTest(dispatcher) {
        val ops = RecordingPlayerOps()
        val attach = RecordingAttach()
        val repo = TestPlayerRepository()
        val binder = newBinder(repo, ops, attach)

        val view: PlayerView = org.mockito.kotlin.mock()
        binder.bind(view, "N")
        repo.complete("N", null)
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(ops.calls.contains("setMediaSource"))
        assertFalse(ops.calls.contains("prepare"))
    }

}
