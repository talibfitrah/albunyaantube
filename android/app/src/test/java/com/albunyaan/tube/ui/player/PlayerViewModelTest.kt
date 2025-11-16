package com.albunyaan.tube.ui.player

import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.PlaybackSelection
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.VideoTrack
import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.model.Category
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.model.CursorResponse
import com.albunyaan.tube.data.source.ContentService
import com.albunyaan.tube.download.DownloadEntry
import com.albunyaan.tube.download.DownloadFileMetadata
import com.albunyaan.tube.download.DownloadRequest
import com.albunyaan.tube.download.DownloadStatus
import com.albunyaan.tube.player.PlayerRepository
import com.albunyaan.tube.policy.EulaManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import androidx.datastore.preferences.core.PreferenceDataStoreFactory

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val scope = TestScope(testDispatcher)
    private val eulaManager = EulaManager(
        PreferenceDataStoreFactory.create(scope = scope) {
            File.createTempFile("eula", ".preferences_pb").apply { deleteOnExit() }
        }
    )
    private val repository = object : PlayerRepository {
        override suspend fun resolveStreams(videoId: String): ResolvedStreams? {
            return ResolvedStreams(
                streamId = videoId,
                videoTracks = listOf(
                    VideoTrack(
                        url = "https://example.com/video.mp4",
                        mimeType = "video/mp4",
                        width = 1280,
                        height = 720,
                        bitrate = 1_500_000,
                        qualityLabel = "720p",
                        fps = 30
                    )
                ),
                audioTracks = listOf(
                    AudioTrack(
                        url = "https://example.com/audio.m4a",
                        mimeType = "audio/mp4",
                        bitrate = 128_000,
                        codec = "aac"
                    )
                ),
                durationSeconds = 600
            )
        }
    }

    private val downloadRepository = object : com.albunyaan.tube.download.DownloadRepository {
        override val downloads = MutableStateFlow<List<com.albunyaan.tube.download.DownloadEntry>>(emptyList())
        val enqueued = mutableListOf<com.albunyaan.tube.download.DownloadRequest>()
        override fun enqueue(request: com.albunyaan.tube.download.DownloadRequest) {
            enqueued += request
        }
        override fun pause(requestId: String) {}
        override fun resume(requestId: String) {}
        override fun cancel(requestId: String) {}
    }

    private val contentService = object : ContentService {
        override suspend fun fetchContent(
            type: ContentType,
            cursor: String?,
            pageSize: Int,
            filters: FilterState
        ): CursorResponse {
            return CursorResponse(data = emptyList(), pageInfo = null)
        }

        override suspend fun search(query: String, type: String?, limit: Int): List<ContentItem> {
            return emptyList()
        }

        override suspend fun fetchCategories(): List<Category> {
            return emptyList()
        }

        override suspend fun fetchSubcategories(parentId: String): List<Category> {
            return emptyList()
        }
    }

    @Test
    fun `hydrateQueue picks first playable item and filters exclusions`() = scope.runTest {
        val viewModel = PlayerViewModel(repository, downloadRepository, eulaManager, contentService, testDispatcher)
        advanceUntilIdle()
        val state = viewModel.state.value

        assertEquals("intro_foundations", state.currentItem?.id)
        assertTrue(state.upNext.all { !it.isExcluded })
        assertTrue(state.excludedItems.all { it.isExcluded })
        assertTrue(state.streamState is StreamState.Ready)
    }

    @Test
    fun `markCurrentComplete advances to next item and emits event`() = scope.runTest {
        val viewModel = PlayerViewModel(repository, downloadRepository, eulaManager, contentService, testDispatcher)
        advanceUntilIdle()

        val initialState = viewModel.state.value
        val initialCurrent = requireNotNull(initialState.currentItem)

        viewModel.markCurrentComplete()
        advanceUntilIdle()

        val updatedState = viewModel.state.value
        assertEquals("tafsir_baqara", updatedState.currentItem?.id)
        val lastEvent = updatedState.lastAnalyticsEvent
        assertNotNull(lastEvent)
        require(
            lastEvent is PlaybackAnalyticsEvent.StreamResolved ||
                lastEvent is PlaybackAnalyticsEvent.PlaybackStarted
        )
        assertTrue(updatedState.upNext.none { it.id == initialCurrent.id })
    }

    @Test
    fun `playItem moves selection and re-queues previous current`() = scope.runTest {
        val viewModel = PlayerViewModel(repository, downloadRepository, eulaManager, contentService, testDispatcher)
        advanceUntilIdle()

        val initialCurrent = requireNotNull(viewModel.state.value.currentItem)
        val target = requireNotNull(viewModel.state.value.upNext.lastOrNull())

        viewModel.playItem(target)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(target.id, state.currentItem?.id)
        assertTrue(state.upNext.any { it.id == initialCurrent.id })
        assertTrue(state.streamState is StreamState.Ready)
    }

    @Test
    fun `setAudioOnly ignores duplicate state`() = scope.runTest {
        val viewModel = PlayerViewModel(repository, downloadRepository, eulaManager, contentService, testDispatcher)
        advanceUntilIdle()

        viewModel.setAudioOnly(true)
        val firstEvent = viewModel.state.value.lastAnalyticsEvent

        viewModel.setAudioOnly(true)
        val secondEvent = viewModel.state.value.lastAnalyticsEvent

        assertEquals(firstEvent, secondEvent)
    }

    @Test
    fun `downloads flow updates current download metadata`() = scope.runTest {
        val viewModel = PlayerViewModel(repository, downloadRepository, eulaManager, contentService, testDispatcher)
        advanceUntilIdle()

        val metadata = DownloadFileMetadata(2_048_000, System.currentTimeMillis(), "audio/mp4")
        val request = DownloadRequest("M7lc1UVf-VE_1", "Foundations Orientation", "M7lc1UVf-VE", audioOnly = true)
        val entry = DownloadEntry(
            request = request,
            status = DownloadStatus.COMPLETED,
            progress = 100,
            filePath = "/tmp/${request.id}.m4a",
            metadata = metadata
        )

        downloadRepository.downloads.value = listOf(entry)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(entry, state.currentDownload)
    }

    @Test
    fun `downloadCurrent returns false until EULA accepted`() = scope.runTest {
        val viewModel = PlayerViewModel(repository, downloadRepository, eulaManager, contentService, testDispatcher)
        advanceUntilIdle()

        val allowedBefore = viewModel.downloadCurrent()
        assertTrue(!allowedBefore)
        assertTrue(downloadRepository.enqueued.isEmpty())

        eulaManager.setAccepted(true)
        advanceUntilIdle()

        val allowedAfter = viewModel.downloadCurrent()
        assertTrue(allowedAfter)
        assertTrue(downloadRepository.enqueued.isNotEmpty())
    }
}
