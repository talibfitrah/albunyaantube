package com.albunyaan.tube.data.source

import com.albunyaan.tube.data.model.api.models.DownloadManifestDto
import com.albunyaan.tube.data.model.api.models.StreamOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for DownloadStreamSelector quality selection logic.
 *
 * Tests the production stream selection algorithm directly, verifying:
 * - targetHeight parameter correctly selects appropriate stream
 * - audioOnly parameter selects audio stream
 * - Best available is selected when no targetHeight specified
 * - Quality label parsing handles various formats
 */
class DownloadQualitySelectionTest {

    private fun createVideoStream(
        qualityLabel: String,
        bitrate: Int,
        requiresMerging: Boolean = false,
        progressiveUrl: String? = if (!requiresMerging) "http://example.com/$qualityLabel" else null,
        videoUrl: String? = if (requiresMerging) "http://example.com/video/$qualityLabel" else null,
        audioUrl: String? = if (requiresMerging) "http://example.com/audio/$qualityLabel" else null
    ) = StreamOption(
        id = "stream_$qualityLabel",
        qualityLabel = qualityLabel,
        mimeType = "video/mp4",
        requiresMerging = requiresMerging,
        progressiveUrl = progressiveUrl,
        videoUrl = videoUrl,
        audioUrl = audioUrl,
        fileSize = (bitrate * 1000L),
        bitrate = bitrate
    )

    private fun createAudioStream(
        qualityLabel: String,
        bitrate: Int
    ) = StreamOption(
        id = "audio_$qualityLabel",
        qualityLabel = qualityLabel,
        mimeType = "audio/m4a",
        requiresMerging = false,
        progressiveUrl = null,
        videoUrl = null,
        audioUrl = "http://example.com/audio/$qualityLabel",
        fileSize = (bitrate * 100L),
        bitrate = bitrate
    )

    private fun createManifest(
        videoStreams: List<StreamOption> = emptyList(),
        audioStreams: List<StreamOption> = emptyList()
    ) = DownloadManifestDto(
        videoId = "test123",
        title = "Test Video",
        expiresAtMillis = System.currentTimeMillis() + 3600000,
        videoStreams = videoStreams,
        audioStreams = audioStreams
    )

    // Tests for targetHeight selection

    @Test
    fun `selects exact height match when available`() {
        val manifest = createManifest(
            videoStreams = listOf(
                createVideoStream("360p", 500),
                createVideoStream("480p", 1000),
                createVideoStream("720p", 2000),
                createVideoStream("1080p", 4000)
            )
        )

        val result = DownloadStreamSelector.selectStream(manifest, audioOnly = false, targetHeight = 720)

        assertEquals("720p", result.qualityLabel)
    }

    @Test
    fun `selects closest height below target when exact not available`() {
        val manifest = createManifest(
            videoStreams = listOf(
                createVideoStream("360p", 500),
                createVideoStream("480p", 1000),
                createVideoStream("1080p", 4000) // No 720p available
            )
        )

        val result = DownloadStreamSelector.selectStream(manifest, audioOnly = false, targetHeight = 720)

        // Should select 480p (closest below 720)
        assertEquals("480p", result.qualityLabel)
    }

    @Test
    fun `falls back to lowest when all heights exceed target`() {
        val manifest = createManifest(
            videoStreams = listOf(
                createVideoStream("720p", 2000),
                createVideoStream("1080p", 4000),
                createVideoStream("1440p", 8000)
            )
        )

        val result = DownloadStreamSelector.selectStream(manifest, audioOnly = false, targetHeight = 360)

        // Should fall back to lowest available (720p)
        assertEquals("720p", result.qualityLabel)
    }

    @Test
    fun `selects best quality when targetHeight is null`() {
        val manifest = createManifest(
            videoStreams = listOf(
                createVideoStream("360p", 500),
                createVideoStream("480p", 1000),
                createVideoStream("720p", 2000),
                createVideoStream("1080p", 4000) // Highest bitrate
            )
        )

        val result = DownloadStreamSelector.selectStream(manifest, audioOnly = false, targetHeight = null)

        // Should select highest bitrate (1080p)
        assertEquals("1080p", result.qualityLabel)
    }

