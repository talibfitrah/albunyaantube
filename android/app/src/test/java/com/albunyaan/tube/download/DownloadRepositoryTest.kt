package com.albunyaan.tube.download

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.albunyaan.tube.analytics.ExtractorMetricsReporter
import com.albunyaan.tube.data.model.ContentType
import java.io.File
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DownloadRepositoryTest {

    private lateinit var context: Context
    private lateinit var storage: DownloadStorage
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        deleteDownloadsDir()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .build()
        )
        workManager = WorkManager.getInstance(context)
        storage = DownloadStorage(context)
    }

    @After
    fun tearDown() {
        try {
            workManager.cancelAllWork().result.get()
            workManager.pruneWork().result.get()
            WorkManagerTestInitHelper.closeWorkDatabase()
        } catch (_: IllegalStateException) {
            // WorkManager may already be torn down by Robolectric between tests.
        }
        deleteDownloadsDir()
    }

    @Test
    fun `refreshPersistedDownloads surfaces completed file committed after repository init`() =
        runTest(UnconfinedTestDispatcher()) {
            val repository = repository(this)
            advanceUntilIdle()
            assertEquals(emptyList<DownloadEntry>(), repository.downloads.value)

            val downloadId = "video_123_1700000000000"
            storage.saveExtendedMetadata(
                downloadId = downloadId,
                title = "Persisted Video",
                thumbnailUrl = "https://example.test/thumb.jpg"
            )
            val tempFile = storage.createTempFile(downloadId)
            tempFile.writeBytes(byteArrayOf(1, 2, 3, 4))
            val committedFile = storage.commit(downloadId, audioOnly = false, tempFile)

            repository.refreshPersistedDownloads()

            val entry = repository.downloads.value.singleOrNull()
            assertNotNull(entry)
            requireNotNull(entry)
            assertEquals(downloadId, entry.request.id)
            assertEquals("video_123", entry.request.videoId)
            assertEquals("Persisted Video", entry.request.title)
            assertEquals(DownloadStatus.COMPLETED, entry.status)
            assertEquals(100, entry.progress)
            assertEquals(committedFile.absolutePath, entry.filePath)
            assertEquals(4L, entry.metadata?.sizeBytes)
        }

    @Test
    fun `refreshPersistedDownloads does not mark active WorkManager download completed from stale file`() =
        runTest {
            val downloadId = "active_video_1700000000000"
            enqueueDelayedDownloadWork(downloadId)
            storage.saveExtendedMetadata(
                downloadId = downloadId,
                title = "Active Video",
                thumbnailUrl = null
            )
            val tempFile = storage.createTempFile(downloadId)
            tempFile.writeBytes(byteArrayOf(9, 8, 7, 6))
            storage.commit(downloadId, audioOnly = false, tempFile)

            val dispatcher = StandardTestDispatcher(testScheduler)
            val repositoryScope = TestScope(dispatcher)
            val repository = repository(repositoryScope)

            repository.refreshPersistedDownloads()
            val entry = repository.downloads.value.single()
            assertEquals(downloadId, entry.request.id)
            assertEquals(DownloadStatus.QUEUED, entry.status)
            assertEquals(0, entry.progress)

            repositoryScope.advanceUntilIdle()
            assertEquals(DownloadStatus.QUEUED, repository.downloads.value.single().status)
        }

    @Test
    fun `refreshPersistedDownloads preserves active request audioOnly over stale video file`() =
        runTest {
            val downloadId = "active_audio_1700000000000"
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repositoryScope = TestScope(dispatcher)
            val repository = repository(repositoryScope)
            repository.enqueue(
                DownloadRequest(
                    id = downloadId,
                    title = "Active Audio",
                    videoId = "active_audio",
                    audioOnly = true
                )
            )
            val tempFile = storage.createTempFile(downloadId)
            tempFile.writeBytes(byteArrayOf(5, 4, 3, 2))
            storage.commit(downloadId, audioOnly = false, tempFile)

            repository.refreshPersistedDownloads()

            val entry = repository.downloads.value.single()
            assertEquals(DownloadStatus.QUEUED, entry.status)
            assertEquals(true, entry.request.audioOnly)
            assertEquals(null, entry.filePath)
        }

    @Test
    fun `refreshPersistedDownloads restores active audio metadata after process death`() =
        runTest {
            val downloadId = "restart_audio_1700000000000"
            enqueueDelayedDownloadWork(downloadId)
            storage.saveExtendedMetadata(
                downloadId = downloadId,
                title = "Restart Audio",
                thumbnailUrl = null,
                audioOnly = true
            )
            val tempFile = storage.createTempFile(downloadId)
            tempFile.writeBytes(byteArrayOf(3, 2, 1))
            storage.commit(downloadId, audioOnly = false, tempFile)

            val dispatcher = StandardTestDispatcher(testScheduler)
            val repositoryScope = TestScope(dispatcher)
            val repository = repository(repositoryScope)
            repository.refreshPersistedDownloads()

            val entry = repository.downloads.value.single()
            assertEquals(downloadId, entry.request.id)
            assertEquals(DownloadStatus.QUEUED, entry.status)
            assertEquals(true, entry.request.audioOnly)
            assertEquals(null, entry.filePath)
        }

    @Test
    fun `isAudioOnlyDownload uses persisted metadata when no committed file exists`() {
        val downloadId = "metadata_audio_1700000000000"
        storage.saveExtendedMetadata(
            downloadId = downloadId,
            title = "Metadata Audio",
            thumbnailUrl = null,
            audioOnly = true
        )

        assertEquals(true, storage.isAudioOnlyDownload(downloadId))
    }

    @Test
    fun `refreshPersistedDownloads removes completed entry when committed file disappears`() =
        runTest(UnconfinedTestDispatcher()) {
            val repository = repository(this)
            advanceUntilIdle()
            val downloadId = "deleted_video_1700000000000"
            storage.saveExtendedMetadata(
                downloadId = downloadId,
                title = "Deleted Video",
                thumbnailUrl = null,
                audioOnly = false
            )
            val tempFile = storage.createTempFile(downloadId)
            tempFile.writeBytes(byteArrayOf(7, 6, 5))
            val committedFile = storage.commit(downloadId, audioOnly = false, tempFile)
            repository.refreshPersistedDownloads()
            assertEquals(DownloadStatus.COMPLETED, repository.downloads.value.single().status)

            committedFile.delete()
            repository.refreshPersistedDownloads()

            assertEquals(emptyList<DownloadEntry>(), repository.downloads.value)
        }

    private fun repository(scope: CoroutineScope): DefaultDownloadRepository =
        DefaultDownloadRepository(
            workManager = workManager,
            scheduler = DownloadScheduler(workManager),
            storage = storage,
            metrics = NoopMetricsReporter,
            expiryPolicy = DownloadExpiryPolicy(Clock.systemUTC()),
            scope = scope
        )

    private fun enqueueDelayedDownloadWork(downloadId: String) {
        val request = OneTimeWorkRequestBuilder<DelayedDownloadWorker>()
            .setInitialDelay(1, TimeUnit.DAYS)
            .addTag("com.albunyaan.tube.download")
            .addTag("download_$downloadId")
            .build()
        workManager
            .beginUniqueWork(downloadId, ExistingWorkPolicy.REPLACE, request)
            .enqueue()
            .result
            .get()
    }

    private fun deleteDownloadsDir() {
        File(context.filesDir, "downloads").deleteRecursively()
    }

    private object NoopMetricsReporter : ExtractorMetricsReporter {
        override fun onCacheHit(type: ContentType, hitCount: Int) = Unit
        override fun onCacheMiss(type: ContentType, missCount: Int) = Unit
        override fun onFetchSuccess(type: ContentType, fetchedCount: Int, durationMillis: Long) = Unit
        override fun onFetchFailure(type: ContentType, ids: List<String>, throwable: Throwable) = Unit
    }
}

class DelayedDownloadWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result = Result.success()
}
