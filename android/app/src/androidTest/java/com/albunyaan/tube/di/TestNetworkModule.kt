package com.albunyaan.tube.di

import com.albunyaan.tube.data.source.api.ContentApi
import com.albunyaan.tube.data.source.api.CursorPage
import com.albunyaan.tube.data.source.api.DownloadApi
import com.albunyaan.tube.data.model.api.models.Category
import com.albunyaan.tube.data.model.api.models.ContentItemDto
import com.albunyaan.tube.data.model.api.models.DownloadCompletedEvent
import com.albunyaan.tube.data.model.api.models.DownloadFailedEvent
import com.albunyaan.tube.data.model.api.models.DownloadManifestDto
import com.albunyaan.tube.data.model.api.models.DownloadPolicyDto
import com.albunyaan.tube.data.model.api.models.DownloadStartedEvent
import com.albunyaan.tube.data.model.api.models.DownloadTokenDto
import com.albunyaan.tube.data.model.api.models.DownloadTokenRequest
import com.albunyaan.tube.data.model.api.models.PageInfo
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Test replacement for NetworkModule that provides fake/stub implementations.
 *
 * This module replaces the production NetworkModule in instrumentation tests,
 * preventing tests from hitting the real backend. Tests can override specific
 * dependencies using @BindValue if needed.
 *
 * Usage:
 * - Tests automatically get these fakes when using @HiltAndroidTest
 * - For custom behavior, use @BindValue to provide your own implementation
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [NetworkModule::class]
)
object TestNetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        // Minimal client for tests that don't need network
        return OkHttpClient.Builder()
            .build()
    }

    @Provides
    @Singleton
    fun provideContentApi(): ContentApi {
        return FakeContentApi()
    }

    @Provides
    @Singleton
    fun provideDownloadApi(): DownloadApi {
        return FakeDownloadApi()
    }
}

/**
 * Fake ContentApi that returns empty/stub responses.
 * Tests can use @BindValue to provide custom implementations for specific test cases.
 */
class FakeContentApi : ContentApi {

    override suspend fun fetchContent(
        type: String,
        cursor: String?,
        limit: Int,
        category: String?,
        length: String?,
        date: String?,
        sort: String?
    ): CursorPage {
        return CursorPage(
            data = emptyList(),
            pageInfo = PageInfo(
                hasNext = false,
                nextCursor = null
            )
        )
    }

    override suspend fun fetchCategories(): List<Category> {
        return emptyList()
    }

    override suspend fun search(
        query: String,
        type: String?,
        limit: Int
    ): List<ContentItemDto> {
        return emptyList()
    }
}

/**
 * Fake DownloadApi that returns stub responses.
 */
class FakeDownloadApi : DownloadApi {

    override suspend fun getDownloadPolicy(videoId: String): DownloadPolicyDto {
        return DownloadPolicyDto(
            allowed = true,
            reason = null,
            requiresEula = false
        )
    }

    override suspend fun generateDownloadToken(
        videoId: String,
        request: DownloadTokenRequest
    ): DownloadTokenDto {
        return DownloadTokenDto(
            token = "fake-token-${videoId}",
            expiresAtMillis = System.currentTimeMillis() + 3600000,
            videoId = videoId
        )
    }

    override suspend fun getDownloadManifest(
        videoId: String,
        token: String,
        supportsMerging: Boolean
    ): DownloadManifestDto {
        return DownloadManifestDto(
            videoId = videoId,
            title = "Fake Video",
            expiresAtMillis = System.currentTimeMillis() + 3600000,
            videoStreams = emptyList(),
            audioStreams = emptyList()
        )
    }

    override suspend fun trackDownloadStarted(event: DownloadStartedEvent) {
        // No-op for tests
    }

    override suspend fun trackDownloadCompleted(event: DownloadCompletedEvent) {
        // No-op for tests
    }

    override suspend fun trackDownloadFailed(event: DownloadFailedEvent) {
        // No-op for tests
    }
}
