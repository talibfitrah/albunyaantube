package com.albunyaan.tube.data.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractionClientTest {

    // --- usesIosUserAgent() ---

    @Test
    fun `NEWPIPE_IOS usesIosUserAgent returns true`() {
        assertTrue(ExtractionClient.NEWPIPE_IOS.usesIosUserAgent())
    }

    @Test
    fun `ANDROID_VR usesIosUserAgent returns false`() {
        assertFalse(ExtractionClient.ANDROID_VR.usesIosUserAgent())
    }

    @Test
    fun `NEWPIPE_ANDROID usesIosUserAgent returns false`() {
        assertFalse(ExtractionClient.NEWPIPE_ANDROID.usesIosUserAgent())
    }

    // --- ResolvedStreams default extractionClient ---

    @Test
    fun `ResolvedStreams default extractionClient is ANDROID_VR`() {
        val streams = ResolvedStreams(
            streamId = "test_id",
            videoTracks = emptyList(),
            audioTracks = listOf(
                AudioTrack(
                    url = "https://example.com/audio.mp4",
                    mimeType = "audio/mp4",
                    bitrate = 128000,
                    codec = "mp4a.40.2"
                )
            ),
            durationSeconds = 60
        )
        assertEquals(ExtractionClient.ANDROID_VR, streams.extractionClient)
    }
}
