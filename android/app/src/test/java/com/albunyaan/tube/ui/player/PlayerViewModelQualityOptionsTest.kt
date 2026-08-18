package com.albunyaan.tube.ui.player

import com.albunyaan.tube.data.extractor.VideoTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PlayerViewModel.buildQualityOptions] — the pure quality-menu derivation.
 *
 * Core rule under test: the menu must only offer qualities that actually play, so the single
 * input is whether the player is genuinely running an adaptive source.
 *  - adaptive active → the track selector can switch, so offer every distinct height.
 *  - adaptive inactive (DashSourceBuilder.decide served progressive, or MPD generation failed)
 *    → offer only the one track it actually serves, so the picker never promises a 720p/1080p
 *    it cannot deliver.
 *
 * The client that minted the URLs used to be a second input (the full ladder required
 * ANDROID_VR). That client was retired on 2026-08-18 — see PlayerViewModel.buildQualityOptions.
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

    // ── adaptive active: full ladder offered ───────────────────────────────────

    @Test
    fun `adaptive active offers the full video-only ladder, highest first`() {
        val tracks = listOf(
            track(360, isVideoOnly = true),
            track(720, isVideoOnly = true),
            track(1080, isVideoOnly = true),
        )

        val opts = PlayerViewModel.buildQualityOptions(tracks, adaptiveActive = true)

        assertEquals(listOf("1080p", "720p", "360p"), labels(opts))
    }

    @Test
    fun `adaptive active dedupes same height preferring muxed over video-only`() {
        val tracks = listOf(
            track(360, isVideoOnly = true, bitrate = 999_999), // higher bitrate, but video-only
            track(360, isVideoOnly = false),                   // muxed — must win
        )

        val opts = PlayerViewModel.buildQualityOptions(tracks, adaptiveActive = true)

        assertEquals(1, opts.size)
        assertEquals("360p", opts.first().label)
        assertFalse("Muxed must be preferred over video-only at the same height", opts.first().track.isVideoOnly)
    }

    @Test
    fun `adaptive inactive (MPD-gen failed) offers only the served track`() {
        // Honesty fix: when ANDROID_VR's own MPD generation fails, the player serves a single
        // progressive track. The menu must reflect that, not the phantom full ladder.
        val tracks = listOf(
            track(360, isVideoOnly = false),  // muxed served by the progressive fallback
            track(720, isVideoOnly = true),
            track(1080, isVideoOnly = true),
        )

        val opts = PlayerViewModel.buildQualityOptions(tracks, adaptiveActive = false)

        assertEquals("MPD-gen failure must collapse the menu to the served track", listOf("360p"), labels(opts))
    }

    // ── NewPipe fallback: single playable quality only ──────────────────────────

    @Test
    fun `progressive with muxed 360 + video-only 720_1080 offers ONLY 360p`() {
        // The Ep3 shape: phantom 720p/1080p iOS video-only tracks must not appear.
        val tracks = listOf(
            track(360, isVideoOnly = false),  // muxed itag-18 — the only sustaining stream
            track(720, isVideoOnly = true),
            track(1080, isVideoOnly = true),
        )

        val opts = PlayerViewModel.buildQualityOptions(tracks, adaptiveActive = false)

        assertEquals("Fallback must offer exactly one quality", 1, opts.size)
        assertEquals("360p", opts.first().label)
        assertFalse(opts.first().track.isVideoOnly)
    }

    @Test
    fun `progressive offers a single muxed quality regardless of ladder size`() {
        val tracks = listOf(
            track(360, isVideoOnly = false),
            track(1080, isVideoOnly = true),
        )

        val opts = PlayerViewModel.buildQualityOptions(tracks, adaptiveActive = false)

        assertEquals(listOf("360p"), labels(opts))
    }

    @Test
    fun `progressive with multiple muxed offers only the highest muxed (matches decide)`() {
        // decide() progressive always picks the highest muxed, so the menu must too — offering a
        // lower muxed would be a no-op tap (cap ignored on the progressive path).
        val tracks = listOf(
            track(360, isVideoOnly = false),
            track(720, isVideoOnly = false),
            track(1080, isVideoOnly = true),
        )

        val opts = PlayerViewModel.buildQualityOptions(tracks, adaptiveActive = false)

        assertEquals(listOf("720p"), labels(opts))
    }

    @Test
    fun `progressive with no muxed falls back to highest video-only`() {
        // decide() pairs best video-only + best audio when no muxed exists; the menu mirrors that.
        val tracks = listOf(
            track(360, isVideoOnly = true),
            track(720, isVideoOnly = true),
        )

        val opts = PlayerViewModel.buildQualityOptions(tracks, adaptiveActive = false)

        assertEquals(listOf("720p"), labels(opts))
        assertTrue(opts.first().track.isVideoOnly)
    }

    // ── Edge cases ──────────────────────────────────────────────────────────────

    @Test
    fun `empty tracks yields empty options whether or not adaptive is active`() {
        for (adaptive in listOf(true, false)) {
            assertTrue(
                "Expected empty options for adaptiveActive=$adaptive",
                PlayerViewModel.buildQualityOptions(emptyList(), adaptiveActive = adaptive).isEmpty(),
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

        val opts = PlayerViewModel.buildQualityOptions(tracks, adaptiveActive = true)

        assertEquals(listOf("480p"), labels(opts))
    }
}
