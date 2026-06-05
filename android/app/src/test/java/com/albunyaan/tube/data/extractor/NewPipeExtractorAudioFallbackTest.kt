package com.albunyaan.tube.data.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the "image, no sound" root cause.
 *
 * When YouTube exposes no separate audio stream, [NewPipeExtractorClient.deriveFallbackAudioTracks]
 * must NOT fabricate an audio track from a video-only URL (which plays silently). It must source
 * the fallback from a muxed track's embedded audio, or return empty when no muxed track exists.
 */
class NewPipeExtractorAudioFallbackTest {

    private fun video(
        url: String,
        height: Int?,
        isVideoOnly: Boolean,
        bitrate: Int? = height?.times(1000)
    ): VideoTrack = VideoTrack(
        url = url,
        mimeType = "video/mp4",
        width = height?.let { it * 16 / 9 },
        height = height,
        bitrate = bitrate,
        qualityLabel = height?.let { "${it}p" },
        fps = 30,
        isVideoOnly = isVideoOnly
    )

    private fun audio(url: String, bitrate: Int? = 128_000): AudioTrack = AudioTrack(
        url = url,
        mimeType = "audio/mp4",
        bitrate = bitrate,
        codec = "mp4a.40.2"
    )

    @Test
    fun `real audio tracks are returned unchanged`() {
        val raw = listOf(audio("https://cdn/a.m4a"))
        val videos = listOf(video("https://cdn/v1080.mp4", 1080, isVideoOnly = true))

        val result = NewPipeExtractorClient.deriveFallbackAudioTracks(raw, videos)

        assertEquals(raw, result)
    }

    @Test
    fun `no audio plus only video-only tracks yields EMPTY — never a silent fabricated track`() {
        // The old code returned listOf(AudioTrack(url = videoTracks.first().url, ...)) here,
        // which is a video-only URL -> silent "audio". The fix returns empty instead.
        val videos = listOf(
            video("https://cdn/v2160.webm", 2160, isVideoOnly = true),
            video("https://cdn/v1080.webm", 1080, isVideoOnly = true)
        )

        val result = NewPipeExtractorClient.deriveFallbackAudioTracks(emptyList(), videos)

        assertTrue("expected empty fallback, got $result", result.isEmpty())
    }

    @Test
    fun `no audio plus a muxed track sources fallback audio from the MUXED url`() {
        val videoOnlyTop = video("https://cdn/v2160.mp4", 2160, isVideoOnly = true)
        val muxed = video("https://cdn/muxed360.mp4", 360, isVideoOnly = false)
        // Sorted highest-first, exactly as the extractor produces.
        val videos = listOf(videoOnlyTop, muxed)

        val result = NewPipeExtractorClient.deriveFallbackAudioTracks(emptyList(), videos)

        assertEquals(1, result.size)
        assertEquals(
            "fallback audio must come from the muxed (audio-bearing) track, not the video-only top track",
            muxed.url,
            result.single().url
        )
    }

    @Test
    fun `muxed fallback picks the highest-resolution muxed track (first in sorted order)`() {
        val videos = listOf(
            video("https://cdn/v2160.mp4", 2160, isVideoOnly = true),
            video("https://cdn/muxed720.mp4", 720, isVideoOnly = false),
            video("https://cdn/muxed360.mp4", 360, isVideoOnly = false)
        )

        val result = NewPipeExtractorClient.deriveFallbackAudioTracks(emptyList(), videos)

        assertEquals("https://cdn/muxed720.mp4", result.single().url)
    }

    @Test
    fun `empty inputs yield empty`() {
        assertTrue(NewPipeExtractorClient.deriveFallbackAudioTracks(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `non-empty raw audio short-circuits even when muxed exists`() {
        val raw = listOf(audio("https://cdn/a.m4a"))
        val videos = listOf(video("https://cdn/muxed360.mp4", 360, isVideoOnly = false))

        val result = NewPipeExtractorClient.deriveFallbackAudioTracks(raw, videos)

        assertSame(raw, result)
    }
}