    @Test
    fun `selects best quality when targetHeight is zero`() {
        val manifest = createManifest(
            videoStreams = listOf(
                createVideoStream("720p", 2000),
                createVideoStream("1080p", 4000)
            )
        )

        val result = DownloadStreamSelector.selectStream(manifest, audioOnly = false, targetHeight = 0)

        // Should select highest bitrate
        assertEquals("1080p", result.qualityLabel)
    }

    // Tests for audioOnly selection

    @Test
    fun `selects best audio stream when audioOnly is true`() {
        val manifest = createManifest(
            videoStreams = listOf(
                createVideoStream("1080p", 4000)
            ),
            audioStreams = listOf(
                createAudioStream("64kbps", 64),
                createAudioStream("128kbps", 128),
                createAudioStream("192kbps", 192) // Highest bitrate
            )
        )

        val result = DownloadStreamSelector.selectStream(manifest, audioOnly = true, targetHeight = null)

        // Should select highest bitrate audio
        assertEquals("192kbps", result.qualityLabel)
    }

    @Test
    fun `audioOnly ignores targetHeight`() {
        val manifest = createManifest(
            videoStreams = listOf(
                createVideoStream("720p", 2000)
            ),
            audioStreams = listOf(
                createAudioStream("128kbps", 128),
                createAudioStream("192kbps", 192)
            )
        )

        val result = DownloadStreamSelector.selectStream(manifest, audioOnly = true, targetHeight = 720)

        // Should still select audio, not video
        assertEquals("192kbps", result.qualityLabel)
    }

    // Tests for quality label parsing

    @Test
    fun `parses height from various quality label formats`() {
        val manifest = createManifest(
            videoStreams = listOf(
                createVideoStream("720p", 2000),      // Standard format
                createVideoStream("1080p60", 4000),   // With framerate
                createVideoStream("480p30", 1000)     // With framerate
            )
        )

        // Should parse 1080 from "1080p60"
        val result1080 = DownloadStreamSelector.selectStream(manifest, audioOnly = false, targetHeight = 1080)
        assertEquals("1080p60", result1080.qualityLabel)

        // Should parse 480 from "480p30" for target 500
        val result500 = DownloadStreamSelector.selectStream(manifest, audioOnly = false, targetHeight = 500)
        assertEquals("480p30", result500.qualityLabel)
    }

    // Test for split streams (requiresMerging = true)

    @Test
    fun `selects split stream based on targetHeight`() {
        val manifest = createManifest(
            videoStreams = listOf(
                createVideoStream("480p", 1000, requiresMerging = false),
                createVideoStream("720p", 2000, requiresMerging = true),
                createVideoStream("1080p", 4000, requiresMerging = true)
            )
        )

        val result = DownloadStreamSelector.selectStream(manifest, audioOnly = false, targetHeight = 720)

        assertEquals("720p", result.qualityLabel)
        assertNotNull("Split stream should have videoUrl", result.videoUrl)
    }

    // Tests for parseHeightFromQualityLabel helper

    @Test
    fun `parseHeightFromQualityLabel parses standard format`() {
        assertEquals(720, DownloadStreamSelector.parseHeightFromQualityLabel("720p"))
        assertEquals(1080, DownloadStreamSelector.parseHeightFromQualityLabel("1080p"))
        assertEquals(360, DownloadStreamSelector.parseHeightFromQualityLabel("360p"))
    }

    @Test
    fun `parseHeightFromQualityLabel parses format with framerate`() {
        assertEquals(1080, DownloadStreamSelector.parseHeightFromQualityLabel("1080p60"))
        assertEquals(720, DownloadStreamSelector.parseHeightFromQualityLabel("720p30"))
        assertEquals(480, DownloadStreamSelector.parseHeightFromQualityLabel("480p24"))
    }

    @Test
    fun `parseHeightFromQualityLabel returns null for invalid formats`() {
        assertNull(DownloadStreamSelector.parseHeightFromQualityLabel("invalid"))
        assertNull(DownloadStreamSelector.parseHeightFromQualityLabel("hd"))
        assertNull(DownloadStreamSelector.parseHeightFromQualityLabel(""))
    }
}
