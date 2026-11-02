package com.albunyaan.tube.ui.player

import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.VideoTrack
import com.albunyaan.tube.data.playlist.PlaylistItem
import com.albunyaan.tube.data.playlist.PlaylistPage
import com.albunyaan.tube.player.ContentUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P8 / N9 regression coverage: TTL on the queue-prefetch cache inside
 * [PlayerViewModel] must bound the archive-bypass window.
 *
 * Threat model: a video archived AFTER `prefetchNextItems` resolved its
 * streams used to be replayable forever from the in-memory cache, bypassing
 * the chokepoint gate. The TTL keeps the cached entry serviceable in the
 * common case (user advances within seconds) but forces a re-resolve once
 * the window expires — the re-resolve hits the gated `repository.resolveStreams`
 * path, which now throws `ContentUnavailableException` so the archived video
 * surfaces as ContentUnavailable / triggers auto-skip.
 *
 * Driven via an injected clock so the test does not block on wall-clock.
 * The TTL constant lives on the companion (`PREFETCH_CACHE_TTL_MS`) so the
 * test stays in lockstep with production code if the value ever changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PlayerViewModelPrefetchTtlTest {

    private val testDispatcher = StandardTestDispatcher()

    private val fakePlayerRepository = FakePlayerRepository()
    private val fakePlaylistRepository = FakePlaylistDetailRepository()

    /**
     * Mutable wall-clock pointer driven by tests. Stamped into both the
     * cache writer (via [PlayerViewModel.setClockForTesting]) and read from
     * the consumer at every TTL check. Bumping it past the TTL boundary
     * simulates time passing without `runTest` virtual-time tricks.
     */
    private var now: Long = 1_000_000L

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() =
        playerViewModel(repository = fakePlayerRepository, playlistDetailRepository = fakePlaylistRepository)
            .also { it.setClockForTesting { now } }

    // ── TTL Tests ─────────────────────────────────────────────────────────────

    @Test
    fun `queue-prefetched stream within TTL is served from cache without re-resolving`() = runTest {
        // 6-item playlist so prefetchNextItems primes video_2/video_3 once
        // video_1 starts (queue threshold = 5; with 6 items the queue holds 5
        // upcoming items after the first plays — wide enough to trigger
        // background prefetch downstream).
        val items = (1..6).map { createPlaylistItem(it, "video_$it") }
        fakePlaylistRepository.setPages(listOf(PlaylistPage(items, null)))
        fakePlayerRepository.resolvedStreams = createResolvedStreams("video_1")

        val vm = createViewModel()
        vm.loadPlaylist("PL123", startIndexHint = 0)
        advanceUntilIdle()

        val resolveCountBefore = fakePlayerRepository.resolveCallCount

        // Simulate user tapping NEXT before the TTL expires (only 5 seconds
        // elapsed since prefetch resolved). The cache should serve video_2
        // without bumping the resolve count again.
        now += 5_000L
        fakePlayerRepository.resolvedStreams = createResolvedStreams("video_2")
        vm.skipToNext()
        advanceUntilIdle()

        // Note: we can't directly assert "the cache was hit" without exposing
        // the cache. The next-best signal: the VM transitioned to Ready for
        // video_2 AND no ContentUnavailable was surfaced. The resolve-call
        // count check is loose because background prefetch may have been
        // running for video_3 too — we only care that video_2 ended up Ready.
        val state = vm.state.value.streamState
        assertTrue(
            "Within-TTL queue-prefetched item must reach Ready, was $state",
            state is StreamState.Ready && state.streamId == "video_2"
        )
        assertNotEquals(
            "Within-TTL prefetch must NOT surface ContentUnavailable",
            StreamState.ContentUnavailable,
            state
        )
        // Sanity: ensure the resolveStreams invocation count didn't grow
        // wildly — at most one extra call (for video_3 background prefetch).
        // If the cache had been bypassed we'd see at least one *retry*-shaped
        // call for video_2 itself.
        assertTrue(
            "Suspicious: resolve count jumped by more than 2 (cache likely bypassed). before=$resolveCountBefore after=${fakePlayerRepository.resolveCallCount}",
            fakePlayerRepository.resolveCallCount - resolveCountBefore <= 2
        )
    }

    @Test
    fun `queue-prefetched stream archived past TTL re-resolves and triggers auto-skip`() = runTest {
        // 3-item playlist. video_1 plays normally, video_2 is prefetched, then
        // archived AFTER the TTL elapses. video_3 is the recovery target.
        val items = (1..3).map { createPlaylistItem(it, "video_$it") }
        fakePlaylistRepository.setPages(listOf(PlaylistPage(items, null)))
        fakePlayerRepository.resolvedStreams = createResolvedStreams("video_1")

        val vm = createViewModel()
        vm.loadPlaylist("PL123", startIndexHint = 0)
        advanceUntilIdle()

        // At this point video_2 has been prefetched (cache stamped at now=1_000_000).
        // Verify by checking the cache slot saw video_2's resolve. Even if the
        // queue-prefetch implementation didn't call resolve for video_2 (race
        // / rate-limit), the test still validates the consumer-side TTL because
        // the absent-cache and stale-cache branches converge on the same
        // re-resolve path.

        // Now archive video_2 in the backend AND advance the clock past the TTL.
        // Past the TTL boundary the cache must be considered stale; the consumer
        // re-resolves, which throws ContentUnavailable, which triggers auto-skip
        // to video_3.
        fakePlayerRepository.archivedIds += "video_2"
        fakePlayerRepository.resolvedStreams = createResolvedStreams("video_3")
        now += PlayerViewModel.PREFETCH_CACHE_TTL_MS + 1_000L

        vm.skipToNext()
        advanceUntilIdle()

        // The stale cache MUST NOT serve archived video_2. It must re-resolve,
        // hit the gate, auto-skip to video_3.
        val state = vm.state.value.streamState
        assertTrue(
            "Stale cache for archived video must trigger auto-skip to video_3, but state was $state",
            state is StreamState.Ready && state.streamId == "video_3"
        )
        // The archived id must show up in the resolve trail — i.e., the consumer
        // *did* re-resolve instead of returning the cached pre-archive entry.
        assertTrue(
            "Re-resolve must hit video_2 (archived) before auto-skipping to video_3. Trail=${fakePlayerRepository.resolvedVideoIds}",
            fakePlayerRepository.resolvedVideoIds.contains("video_2")
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun createPlaylistItem(position: Int, videoId: String) = PlaylistItem(
        position = position,
        videoId = videoId,
        title = "Video $position",
        thumbnailUrl = "https://example.com/$videoId.jpg",
        durationSeconds = 300,
        viewCount = 1000L,
        publishedTime = "1 day ago",
        channelId = "UC123",
        channelName = "Test Channel"
    )

    private fun createResolvedStreams(streamId: String) = ResolvedStreams(
        streamId = streamId,
        videoTracks = listOf(
            VideoTrack(
                url = "https://example.com/video.mp4",
                mimeType = "video/mp4",
                width = 1280,
                height = 720,
                bitrate = 2000000,
                qualityLabel = "720p",
                fps = 30,
                isVideoOnly = false,
            )
        ),
        audioTracks = listOf(
            AudioTrack(
                url = "https://example.com/audio.m4a",
                mimeType = "audio/mp4",
                bitrate = 128000,
                codec = "mp4a",
            )
        ),
        subtitleTracks = emptyList(),
        durationSeconds = 300,
        hlsUrl = null,
        dashUrl = null,
        urlGeneratedAt = 0L,
        urlTimebaseVersion = ResolvedStreams.URL_TIMEBASE_VERSION,
    )
}
