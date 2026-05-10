package com.albunyaan.tube.ui.player

import com.albunyaan.tube.analytics.ExtractorMetricsReporter
import com.albunyaan.tube.analytics.PlaybackMetricsCollector
import com.albunyaan.tube.data.extractor.ChannelMetadata
import com.albunyaan.tube.data.extractor.ExtractorClient
import com.albunyaan.tube.data.extractor.PlaylistMetadata
import com.albunyaan.tube.data.extractor.Priority
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.VideoMetadata
import com.albunyaan.tube.data.local.FavoriteVideo
import com.albunyaan.tube.data.local.FavoritesRepository
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.playlist.PlaylistDetailRepository
import com.albunyaan.tube.data.playlist.PlaylistPage
import com.albunyaan.tube.data.source.AvailabilityCheckType
import com.albunyaan.tube.data.source.ContentService
import com.albunyaan.tube.download.DownloadEntry
import com.albunyaan.tube.download.DownloadPolicy
import com.albunyaan.tube.download.DownloadRepository
import com.albunyaan.tube.download.DownloadRequest
import com.albunyaan.tube.download.PlaylistDownloadItem
import com.albunyaan.tube.player.ExtractionRateLimiter
import com.albunyaan.tube.player.PlayerRepository
import com.albunyaan.tube.player.StreamPrefetchService
import com.albunyaan.tube.player.SyntheticDashMpdRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T12: Tests for the PlayerViewModel backend availability gate.
 *
 * Verifies:
 * - Archived video → ContentUnavailable state, NewPipe never called
 * - Valid video → proceeds normally (existing behavior preserved)
 * - Transport error on availability check → fail-open, proceeds to NewPipe
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PlayerViewModelAvailabilityGateTest {

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

    private fun createViewModel(contentService: ContentService): PlayerViewModel {
        return PlayerViewModel(
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
            contentService = contentService,
        )
    }

    // ── Availability Gate Tests ───────────────────────────────────────────────

    @Test
    fun `loadVideo archived video emits ContentUnavailable and never calls repository`() = runTest {
        val mockContentService: ContentService = mock()
        whenever(mockContentService.verifyAvailable(AvailabilityCheckType.VIDEO, "vid_archived"))
            .thenReturn(false)

        val vm = createViewModel(mockContentService)
        vm.loadVideo(videoId = "vid_archived")
        advanceUntilIdle()

        assertEquals(
            "Archived video must emit ContentUnavailable",
            StreamState.ContentUnavailable,
            vm.state.value.streamState,
        )
        // NewPipe (PlayerRepository.resolveStreams) must never have been called
        assertEquals(
            "resolveStreams must not be called for archived video",
            0,
            fakePlayerRepository.resolveCallCount,
        )
    }

    @Test
    fun `loadVideo valid video proceeds to stream resolution`() = runTest {
        val mockContentService: ContentService = mock()
        whenever(mockContentService.verifyAvailable(AvailabilityCheckType.VIDEO, "vid_valid"))
            .thenReturn(true)
        // Provide a resolvable stream so the VM can reach Ready
        fakePlayerRepository.resolvedStreams = null // returns null → Error, but still called

        val vm = createViewModel(mockContentService)
        vm.loadVideo(videoId = "vid_valid")
        advanceUntilIdle()

        // State must NOT be ContentUnavailable — it should be Error (no streams returned by fake)
        assertNotEquals(
            "Valid video must not end in ContentUnavailable",
            StreamState.ContentUnavailable,
            vm.state.value.streamState,
        )
        // resolveStreams MUST have been called at least once
        assert(fakePlayerRepository.resolveCallCount > 0) {
            "resolveStreams must be called for a valid video"
        }
    }

    @Test
    fun `loadVideo transport error on availability check fails open and proceeds to resolution`() = runTest {
        val mockContentService: ContentService = mock()
        whenever(mockContentService.verifyAvailable(AvailabilityCheckType.VIDEO, "vid_net_error"))
            .thenThrow(RuntimeException("network timeout"))

        val vm = createViewModel(mockContentService)
        vm.loadVideo(videoId = "vid_net_error")
        advanceUntilIdle()

        // Fail-open: a transport error must not gate the user out
        assertNotEquals(
            "Transport error must not emit ContentUnavailable (fail-open)",
            StreamState.ContentUnavailable,
            vm.state.value.streamState,
        )
        // Resolution must have been attempted
        assert(fakePlayerRepository.resolveCallCount > 0) {
            "resolveStreams must be called when availability check throws"
        }
    }

    // ── Internal Fakes ────────────────────────────────────────────────────────

    private class FakePlayerRepository : PlayerRepository {
        var resolvedStreams: ResolvedStreams? = null
        var resolveCallCount = 0

        override suspend fun resolveStreams(
            videoId: String,
            forceRefresh: Boolean,
            priority: Priority,
        ): ResolvedStreams? {
            resolveCallCount++
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
        override suspend fun getHeader(
            playlistId: String,
            forceRefresh: Boolean,
            category: String?,
            excluded: Boolean,
            downloadPolicy: DownloadPolicy,
        ): com.albunyaan.tube.data.playlist.PlaylistHeader =
            com.albunyaan.tube.data.playlist.PlaylistHeader(
                id = playlistId,
                title = "Test",
                thumbnailUrl = null,
                bannerUrl = null,
                channelId = null,
                channelName = null,
                itemCount = 0L,
                totalDurationSeconds = null,
                description = null,
                tags = emptyList(),
                category = null,
                excluded = false,
                downloadPolicy = DownloadPolicy.ENABLED,
            )

        override suspend fun getItems(
            playlistId: String,
            page: com.albunyaan.tube.data.channel.Page?,
            itemOffset: Int,
        ): PlaylistPage<com.albunyaan.tube.data.playlist.PlaylistItem> =
            PlaylistPage(emptyList(), null)
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
        override suspend fun clearAll() {}
    }

    private class FakeExtractorMetricsReporter : ExtractorMetricsReporter {
        override fun onCacheHit(type: ContentType, hitCount: Int) {}
        override fun onCacheMiss(type: ContentType, missCount: Int) {}
        override fun onFetchSuccess(type: ContentType, fetchedCount: Int, durationMillis: Long) {}
        override fun onFetchFailure(type: ContentType, ids: List<String>, throwable: Throwable) {}
    }
}
