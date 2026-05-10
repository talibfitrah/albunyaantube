package com.albunyaan.tube.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.albunyaan.tube.BuildConfig
import com.albunyaan.tube.analytics.ExtractorMetricsReporter
import com.albunyaan.tube.analytics.ListMetricsReporter
import com.albunyaan.tube.analytics.LogExtractorMetricsReporter
import com.albunyaan.tube.analytics.LogListMetricsReporter
import com.albunyaan.tube.analytics.TelemetryExtractorMetricsReporter
import com.albunyaan.tube.data.extractor.ExtractorClient
import com.albunyaan.tube.data.extractor.MetadataHydrator
import com.albunyaan.tube.data.extractor.NewPipeExtractorClient
import com.albunyaan.tube.data.extractor.NoOpMetadataHydrator
import com.albunyaan.tube.data.extractor.OkHttpDownloader
import com.albunyaan.tube.data.extractor.YoutubeClientRotator
import com.albunyaan.tube.data.extractor.cache.MetadataCache
import com.albunyaan.tube.data.filters.FilterManager
import com.albunyaan.tube.data.paging.ContentPagingRepository
import com.albunyaan.tube.data.paging.DefaultContentPagingRepository
import com.albunyaan.tube.data.report.ReportRepository
import com.albunyaan.tube.data.report.RetrofitReportRepository
import com.albunyaan.tube.data.source.ContentService
import com.albunyaan.tube.data.source.FakeContentService
import com.albunyaan.tube.data.source.RetrofitContentService
import com.albunyaan.tube.data.source.RetrofitDownloadService
import com.albunyaan.tube.data.source.api.ContentApi
import com.albunyaan.tube.data.source.api.DownloadApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.albunyaan.tube.data.source.api.ReportApi
import com.albunyaan.tube.player.CronetDataSourceFactory
import com.albunyaan.tube.player.DefaultPlayerRepository
import com.albunyaan.tube.player.ExtractionRateLimiter
import com.albunyaan.tube.player.GlobalStreamResolver
import com.albunyaan.tube.player.PlaybackFeatureFlags
import com.albunyaan.tube.player.PlayerRepository
import com.albunyaan.tube.player.DefaultStreamPrefetchService
import com.albunyaan.tube.player.MultiRepresentationMpdGenerator
import com.albunyaan.tube.player.SegmentPreBuffer
import com.albunyaan.tube.player.StreamPrefetchService
import com.albunyaan.tube.player.SyntheticDashMpdRegistry
import com.albunyaan.tube.telemetry.LogTelemetryClient
import com.albunyaan.tube.telemetry.TelemetryClient
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Named
import javax.inject.Singleton

