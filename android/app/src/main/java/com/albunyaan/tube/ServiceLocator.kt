package com.albunyaan.tube

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.albunyaan.tube.analytics.ExtractorMetricsReporter
import com.albunyaan.tube.analytics.LogExtractorMetricsReporter
import com.albunyaan.tube.analytics.ListMetricsReporter
import com.albunyaan.tube.analytics.LogListMetricsReporter
import com.albunyaan.tube.analytics.TelemetryExtractorMetricsReporter
import com.albunyaan.tube.telemetry.LogTelemetryClient
import com.albunyaan.tube.telemetry.TelemetryClient
import com.albunyaan.tube.download.DefaultDownloadRepository
import com.albunyaan.tube.download.DownloadRepository
import com.albunyaan.tube.download.DownloadScheduler
import com.albunyaan.tube.download.DownloadStorage
import com.albunyaan.tube.data.extractor.MetadataHydrator
import com.albunyaan.tube.data.extractor.NoOpMetadataHydrator
import com.albunyaan.tube.data.extractor.NewPipeExtractorClient
import com.albunyaan.tube.data.extractor.OkHttpDownloader
import com.albunyaan.tube.data.extractor.cache.MetadataCache
import com.albunyaan.tube.data.filters.FilterManager
import com.albunyaan.tube.data.paging.ContentPagingRepository
import com.albunyaan.tube.data.paging.DefaultContentPagingRepository
import com.albunyaan.tube.data.source.ContentService
import com.albunyaan.tube.data.source.FakeContentService
import com.albunyaan.tube.data.source.FallbackContentService
import com.albunyaan.tube.data.source.RetrofitContentService
import com.albunyaan.tube.data.source.api.ContentApi
import com.albunyaan.tube.player.DefaultPlayerRepository
import com.albunyaan.tube.player.PlayerRepository
import androidx.work.WorkManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.albunyaan.tube.policy.EulaManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache

object ServiceLocator {

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var overrideDownloadRepository: DownloadRepository? = null
    @Volatile
    private var overrideDownloadStorage: DownloadStorage? = null
    @Volatile
    private var overrideMetrics: ExtractorMetricsReporter? = null
    @Volatile
    private var overrideTelemetryClient: TelemetryClient? = null
    @Volatile
    private var overrideEulaManager: EulaManager? = null
    @Volatile
    private var overrideImageLoader: ImageLoader? = null
    @Volatile
    private var overrideImagesEnabled: Boolean? = null

    private val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = scope) {
            File(appContext.filesDir, "filters.preferences_pb")
        }
    }

    private val policyDataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = scope) {
            File(appContext.filesDir, "policy.preferences_pb")
        }
    }

    private val filterManager: FilterManager by lazy { FilterManager(dataStore, scope) }
    private val telemetryClient: TelemetryClient by lazy { LogTelemetryClient() }
    private val extractorMetrics: ExtractorMetricsReporter by lazy {
        TelemetryExtractorMetricsReporter(LogExtractorMetricsReporter(), telemetryClient)
    }
    private val extractorCache: MetadataCache by lazy { MetadataCache(ttlMillis = 15 * 60 * 1000L, maxEntriesPerBucket = 200) }
    private val extractorDownloader: OkHttpDownloader by lazy { OkHttpDownloader(httpClient) }
    private val extractorClient by lazy {
        NewPipeExtractorClient(extractorDownloader, extractorCache, extractorMetrics)
    }
    // Use NoOpMetadataHydrator since backend provides complete data (fast loading)
    private val metadataHydrator: MetadataHydrator by lazy { NoOpMetadataHydrator() }
    private val retrofitContentService: ContentService by lazy { RetrofitContentService(contentApi, metadataHydrator) }
    private val fakeContentService: ContentService by lazy { FakeContentService() }
    private val contentService: ContentService by lazy { FallbackContentService(retrofitContentService, fakeContentService) }
    private val pagingRepository: ContentPagingRepository by lazy { DefaultContentPagingRepository(contentService) }
    private val listMetricsReporter: ListMetricsReporter by lazy { LogListMetricsReporter() }
    private val playerRepository: PlayerRepository by lazy { DefaultPlayerRepository(extractorClient) }
    private val downloadScheduler: DownloadScheduler by lazy { DownloadScheduler(workManager) }
    private val downloadStorage: DownloadStorage by lazy { DownloadStorage(appContext) }
    private val downloadRepository: DownloadRepository by lazy {
        DefaultDownloadRepository(workManager, downloadScheduler, downloadStorage, extractorMetrics, scope)
    }
    private val eulaManager: EulaManager by lazy { EulaManager(policyDataStore) }
    private val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(appContext)
            .crossfade(true)
            .memoryCache {
                MemoryCache.Builder(appContext)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(appContext.cacheDir, "coil_image_cache"))
                    .maxSizeBytes(60L * 1024 * 1024)
                    .build()
            }
            .allowHardware(false)
            .respectCacheHeaders(false)
            .build()
    }

    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }


    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .cache(
                okhttp3.Cache(
                    directory = File(appContext.cacheDir, "http_cache"),
                    maxSize = 30L * 1024 * 1024 // 30 MB
                )
            )
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }







    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(httpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    private val contentApi: ContentApi by lazy { retrofit.create(ContentApi::class.java) }
    private val workManager: WorkManager by lazy { WorkManager.getInstance(appContext) }

    fun init(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
        }
    }

    fun provideFilterManager(): FilterManager = filterManager

    fun provideContentService(): ContentService = contentService

    fun provideContentRepository(): ContentPagingRepository = pagingRepository

    fun provideListMetricsReporter(): ListMetricsReporter = listMetricsReporter

    fun providePlayerRepository(): PlayerRepository = playerRepository

    fun provideDownloadRepository(): DownloadRepository = overrideDownloadRepository ?: downloadRepository

    fun provideExtractorMetricsReporter(): ExtractorMetricsReporter = overrideMetrics ?: extractorMetrics

    fun provideTelemetryClient(): TelemetryClient = overrideTelemetryClient ?: telemetryClient

    fun provideDownloadStorage(): DownloadStorage = overrideDownloadStorage ?: downloadStorage

    fun provideEulaManager(): EulaManager = overrideEulaManager ?: eulaManager

    fun provideImageLoader(): ImageLoader = overrideImageLoader ?: imageLoader

    fun isImageLoadingEnabled(): Boolean = overrideImagesEnabled ?: BuildConfig.ENABLE_THUMBNAIL_IMAGES

    fun setDownloadRepositoryForTesting(repository: DownloadRepository?) {
        overrideDownloadRepository = repository
    }

    fun setDownloadStorageForTesting(storage: DownloadStorage?) {
        overrideDownloadStorage = storage
    }

    fun setExtractorMetricsForTesting(metrics: ExtractorMetricsReporter?) {
        overrideMetrics = metrics
    }

    fun setTelemetryClientForTesting(client: TelemetryClient?) {
        overrideTelemetryClient = client
    }

    fun setEulaManagerForTesting(manager: EulaManager?) {
        overrideEulaManager = manager
    }

    fun setImageLoaderForTesting(loader: ImageLoader?) {
        overrideImageLoader = loader
    }

    fun setImagesEnabledForTesting(enabled: Boolean?) {
        overrideImagesEnabled = enabled
    }
}
