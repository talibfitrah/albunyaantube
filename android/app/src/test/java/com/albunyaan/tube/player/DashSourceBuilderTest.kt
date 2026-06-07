package com.albunyaan.tube.player

import androidx.media3.common.MimeTypes
import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.AudioTrackKind
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.SubtitleTrack
import com.albunyaan.tube.data.extractor.SyntheticDashMetadata
import com.albunyaan.tube.data.extractor.VideoTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Unit tests for DashSourceBuilder — covers only the pure functions:
 *   - decide(resolved)
 *   - subtitleMimeType(format)
 *
 * build() is NOT called here (requires Android / Media3 runtime).
 */
class DashSourceBuilderTest {

    private lateinit var builder: DashSourceBuilder

    // -------------------------------------------------------------------------
    // Fixtures — shared with MultiRepresentationMpdGeneratorTest style
    // -------------------------------------------------------------------------

    private fun validMeta(itag: Int, codec: String? = null) = SyntheticDashMetadata(
        itag = itag,
        initStart = 0,
        initEnd = 100,
        indexStart = 101,
        indexEnd = 200,
        approxDurationMs = 60_000L,
        codec = codec
    )

    private fun videoOnlyTrack(height: Int, itag: Int, bitrate: Int = height * 4000) = VideoTrack(
        url = "https://example.com/video$itag",
        mimeType = "video/mp4",
        width = height * 16 / 9,
        height = height,
        bitrate = bitrate,
        qualityLabel = "${height}p",
        fps = 30,
        isVideoOnly = true,
        syntheticDashMetadata = validMeta(itag, "avc1.64001f"),
        codec = "avc1.64001f"
    )

    /** Muxed (non video-only) track, e.g. itag 18 / 22. */
    private fun muxedTrack(height: Int, itag: Int, url: String = "https://example.com/muxed$itag") = VideoTrack(
        url = url,
        mimeType = "video/mp4",
        width = height * 16 / 9,
        height = height,
        bitrate = height * 1500,
        qualityLabel = "${height}p",
        fps = 30,
        isVideoOnly = false,
        syntheticDashMetadata = null,
        codec = "avc1.64001f"
    )

    private fun audioTrack(itag: Int, bitrate: Int = 128_000) = AudioTrack(
        url = "https://example.com/audio$itag",
        mimeType = "audio/mp4",
        bitrate = bitrate,
        codec = "mp4a.40.2",
        syntheticDashMetadata = validMeta(itag),
        language = null,
        trackType = AudioTrackKind.ORIGINAL
    )

    private fun streams(
        videoTracks: List<VideoTrack>,
        audioTracks: List<AudioTrack>,
        subtitleTracks: List<SubtitleTrack> = emptyList(),
        durationSeconds: Int = 120,
        isLive: Boolean = false,
        hlsUrl: String? = null,
        dashUrl: String? = null,
    ) = ResolvedStreams(
        streamId = "test-stream",
        videoTracks = videoTracks,
        audioTracks = audioTracks,
        subtitleTracks = subtitleTracks,
        durationSeconds = durationSeconds,
        isLive = isLive,
        hlsUrl = hlsUrl,
        dashUrl = dashUrl,
    )

    @Before
    fun setUp() {
        // SegmentDataSourceFactoryProvider is NOT used by decide/subtitleMimeType — mock is fine.
        builder = DashSourceBuilder(
            mpdGenerator = MultiRepresentationMpdGenerator(),
            dataSourceFactoryProvider = mock()
        )
    }

    // =========================================================================
    // decide() — VOD paths
    // =========================================================================

    @Test
    fun `VOD 3 video-only same-codec mp4 + 1 audio → LocalDash with data-uri prefix`() {
        val resolved = streams(
            videoTracks = listOf(
                videoOnlyTrack(360, itag = 134),
                videoOnlyTrack(720, itag = 136),
                videoOnlyTrack(1080, itag = 137),
            ),
            audioTracks = listOf(audioTrack(itag = 140))
        )

        val decision = builder.decide(resolved)

        assertTrue("Expected LocalDash but got: $decision", decision is SourceDecision.LocalDash)
        val localDash = decision as SourceDecision.LocalDash
        assertTrue(
            "dataUri must start with 'data:application/dash+xml;base64,'",
            localDash.dataUri.startsWith("data:application/dash+xml;base64,")
        )
    }

    @Test
    fun `VOD MPD ineligible 1 video-only + muxed track → Progressive with muxed url and no audio url`() {
        val muxed = muxedTrack(height = 360, itag = 18, url = "https://example.com/muxed18")
        val resolved = streams(
            videoTracks = listOf(
                videoOnlyTrack(720, itag = 136),  // only 1 video-only → MPD ineligible
                muxed,
            ),
            audioTracks = listOf(audioTrack(itag = 140))
        )

        val decision = builder.decide(resolved)

        assertTrue("Expected Progressive but got: $decision", decision is SourceDecision.Progressive)
        val prog = decision as SourceDecision.Progressive
        assertEquals("videoUrl must be the muxed track url", muxed.url, prog.videoUrl)
        assertNull("audioUrl must be null for muxed track", prog.audioUrl)
    }

