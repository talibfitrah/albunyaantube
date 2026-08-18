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
        // The safety property is the User-Agent, not which constant is named: a UA mismatch
        // against the minting client is what 403s every segment. The default moved off the
        // retired ANDROID_VR to NEWPIPE_ANDROID (2026-08-18) and both are Android-UA, so this
        // asserts the invariant that survives that rename rather than the constant itself.
        assertFalse(streams.extractionClient.usesIosUserAgent())
    }
}
