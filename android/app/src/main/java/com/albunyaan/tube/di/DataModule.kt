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
import com.albunyaan.tube.data.extractor.MetadataHydrator
import com.albunyaan.tube.data.extractor.NewPipeExtractorClient
import com.albunyaan.tube.data.extractor.NoOpMetadataHydrator
import com.albunyaan.tube.data.extractor.OkHttpDownloader
import com.albunyaan.tube.data.extractor.cache.MetadataCache
import com.albunyaan.tube.data.filters.FilterManager
import com.albunyaan.tube.data.paging.ContentPagingRepository
import com.albunyaan.tube.data.paging.DefaultContentPagingRepository
import com.albunyaan.tube.data.source.ContentService
import com.albunyaan.tube.data.source.FakeContentService
import com.albunyaan.tube.data.source.FallbackContentService
import com.albunyaan.tube.data.source.RetrofitContentService
import com.albunyaan.tube.data.source.RetrofitDownloadService
import com.albunyaan.tube.data.source.api.ContentApi
import com.albunyaan.tube.data.source.api.DownloadApi
import com.albunyaan.tube.player.DefaultPlayerRepository
import com.albunyaan.tube.player.ExtractionRateLimiter
import com.albunyaan.tube.player.GlobalStreamResolver
import com.albunyaan.tube.player.PlaybackFeatureFlags
import com.albunyaan.tube.player.PlayerRepository
import com.albunyaan.tube.player.DefaultStreamPrefetchService
import com.albunyaan.tube.player.MultiRepresentationMpdGenerator
import com.albunyaan.tube.player.StreamPrefetchService
import com.albunyaan.tube.player.SyntheticDashMpdRegistry
import com.albunyaan.tube.telemetry.LogTelemetryClient
import com.albunyaan.tube.telemetry.TelemetryClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    @Provides
    @Singleton
    fun provideOkHttpDownloader(
        okHttpClient: OkHttpClient,
        @ApplicationContext context: Context
    ): OkHttpDownloader {
        return OkHttpDownloader(okHttpClient, context.cacheDir)
    }

    @Provides
    @Singleton
    fun provideNewPipeExtractorClient(
        downloader: OkHttpDownloader,
        cache: MetadataCache,
        metrics: ExtractorMetricsReporter,
        featureFlags: PlaybackFeatureFlags
    ): NewPipeExtractorClient {
        return NewPipeExtractorClient(downloader, cache, metrics, featureFlags)
    }

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
        @Named("retrofitContentService") retrofitService: ContentService,
        @Named("fakeContentService") fakeService: ContentService
    ): ContentService {
        return FallbackContentService(retrofitService, fakeService)
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
     */
    @Provides
    @Singleton
    fun providePlayerRepository(globalResolver: GlobalStreamResolver): PlayerRepository {
        return DefaultPlayerRepository(globalResolver)
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
        featureFlags: PlaybackFeatureFlags
    ): StreamPrefetchService {
        return DefaultStreamPrefetchService(globalResolver, rateLimiter, mpdGenerator, mpdRegistry, featureFlags)
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
                    .maxSizePercent(0.25)
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
    fun provideImagesEnabled(): Boolean {
        return BuildConfig.ENABLE_THUMBNAIL_IMAGES
    }

    // Note: ChannelDetailRepository binding moved to ChannelDetailRepositoryModule
    // for easier test replacement via @TestInstallIn
}