    @Test
    fun `VOD MPD ineligible no muxed 1 video-only + 1 audio → Progressive with both urls set`() {
        val video = videoOnlyTrack(720, itag = 136)
        val audio = audioTrack(itag = 140)
        val resolved = streams(
            videoTracks = listOf(video),  // only 1 video-only → MPD ineligible
            audioTracks = listOf(audio)
        )

        val decision = builder.decide(resolved)

        assertTrue("Expected Progressive but got: $decision", decision is SourceDecision.Progressive)
        val prog = decision as SourceDecision.Progressive
        assertEquals("videoUrl must match video-only track", video.url, prog.videoUrl)
        assertEquals("audioUrl must match audio track", audio.url, prog.audioUrl)
    }

    @Test
    fun `VOD no tracks at all → None`() {
        val resolved = streams(
            videoTracks = emptyList(),
            audioTracks = emptyList()
        )

        val decision = builder.decide(resolved)

        assertTrue("Expected None but got: $decision", decision is SourceDecision.None)
    }

    // =========================================================================
    // decide() — Live paths
    // =========================================================================

    @Test
    fun `Live + dashUrl set → ServerDash`() {
        val resolved = streams(
            videoTracks = emptyList(),
            audioTracks = emptyList(),
            isLive = true,
            dashUrl = "https://manifest.googlevideo.com/api/manifest/dash/id/abc"
        )

        val decision = builder.decide(resolved)

        assertTrue("Expected ServerDash but got: $decision", decision is SourceDecision.ServerDash)
        assertEquals(
            "ServerDash url must match dashUrl",
            "https://manifest.googlevideo.com/api/manifest/dash/id/abc",
            (decision as SourceDecision.ServerDash).url
        )
    }

    @Test
    fun `Live + only hlsUrl set → Hls`() {
        val resolved = streams(
            videoTracks = emptyList(),
            audioTracks = emptyList(),
            isLive = true,
            hlsUrl = "https://manifest.googlevideo.com/api/manifest/hls_playlist/id/abc"
        )

        val decision = builder.decide(resolved)

        assertTrue("Expected Hls but got: $decision", decision is SourceDecision.Hls)
        assertEquals(
            "Hls url must match hlsUrl",
            "https://manifest.googlevideo.com/api/manifest/hls_playlist/id/abc",
            (decision as SourceDecision.Hls).url
        )
    }

    @Test
    fun `Live + neither dashUrl nor hlsUrl → None`() {
        val resolved = streams(
            videoTracks = emptyList(),
            audioTracks = emptyList(),
            isLive = true,
            dashUrl = null,
            hlsUrl = null,
        )

        val decision = builder.decide(resolved)

        assertTrue("Expected None but got: $decision", decision is SourceDecision.None)
        assertEquals("LIVE_NO_MANIFEST", (decision as SourceDecision.None).reason)
    }

    // =========================================================================
    // subtitleMimeType() mapping
    // =========================================================================

    @Test
    fun `subtitleMimeType vtt → TEXT_VTT`() {
        assertEquals(MimeTypes.TEXT_VTT, builder.subtitleMimeType("vtt"))
    }

    @Test
    fun `subtitleMimeType VTT uppercase → TEXT_VTT`() {
        assertEquals(MimeTypes.TEXT_VTT, builder.subtitleMimeType("VTT"))
    }

    @Test
    fun `subtitleMimeType ttml → APPLICATION_TTML`() {
        assertEquals(MimeTypes.APPLICATION_TTML, builder.subtitleMimeType("ttml"))
    }

    @Test
    fun `subtitleMimeType srt → APPLICATION_SUBRIP`() {
        assertEquals(MimeTypes.APPLICATION_SUBRIP, builder.subtitleMimeType("srt"))
    }

    @Test
    fun `subtitleMimeType srv3 → APPLICATION_SUBRIP`() {
        assertEquals(MimeTypes.APPLICATION_SUBRIP, builder.subtitleMimeType("srv3"))
    }

    @Test
    fun `subtitleMimeType srv1 → APPLICATION_SUBRIP`() {
        assertEquals(MimeTypes.APPLICATION_SUBRIP, builder.subtitleMimeType("srv1"))
    }

    @Test
    fun `subtitleMimeType unknown xyz → null`() {
        assertNull(builder.subtitleMimeType("xyz"))
    }

    @Test
    fun `subtitleMimeType null → null`() {
        assertNull(builder.subtitleMimeType(null))
    }
}
