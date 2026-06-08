package com.albunyaan.tube.ui.shorts

import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.Priority
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.VideoTrack
import com.albunyaan.tube.player.PlayerRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
import org.mockito.kotlin.doReturn
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
     *
     * For the C2 fix, [completeExceptionally] propagates a thrown exception
     * (typically [com.albunyaan.tube.player.ContentUnavailableException]) so
     * we can verify the shorts binder treats archived content the same way
     * it treats a null resolve: emit failureEvents, never touch the player.
     */
    private class TestPlayerRepository : PlayerRepository {
        data class ResolveCall(
            val videoId: String,
            val forceRefresh: Boolean,
            val sourceChannelId: String?,
        )

        private val deferred = mutableMapOf<String, CompletableDeferred<ResolvedStreams?>>()
        val calls = mutableListOf<ResolveCall>()

        override suspend fun resolveStreams(
            videoId: String,
            forceRefresh: Boolean,
            priority: Priority,
            sourceChannelId: String?,
        ): ResolvedStreams? {
            calls += ResolveCall(videoId, forceRefresh, sourceChannelId)
            return deferred.getOrPut(videoId) { CompletableDeferred() }.await()
        }

        fun complete(videoId: String, result: ResolvedStreams?) {
            deferred.getOrPut(videoId) { CompletableDeferred() }.complete(result)
        }

        fun completeExceptionally(videoId: String, error: Throwable) {
            deferred.getOrPut(videoId) { CompletableDeferred() }.completeExceptionally(error)
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
    }

    private class RecordingAttach : PlayerBinder.PlayerViewAttach {
        data class Event(val view: PlayerView, val attached: Boolean)
        val events = mutableListOf<Event>()
        override fun attach(view: PlayerView, attached: Boolean) {
            events += Event(view, attached)
        }
    }

    /**
     * Build a resolved-streams payload whose [buildProgressiveSource] will
     * succeed. Includes one (video-only) muxed track plus one audio track so
     * the staleness-gate tests can hand [PlayerBinder.switchAudioTrack] a real
     * [AudioTrack] without re-resolving.
     */
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
        audioTracks = listOf(audioTrack(id)),
        durationSeconds = 30
    )

    /** A muxed-friendly audio track for [id] (progressive fallback can merge it). */
    private fun audioTrack(id: String): AudioTrack = AudioTrack(
        url = "https://example.test/$id-audio.mp4",
        mimeType = "audio/mp4",
        bitrate = 128_000,
        codec = "mp4a.40.2",
        language = "en"
    )

    private fun newBinder(
        repo: PlayerRepository,
        ops: PlayerBinder.PlayerOps = RecordingPlayerOps(),
        attach: PlayerBinder.PlayerViewAttach = RecordingAttach(),
        factoryProvider: com.albunyaan.tube.player.SegmentDataSourceFactoryProvider =
            org.mockito.kotlin.mock {
                on { forStreams(org.mockito.kotlin.any()) } doReturn org.mockito.kotlin.mock()
            },
    ) = PlayerBinder(
        repo,
        ops,
        attach,
        // Stub forStreams() so any code path that drops into the progressive
        // fallback (e.g. buildProgressiveSource) gets a non-null DataSource
        // factory instead of NPEing on Mockito's null default.
        factoryProvider,
    )

    /**
     * A resolved-streams payload exposing TWO audio languages plus a
     * video-only track, so the source builders must *pick* an audio track
     * (no muxed shortcut). Lets a test verify that a pinned dub language
     * survives a quality switch.
     */
    private fun multiLangResolved(id: String): ResolvedStreams = ResolvedStreams(
        streamId = id,
        videoTracks = listOf(
            VideoTrack(
                url = "https://example.test/$id-video.mp4",
                mimeType = "video/mp4",
                width = 720,
                height = 1280,
                bitrate = 1_000_000,
                qualityLabel = "720p",
                fps = 30,
                isVideoOnly = true
            )
        ),
        audioTracks = listOf(
            AudioTrack(
                url = "https://example.test/$id-audio-en.mp4",
                mimeType = "audio/mp4",
                bitrate = 128_000,
                codec = "mp4a.40.2",
                language = "en"
            ),
            AudioTrack(
                url = "https://example.test/$id-audio-ar.mp4",
                mimeType = "audio/mp4",
                bitrate = 128_000,
                codec = "mp4a.40.2",
                language = "ar"
            ),
        ),
        durationSeconds = 30
    )

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
    fun forceRefreshCurrent_refreshesBoundVideoNotMostRecentCacheEntry() = runTest(dispatcher) {
        val ops = RecordingPlayerOps()
        val attach = RecordingAttach()
        val repo = TestPlayerRepository()
        val binder = newBinder(repo, ops, attach)

        val viewA: PlayerView = org.mockito.kotlin.mock()
        val viewB: PlayerView = org.mockito.kotlin.mock()

        binder.bind(viewA, "A")
        repo.complete("A", resolved("A"))
        dispatcher.scheduler.advanceUntilIdle()

        binder.bind(viewB, "B")
        repo.complete("B", resolved("B"))
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue("A must be cached for the recency trap", binder.resolvedStreamsFor("A") != null)
        binder.forceRefreshCurrent()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "Refresh must target the bound short, not the most-recent cache entry",
            TestPlayerRepository.ResolveCall("B", forceRefresh = true, sourceChannelId = null),
            repo.calls.last(),
        )
    }

    @Test
    fun forceRefreshCurrent_keepsExistingMediaWhileResolveIsPending() = runTest(dispatcher) {
        val ops = RecordingPlayerOps()
        val attach = RecordingAttach()
        val repo = TestPlayerRepository()
        val binder = newBinder(repo, ops, attach)

        val view: PlayerView = org.mockito.kotlin.mock()
        binder.bind(view, "A")
        repo.complete("A", resolved("A"))
        dispatcher.scheduler.advanceUntilIdle()

        val stopCountBeforeRefresh = ops.calls.count { it == "stop" }
        val clearCountBeforeRefresh = ops.calls.count { it == "clearMediaItems" }

        binder.forceRefreshCurrent()
        dispatcher.scheduler.runCurrent()

        assertEquals(
            "Force refresh must not blank the current short while new URLs resolve",
            stopCountBeforeRefresh,
            ops.calls.count { it == "stop" },
        )
        assertEquals(
            "Force refresh must keep the existing media source until replacement is ready",
            clearCountBeforeRefresh,
            ops.calls.count { it == "clearMediaItems" },
        )
        assertEquals(
            "Force refresh still needs to bypass the stream cache",
            TestPlayerRepository.ResolveCall("A", forceRefresh = true, sourceChannelId = null),
            repo.calls.last(),
        )
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
    fun cancelScope_cancelsPendingResolveAndLeavesPlayerUntouched() = runTest(dispatcher) {
        // cancelScope() aborts its in-flight bind coroutine without releasing
        // the VM-owned player. Proves a late resolve
        // after fragment teardown cannot mutate the surviving player.
        val ops = RecordingPlayerOps()
        val attach = RecordingAttach()
        val repo = TestPlayerRepository()
        val binder = newBinder(repo, ops, attach)

        val view: PlayerView = org.mockito.kotlin.mock()
        binder.bind(view, "Z")
        binder.cancelScope()

        // Complete AFTER cancelScope — the cancelled scope must swallow the result.
        repo.complete("Z", resolved("Z"))
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(
            "No setMediaSource after cancelScope",
            ops.calls.contains("setMediaSource")
        )
        assertFalse("No prepare after cancelScope", ops.calls.contains("prepare"))
    }

    @Test
    fun bind_afterCancelScope_isSilentNoOp() = runTest(dispatcher) {
        // bindInternal previously threw IllegalStateException when called
        // after cancelScope to surface programmer error loudly. The behaviour
        // was changed to an early-return after a P2 review finding: a
        // late-arriving callback (e.g. a ViewPager scroll posted on the main
        // queue during onDestroyView teardown) hitting bind() is a benign
        // race, not a misuse worth crashing the host activity. This test
        // now pins the no-op contract.
        val ops = RecordingPlayerOps()
        val attach = RecordingAttach()
        val repo = TestPlayerRepository()
        val binder = newBinder(repo, ops, attach)

        binder.cancelScope()

        val view: PlayerView = org.mockito.kotlin.mock()
        binder.bind(view, "Q")
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(
            "bind after cancelScope must not attach the view",
            attach.events.any { it.attached }
        )
        assertFalse("bind after cancelScope must not touch player", ops.calls.contains("stop"))
        assertFalse(
            "bind after cancelScope must not run setMediaSource",
            ops.calls.contains("setMediaSource")
        )
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

    /**
     * C2 fix: archived shorts must not play. The chokepoint gate inside
     * [com.albunyaan.tube.player.DefaultPlayerRepository.resolveStreams]
     * throws [com.albunyaan.tube.player.ContentUnavailableException] for
     * archived ids. PlayerBinder wraps the repo call in `runCatching{}.getOrNull()`,
     * so the exception is treated identically to a null resolve: the player
     * never receives a media source, and [PlayerBinder.failureEvents] emits
     * the failing id so the fragment can advance the pager (existing UX —
     * shorts skip past the unplayable page).
     */
    @Test
    fun resolveThrowingContentUnavailable_emitsFailureAndDoesNotTouchPlayer() = runTest(dispatcher) {
        val ops = RecordingPlayerOps()
        val attach = RecordingAttach()
        val repo = TestPlayerRepository()
        val binder = newBinder(repo, ops, attach)

        // Subscribe BEFORE bind() so we don't miss the emission. backgroundScope
        // (provided by runTest) is auto-cancelled at end of test, so we don't
        // leak the collector. UnconfinedTestDispatcher means the collector
        // starts running synchronously before bind() suspends.
        val received = mutableListOf<String>()
        backgroundScope.launch {
            binder.failureEvents.collect { received += it }
        }

        val view: PlayerView = org.mockito.kotlin.mock()
        binder.bind(view, "ARCHIVED")
        repo.completeExceptionally(
            "ARCHIVED",
            com.albunyaan.tube.player.ContentUnavailableException("ARCHIVED"),
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "failureEvents must emit the archived videoId",
            listOf("ARCHIVED"),
            received,
        )
        assertFalse(
            "setMediaSource must NOT fire when resolve throws ContentUnavailableException",
            ops.calls.contains("setMediaSource"),
        )
        assertFalse(
            "prepare must NOT fire when resolve throws ContentUnavailableException",
            ops.calls.contains("prepare"),
        )
    }

    /**
     * Critical race fix: [PlayerBinder.switchAudioTrack] must reject a switch
     * for a video that is no longer bound, even when that video still has a
     * live entry in [PlayerBinder.resolvedStreamsFor]'s cache.
     *
     * The audio-language picker captures its video id at open time and can
     * resolve a selection AFTER the user has swiped to a different short. Both
     * A and B are resolved here so `resolvedCache["A"]` is non-null — meaning
     * the existing `resolvedStreamsFor(videoId) ?: return` early-return does
     * NOT cover this case. Only the `if (videoId != boundVideoId) return`
     * staleness gate stops A's media from being swapped onto the shared player
     * that is now showing B. Without that gate, this test would observe a
     * `setMediaSource` (count + 1) for the stale switch.
     */
    @Test
    fun switchAudioTrack_rejectsStaleSwitchForNonBoundVideo() = runTest(dispatcher) {
        val ops = RecordingPlayerOps()
        val attach = RecordingAttach()
        val repo = TestPlayerRepository()
        val binder = newBinder(repo, ops, attach)

        val viewA: PlayerView = org.mockito.kotlin.mock()
        val viewB: PlayerView = org.mockito.kotlin.mock()

        // Bind + resolve A, then bind + resolve B. After this, resolvedCache
        // holds BOTH A and B, but boundVideoId == "B".
        val streamsA = resolved("A")
        binder.bind(viewA, "A")
        repo.complete("A", streamsA)
        dispatcher.scheduler.advanceUntilIdle()

        binder.bind(viewB, "B")
        repo.complete("B", resolved("B"))
        dispatcher.scheduler.advanceUntilIdle()

        // Sanity: A is still cached (so the null-cache early-return does NOT
        // fire — the staleness gate is the only thing that can reject this).
        assertTrue("A must still be cached", binder.resolvedStreamsFor("A") != null)

        val mediaSourcesBefore = ops.calls.count { it == "setMediaSource" }

        // Stale switch: pick an audio track from A while B is bound.
        binder.switchAudioTrack("A", streamsA.audioTracks.first())
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "Stale switchAudioTrack for a non-bound video must be a no-op",
            mediaSourcesBefore,
            ops.calls.count { it == "setMediaSource" },
        )
    }

    /**
     * Parallel to [switchAudioTrack_rejectsStaleSwitchForNonBoundVideo] for
     * [PlayerBinder.switchQuality]: a quality pick for short A must not swap
     * media onto the shared player once the user has swiped to B, even though
     * A remains in the resolved-streams cache. Without the
     * `if (videoId != boundVideoId) return` gate this would fire a stale
     * `setMediaSource`.
     */
    @Test
    fun switchQuality_rejectsStaleSwitchForNonBoundVideo() = runTest(dispatcher) {
        val ops = RecordingPlayerOps()
        val attach = RecordingAttach()
        val repo = TestPlayerRepository()
        val binder = newBinder(repo, ops, attach)

        val viewA: PlayerView = org.mockito.kotlin.mock()
        val viewB: PlayerView = org.mockito.kotlin.mock()

        binder.bind(viewA, "A")
        repo.complete("A", resolved("A"))
        dispatcher.scheduler.advanceUntilIdle()

        binder.bind(viewB, "B")
        repo.complete("B", resolved("B"))
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue("A must still be cached", binder.resolvedStreamsFor("A") != null)

        val mediaSourcesBefore = ops.calls.count { it == "setMediaSource" }

        // Stale switch: cap quality on A while B is bound.
        binder.switchQuality("A", 720)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "Stale switchQuality for a non-bound video must be a no-op",
            mediaSourcesBefore,
            ops.calls.count { it == "setMediaSource" },
        )
    }

    /**
     * Fix B regression: a user who pins a non-default dub language via
     * [PlayerBinder.switchAudioTrack] must keep that language after a
     * subsequent [PlayerBinder.switchQuality]. Before the fix, switchQuality
     * rebuilt from the *unfiltered* resolved streams, so the source builder
     * re-picked audio by max bitrate and reverted the pinned dub.
     *
     * We observe the filtering at the [SegmentDataSourceFactoryProvider.forStreams]
     * seam: it receives the *effective* (filtered) ResolvedStreams the builder
     * works from. After pinning "ar", switchQuality must hand it streams whose
     * audioTracks are exactly the "ar" track.
     *
     * The pin survives because switchAudioTrack records the sticky language —
     * but a re-resolve (URL expiry / rebuffer recovery, simulated here via
     * forceRefreshCurrent) repopulates the cache with the FULL track list.
     * That re-resolve is what makes Fix B load-bearing: without the
     * sticky-language filter in switchQuality, the builder would re-pick audio
     * by bitrate from the full list and revert the dub. The re-resolve step is
     * essential — without it the cache already holds only the pinned track and
     * the test would pass vacuously.
     */
    @Test
    fun switchQuality_keepsPinnedDubLanguage() = runTest(dispatcher) {
        val ops = RecordingPlayerOps()
        val attach = RecordingAttach()
        val repo = TestPlayerRepository()
        val factoryProvider = org.mockito.kotlin.mock<com.albunyaan.tube.player.SegmentDataSourceFactoryProvider> {
            on { forStreams(org.mockito.kotlin.any()) } doReturn org.mockito.kotlin.mock()
        }
        val binder = newBinder(repo, ops, attach, factoryProvider)

        val view: PlayerView = org.mockito.kotlin.mock()
        val streams = multiLangResolved("A")
        binder.bind(view, "A")
        repo.complete("A", streams)
        dispatcher.scheduler.advanceUntilIdle()

        // Pin the Arabic dub (a non-default, non-max-bitrate-distinguishable track).
        binder.switchAudioTrack("A", streams.audioTracks.first { it.language == "ar" })
        dispatcher.scheduler.advanceUntilIdle()

        // Re-resolve A: repopulates the cache with the FULL (en + ar) track list,
        // mimicking a URL-expiry/rebuffer recovery. The sticky "ar" pin persists.
        binder.forceRefreshCurrent()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            "Re-resolve must repopulate cache with both languages",
            listOf("en", "ar"),
            binder.resolvedStreamsFor("A")!!.audioTracks.map { it.language },
        )

        // Switch quality (AUTO cap -> 0 so the cap-clearing branch is exercised).
        binder.switchQuality("A", 0)
        dispatcher.scheduler.advanceUntilIdle()

        val captor = org.mockito.kotlin.argumentCaptor<ResolvedStreams>()
        org.mockito.kotlin.verify(factoryProvider, org.mockito.kotlin.atLeastOnce())
            .forStreams(captor.capture())

        // The MOST RECENT forStreams call belongs to switchQuality. Its streams
        // must carry only the pinned "ar" audio track despite the cache holding both.
        val effective = captor.lastValue
        assertEquals(
            "switchQuality must filter audio to the pinned dub language",
            listOf("ar"),
            effective.audioTracks.map { it.language },
        )
    }

}
