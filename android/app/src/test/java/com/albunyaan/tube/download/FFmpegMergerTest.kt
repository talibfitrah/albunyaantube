package com.albunyaan.tube.download

import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.0.4 round 4 (review I1): a playlist "Audio Only" download id holds a space
 * ("PLx|Audio Only|vid"), and so do its temp paths. A command string is split at every space,
 * so FFmpeg got the path as two arguments and every such fallback ended in MERGE.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class FFmpegMergerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val ran = mutableListOf<Array<String>>()
    private val merger = FFmpegMerger().apply {
        runFfmpeg = { args ->
            ran += args
            mock<FFmpegSession> { on { returnCode } doReturn ReturnCode(ReturnCode.SUCCESS) }
        }
    }

    @Test fun `a path with a space reaches FFmpeg as one argument`() = runTest {
        val dir = tmp.newFolder("PLx|Audio Only|vid")
        val muxed = java.io.File(dir, "PLx|Audio Only|vid.run-fallback-muxed.tmp").apply { writeBytes(byteArrayOf(1)) }
        val audio = java.io.File(dir, "PLx|Audio Only|vid.run-fallback-audio.tmp")
        val video = java.io.File(dir, "PLx|Audio Only|vid.run-video.tmp").apply { writeBytes(byteArrayOf(1)) }
        val merged = java.io.File(dir, "PLx|Audio Only|vid.run-merged.tmp")

        merger.extractAudio(muxed, audio)
        merger.merge(video, muxed, merged)

        assertTrue("extractAudio: ${ran[0].toList()}", muxed.absolutePath in ran[0] && audio.absolutePath in ran[0])
        assertTrue("merge: ${ran[1].toList()}", video.absolutePath in ran[1] && merged.absolutePath in ran[1])
    }
}
