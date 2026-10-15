package com.albunyaan.tube.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.Priority
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_AUDIO_ONLY
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_DOWNLOAD_ID
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_ERROR_REASON
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_FILE_PATH
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_TITLE
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_VIDEO_ID
import com.albunyaan.tube.player.PlayerRepository
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * googlevideo paces an un-ranged GET of an adaptive format (every audio-only download, every
 * Split video leg) at about 2x bitrate, so a 4 MB lecture took 131 s and longer ones died on a
 * dropped connection. A `Range` request of the same URL arrives at line rate. The worker walks
 * the file in bounded Range chunks, as iOS OfflineEngine and yt-dlp's http_chunk_size do.
 * Evidence: .superpowers/sdd/2026-10-14-offline-downloads/audio-download-investigation.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class DownloadWorkerRangeTest {

    private val chunk = 10L * 1024 * 1024
    private val body = Random(42).nextBytes(12 * 1024 * 1024)
    private val gets = CopyOnWriteArrayList<RecordedRequest>()
    private val server = MockWebServer()
    private lateinit var context: Context

    /** How the CDN answers a GET; googlevideo by default. */
    private var answer: (RecordedRequest) -> MockResponse = ::googlevideo

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "HEAD") return MockResponse().setHeader("Content-Length", body.size)
                gets += request
                return answer(request)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** 206 with Content-Range for `bytes=a-b`; a 200 of the whole body without a Range. */
    private fun googlevideo(request: RecordedRequest): MockResponse {
        val range = request.getHeader("Range") ?: return MockResponse().setBody(Buffer().write(body))
        val (a, b) = Regex("""bytes=(\d+)-(\d*)""").matchEntire(range)!!.destructured
        val start = a.toInt()
        val end = minOf(b.toIntOrNull() ?: body.lastIndex, body.lastIndex)
        return MockResponse().setResponseCode(206)
            .setHeader("Content-Range", "bytes $start-$end/${body.size}")
            .setBody(Buffer().write(body, start, end - start + 1))
    }

    private suspend fun runAudioOnlyDownload(): ListenableWorker.Result {
        val url = server.url("/videoplayback?itag=140&c=VISIONOS").toString()
        val repository = object : PlayerRepository {
            override suspend fun resolveStreams(
                videoId: String,
                forceRefresh: Boolean,
                priority: Priority,
                sourceChannelId: String?,
            ) = ResolvedStreams(
                streamId = videoId,
                videoTracks = emptyList(),
                audioTracks = listOf(AudioTrack(url = url, mimeType = "audio/mp4", bitrate = 128_000, codec = null)),
                durationSeconds = 262,
            )
        }
        val worker = TestListenableWorkerBuilder<DownloadWorker>(context)
            .setInputData(
                workDataOf(
                    KEY_DOWNLOAD_ID to "lecture_1",
                    KEY_VIDEO_ID to "lecture",
                    KEY_TITLE to "Lecture",
                    KEY_AUDIO_ONLY to true,
                )
            )
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ) = DownloadWorker(appContext, workerParameters, DownloadStorage(appContext), repository, FFmpegMerger())
            })
            .build()
        return worker.doWork()
    }

    @Test
    fun `audio-only download fetches googlevideo in bounded Range chunks`() = runTest {
        val result = runAudioOnlyDownload()

        assertTrue("download failed: $result", result is ListenableWorker.Result.Success)
        assertTrue("expected at least 2 GETs for 12 MiB, got ${gets.size}", gets.size >= 2)
        for (get in gets) {
            val range = get.getHeader("Range")
            val match = range?.let { Regex("""bytes=(\d+)-(\d+)""").matchEntire(it) }
            assertTrue("un-ranged GET: googlevideo paces it at ~2x bitrate (Range=$range)", match != null)
            val (start, end) = match!!.destructured
            assertTrue("chunk over 10 MiB: $range", end.toLong() - start.toLong() + 1 <= chunk)
        }
        val file = File((result as ListenableWorker.Result.Success).outputData.getString(KEY_FILE_PATH)!!)
        assertArrayEquals(body, file.readBytes())
    }

    @Test
    fun `a server that ignores Range is read once, whole`() = runTest {
        answer = { MockResponse().setBody(Buffer().write(body)) }

        val result = runAudioOnlyDownload()

        assertTrue("download failed: $result", result is ListenableWorker.Result.Success)
        assertEquals("a 200 is the whole file", 1, gets.size)
        val file = File((result as ListenableWorker.Result.Success).outputData.getString(KEY_FILE_PATH)!!)
        assertArrayEquals(body, file.readBytes())
    }

    @Test
    fun `a 403 on a chunk still maps to the 403 error after one re-resolve`() = runTest {
        answer = { MockResponse().setResponseCode(403) }

        val result = runAudioOnlyDownload()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(DownloadErrorCode.HTTP_403, result.outputData.getString(KEY_ERROR_REASON))
        assertEquals("first try plus one after re-resolving", 2, gets.size)
    }
}
