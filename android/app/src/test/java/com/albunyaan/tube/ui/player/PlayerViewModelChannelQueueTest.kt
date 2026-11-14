package com.albunyaan.tube.ui.player

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.testing.WorkManagerTestInitHelper.ExecutorsMode
import com.albunyaan.tube.data.channel.ChannelVideo
import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.VideoTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner request: a video opened from a channel's Videos tab gets that channel's later videos as Up next, in channel
 * order (newest to oldest), paged like a playlist. Not found within the deep-start limits: Up next stays empty.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PlayerViewModelChannelQueueTest {

    private val testDispatcher = StandardTestDispatcher()
    private val fakePlayerRepository = FakePlayerRepository()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // Same WorkManager pinning as PlayerViewModelPlaylistPagingTest (see its comment).
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ApplicationProvider.getApplicationContext(),
            Configuration.Builder().build(),
            ExecutorsMode.LEGACY_OVERRIDE_WITH_SYNCHRONOUS_EXECUTORS,
        )
    }

    @After
    fun tearDown() {
        try {
            val wm = WorkManager.getInstance(ApplicationProvider.getApplicationContext())
            wm.cancelAllWork().result.get()
            wm.pruneWork().result.get()
            WorkManagerTestInitHelper.closeWorkDatabase()
        } catch (_: IllegalStateException) {
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun video(id: String) = ChannelVideo(
        id = id, title = "Lecture $id", thumbnailUrl = "https://example.com/$id.jpg",
        durationSeconds = 1200, viewCount = 10L, publishedTime = "1 day ago", uploaderName = "Lecture Channel",
    )

    private fun page(vararg ids: String) = ids.map(::video)

    private fun open(vm: PlayerViewModel, videoId: String, channelQueue: Boolean = true) = vm.loadVideo(
        videoId = videoId, title = "Lecture $videoId", channelName = "Lecture Channel",
        sourceChannelId = "UC_lectures", channelQueue = channelQueue,
    )

    private fun streams(id: String) = ResolvedStreams(
        streamId = id,
        videoTracks = listOf(VideoTrack("https://example.com/v.mp4", "video/mp4", 1280, 720, 2_000_000, "720p", 30, false)),
        audioTracks = listOf(AudioTrack("https://example.com/a.m4a", "audio/mp4", 128_000, "mp4a")),
        subtitleTracks = emptyList(),
        durationSeconds = 1200,
    )

    @Test
    fun `Up next lists the channel videos after the tapped one, newest to oldest`() = runTest {
        val channel = FakeChannelVideosRepository(listOf(page("v1", "v2", "v3", "v4", "v5")))
        fakePlayerRepository.resolvedStreams = streams("v3")
        val vm = playerViewModel(repository = fakePlayerRepository, channelDetailRepository = channel)

        open(vm, "v3")
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals("v3", state.currentItem?.id)
        assertEquals(listOf("v4", "v5"), state.upNext.map { it.id })
        assertTrue(state.hasNext)
        // Channel videos are approved through their channel, not one by one: each keeps it for the stream gate.
        assertTrue(state.upNext.all { it.sourceChannelId == "UC_lectures" })
        assertTrue(state.upNext.all { it.channelName == "Lecture Channel" })
    }

    @Test
    fun `a tapped video on a later page is searched for and the queue starts after it`() = runTest {
        val channel = FakeChannelVideosRepository(listOf(page("v1", "v2", "v3"), page("v4", "v5", "v6")))
        fakePlayerRepository.resolvedStreams = streams("v5")
        val vm = playerViewModel(repository = fakePlayerRepository, channelDetailRepository = channel)

        open(vm, "v5")
        advanceUntilIdle()

        assertEquals("v5", vm.state.value.currentItem?.id)
        assertEquals(listOf("v6"), vm.state.value.upNext.map { it.id })
    }

    @Test
    fun `a tapped video the channel search cannot find leaves Up next empty and keeps playing it`() = runTest {
        val channel = FakeChannelVideosRepository(listOf(page("v1", "v2"), page("v3", "v4")))
        fakePlayerRepository.resolvedStreams = streams("gone")
        val vm = playerViewModel(repository = fakePlayerRepository, channelDetailRepository = channel)

        open(vm, "gone")
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals("gone", state.currentItem?.id)
        assertTrue(state.upNext.isEmpty())
        assertFalse(state.hasNext)
    }

    @Test
    fun `without the channel queue flag a channel id alone fetches nothing`() = runTest {
        val channel = FakeChannelVideosRepository(listOf(page("v1", "v2", "v3")))
        fakePlayerRepository.resolvedStreams = streams("v1")
        val vm = playerViewModel(repository = fakePlayerRepository, channelDetailRepository = channel)

        open(vm, "v1", channelQueue = false)
        advanceUntilIdle()

        assertEquals(0, channel.calls)
        assertTrue(vm.state.value.upNext.isEmpty())
    }

    @Test
    fun `loadMoreUpNext pages the channel into the queue`() = runTest {
        val channel = FakeChannelVideosRepository(listOf(page("v1", "v2"), page("v3", "v4")))
        fakePlayerRepository.resolvedStreams = streams("v1")
        val vm = playerViewModel(repository = fakePlayerRepository, channelDetailRepository = channel)

        open(vm, "v1")
        advanceUntilIdle()
        assertEquals(listOf("v2"), vm.state.value.upNext.map { it.id })

        vm.loadMoreUpNext()
        advanceUntilIdle()

        assertEquals(listOf("v2", "v3", "v4"), vm.state.value.upNext.map { it.id })
        assertTrue(vm.state.value.upNext.all { it.sourceChannelId == "UC_lectures" })
    }

    @Test
    fun `the end of a video plays the next channel video, as a playlist does`() = runTest {
        val channel = FakeChannelVideosRepository(listOf(page("v1", "v2", "v3")))
        fakePlayerRepository.resolvedStreams = streams("v2")
        val vm = playerViewModel(repository = fakePlayerRepository, channelDetailRepository = channel)

        open(vm, "v2")
        advanceUntilIdle()
        val advanced = vm.markCurrentComplete()
        advanceUntilIdle()

        assertTrue(advanced)
        assertEquals("v3", vm.state.value.currentItem?.id)
        assertEquals("UC_lectures", vm.state.value.currentItem?.sourceChannelId)
        assertEquals("v3", fakePlayerRepository.resolvedVideoIds.last())
    }

    @Test
    fun `a channel video that cannot play is skipped, as in a playlist`() = runTest {
        val channel = FakeChannelVideosRepository(listOf(page("v1", "v2", "v3", "v4")))
        fakePlayerRepository.resolvedStreams = streams("v2")
        fakePlayerRepository.archivedIds += "v3"
        val vm = playerViewModel(repository = fakePlayerRepository, channelDetailRepository = channel)

        open(vm, "v2")
        advanceUntilIdle()
        vm.markCurrentComplete()
        advanceUntilIdle()

        assertEquals("v4", vm.state.value.currentItem?.id)
    }

    @Test
    fun `a video deep in the channel still gets its Up next`() = runTest {
        // 20 pages of 30: the tapped video is item 451, past the playlist start scan's 250 items.
        val pages = (0 until 20).map { p -> (0 until 30).map { video("v${p * 30 + it}") } }
        val channel = FakeChannelVideosRepository(pages)
        fakePlayerRepository.resolvedStreams = streams("v450")
        val vm = playerViewModel(repository = fakePlayerRepository, channelDetailRepository = channel)

        open(vm, "v450")
        advanceUntilIdle()

        assertEquals("v451", vm.state.value.upNext.firstOrNull()?.id)
    }

    @Test
    fun `a page the exclusions emptied does not stall Up next paging`() = runTest {
        val channel = FakeChannelVideosRepository(listOf(page("v1", "v2"), page(), page("v3")))
        fakePlayerRepository.resolvedStreams = streams("v1")
        val vm = playerViewModel(repository = fakePlayerRepository, channelDetailRepository = channel)

        open(vm, "v1")
        advanceUntilIdle()
        vm.loadMoreUpNext()
        advanceUntilIdle()

        assertEquals(listOf("v2", "v3"), vm.state.value.upNext.map { it.id })
    }

    @Test
    fun `a channel whose Videos tab is unavailable queues from the uploads playlist, as the tab does`() = runTest {
        // ChannelDetailViewModel.loadVideosInitial: the channel tab first, the uploads playlist's page 1 when the tab
        // gives nothing. A video the tab showed from there (here the 60th) must find its Up next the same way.
        val channel = FakeChannelVideosRepository(pages = emptyList(), uploads = (0 until 100).map { video("v$it") })
        fakePlayerRepository.resolvedStreams = streams("v60")
        val vm = playerViewModel(repository = fakePlayerRepository, channelDetailRepository = channel)

        open(vm, "v60")
        advanceUntilIdle()

        assertEquals((61 until 100).map { "v$it" }, vm.state.value.upNext.map { it.id })
    }

    @Test
    fun `the tapped channel video that cannot play shows its error and is never skipped`() = runTest {
        val channel = FakeChannelVideosRepository(listOf(page("v1", "v2", "v3")))
        fakePlayerRepository.archivedIds += "v2"
        fakePlayerRepository.resolveDelayMs = 5_000 // the queue is in place before the tapped video's answer
        val vm = playerViewModel(repository = fakePlayerRepository, channelDetailRepository = channel)

        open(vm, "v2")
        advanceUntilIdle()

        assertEquals("v2", vm.state.value.currentItem?.id)
        assertEquals(StreamState.ContentUnavailable, vm.state.value.streamState)
    }

    @Test
    fun `an extractor outage stops after three skips instead of walking the queue`() = runTest {
        val channel = FakeChannelVideosRepository(listOf(page("v1", "v2", "v3", "v4", "v5", "v6", "v7", "v8")))
        fakePlayerRepository.resolvedStreams = streams("v1")
        val vm = playerViewModel(repository = fakePlayerRepository, channelDetailRepository = channel)

        open(vm, "v1")
        advanceUntilIdle()
        fakePlayerRepository.archivedIds += listOf("v2", "v3", "v4", "v5", "v6", "v7", "v8")
        vm.markCurrentComplete()
        advanceUntilIdle()

        // v2, v3, v4 skipped; v5's failure is the fourth in a row and shows instead.
        assertEquals("v5", vm.state.value.currentItem?.id)
        assertFalse("v6" in fakePlayerRepository.resolvedVideoIds)
    }

    @Test
    fun `after a failed page only a user scroll retries it`() = runTest {
        val channel = FakeChannelVideosRepository(listOf(page("v1", "v2"), page("v3")))
        channel.failingCalls += 2
        fakePlayerRepository.resolvedStreams = streams("v1")
        val vm = playerViewModel(repository = fakePlayerRepository, channelDetailRepository = channel)

        open(vm, "v1")
        advanceUntilIdle()
        vm.loadMoreUpNext()       // fails
        advanceUntilIdle()
        vm.loadMoreUpNext()       // automatic: respects the failure
        advanceUntilIdle()
        assertEquals(2, channel.calls)

        vm.loadMoreUpNext(userScroll = true)
        advanceUntilIdle()

        assertEquals(listOf("v2", "v3"), vm.state.value.upNext.map { it.id })
    }

    @Test
    fun `a run of emptied pages is followed a bounded number of times`() = runTest {
        val channel = FakeChannelVideosRepository(listOf(page("v1", "v2"), page(), page(), page(), page(), page(), page("v3")))
        fakePlayerRepository.resolvedStreams = streams("v1")
        val vm = playerViewModel(repository = fakePlayerRepository, channelDetailRepository = channel)

        open(vm, "v1")
        advanceUntilIdle()
        vm.loadMoreUpNext()
        advanceUntilIdle()

        assertEquals(1 + 3, channel.calls) // the page, then MAX_EMPTIED_PAGES_FOLLOWED (2) more
        assertEquals(listOf("v2"), vm.state.value.upNext.map { it.id })
    }

    @Test
    fun `an emptied page whose cursor leads back to itself is not fetched again`() = runTest {
        val channel = FakeChannelVideosRepository(listOf(page("v1", "v2"), page(), page("v3")))
        channel.nextOf = { if (it == 1) 1 else it + 1 } // page 1 hands back its own cursor
        fakePlayerRepository.resolvedStreams = streams("v1")
        val vm = playerViewModel(repository = fakePlayerRepository, channelDetailRepository = channel)

        open(vm, "v1")
        advanceUntilIdle()
        vm.loadMoreUpNext()
        advanceUntilIdle()

        assertEquals(1 + 1, channel.calls)
    }
    @Test
    fun `tapping a later Up next row continues the queue from that row`() = runTest {
        val channel = FakeChannelVideosRepository(listOf(page("v1", "v2", "v3", "v4")))
        fakePlayerRepository.resolvedStreams = streams("v1")
        val vm = playerViewModel(repository = fakePlayerRepository, channelDetailRepository = channel)

        open(vm, "v1")
        advanceUntilIdle()
        vm.playItem(vm.state.value.upNext.first { it.id == "v3" })
        advanceUntilIdle()

        assertEquals(listOf("v4"), vm.state.value.upNext.map { it.id })
        vm.markCurrentComplete()
        advanceUntilIdle()
        assertEquals("v4", vm.state.value.currentItem?.id)
    }

    @Test
    fun `Previous to a video that cannot play shows its error instead of skipping forward`() = runTest {
        val channel = FakeChannelVideosRepository(listOf(page("v1", "v2", "v3", "v4")))
        fakePlayerRepository.resolvedStreams = streams("v1")
        fakePlayerRepository.archivedIds += "v2"
        val vm = playerViewModel(repository = fakePlayerRepository, channelDetailRepository = channel)

        open(vm, "v1")
        advanceUntilIdle()
        vm.markCurrentComplete() // v2 cannot play and is auto-skipped: v3 plays
        advanceUntilIdle()
        assertEquals("v3", vm.state.value.currentItem?.id)

        vm.skipToPrevious()
        advanceUntilIdle()

        assertEquals("v2", vm.state.value.currentItem?.id)
        assertEquals(StreamState.ContentUnavailable, vm.state.value.streamState)
    }
}
