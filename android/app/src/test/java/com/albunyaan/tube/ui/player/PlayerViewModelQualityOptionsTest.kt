package com.albunyaan.tube.ui.player

import com.albunyaan.tube.data.extractor.ExtractionClient
import com.albunyaan.tube.data.extractor.VideoTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PlayerViewModel.buildQualityOptions] — the pure quality-menu derivation.
 *
 * Core rule under test: the menu must only offer qualities that actually play.
 *  - ANDROID_VR (primary) serves an adaptive ladder → offer every distinct height.
 *  - NewPipe fallback (iOS/android) serves a SINGLE progressive muxed track (the adaptive
 *    video-only ladder 403s mid-stream and is gated out by DashSourceBuilder.decide) → offer
 *    only the one track decide() would serve, so the picker doesn't promise 720p/1080p it can't
 *    deliver.
 *  - [adaptiveActive] = false (e.g. ANDROID_VR whose MPD generation failed and fell back to
 *    progressive) also collapses to the single served track, even on ANDROID_VR.
 */
class PlayerViewModelQualityOptionsTest {

    private fun track(
        height: Int,
        isVideoOnly: Boolean,
        bitrate: Int = height * 3000,
        url: String = "https://example.com/${if (isVideoOnly) "vo" else "mx"}$height",
    ) = VideoTrack(
        url = url,
        mimeType = "video/mp4",
        width = height * 16 / 9,
        height = height,
        bitrate = bitrate,
        qualityLabel = "${height}p",
        fps = 30,
        isVideoOnly = isVideoOnly,
        syntheticDashMetadata = null,
        codec = "avc1.64001f",
    )

    private fun labels(opts: List<QualityOption>) = opts.map { it.label }

    // ── ANDROID_VR: full ladder preserved ──────────────────────────────────────

    @Test
    fun `ANDROID_VR offers the full video-only ladder, highest first`() {
        val tracks = listOf(
            track(360, isVideoOnly = true),
            track(720, isVideoOnly = true),
            track(1080, isVideoOnly = true),
        )

        val opts = PlayerViewModel.buildQualityOptions(tracks, ExtractionClient.ANDROID_VR, adaptiveActive = true)

        assertEquals(listOf("1080p", "720p", "360p"), labels(opts))
    }

    @Test
    fun `ANDROID_VR dedupes same height preferring muxed over video-only`() {
        val tracks = listOf(
            track(360, isVideoOnly = true, bitrate = 999_999), // higher bitrate, but video-only
            track(360, isVideoOnly = false),                   // muxed — must win
        )

        val opts = PlayerViewModel.buildQualityOptions(tracks, ExtractionClient.ANDROID_VR, adaptiveActive = true)

        assertEquals(1, opts.size)
        assertEquals("360p", opts.first().label)
        assertFalse("Muxed must be preferred over video-only at the same height", opts.first().track.isVideoOnly)
    }

    @Test
    fun `ANDROID_VR with adaptive inactive (MPD-gen failed) offers only the served track`() {
        // Honesty fix: when ANDROID_VR's own MPD generation fails, the player serves a single
        // progressive track. The menu must reflect that, not the phantom full ladder.
        val tracks = listOf(
            track(360, isVideoOnly = false),  // muxed served by the progressive fallback
            track(720, isVideoOnly = true),
            track(1080, isVideoOnly = true),
        )

        val opts = PlayerViewModel.buildQualityOptions(tracks, ExtractionClient.ANDROID_VR, adaptiveActive = false)

        assertEquals("MPD-gen failure must collapse the menu to the served track", listOf("360p"), labels(opts))
    }

    // ── NewPipe fallback: single playable quality only ──────────────────────────

    @Test
    fun `NEWPIPE_IOS with muxed 360 + video-only 720_1080 offers ONLY 360p`() {
        // The Ep3 shape: phantom 720p/1080p iOS video-only tracks must not appear.
        val tracks = listOf(
            track(360, isVideoOnly = false),  // muxed itag-18 — the only sustaining stream
            track(720, isVideoOnly = true),
            track(1080, isVideoOnly = true),
        )

        val opts = PlayerViewModel.buildQualityOptions(tracks, ExtractionClient.NEWPIPE_IOS, adaptiveActive = false)

        assertEquals("Fallback must offer exactly one quality", 1, opts.size)
        assertEquals("360p", opts.first().label)
        assertFalse(opts.first().track.isVideoOnly)
    }

    @Test
    fun `NEWPIPE_ANDROID behaves like iOS — single muxed quality only`() {
        val tracks = listOf(
            track(360, isVideoOnly = false),
            track(1080, isVideoOnly = true),
        )

        val opts = PlayerViewModel.buildQualityOptions(tracks, ExtractionClient.NEWPIPE_ANDROID, adaptiveActive = false)

        assertEquals(listOf("360p"), labels(opts))
    }

    @Test
    fun `NEWPIPE_IOS with multiple muxed offers only the highest muxed (matches decide)`() {
        // decide() progressive always picks the highest muxed, so the menu must too — offering a
        // lower muxed would be a no-op tap (cap ignored on the progressive path).
        val tracks = listOf(
            track(360, isVideoOnly = false),
            track(720, isVideoOnly = false),
            track(1080, isVideoOnly = true),
        )

        val opts = PlayerViewModel.buildQualityOptions(tracks, ExtractionClient.NEWPIPE_IOS, adaptiveActive = false)

        assertEquals(listOf("720p"), labels(opts))
    }

    @Test
    fun `NEWPIPE_IOS with no muxed falls back to highest video-only`() {
        // decide() pairs best video-only + best audio when no muxed exists; the menu mirrors that.
        val tracks = listOf(
            track(360, isVideoOnly = true),
            track(720, isVideoOnly = true),
        )

        val opts = PlayerViewModel.buildQualityOptions(tracks, ExtractionClient.NEWPIPE_IOS, adaptiveActive = false)

        assertEquals(listOf("720p"), labels(opts))
        assertTrue(opts.first().track.isVideoOnly)
    }

    // ── Edge cases ──────────────────────────────────────────────────────────────

    @Test
    fun `empty tracks yields empty options for every client`() {
        for (client in ExtractionClient.values()) {
            assertTrue(
                "Expected empty options for $client",
                PlayerViewModel.buildQualityOptions(emptyList(), client, adaptiveActive = true).isEmpty(),
            )
        }
    }

    @Test
    fun `tracks without height or label are ignored`() {
        val tracks = listOf(
            track(720, isVideoOnly = true).copy(height = null),
            track(360, isVideoOnly = false).copy(qualityLabel = null),
            track(480, isVideoOnly = true),
        )

        val opts = PlayerViewModel.buildQualityOptions(tracks, ExtractionClient.ANDROID_VR, adaptiveActive = true)

        assertEquals(listOf("480p"), labels(opts))
    }
}
