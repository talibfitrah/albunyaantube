package com.albunyaan.tube

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesDataStoreFile
import com.albunyaan.tube.data.filters.FilterManager
import com.albunyaan.tube.data.paging.ContentPagingRepository
import com.albunyaan.tube.data.paging.DefaultContentPagingRepository
import com.albunyaan.tube.data.source.ContentService
import com.albunyaan.tube.data.source.FakeContentService
import com.albunyaan.tube.data.source.FallbackContentService
import com.albunyaan.tube.data.source.RetrofitContentService
import com.albunyaan.tube.data.source.api.ContentApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
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

    private val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = scope) {
            appContext.preferencesDataStoreFile("filters.preferences")
        }
    }

    private val filterManager: FilterManager by lazy { FilterManager(dataStore, scope) }
    private val retrofitContentService: ContentService by lazy { RetrofitContentService(contentApi) }
    private val fakeContentService: ContentService by lazy { FakeContentService() }
    private val contentService: ContentService by lazy { FallbackContentService(retrofitContentService, fakeContentService) }
    private val pagingRepository: ContentPagingRepository by lazy { DefaultContentPagingRepository(contentService) }

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

    fun init(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
        }
    }

    fun provideFilterManager(): FilterManager = filterManager

    fun provideContentRepository(): ContentPagingRepository = pagingRepository
}
