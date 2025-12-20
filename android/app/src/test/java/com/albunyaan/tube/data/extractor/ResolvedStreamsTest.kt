package com.albunyaan.tube.data.extractor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolvedStreamsTest {

    @Test
    fun `areUrlsExpired returns true when clock moves backwards`() {
        val streams = createResolvedStreams(urlGeneratedAt = 10_000L)

        assertTrue("Backward time should expire URLs", streams.areUrlsExpired(nowMs = 9_000L))
    }

    @Test
    fun `areUrlsExpired respects ttl window`() {
        val streams = createResolvedStreams(urlGeneratedAt = 1_000L)

        assertFalse(
            "URLs should be valid before TTL",
            streams.areUrlsExpired(nowMs = 1_000L + ResolvedStreams.URL_TTL_MS - 1)
        )
        assertTrue(
            "URLs should expire after TTL",
            streams.areUrlsExpired(nowMs = 1_000L + ResolvedStreams.URL_TTL_MS + 1)
        )
    }

    @Test
    fun `areUrlsExpired returns true when timebase version mismatches`() {
        val streams = createResolvedStreams(
            urlGeneratedAt = 1_000L,
            urlTimebaseVersion = ResolvedStreams.URL_TIMEBASE_VERSION + 1
        )

        assertTrue("Mismatched timebase version should expire URLs", streams.areUrlsExpired(nowMs = 2_000L))
    }

    private fun createResolvedStreams(
        urlGeneratedAt: Long,
        urlTimebaseVersion: Int = ResolvedStreams.URL_TIMEBASE_VERSION
    ): ResolvedStreams {
        return ResolvedStreams(
            streamId = "stream_1",
            videoTracks = emptyList(),
            audioTracks = listOf(
                AudioTrack(
                    url = "https://example.com/audio",
                    mimeType = null,
                    bitrate = null,
                    codec = null
                )
            ),
            durationSeconds = 120,
            urlGeneratedAt = urlGeneratedAt,
            urlTimebaseVersion = urlTimebaseVersion
        )
    }
}
