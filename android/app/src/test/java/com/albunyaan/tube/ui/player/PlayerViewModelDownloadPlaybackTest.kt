package com.albunyaan.tube.ui.player

import com.albunyaan.tube.R
import com.albunyaan.tube.download.DownloadEntry
import com.albunyaan.tube.download.DownloadFileMetadata
import com.albunyaan.tube.download.DownloadRequest
import com.albunyaan.tube.download.DownloadStatus
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Offline downloads (1.0.3, R1/R2): a completed download plays inside FitrahTube's
 * own player from its local file — the Android counterpart of iOS's OfflineResolver
 * (PlayerScreen.swift:141-158). No stream resolution, no metadata fetch, no queue.
 * Replaces the 2026-10-14 RED test that pinned the external-app hand-off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PlayerViewModelDownloadPlaybackTest {

    @get:Rule val tmp = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private val streams = FakePlayerRepository()
    private val metadata = FakeExtractorClient()
    private val downloads = FakeDownloadRepository()
    private val favorites = FakeFavoritesRepository()

    @Before fun setUp() = Dispatchers.setMain(testDispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun completed(
        id: String,
        videoId: String,
        file: File,
        audioOnly: Boolean = false,
        status: DownloadStatus = DownloadStatus.COMPLETED,
    ) = DownloadEntry(
        request = DownloadRequest(
            id = id, title = "Request title", videoId = videoId, audioOnly = audioOnly,
            thumbnailUrl = "https://i.example/t.jpg",
        ),
        status = status,
        progress = 100,
        filePath = file.absolutePath,
        metadata = DownloadFileMetadata(
            sizeBytes = file.length(), completedAtMillis = 0L,
            mimeType = if (audioOnly) "audio/mp4" else "video/mp4", title = "Lecture",
        ),
    )

    private fun noNetwork() {
        assertEquals("stream resolution must not run for a download", 0, streams.resolveCallCount)
        assertEquals("metadata fetch must not run for a download", 0, metadata.calls)
    }

    @Test fun `a completed download plays from its local file with no network and no queue`() = runTest {
        val file = tmp.newFile("v1.mp4")
        downloads.entries.value = listOf(completed("d1", "v1", file))
        val vm = createViewModel()

        vm.loadDownload("d1")
        advanceUntilIdle()

        val state = vm.state.value
        val ready = state.streamState as StreamState.Ready
        val uri = file.toURI().toString()
        assertEquals("v1", ready.streamId)
        assertEquals(uri, ready.selection.video?.url)
        assertEquals(uri, ready.selection.audio.url)
        assertTrue("no captions to fetch offline", ready.selection.resolved.subtitleTracks.isEmpty())
        assertTrue(state.playingDownload)
        assertFalse(state.audioOnly)
        assertEquals("Lecture", state.currentItem?.title)
        assertEquals("https://i.example/t.jpg", state.currentItem?.thumbnailUrl)
        assertTrue(state.upNext.isEmpty())
        assertFalse(state.hasNext)
        assertFalse(state.hasPrevious)
        noNetwork()
    }

    @Test fun `an audio-only download plays as audio`() = runTest {
        val file = tmp.newFile("a1.m4a")
        downloads.entries.value = listOf(completed("d2", "a1", file, audioOnly = true))
        val vm = createViewModel()

        vm.loadDownload("d2")
        advanceUntilIdle()

        val ready = vm.state.value.streamState as StreamState.Ready
        assertTrue(vm.state.value.audioOnly)
        assertNull(ready.selection.video)
        assertEquals(file.toURI().toString(), ready.selection.audio.url)
        noNetwork()
    }

    @Test fun `a missing file shows the unavailable error and never goes online`() = runTest {
        val file = File(tmp.root, "gone.mp4")
        downloads.entries.value = listOf(completed("d3", "v3", file))
        val vm = createViewModel()

        vm.loadDownload("d3")
        advanceUntilIdle()

        assertEquals(StreamState.Error(R.string.player_stream_unavailable), vm.state.value.streamState)
        noNetwork()
    }

    @Test fun `an unknown download shows the unavailable error`() = runTest {
        val vm = createViewModel()

        vm.loadDownload("nope")
        advanceUntilIdle()

        assertEquals(StreamState.Error(R.string.player_stream_unavailable), vm.state.value.streamState)
        noNetwork()
    }

    @Test fun `retry and refresh re-read the local file and never go online`() = runTest {
        val file = tmp.newFile("v4.mp4")
        downloads.entries.value = listOf(completed("d4", "v4", file))
        val vm = createViewModel()
        vm.loadDownload("d4")
        advanceUntilIdle()

        vm.forceRefreshForAutoRecovery()
        advanceUntilIdle()
        assertTrue(vm.state.value.streamState is StreamState.Ready)

        // Deleted or expired while open: the next re-read shows the error, never a stream.
        file.delete()
        vm.retryCurrentStream()
        advanceUntilIdle()

        assertEquals(StreamState.Error(R.string.player_stream_unavailable), vm.state.value.streamState)
        noNetwork()
    }

    @Test fun `a player restored after process death waits for the repository's startup load`() = runTest {
        // The OS restores PlayerFragment with its downloadId before DefaultDownloadRepository's
        // init has listed the files; refreshPersistedDownloads() waits for that load.
        val file = tmp.newFile("v5.mp4")
        downloads.listedOnRefresh = listOf(completed("d5", "v5", file))
        val vm = createViewModel()

        vm.loadDownload("d5")
        advanceUntilIdle()

        val ready = vm.state.value.streamState as StreamState.Ready
        assertEquals(file.toURI().toString(), ready.selection.audio.url)
        assertEquals("Lecture", vm.state.value.currentItem?.title)
        noNetwork()
    }

    @Test fun `a failed entry listed first does not hide the completed download`() = runTest {
        val file = tmp.newFile("v6.mp4")
        downloads.entries.value = listOf(
            completed("failed", "v6", File(tmp.root, "none.m4a"), audioOnly = true, status = DownloadStatus.FAILED),
            completed("done", "v6", file),
        )
        val vm = createViewModel()

        vm.loadVideo(videoId = "v6")
        advanceUntilIdle()

        // The streaming player's Download button opens this entry (PlayerFragment.updateDownloadControls).
        assertEquals("done", vm.state.value.currentDownload?.request?.id)
        assertEquals(DownloadStatus.COMPLETED, vm.state.value.currentDownload?.status)
    }

    @Test fun `an active download is not hidden behind an older failed one`() = runTest {
        // Otherwise the streaming player's Download button stays on the quality picker and a
        // second tap enqueues a duplicate.
        for (active in listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.PAUSED)) {
            downloads.entries.value = listOf(
                completed("failed", "v7", File(tmp.root, "none.m4a"), audioOnly = true, status = DownloadStatus.FAILED),
                completed("active", "v7", File(tmp.root, "partial.mp4"), status = active),
            )
            val vm = createViewModel()

            vm.loadVideo(videoId = "v7")
            advanceUntilIdle()

            assertEquals("$active", "active", vm.state.value.currentDownload?.request?.id)
        }
    }

    @Test fun `a completed download still wins over an active one`() = runTest {
        val file = tmp.newFile("v8.mp4")
        downloads.entries.value = listOf(
            completed("active", "v8", File(tmp.root, "partial8.mp4"), status = DownloadStatus.RUNNING),
            completed("done", "v8", file),
        )
        val vm = createViewModel()

        vm.loadVideo(videoId = "v8")
        advanceUntilIdle()

        assertEquals("done", vm.state.value.currentDownload?.request?.id)
    }

    // Stage 1346 C1 / stage 5 F6: a saved lecture used to favourite with a blank channel and
    // 0:00, which the server rejects (400) and sync then drops without a word.
    @Test fun `a download keeps the channel and duration the streaming player knows`() = runTest {
        val vm = createViewModel()
        vm.loadVideo(videoId = "lec9", title = "Lecture", channelName = "Lecturer", durationSeconds = 1800)
        advanceUntilIdle()

        vm.downloadCurrent(targetHeight = 360)

        val request = downloads.enqueued.single()
        assertEquals("Lecturer", request.channelName)
        assertEquals(1800, request.durationSeconds)
    }

    @Test fun `a saved lecture favourites with its channel and duration`() = runTest {
        val file = tmp.newFile("v10.mp4")
        val entry = completed("d10", "v10", file)
        downloads.entries.value = listOf(
            entry.copy(request = entry.request.copy(channelName = "Lecturer", durationSeconds = 1800))
        )
        val vm = createViewModel()
        vm.loadDownload("d10")
        advanceUntilIdle()

        vm.toggleFavorite()
        advanceUntilIdle()

        assertEquals(Triple("v10", "Lecturer", 1800), favorites.toggled.single())
    }

    // 1.0.4 A9 (R3 local-first): Home, Search, Favorites, History, Me, a channel tab and a deep link
    // all open a single video through loadVideo. A saved copy plays from its file, as from Downloads.
    @Test fun `opening a saved video from any entry point plays the local file`() = runTest {
        val file = tmp.newFile("v11.mp4")
        downloads.entries.value = listOf(completed("d11", "v11", file))
        val vm = createViewModel()

        vm.loadVideo(videoId = "v11", title = "From Home", channelName = "Lecturer")
        advanceUntilIdle()

        val ready = vm.state.value.streamState as StreamState.Ready
        assertTrue(vm.state.value.playingDownload)
        assertEquals(file.toURI().toString(), ready.selection.video?.url)
        noNetwork()
    }

    // The first open of a process (Home after launch, a cold deep link, a restored player) comes
    // before DefaultDownloadRepository's startup load has listed the saved files.
    @Test fun `the first open of a process waits for the saved files to be listed`() = runTest {
        val file = tmp.newFile("v14.mp4")
        val load = kotlinx.coroutines.CompletableDeferred<Unit>()
        downloads.startupLoad = load
        val vm = createViewModel()

        vm.loadVideo(videoId = "v14")
        downloads.entries.value = listOf(completed("d14", "v14", file)) // what the startup load finds
        load.complete(Unit)
        advanceUntilIdle()

        assertTrue(vm.state.value.playingDownload)
        noNetwork()
    }

    // 1.0.4 round 2: a copy saved before the channel name was kept (or saved blank from a
    // standalone playlist) lost the heart once Home opened it locally. The entry point knows the name.
    @Test fun `a local-first open keeps the entry point's channel and duration for the favourite`() = runTest {
        downloads.entries.value = listOf(completed("d15", "v15", tmp.newFile("v15.mp4")))
        val vm = createViewModel()

        vm.loadVideo(videoId = "v15", title = "From Home", channelName = "Lecturer", durationSeconds = 1800)
        advanceUntilIdle()
        assertTrue(vm.state.value.playingDownload)
        assertEquals("the heart shows for a known channel", "Lecturer", vm.state.value.currentItem?.channelName)

        vm.toggleFavorite()
        advanceUntilIdle()

        assertEquals(Triple("v15", "Lecturer", 1800), favorites.toggled.single())
    }

    @Test fun `a saved video copy wins over an audio-only one, and a missing file streams`() = runTest {
        val video = tmp.newFile("v12.mp4")
        downloads.entries.value = listOf(
            completed("audio", "v12", tmp.newFile("v12.m4a"), audioOnly = true),
            completed("video", "v12", video),
            completed("gone", "v13", File(tmp.root, "v13.mp4")),
        )
        val vm = createViewModel()

        vm.loadVideo(videoId = "v12")
        advanceUntilIdle()
        assertEquals(video.toURI().toString(), (vm.state.value.streamState as StreamState.Ready).selection.video?.url)

        val streaming = createViewModel()
        streaming.loadVideo(videoId = "v13")
        advanceUntilIdle()
        assertFalse("a deleted file never blocks the stream", streaming.state.value.playingDownload)
        assertTrue(streams.resolveCallCount > 0)
    }

    private fun createViewModel() = playerViewModel(
        repository = streams, downloadRepository = downloads, favoritesRepository = favorites, extractorClient = metadata,
    )
}
