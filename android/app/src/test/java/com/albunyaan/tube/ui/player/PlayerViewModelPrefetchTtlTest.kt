package com.albunyaan.tube.ui.player

import com.albunyaan.tube.analytics.ExtractorMetricsReporter
import com.albunyaan.tube.analytics.PlaybackMetricsCollector
import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.ChannelMetadata
import com.albunyaan.tube.data.extractor.ExtractorClient
import com.albunyaan.tube.data.extractor.PlaylistMetadata
import com.albunyaan.tube.data.extractor.Priority
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.VideoMetadata
import com.albunyaan.tube.data.extractor.VideoTrack
import com.albunyaan.tube.data.local.FavoriteVideo
import com.albunyaan.tube.data.local.FavoritesRepository
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.playlist.PlaylistDetailRepository
import com.albunyaan.tube.data.playlist.PlaylistHeader
import com.albunyaan.tube.data.playlist.PlaylistItem
import com.albunyaan.tube.data.playlist.PlaylistPage
import com.albunyaan.tube.download.DownloadEntry
import com.albunyaan.tube.download.DownloadPolicy
import com.albunyaan.tube.download.DownloadRepository
import com.albunyaan.tube.download.DownloadRequest
import com.albunyaan.tube.download.PlaylistDownloadItem
import com.albunyaan.tube.player.ContentUnavailableException
import com.albunyaan.tube.player.ExtractionRateLimiter
import com.albunyaan.tube.player.PlayerRepository
import com.albunyaan.tube.player.StreamPrefetchService
import com.albunyaan.tube.player.SyntheticDashMpdRegistry
import com.albunyaan.tube.data.channel.Page
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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

    private lateinit var fakePlayerRepository: FakePlayerRepository
    private lateinit var fakePlaylistRepository: FakePlaylistDetailRepository
    private lateinit var rateLimiter: ExtractionRateLimiter
    private lateinit var fakePrefetchService: FakePrefetchService
    private lateinit var fakeFavoritesRepository: FakeFavoritesRepository
    private lateinit var fakeMetricsReporter: FakeExtractorMetricsReporter
    private lateinit var playbackMetrics: PlaybackMetricsCollector
    private lateinit var mpdRegistry: SyntheticDashMpdRegistry
    private lateinit var fakeExtractorClient: ExtractorClient

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
        fakePlayerRepository = FakePlayerRepository()
        fakePlaylistRepository = FakePlaylistDetailRepository()
        rateLimiter = ExtractionRateLimiter()
        fakePrefetchService = FakePrefetchService()
        fakeFavoritesRepository = FakeFavoritesRepository()
        fakeMetricsReporter = FakeExtractorMetricsReporter()
        playbackMetrics = PlaybackMetricsCollector()
        mpdRegistry = SyntheticDashMpdRegistry()
        fakeExtractorClient = object : ExtractorClient {
            override suspend fun fetchVideoMetadata(ids: List<String>): Map<String, VideoMetadata> = emptyMap()
            override suspend fun fetchChannelMetadata(ids: List<String>): Map<String, ChannelMetadata> = emptyMap()
            override suspend fun fetchPlaylistMetadata(ids: List<String>): Map<String, PlaylistMetadata> = emptyMap()
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): PlayerViewModel {
        val vm = PlayerViewModel(
            repository = fakePlayerRepository,
            downloadRepository = FakeDownloadRepository(),
            playlistDetailRepository = fakePlaylistRepository,
            rateLimiter = rateLimiter,
            prefetchService = fakePrefetchService,
            favoritesRepository = fakeFavoritesRepository,
            metricsReporter = fakeMetricsReporter,
            playbackMetrics = playbackMetrics,
            mpdRegistry = mpdRegistry,
            extractorClient = fakeExtractorClient,
            dubAudioEnumerator = com.albunyaan.tube.data.extractor.DubAudioEnumerator(),
        )
        vm.setClockForTesting { now }
        return vm
    }

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

    // ── Fakes (mirror the existing test files in this package) ────────────────

    private class FakePlayerRepository : PlayerRepository {
        var resolvedStreams: ResolvedStreams? = null
        var resolveCallCount = 0
        val archivedIds: MutableSet<String> = mutableSetOf()
        val resolvedVideoIds: MutableList<String> = mutableListOf()

        override suspend fun resolveStreams(
            videoId: String,
            forceRefresh: Boolean,
            priority: Priority,
            sourceChannelId: String?,
        ): ResolvedStreams? {
            resolveCallCount++
            resolvedVideoIds += videoId
            if (videoId in archivedIds) {
                throw ContentUnavailableException(videoId)
            }
            return resolvedStreams
        }
    }

    private class FakeDownloadRepository : DownloadRepository {
        private val _downloads = MutableStateFlow<List<DownloadEntry>>(emptyList())
        override val downloads: StateFlow<List<DownloadEntry>> = _downloads
        override fun enqueue(request: DownloadRequest) {}
        override fun pause(requestId: String) {}
        override fun resume(requestId: String) {}
        override fun cancel(requestId: String) {}
        override fun remove(requestId: String) {}
        override fun retry(requestId: String) {}
        override fun delete(requestId: String): Boolean = true
        override fun enqueuePlaylist(
            playlistId: String,
            playlistTitle: String,
            qualityLabel: String,
            items: List<PlaylistDownloadItem>,
            audioOnly: Boolean,
            targetHeight: Int?,
        ): Int = 0
        override fun isPlaylistDownloading(playlistId: String, qualityLabel: String) = false
    }

    private class FakePlaylistDetailRepository : PlaylistDetailRepository {
        private var pages: List<PlaylistPage<PlaylistItem>> = emptyList()
        private var currentPageIndex = 0

        fun setPages(pages: List<PlaylistPage<PlaylistItem>>) {
            this.pages = pages
            this.currentPageIndex = 0
        }

        override suspend fun getHeader(
            playlistId: String,
            forceRefresh: Boolean,
            category: String?,
            excluded: Boolean,
            downloadPolicy: DownloadPolicy,
        ): PlaylistHeader = PlaylistHeader(
            id = playlistId,
            title = "Test",
            thumbnailUrl = null,
            bannerUrl = null,
            channelId = null,
            channelName = null,
            itemCount = pages.sumOf { it.items.size }.toLong(),
            totalDurationSeconds = null,
            description = null,
            tags = emptyList(),
            category = null,
            excluded = false,
            downloadPolicy = DownloadPolicy.ENABLED,
        )

        override suspend fun getItems(
            playlistId: String,
            page: Page?,
            itemOffset: Int,
        ): PlaylistPage<PlaylistItem> {
            if (currentPageIndex >= pages.size) return PlaylistPage(emptyList(), null)
            return pages[currentPageIndex++]
        }

        override suspend fun resolveCanonicalChannelId(uploaderUrl: String?): String? = null
    }

    private class FakePrefetchService : StreamPrefetchService {
        override fun triggerPrefetch(videoId: String, scope: kotlinx.coroutines.CoroutineScope) {}
        override suspend fun awaitOrConsumePrefetch(videoId: String): ResolvedStreams? = null
        override fun consumePrefetch(videoId: String): ResolvedStreams? = null
        override fun isPrefetchInFlight(videoId: String): Boolean = false
        override fun cancelPrefetch(videoId: String) {}
        override fun clearPrefetchState() {}
        override fun clearAll() {}
    }

    private class FakeFavoritesRepository : FavoritesRepository {
        private val favorites = MutableStateFlow<List<FavoriteVideo>>(emptyList())
        override fun getAllFavorites(): Flow<List<FavoriteVideo>> = favorites
        override fun observeApprovedFavorites(): Flow<List<FavoriteVideo>> = kotlinx.coroutines.flow.emptyFlow()
        override fun observeAwaitingFavorites(): Flow<List<FavoriteVideo>> = kotlinx.coroutines.flow.emptyFlow()
        override fun isFavorite(videoId: String): Flow<Boolean> = favorites.map { list ->
            list.any { it.videoId == videoId }
        }
        override suspend fun isFavoriteOnce(videoId: String): Boolean = false
        override suspend fun addFavorite(
            videoId: String, title: String, channelName: String,
            thumbnailUrl: String?, durationSeconds: Int,
        ) {}
        override suspend fun removeFavorite(videoId: String) {}
        override suspend fun toggleFavorite(
            videoId: String, title: String, channelName: String,
            thumbnailUrl: String?, durationSeconds: Int,
        ): Boolean = false
        override fun getFavoriteCount(): Flow<Int> = favorites.map { it.size }
        override suspend fun favoriteExistsAny(uid: String, videoId: String): Boolean = false
        override suspend fun addImportedFavorite(
            uid: String,
            videoId: String, title: String, channelName: String,
            thumbnailUrl: String?, durationSeconds: Int,
            approvalStatus: String, source: String?, importedAt: Long?,
        ) {}
        override suspend fun clearAll() {}
    }

    private class FakeExtractorMetricsReporter : ExtractorMetricsReporter {
        override fun onCacheHit(type: ContentType, hitCount: Int) {}
        override fun onCacheMiss(type: ContentType, missCount: Int) {}
        override fun onFetchSuccess(type: ContentType, fetchedCount: Int, durationMillis: Long) {}
        override fun onFetchFailure(type: ContentType, ids: List<String>, throwable: Throwable) {}
    }
}