/**
 * P3-T1: Data DI Module
 *
 * Provides data-related dependencies: DataStores, Services, Repositories
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    /**
     * Provides an application-scoped CoroutineScope for background work.
     *
     * Uses SupervisorJob to prevent child failures from cancelling sibling coroutines.
     * Dispatches on Dispatchers.IO for disk/network operations.
     *
     * Note: This scope lives for the entire process lifetime and is not explicitly cancelled.
     * In production this is fine (process death cleans up). For instrumentation tests that
     * need isolation, inject a custom scope via @TestInstallIn or use Dispatchers.Main.immediate
     * with IdlingResource for proper synchronization.
     */
    @Provides
    @Singleton
    @Named("applicationScope")
    fun provideApplicationScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @Provides
    @Singleton
    @Named("filtersDataStore")
    fun provideFiltersDataStore(
        @ApplicationContext context: Context,
        @Named("applicationScope") scope: CoroutineScope
    ): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create(scope = scope) {
            File(context.filesDir, "filters.preferences_pb")
        }
    }

    @Provides
    @Singleton
    @Named("policyDataStore")
    fun providePolicyDataStore(
        @ApplicationContext context: Context,
        @Named("applicationScope") scope: CoroutineScope
    ): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create(scope = scope) {
            File(context.filesDir, "policy.preferences_pb")
        }
    }

    @Provides
    @Singleton
    @Named("cooldownDataStore")
    fun provideCooldownDataStore(
        @ApplicationContext context: Context,
        @Named("applicationScope") scope: CoroutineScope
    ): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create(scope = scope) {
            File(context.filesDir, "cooldown.preferences_pb")
        }
    }

    @Provides
    @Singleton
    fun provideFilterManager(
        @Named("filtersDataStore") dataStore: DataStore<Preferences>,
        @Named("applicationScope") scope: CoroutineScope
    ): FilterManager {
        return FilterManager(dataStore, scope)
    }

    @Provides
    @Singleton
    fun provideTelemetryClient(): TelemetryClient {
        return LogTelemetryClient()
    }

    @Provides
    @Singleton
    fun provideExtractorMetricsReporter(telemetryClient: TelemetryClient): ExtractorMetricsReporter {
        return TelemetryExtractorMetricsReporter(LogExtractorMetricsReporter(), telemetryClient)
    }

    @Provides
    @Singleton
    fun provideMetadataCache(): MetadataCache {
        return MetadataCache(ttlMillis = 15 * 60 * 1000L, maxEntriesPerBucket = 200)
    }

    /**
     * Build a NewPipe-only [OkHttpClient] view that shares the singleton's
     * connection pool but uses its own [Dispatcher].
     *
     * Why: the singleton [OkHttpClient] is also used by Retrofit (backend API)
     * and [com.albunyaan.tube.data.me.AtomChannelFeedFetcher] (Me-tab ATOM
     * refresh). OkHttp's per-host slot cap defaults to 5, and
     * [com.albunyaan.tube.data.me.MeFeedRepository.MAX_CONCURRENT] = 4 ATOM
     * fetches all hit `youtube.com`. With a shared dispatcher, those four
     * BG fetches consume four of the five `youtube.com` slots and a user-
     * gesture channel-detail tap (also `youtube.com` via NewPipe innertube)
     * waits behind them. Beta.2 had no ATOM/prefetch traffic so user gestures
     * had the pool to themselves; the slowdown reported in beta.5 vs beta.2
     * is the cost of sharing the dispatcher with autonomous traffic.
     *
     * Giving NewPipe its own [Dispatcher] (default per-host = 5, same as
     * beta.2 had effectively) restores user-gesture throughput while keeping
     * ATOM's 4-concurrent fan-out on the shared dispatcher. The connection
     * pool is shared (`newBuilder()` keeps it) so we don't waste sockets.
     */
    @Provides
    @Singleton
    fun provideOkHttpDownloader(
        okHttpClient: OkHttpClient,
        @ApplicationContext context: Context
    ): OkHttpDownloader {
        val newPipeClient = okHttpClient.newBuilder()
            .dispatcher(Dispatcher())
            .build()
        return OkHttpDownloader(newPipeClient, context.cacheDir)
    }

    /**
     * Wrap the production [OkHttpDownloader] in [RateLimitedDownloader] so all
     * NewPipe HTTP traffic flows through the global rate-limit + cooldown
     * gates (spec §4.4). Player priority bypasses both gates per spec D1; see
     * [RateLimitedDownloader] for the bypass logic.
     */
    @Provides
    @Singleton
    fun provideRateLimitedDownloader(
        delegate: OkHttpDownloader,
        rateLimiter: com.albunyaan.tube.data.extractor.GlobalNewPipeRateLimiter,
        cooldownState: com.albunyaan.tube.data.extractor.CooldownState,
    ): com.albunyaan.tube.data.extractor.RateLimitedDownloader {
        return com.albunyaan.tube.data.extractor.RateLimitedDownloader(
            delegate, rateLimiter, cooldownState
        )
    }

    @Provides
    @Singleton
    fun provideNewPipeExtractorClient(
        // Inject the rate-limited wrapper, not the bare [OkHttpDownloader] —
        // every NewPipe HTTP call must pass through the gates (spec §4.4).
        downloader: com.albunyaan.tube.data.extractor.RateLimitedDownloader,
        cache: MetadataCache,
        metrics: ExtractorMetricsReporter,
        featureFlags: PlaybackFeatureFlags,
        clientRotator: YoutubeClientRotator
    ): NewPipeExtractorClient {
        return NewPipeExtractorClient(downloader, cache, metrics, featureFlags, clientRotator)
    }

    @Provides
    @Singleton
    fun provideExtractorClient(client: NewPipeExtractorClient): ExtractorClient = client

    @Provides
    @Singleton
    fun provideMetadataHydrator(): MetadataHydrator {
        // Use NoOpMetadataHydrator since backend provides complete data (fast loading)
        return NoOpMetadataHydrator()
    }

    @Provides
    @Singleton
    @Named("retrofitContentService")
    fun provideRetrofitContentService(
        contentApi: ContentApi,
        metadataHydrator: MetadataHydrator
    ): ContentService {
        return RetrofitContentService(contentApi, metadataHydrator)
    }

    @Provides
    @Singleton
    @Named("fakeContentService")
    fun provideFakeContentService(): ContentService {
        return FakeContentService()
    }

    @Provides
    @Singleton
    @Named("real")
    fun provideContentService(
        @Named("retrofitContentService") retrofitService: ContentService
    ): ContentService {
        // The app must never silently fall back to fake/mock data — even in debug builds.
        // When the device is offline (or the backend is unreachable) the real network
        // failure must propagate so each list screen can render skeleton placeholders
        // and the global offline banner. ANDROID-MULTI-01 #2.
        return retrofitService
    }

    @Provides
    @Singleton
    fun provideContentPagingRepository(@Named("real") contentService: ContentService): ContentPagingRepository {
        return DefaultContentPagingRepository(contentService)
    }

    @Provides
    @Singleton
    fun provideListMetricsReporter(): ListMetricsReporter {
        return LogListMetricsReporter()
    }

    /**
     * Phase 1A: PlayerRepository now uses GlobalStreamResolver for single-flight semantics.
     *
     * Archived-content NB1 fix: the backend availability gate moved one level
     * deeper into [GlobalStreamResolver] so both the player path AND the
     * tap-prefetch path ([StreamPrefetchService]) share a single chokepoint.
     * The per-caller gate that used to live in [DefaultPlayerRepository] is
     * gone — this provider no longer wires a ContentService here.
     */
    @Provides
    @Singleton
    fun providePlayerRepository(
        globalResolver: GlobalStreamResolver,
    ): PlayerRepository {
        return DefaultPlayerRepository(globalResolver)
    }

    @OptIn(UnstableApi::class)
    @Provides
    @Singleton
    fun provideSimpleCache(@ApplicationContext context: Context): SimpleCache {
        val cacheDir = java.io.File(context.cacheDir, "media3")
        val dbProvider = StandaloneDatabaseProvider(context)
        val evictor = LeastRecentlyUsedCacheEvictor(100 * 1024 * 1024L)
        return SimpleCache(cacheDir, evictor, dbProvider)
    }

    @OptIn(UnstableApi::class)
    @Provides
    @Singleton
    fun provideSegmentPreBuffer(
        @ApplicationContext context: Context,
        cache: SimpleCache,
        cronetDataSourceFactory: CronetDataSourceFactory
    ): SegmentPreBuffer {
        return SegmentPreBuffer(context, cache, cronetDataSourceFactory.createForAndroidUA())
    }

    /**
     * Phase 1A: StreamPrefetchService now uses GlobalStreamResolver for single-flight semantics.
     * Phase 5: Also pre-generates synthetic DASH MPD during prefetch for faster playback start.
     */
    @Provides
    @Singleton
    fun provideStreamPrefetchService(
        globalResolver: GlobalStreamResolver,
        rateLimiter: ExtractionRateLimiter,
        mpdGenerator: MultiRepresentationMpdGenerator,
        mpdRegistry: SyntheticDashMpdRegistry,
        featureFlags: PlaybackFeatureFlags,
        segmentPreBuffer: SegmentPreBuffer
    ): StreamPrefetchService {
        return DefaultStreamPrefetchService(globalResolver, rateLimiter, mpdGenerator, mpdRegistry, featureFlags, segmentPreBuffer)
    }

    @Provides
    @Singleton
    fun provideRetrofitDownloadService(downloadApi: DownloadApi): RetrofitDownloadService {
        return RetrofitDownloadService(downloadApi)
    }

    @Provides
    @Singleton
    fun provideImageLoader(@ApplicationContext context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .crossfade(true)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.10)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(context.cacheDir, "coil_image_cache"))
                    .maxSizeBytes(60L * 1024 * 1024)
                    .build()
            }
            .allowHardware(false)
            .respectCacheHeaders(false)
            .build()
    }

    @Provides
    @Singleton
    fun provideNetworkMonitor(
        @ApplicationContext context: Context
    ): com.albunyaan.tube.util.NetworkMonitor {
        return com.albunyaan.tube.util.NetworkMonitor(context)
    }

    @Provides
    fun provideImagesEnabled(): Boolean {
        return BuildConfig.ENABLE_THUMBNAIL_IMAGES
    }

    @Provides
    @Singleton
    fun provideReportRepository(api: ReportApi): ReportRepository {
        return RetrofitReportRepository(api)
    }

    // Note: ChannelDetailRepository binding moved to ChannelDetailRepositoryModule
    // for easier test replacement via @TestInstallIn
}
