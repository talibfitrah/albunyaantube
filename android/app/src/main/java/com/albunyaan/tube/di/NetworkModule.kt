package com.albunyaan.tube.di

import android.content.Context
import com.albunyaan.tube.BuildConfig
import com.albunyaan.tube.data.source.api.ContentApi
import com.albunyaan.tube.data.source.api.DownloadApi
import com.albunyaan.tube.data.source.api.ReportApi
import com.squareup.moshi.FromJson
import com.squareup.moshi.Moshi
import com.squareup.moshi.ToJson
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit
import com.albunyaan.tube.player.ExtractionRateLimiter
import com.albunyaan.tube.player.StreamRequestTelemetry
import com.albunyaan.tube.player.StreamUrlRefreshManager
import javax.inject.Singleton

/**
 * P3-T1: Network DI Module
 *
 * Provides network-related dependencies: OkHttpClient, Retrofit, Moshi, APIs
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideExtractionRateLimiter(): ExtractionRateLimiter {
        return ExtractionRateLimiter()
    }

    @Provides
    @Singleton
    fun provideStreamRequestTelemetry(): StreamRequestTelemetry {
        return StreamRequestTelemetry()
    }

    @Provides
    @Singleton
    fun provideStreamUrlRefreshManager(
        telemetry: StreamRequestTelemetry,
        rateLimiter: ExtractionRateLimiter
    ): StreamUrlRefreshManager {
        return StreamUrlRefreshManager(telemetry, rateLimiter)
    }

    @Provides
    @Singleton
    fun provideMoshi(): Moshi {
        return Moshi.Builder()
            .add(OffsetDateTimeAdapter())
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient {
        // One-time cleanup: delete stale HTTP cache left by prior versions that
        // cached API responses (e.g. categories missing due to a backend bug).
        // Runs on a background thread to avoid blocking DI initialization.
        val cacheDir = File(context.cacheDir, "http_cache")
        if (cacheDir.exists()) {
            Thread { cacheDir.deleteRecursively() }.start()
        }

        val deviceId = getOrCreateDeviceId(context)

        return OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("X-Device-Id", deviceId)
                        .build()
                )
            }
            // No HTTP cache — API responses are admin-curated and change frequently.
            // Server-side Caffeine cache handles backend performance.
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private fun getOrCreateDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
        val existing = prefs.getString("device_id", null)
        if (existing != null) return existing
        val newId = java.util.UUID.randomUUID().toString()
        prefs.edit().putString("device_id", newId).apply()
        return newId
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    @Provides
    @Singleton
    fun provideContentApi(retrofit: Retrofit): ContentApi {
        return retrofit.create(ContentApi::class.java)
    }

    @Provides
    @Singleton
    fun provideDownloadApi(retrofit: Retrofit): DownloadApi {
        return retrofit.create(DownloadApi::class.java)
    }

    @Provides
    @Singleton
    fun provideReportApi(retrofit: Retrofit): ReportApi {
        return retrofit.create(ReportApi::class.java)
    }
}

/** Moshi adapter for java.time.OffsetDateTime used in OpenAPI-generated models. */
class OffsetDateTimeAdapter {
    @FromJson
    fun fromJson(value: String?): OffsetDateTime? {
        return value?.let {
            try {
                OffsetDateTime.parse(it)
            } catch (e: DateTimeParseException) {
                android.util.Log.w("OffsetDateTimeAdapter", "Failed to parse OffsetDateTime: \"$it\"", e)
                null
            }
        }
    }

    @ToJson
    fun toJson(value: OffsetDateTime?): String? {
        return value?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }
}
