package com.albunyaan.tube.ui.player

import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.VideoTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Owner issue 1 (a): the player shows the resolved stream's title (videoDetails.title), not the list row's hl=en translation. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PlayerViewModelOriginalTitleTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository = FakePlayerRepository()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    // Ids are not 11 characters, so maybeEnumerateDubs never makes its MWEB request.
    private fun streams(id: String = "vid", title: String? = ORIGINAL, isLive: Boolean = false) = ResolvedStreams(
        streamId = id,
        videoTracks = listOf(VideoTrack("https://v.test/$id", "video/mp4", 1280, 720, 2_000_000, "720p", 30, isVideoOnly = false)),
        audioTracks = listOf(AudioTrack("https://a.test/$id", "audio/mp4", 128_000, "mp4a")),
        durationSeconds = 300,
        hlsUrl = if (isLive) "https://h.test/$id.m3u8" else null,
        isLive = isLive,
        title = title,
    )

    private inline fun PlayerViewModel.cancelAfter(block: () -> Unit) = try { block() } finally { viewModelScope.cancel() }

    @Test fun `the resolved original title replaces the list row title`() = runTest(dispatcher) {
        repository.resolvedStreams = streams()
        val vm = playerViewModel(repository = repository)
        vm.cancelAfter {
            vm.loadVideo(videoId = "vid", title = LIST_TITLE)
            advanceUntilIdle()

            assertTrue(vm.state.value.streamState is StreamState.Ready)
            assertEquals(ORIGINAL, vm.state.value.currentItem?.title)
        }
    }

    @Test fun `a resolve without a title keeps the list row title`() = runTest(dispatcher) {
        repository.resolvedStreams = streams(title = null)
        val vm = playerViewModel(repository = repository)
        vm.cancelAfter {
            vm.loadVideo(videoId = "vid", title = LIST_TITLE)
            advanceUntilIdle()

            assertTrue(vm.state.value.streamState is StreamState.Ready)
            assertEquals(LIST_TITLE, vm.state.value.currentItem?.title)
        }
    }

    @Test fun `the original title is current when the stream turns Ready`() = runTest(dispatcher) {
        // The fragment syncs the MediaSession from state.currentItem at the Ready-driven prepare, and never again.
        repository.resolvedStreams = streams()
        val vm = playerViewModel(repository = repository)
        vm.cancelAfter {
            val seen = mutableListOf<PlayerState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.toList(seen) }
            vm.loadVideo(videoId = "vid", title = LIST_TITLE)
            advanceUntilIdle()

            assertEquals(ORIGINAL, seen.first { it.streamState is StreamState.Ready }.currentItem?.title)
        }
    }

    @Test fun `a tap-prefetched stream brings its original title too`() = runTest(dispatcher) {
        val vm = playerViewModel(repository = repository, prefetchService = FakePrefetchService(streams()))
        vm.cancelAfter {
            vm.loadVideo(videoId = "vid", title = LIST_TITLE)
            advanceUntilIdle()

            assertEquals(0, repository.resolveCallCount)
            assertEquals(ORIGINAL, vm.state.value.currentItem?.title)
        }
    }

    @Test fun `a live channel shows the original title and recovers on the backend's`() = runTest(dispatcher) {
        // Codex C2: LiveChannelRecovery's D12 match compares the backend's own title field, never the display title.
        repository.resolvedStreams = streams(id = "live1", isLive = true)
        val content = com.albunyaan.tube.data.source.FakeContentService()
        val vm = playerViewModel(repository = repository, contentService = content, ioDispatcher = dispatcher)
        vm.cancelAfter {
            vm.loadVideo(videoId = "live1", title = LIST_TITLE, sourceChannelId = "UCx", liveChannelId = "UCx")
            runCurrent()
            assertTrue(vm.state.value.streamState is StreamState.Ready)
            assertEquals(ORIGINAL, vm.state.value.currentItem?.title)

            content.channelLives["UCx"] = com.albunyaan.tube.data.source.api.ChannelLives(listOf(
                com.albunyaan.tube.data.source.api.LiveItem("otherShow01", ORIGINAL),
                com.albunyaan.tube.data.source.api.LiveItem("liveSunnah2", LIST_TITLE),
            ))
            // Two languages: an 11-character id would otherwise send maybeEnumerateDubs to MWEB.
            repository.resolvedStreams = streams(id = "liveSunnah2", isLive = true).let {
                it.copy(audioTracks = it.audioTracks + AudioTrack("https://a.test/ar", "audio/mp4", 48_000, "mp4a", language = "ar"))
            }
            vm.markCurrentComplete()
            runCurrent()

            assertEquals("liveSunnah2", vm.state.value.currentItem?.streamId)
        }
    }

    private companion object {
        const val LIST_TITLE = "Juz Amma (list)"
        const val ORIGINAL = "جزء عم المعلم مشاري راشد العفاسي"
    }
}
