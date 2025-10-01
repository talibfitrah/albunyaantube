package com.albunyaan.tube.ui.player

import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.PlaybackSelection
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.VideoTrack
import com.albunyaan.tube.player.PlayerRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val scope = TestScope(testDispatcher)
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

    @Test
    fun `hydrateQueue picks first playable item and filters exclusions`() = scope.runTest {
        val viewModel = PlayerViewModel(repository, testDispatcher)
        advanceUntilIdle()
        val state = viewModel.state.value

        assertEquals("intro_foundations", state.currentItem?.id)
        assertTrue(state.upNext.all { !it.isExcluded })
        assertTrue(state.excludedItems.all { it.isExcluded })
        assertTrue(state.streamState is StreamState.Ready)
    }

    @Test
    fun `markCurrentComplete advances to next item and emits event`() = scope.runTest {
        val viewModel = PlayerViewModel(repository, testDispatcher)
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
        val viewModel = PlayerViewModel(repository, testDispatcher)
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
        val viewModel = PlayerViewModel(repository, testDispatcher)
        advanceUntilIdle()

        viewModel.setAudioOnly(true)
        val firstEvent = viewModel.state.value.lastAnalyticsEvent

        viewModel.setAudioOnly(true)
        val secondEvent = viewModel.state.value.lastAnalyticsEvent

        assertEquals(firstEvent, secondEvent)
    }
}
