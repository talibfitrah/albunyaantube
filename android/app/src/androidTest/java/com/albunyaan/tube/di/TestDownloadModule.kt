package com.albunyaan.tube.di

import android.content.Context
import androidx.work.WorkManager
import com.albunyaan.tube.download.DownloadEntry
import com.albunyaan.tube.download.DownloadRepository
import com.albunyaan.tube.download.DownloadRequest
import com.albunyaan.tube.download.DownloadScheduler
import com.albunyaan.tube.download.DownloadStorage
import com.albunyaan.tube.download.PlaylistDownloadItem
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Clock
import javax.inject.Singleton

/**
 * Test replacement for DownloadModule that provides test-friendly implementations.
 *
 * This module replaces production DownloadModule in instrumentation tests,
 * providing WorkManager test instance and other dependencies.
 *
 * Note: DownloadRepository is NOT provided here - tests should use @BindValue
 * to provide their own FakeDownloadRepository for full control.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [DownloadModule::class]
)
object TestDownloadModule {

    @Provides
    @Singleton
    fun provideClock(): Clock {
        return Clock.systemUTC()
    }

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager {
        // Use test WorkManager - must be initialized in test setup
        return WorkManager.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideDownloadScheduler(workManager: WorkManager): DownloadScheduler {
        return DownloadScheduler(workManager)
    }

    @Provides
    @Singleton
    fun provideDownloadStorage(@ApplicationContext context: Context): DownloadStorage {
        return DownloadStorage(context)
    }

    @Provides
    @Singleton
    fun provideDownloadRepository(): DownloadRepository {
        // Shared fake repository for all tests. Tests can access and configure
        // via TestDownloadModule.fakeRepository.
        return fakeRepository
    }

    /**
     * Shared fake repository instance accessible by tests for configuration.
     *
     * Usage in tests:
     * ```
     * TestDownloadModule.fakeRepository.emit(listOf(entry))
     * assert(TestDownloadModule.fakeRepository.actions.contains("pause:id"))
     * ```
     */
    val fakeRepository = FakeDownloadRepository()
}

/**
 * Fake DownloadRepository for instrumentation tests.
 *
 * Tracks actions and allows emitting download entries for UI testing.
 */
class FakeDownloadRepository : DownloadRepository {
    private val _downloads = MutableStateFlow<List<DownloadEntry>>(emptyList())
    val actions = mutableListOf<String>()

    override val downloads: StateFlow<List<DownloadEntry>> = _downloads.asStateFlow()

    override fun enqueue(request: DownloadRequest) {
        actions += "enqueue:${request.id}"
    }

    override fun pause(requestId: String) {
        actions += "pause:$requestId"
    }

    override fun resume(requestId: String) {
        actions += "resume:$requestId"
    }

    override fun cancel(requestId: String) {
        actions += "cancel:$requestId"
    }

    override fun enqueuePlaylist(
        playlistId: String,
        playlistTitle: String,
        qualityLabel: String,
        items: List<PlaylistDownloadItem>,
        audioOnly: Boolean,
        targetHeight: Int?
    ): Int {
        actions += "enqueuePlaylist:$playlistId:$qualityLabel:${items.size}:audioOnly=$audioOnly:targetHeight=$targetHeight"
        return items.size
    }

    override fun isPlaylistDownloading(playlistId: String, qualityLabel: String): Boolean {
        // For tests, default to false
        return false
    }

    fun emit(entries: List<DownloadEntry>) {
        _downloads.value = entries
    }

    fun clear() {
        _downloads.value = emptyList()
        actions.clear()
    }
}
