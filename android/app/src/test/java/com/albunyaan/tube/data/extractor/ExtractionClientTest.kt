package com.albunyaan.tube.data.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    fun `ResolvedStreams default extractionClient uses the Android User-Agent`() {
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
        // Two things matter here, so both are asserted:
        // 1. The default must not name ANDROID_VR — that client was retired on 2026-08-18 and no
        //    resolve path produces it, so defaulting to it would mislabel every stream that omits
        //    the argument (this is the change the test was rewritten for).
        // 2. The default must keep the Android User-Agent, because a UA mismatch against the
        //    minting client is what 403s every segment. That is the property that must survive
        //    any future rename of the constant.
        assertNotEquals(ExtractionClient.ANDROID_VR, streams.extractionClient)
        assertFalse(streams.extractionClient.usesIosUserAgent())
    }
}
