package com.albunyaan.tube

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.albunyaan.tube.analytics.ExtractorMetricsReporter
import com.albunyaan.tube.analytics.LogExtractorMetricsReporter
import com.albunyaan.tube.analytics.ListMetricsReporter
import com.albunyaan.tube.analytics.LogListMetricsReporter
import com.albunyaan.tube.download.DefaultDownloadRepository
import com.albunyaan.tube.download.DownloadRepository
import com.albunyaan.tube.download.DownloadScheduler
import com.albunyaan.tube.download.DownloadStorage
import com.albunyaan.tube.data.extractor.MetadataHydrator
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
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

object ServiceLocator {

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var overrideDownloadRepository: DownloadRepository? = null
    @Volatile
    private var overrideDownloadStorage: DownloadStorage? = null
    @Volatile
    private var overrideMetrics: ExtractorMetricsReporter? = null

    private val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = scope) {
            File(appContext.filesDir, "filters.preferences_pb")
        }
    }

    private val filterManager: FilterManager by lazy { FilterManager(dataStore, scope) }
    private val extractorMetrics: ExtractorMetricsReporter by lazy { LogExtractorMetricsReporter() }
    private val extractorCache: MetadataCache by lazy { MetadataCache(ttlMillis = 15 * 60 * 1000L, maxEntriesPerBucket = 200) }
    private val extractorDownloader: OkHttpDownloader by lazy { OkHttpDownloader(httpClient) }
    private val extractorClient by lazy {
        NewPipeExtractorClient(extractorDownloader, extractorCache, extractorMetrics)
    }
    private val metadataHydrator: MetadataHydrator by lazy { MetadataHydrator(extractorClient) }
    private val retrofitContentService: ContentService by lazy { RetrofitContentService(contentApi, metadataHydrator) }
    private val fakeContentService: ContentService by lazy { FakeContentService() }
    private val contentService: ContentService by lazy { FallbackContentService(retrofitContentService, fakeContentService) }
    private val pagingRepository: ContentPagingRepository by lazy { DefaultContentPagingRepository(contentService) }
    private val listMetricsReporter: ListMetricsReporter by lazy { LogListMetricsReporter() }
    private val playerRepository: PlayerRepository by lazy { DefaultPlayerRepository(extractorClient) }
    private val downloadScheduler: DownloadScheduler by lazy { DownloadScheduler(workManager) }
    private val downloadStorage: DownloadStorage by lazy { DownloadStorage(appContext, DOWNLOAD_QUOTA_BYTES) }
    private val downloadRepository: DownloadRepository by lazy {
        DefaultDownloadRepository(workManager, downloadScheduler, downloadStorage, extractorMetrics, scope)
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

    fun provideContentRepository(): ContentPagingRepository = pagingRepository

    fun provideListMetricsReporter(): ListMetricsReporter = listMetricsReporter

    fun providePlayerRepository(): PlayerRepository = playerRepository

    fun provideDownloadRepository(): DownloadRepository = overrideDownloadRepository ?: downloadRepository

    fun provideExtractorMetricsReporter(): ExtractorMetricsReporter = overrideMetrics ?: extractorMetrics

    fun provideDownloadStorage(): DownloadStorage = overrideDownloadStorage ?: downloadStorage

    fun setDownloadRepositoryForTesting(repository: DownloadRepository?) {
        overrideDownloadRepository = repository
    }

    fun setDownloadStorageForTesting(storage: DownloadStorage?) {
        overrideDownloadStorage = storage
    }

    fun setExtractorMetricsForTesting(metrics: ExtractorMetricsReporter?) {
        overrideMetrics = metrics
    }

    private const val DOWNLOAD_QUOTA_BYTES = 500L * 1024 * 1024 // 500 MB
}
