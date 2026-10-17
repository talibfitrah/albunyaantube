package com.albunyaan.tube.ui.player

import com.albunyaan.tube.analytics.ExtractorMetricsReporter
import com.albunyaan.tube.analytics.PlaybackMetricsCollector
import com.albunyaan.tube.data.channel.Page
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

/** The PlayerViewModel test doubles, once (1.0.4 A10: four test files each carried a copy). */
internal fun playerViewModel(
    repository: PlayerRepository = FakePlayerRepository(),
    downloadRepository: DownloadRepository = FakeDownloadRepository(),
    playlistDetailRepository: PlaylistDetailRepository = FakePlaylistDetailRepository(),
    favoritesRepository: FavoritesRepository = FakeFavoritesRepository(),
    extractorClient: ExtractorClient = FakeExtractorClient(),
) = PlayerViewModel(
    repository = repository,
    downloadRepository = downloadRepository,
    playlistDetailRepository = playlistDetailRepository,
    rateLimiter = ExtractionRateLimiter(),
    prefetchService = FakePrefetchService(),
    favoritesRepository = favoritesRepository,
    metricsReporter = FakeExtractorMetricsReporter(),
    playbackMetrics = PlaybackMetricsCollector(),
    mpdRegistry = SyntheticDashMpdRegistry(),
    extractorClient = extractorClient,
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

internal class FakePlayerRepository : PlayerRepository {
    var resolvedStreams: ResolvedStreams? = null
    var resolveCallCount = 0
    var failNextResolve = false
    var alwaysFail = false
    var resolvedStreamsAfterFailure: ResolvedStreams? = null
    private var failCount = 0

    /** Mirrors the chokepoint inside GlobalStreamResolver: these ids throw [ContentUnavailableException]. */
    val archivedIds: MutableSet<String> = mutableSetOf()

    /** The priority handed to the latest resolve, so tests can assert callers plumb the lane through. */
    var lastObservedPriority: Priority? = null

    /** Every videoId resolveStreams was asked for, in order. */
    val resolvedVideoIds: MutableList<String> = mutableListOf()

    /** Suspend forever, like a cold-start extraction that outlives the player's timeout. */
    var hang = false

    override suspend fun resolveStreams(
        videoId: String,
        forceRefresh: Boolean,
        priority: Priority,
        sourceChannelId: String?,
    ): ResolvedStreams? {
        resolveCallCount++
        lastObservedPriority = priority
        resolvedVideoIds += videoId
        if (hang) awaitCancellation()
        if (videoId in archivedIds) throw ContentUnavailableException(videoId)
        if (alwaysFail) return null
        if (failNextResolve) {
            failCount++
            if (failCount > 3) { // all 3 attempts of one resolve failed; the next call succeeds
                failNextResolve = false
                failCount = 0
                return resolvedStreamsAfterFailure
            }
            return null
        }
        return resolvedStreams
    }
}

internal class FakeDownloadRepository : DownloadRepository {
    val entries = MutableStateFlow<List<DownloadEntry>>(emptyList())

    /** What the repository's startup load finds on disk, published only once it completes. */
    var listedOnRefresh: List<DownloadEntry>? = null
    val enqueued = mutableListOf<DownloadRequest>()
    override var startupLoad: kotlinx.coroutines.Job? = null

    override val downloads: StateFlow<List<DownloadEntry>> = entries
    override suspend fun refreshPersistedDownloads() {
        listedOnRefresh?.let { entries.value = it }
    }
    override fun enqueue(request: DownloadRequest) { enqueued += request }
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

internal class FakePlaylistDetailRepository : PlaylistDetailRepository {
    private var pages: List<PlaylistPage<PlaylistItem>> = emptyList()
    private var currentPageIndex = 0
    var getItemsCallCount = 0
    var failOnSecondPage = false

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
        title = "Test Playlist",
        thumbnailUrl = "https://example.com/thumb.jpg",
        bannerUrl = null,
        channelId = "UC123",
        channelName = "Test Channel",
        itemCount = 10L,
        totalDurationSeconds = 3000L,
        description = "Test",
        tags = emptyList(),
        category = category,
        excluded = excluded,
        downloadPolicy = downloadPolicy,
    )

    override suspend fun getItems(playlistId: String, page: Page?, itemOffset: Int): PlaylistPage<PlaylistItem> {
        getItemsCallCount++
        if (page != null && failOnSecondPage) throw java.io.IOException("Simulated network error for testing")
        val pageIndex = if (page == null) 0 else currentPageIndex + 1
        currentPageIndex = pageIndex
        return pages.getOrNull(pageIndex) ?: PlaylistPage(emptyList(), null)
    }

    override suspend fun resolveCanonicalChannelId(uploaderUrl: String?): String? = null
}

/** No prefetch data: cold-start playback. */
internal class FakePrefetchService : StreamPrefetchService {
    override fun triggerPrefetch(videoId: String, scope: CoroutineScope) {}
    override suspend fun awaitOrConsumePrefetch(videoId: String): ResolvedStreams? = null
    override fun consumePrefetch(videoId: String): ResolvedStreams? = null
    override fun isPrefetchInFlight(videoId: String): Boolean = false
    override fun cancelPrefetch(videoId: String) {}
    override fun clearPrefetchState() {}
    override fun clearAll() {}
}

/** Favourites in memory; [toggled] records what each toggle sent. */
internal class FakeFavoritesRepository : FavoritesRepository {
    private val favorites = MutableStateFlow<List<FavoriteVideo>>(emptyList())
    val toggled = mutableListOf<Triple<String, String, Int>>()

    override fun getAllFavorites(): Flow<List<FavoriteVideo>> = favorites
    override fun observeApprovedFavorites(): Flow<List<FavoriteVideo>> = emptyFlow()
    override fun observeAwaitingFavorites(): Flow<List<FavoriteVideo>> = emptyFlow()
    override fun isFavorite(videoId: String): Flow<Boolean> = favorites.map { list -> list.any { it.videoId == videoId } }
    override suspend fun isFavoriteOnce(videoId: String): Boolean = favorites.value.any { it.videoId == videoId }
    override suspend fun addFavorite(
        videoId: String, title: String, channelName: String,
        thumbnailUrl: String?, durationSeconds: Int,
    ) {
        favorites.value = favorites.value + FavoriteVideo(
            videoId = videoId, title = title, channelName = channelName,
            thumbnailUrl = thumbnailUrl, durationSeconds = durationSeconds,
        )
    }
    override suspend fun removeFavorite(videoId: String) {
        favorites.value = favorites.value.filter { it.videoId != videoId }
    }
    override suspend fun toggleFavorite(
        videoId: String, title: String, channelName: String,
        thumbnailUrl: String?, durationSeconds: Int,
    ): Boolean {
        toggled += Triple(videoId, channelName, durationSeconds)
        if (isFavoriteOnce(videoId)) {
            removeFavorite(videoId)
            return false
        }
        addFavorite(videoId, title, channelName, thumbnailUrl, durationSeconds)
        return true
    }
    override fun getFavoriteCount(): Flow<Int> = favorites.map { it.size }
    override suspend fun favoriteExistsAny(uid: String, videoId: String): Boolean = isFavoriteOnce(videoId)
    override suspend fun addImportedFavorite(
        uid: String,
        videoId: String, title: String, channelName: String,
        thumbnailUrl: String?, durationSeconds: Int,
        approvalStatus: String, source: String?, importedAt: Long?,
    ) = addFavorite(videoId, title, channelName, thumbnailUrl, durationSeconds)
    override suspend fun clearAll() { favorites.value = emptyList() }
}

/** No metadata; [calls] counts the video-metadata fetches. */
internal class FakeExtractorClient : ExtractorClient {
    var calls = 0
    override suspend fun fetchVideoMetadata(ids: List<String>): Map<String, VideoMetadata> {
        calls++
        return emptyMap()
    }
    override suspend fun fetchChannelMetadata(ids: List<String>): Map<String, ChannelMetadata> = emptyMap()
    override suspend fun fetchPlaylistMetadata(ids: List<String>): Map<String, PlaylistMetadata> = emptyMap()
}

internal class FakeExtractorMetricsReporter : ExtractorMetricsReporter {
    override fun onCacheHit(type: ContentType, hitCount: Int) {}
    override fun onCacheMiss(type: ContentType, missCount: Int) {}
    override fun onFetchSuccess(type: ContentType, fetchedCount: Int, durationMillis: Long) {}
    override fun onFetchFailure(type: ContentType, ids: List<String>, throwable: Throwable) {}
}
