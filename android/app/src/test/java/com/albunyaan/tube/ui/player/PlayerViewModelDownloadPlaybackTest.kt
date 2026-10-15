package com.albunyaan.tube.ui.player

import com.albunyaan.tube.R
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
import com.albunyaan.tube.download.DownloadEntry
import com.albunyaan.tube.download.DownloadFileMetadata
import com.albunyaan.tube.download.DownloadPolicy
import com.albunyaan.tube.download.DownloadRepository
import com.albunyaan.tube.download.DownloadRequest
import com.albunyaan.tube.download.DownloadStatus
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Offline downloads (1.0.3, R1/R2): a completed download plays inside FitrahTube's
 * own player from its local file — the Android counterpart of iOS's OfflineResolver
 * (PlayerScreen.swift:141-158). No stream resolution, no metadata fetch, no queue.
 * Replaces the 2026-10-14 RED test that pinned the external-app hand-off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PlayerViewModelDownloadPlaybackTest {

    @get:Rule val tmp = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private val streams = CountingPlayerRepository()
    private val metadata = CountingExtractorClient()
    private val downloads = FakeDownloadRepository()

    @Before fun setUp() = Dispatchers.setMain(testDispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun completed(
        id: String,
        videoId: String,
        file: File,
        audioOnly: Boolean = false,
        status: DownloadStatus = DownloadStatus.COMPLETED,
    ) = DownloadEntry(
        request = DownloadRequest(
            id = id, title = "Request title", videoId = videoId, audioOnly = audioOnly,
            thumbnailUrl = "https://i.example/t.jpg",
        ),
        status = status,
        progress = 100,
        filePath = file.absolutePath,
        metadata = DownloadFileMetadata(
            sizeBytes = file.length(), completedAtMillis = 0L,
            mimeType = if (audioOnly) "audio/mp4" else "video/mp4", title = "Lecture",
        ),
    )

    private fun noNetwork() {
        assertEquals("stream resolution must not run for a download", 0, streams.resolveCallCount)
        assertEquals("metadata fetch must not run for a download", 0, metadata.calls)
    }

    @Test fun `a completed download plays from its local file with no network and no queue`() = runTest {
        val file = tmp.newFile("v1.mp4")
        downloads.entries.value = listOf(completed("d1", "v1", file))
        val vm = createViewModel()

        vm.loadDownload("d1")
        advanceUntilIdle()

        val state = vm.state.value
        val ready = state.streamState as StreamState.Ready
        val uri = file.toURI().toString()
        assertEquals("v1", ready.streamId)
        assertEquals(uri, ready.selection.video?.url)
        assertEquals(uri, ready.selection.audio.url)
        assertTrue("no captions to fetch offline", ready.selection.resolved.subtitleTracks.isEmpty())
        assertTrue(state.playingDownload)
        assertFalse(state.audioOnly)
        assertEquals("Lecture", state.currentItem?.title)
        assertEquals("https://i.example/t.jpg", state.currentItem?.thumbnailUrl)
        assertTrue(state.upNext.isEmpty())
        assertFalse(state.hasNext)
        assertFalse(state.hasPrevious)
        noNetwork()
    }

    @Test fun `an audio-only download plays as audio`() = runTest {
        val file = tmp.newFile("a1.m4a")
        downloads.entries.value = listOf(completed("d2", "a1", file, audioOnly = true))
        val vm = createViewModel()

        vm.loadDownload("d2")
        advanceUntilIdle()

        val ready = vm.state.value.streamState as StreamState.Ready
        assertTrue(vm.state.value.audioOnly)
        assertNull(ready.selection.video)
        assertEquals(file.toURI().toString(), ready.selection.audio.url)
        noNetwork()
    }

    @Test fun `a missing file shows the unavailable error and never goes online`() = runTest {
        val file = File(tmp.root, "gone.mp4")
        downloads.entries.value = listOf(completed("d3", "v3", file))
        val vm = createViewModel()

        vm.loadDownload("d3")
        advanceUntilIdle()

        assertEquals(StreamState.Error(R.string.player_stream_unavailable), vm.state.value.streamState)
        noNetwork()
    }

    @Test fun `an unknown download shows the unavailable error`() = runTest {
        val vm = createViewModel()

        vm.loadDownload("nope")
        advanceUntilIdle()

        assertEquals(StreamState.Error(R.string.player_stream_unavailable), vm.state.value.streamState)
        noNetwork()
    }

    @Test fun `retry and refresh re-read the local file and never go online`() = runTest {
        val file = tmp.newFile("v4.mp4")
        downloads.entries.value = listOf(completed("d4", "v4", file))
        val vm = createViewModel()
        vm.loadDownload("d4")
        advanceUntilIdle()

        vm.forceRefreshForAutoRecovery()
        advanceUntilIdle()
        assertTrue(vm.state.value.streamState is StreamState.Ready)

        // Deleted or expired while open: the next re-read shows the error, never a stream.
        file.delete()
        vm.retryCurrentStream()
        advanceUntilIdle()

        assertEquals(StreamState.Error(R.string.player_stream_unavailable), vm.state.value.streamState)
        noNetwork()
    }

    @Test fun `a player restored after process death waits for the repository's startup load`() = runTest {
        // The OS restores PlayerFragment with its downloadId before DefaultDownloadRepository's
        // init has listed the files; refreshPersistedDownloads() waits for that load.
        val file = tmp.newFile("v5.mp4")
        downloads.listedOnRefresh = listOf(completed("d5", "v5", file))
        val vm = createViewModel()

        vm.loadDownload("d5")
        advanceUntilIdle()

        val ready = vm.state.value.streamState as StreamState.Ready
        assertEquals(file.toURI().toString(), ready.selection.audio.url)
        assertEquals("Lecture", vm.state.value.currentItem?.title)
        noNetwork()
    }

    @Test fun `a failed entry listed first does not hide the completed download`() = runTest {
        val file = tmp.newFile("v6.mp4")
        downloads.entries.value = listOf(
            completed("failed", "v6", File(tmp.root, "none.m4a"), audioOnly = true, status = DownloadStatus.FAILED),
            completed("done", "v6", file),
        )
        val vm = createViewModel()

        vm.loadVideo(videoId = "v6")
        advanceUntilIdle()

        // The streaming player's Download button opens this entry (PlayerFragment.updateDownloadControls).
        assertEquals("done", vm.state.value.currentDownload?.request?.id)
        assertEquals(DownloadStatus.COMPLETED, vm.state.value.currentDownload?.status)
    }

    private fun createViewModel() = PlayerViewModel(
        repository = streams,
        downloadRepository = downloads,
        playlistDetailRepository = FakePlaylistDetailRepository(),
        rateLimiter = ExtractionRateLimiter(),
        prefetchService = FakePrefetchService(),
        favoritesRepository = FakeFavoritesRepository(),
        metricsReporter = FakeExtractorMetricsReporter(),
        playbackMetrics = PlaybackMetricsCollector(),
        mpdRegistry = SyntheticDashMpdRegistry(),
        extractorClient = metadata,
        dubAudioEnumerator = com.albunyaan.tube.data.extractor.DubAudioEnumerator(),
        dubAudioResolver = com.albunyaan.tube.data.extractor.DubAudioResolver(
            com.albunyaan.tube.data.extractor.DubAudioEnumerator(),
            com.albunyaan.tube.data.extractor.NoOpPoTokenProvider,
            com.albunyaan.tube.data.extractor.nsig.NsigSolver(
                androidx.test.core.app.ApplicationProvider.getApplicationContext()
            ),
        ),
        contentService = com.albunyaan.tube.data.source.FakeContentService(),
    )

    // ── Fakes ────────────────────────────────────────────────────────────

    private class CountingPlayerRepository : PlayerRepository {
        var resolveCallCount = 0
        override suspend fun resolveStreams(
            videoId: String,
            forceRefresh: Boolean,
            priority: Priority,
            sourceChannelId: String?,
        ): ResolvedStreams? {
            resolveCallCount++
            return null
        }
    }

    private class CountingExtractorClient : ExtractorClient {
        var calls = 0
        override suspend fun fetchVideoMetadata(ids: List<String>): Map<String, VideoMetadata> {
            calls++
            return emptyMap()
        }
        override suspend fun fetchChannelMetadata(ids: List<String>): Map<String, ChannelMetadata> = emptyMap()
        override suspend fun fetchPlaylistMetadata(ids: List<String>): Map<String, PlaylistMetadata> = emptyMap()
    }

    private class FakeDownloadRepository : DownloadRepository {
        val entries = MutableStateFlow<List<DownloadEntry>>(emptyList())
        /** What the repository's startup load finds on disk, published only once it completes. */
        var listedOnRefresh: List<DownloadEntry>? = null
        override val downloads: StateFlow<List<DownloadEntry>> = entries
        override suspend fun refreshPersistedDownloads() {
            listedOnRefresh?.let { entries.value = it }
        }
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
        ): com.albunyaan.tube.data.playlist.PlaylistHeader = error("not used")

        override suspend fun getItems(
            playlistId: String,
            page: com.albunyaan.tube.data.channel.Page?,
            itemOffset: Int,
        ): PlaylistPage<com.albunyaan.tube.data.playlist.PlaylistItem> = PlaylistPage(emptyList(), null)

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
        override fun getAllFavorites(): Flow<List<FavoriteVideo>> = flowOf(emptyList())
        override fun observeApprovedFavorites(): Flow<List<FavoriteVideo>> = emptyFlow()
        override fun observeAwaitingFavorites(): Flow<List<FavoriteVideo>> = emptyFlow()
        override fun isFavorite(videoId: String): Flow<Boolean> = flowOf(false)
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
        override fun getFavoriteCount(): Flow<Int> = flowOf(0)
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
