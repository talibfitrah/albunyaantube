package com.albunyaan.tube.player

import androidx.media3.common.MimeTypes
import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.AudioTrackKind
import com.albunyaan.tube.data.extractor.ExtractionClient
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
        extractionClient: ExtractionClient = ExtractionClient.ANDROID_VR,
    ) = ResolvedStreams(
        streamId = "test-stream",
        videoTracks = videoTracks,
        audioTracks = audioTracks,
        subtitleTracks = subtitleTracks,
        durationSeconds = durationSeconds,
        isLive = isLive,
        hlsUrl = hlsUrl,
        dashUrl = dashUrl,
        extractionClient = extractionClient,
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
    fun `VOD MPD ineligible (no audio) + muxed track → Progressive with muxed url and no audio url`() {
        // MPD now fails only for a genuine reason (here: no audio with ranges). When it does, the
        // progressive fallback still prefers the muxed track. (1 video-only is no longer "too few".)
        val muxed = muxedTrack(height = 360, itag = 18, url = "https://example.com/muxed18")
        val resolved = streams(
            videoTracks = listOf(
                videoOnlyTrack(720, itag = 136),
                muxed,
            ),
            audioTracks = emptyList()  // no audio → MPD ineligible → progressive
        )

        val decision = builder.decide(resolved)

        assertTrue("Expected Progressive but got: $decision", decision is SourceDecision.Progressive)
        val prog = decision as SourceDecision.Progressive
        assertEquals("videoUrl must be the muxed track url", muxed.url, prog.videoUrl)
        assertNull("audioUrl must be null for muxed track", prog.audioUrl)
    }

    @Test
    fun `VOD 1 video-only + 1 audio → LocalDash adaptive (no min-rep gate)`() {
        // Behavior change (the fix): a single video-only track + audio is a valid 1-rep DASH ladder
        // (LibreTube parity), so we build LocalDash instead of collapsing to progressive 360p.
        val video = videoOnlyTrack(720, itag = 136)
        val audio = audioTrack(itag = 140)
        val resolved = streams(
            videoTracks = listOf(video),
            audioTracks = listOf(audio)
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
    fun `VOD MPD ineligible single video-only no audio → Progressive with videoUrl set and null audio`() {
        val video = videoOnlyTrack(720, itag = 136)
        val resolved = streams(
            videoTracks = listOf(video),  // only 1 video-only → MPD ineligible
            audioTracks = emptyList(),    // no audio tracks
        )

        val decision = builder.decide(resolved)

        assertTrue("Expected Progressive but got: $decision", decision is SourceDecision.Progressive)
        val prog = decision as SourceDecision.Progressive
        assertEquals("videoUrl must match the single video-only track", video.url, prog.videoUrl)
        assertEquals("videoMime must match the track mimeType", video.mimeType, prog.videoMime)
        assertNull("audioUrl must be null when no audio track available", prog.audioUrl)
        assertNull("audioMime must be null when no audio track available", prog.audioMime)
    }

    @Test
    fun `VOD no tracks at all → None with NO_VIDEO_TRACK reason`() {
        val resolved = streams(
            videoTracks = emptyList(),
            audioTracks = emptyList()
        )

        val decision = builder.decide(resolved)

        assertTrue("Expected None but got: $decision", decision is SourceDecision.None)
        assertEquals("NO_VIDEO_TRACK", (decision as SourceDecision.None).reason)
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

    @Test
    fun `Live with BOTH dashUrl and hlsUrl → ServerDash (DASH preferred)`() {
        val resolved = streams(
            videoTracks = emptyList(),
            audioTracks = emptyList(),
            isLive = true,
            dashUrl = "https://manifest.googlevideo.com/api/manifest/dash/id/live123",
            hlsUrl = "https://manifest.googlevideo.com/api/manifest/hls_playlist/id/live123",
        )

        val decision = builder.decide(resolved)

        assertTrue("Expected ServerDash but got: $decision", decision is SourceDecision.ServerDash)
        assertEquals(
            "DASH must be preferred over HLS for live when both are present",
            "https://manifest.googlevideo.com/api/manifest/dash/id/live123",
            (decision as SourceDecision.ServerDash).url
        )
    }

    @Test
    fun `VOD MPD succeeds even when dashUrl and hlsUrl are non-null → LocalDash`() {
        // When MPD generation succeeds (≥2 video-only same-codec tracks), LocalDash is chosen
        // regardless of dashUrl/hlsUrl being present.
        val resolved = streams(
            videoTracks = listOf(
                videoOnlyTrack(360, itag = 134),
                videoOnlyTrack(720, itag = 136),
            ),
            audioTracks = listOf(audioTrack(itag = 140)),
            dashUrl = "https://manifest.googlevideo.com/api/manifest/dash/id/vod123",
            hlsUrl = "https://manifest.googlevideo.com/api/manifest/hls_playlist/id/vod123",
        )

        val decision = builder.decide(resolved)

        assertTrue("VOD always prefers local DASH MPD but got: $decision", decision is SourceDecision.LocalDash)
    }

    @Test
    fun `muxed best-quality VOD ineligible two muxed tracks → Progressive picks 720p not 360p`() {
        val muxed360 = muxedTrack(height = 360, itag = 18, url = "https://example.com/muxed18")
        val muxed720 = muxedTrack(height = 720, itag = 22, url = "https://example.com/muxed22")
        // Only muxed tracks → MPD ineligible. No video-only tracks → bestVideo branch not taken.
        val resolved = streams(
            videoTracks = listOf(muxed360, muxed720),
            audioTracks = emptyList(),
        )

        val decision = builder.decide(resolved)

        assertTrue("Expected Progressive but got: $decision", decision is SourceDecision.Progressive)
        val prog = decision as SourceDecision.Progressive
        assertEquals(
            "Must pick the highest-quality muxed track (720p itag 22), not 360p itag 18",
            muxed720.url,
            prog.videoUrl
        )
        assertNull("Muxed track needs no separate audio", prog.audioUrl)
    }

    // =========================================================================
    // decide() — extractionClient gate (adaptive only sustains on ANDROID_VR)
    // =========================================================================
    //
    // The NewPipe poToken fallback clients (iOS / android) mint GVS segment URLs whose adaptive
    // ranges YouTube only honors for the *initial* ~60s — later segments 403 (verified on-device +
    // yt-dlp for One4kids "Zaky's Learning Club" and a control video). Only ANDROID_VR's adaptive
    // segments sustain. So a fallback resolve must SKIP the (doomed) adaptive MPD and serve the
    // always-present muxed itag-18 progressive stream directly — even when the tracks would
    // otherwise be LocalDash-eligible. ANDROID_VR keeps its full HD/4K adaptive ladder.

    /** The Ep3 fallback shape: MPD-eligible video-only tracks + muxed itag 18 + audio. */
    private fun fallbackEligibleStreams(extractionClient: ExtractionClient) = streams(
        videoTracks = listOf(
            videoOnlyTrack(360, itag = 134),
            videoOnlyTrack(720, itag = 136),
            videoOnlyTrack(1080, itag = 137),
            muxedTrack(height = 360, itag = 18, url = "https://example.com/muxed18"),
        ),
        audioTracks = listOf(audioTrack(itag = 140)),
        extractionClient = extractionClient,
    )

    @Test
    fun `NEWPIPE_IOS fallback + LocalDash-eligible tracks → Progressive muxed 360p (adaptive skipped)`() {
        val decision = builder.decide(fallbackEligibleStreams(ExtractionClient.NEWPIPE_IOS))

        assertTrue("Expected Progressive but got: $decision", decision is SourceDecision.Progressive)
        val prog = decision as SourceDecision.Progressive
        assertEquals(
            "Fallback must serve the sustaining muxed itag-18 stream, not the doomed adaptive ladder",
            "https://example.com/muxed18",
            prog.videoUrl,
        )
        assertNull("Muxed track carries its own audio — no separate audio url", prog.audioUrl)
    }

    @Test
    fun `NEWPIPE_ANDROID fallback + LocalDash-eligible tracks → Progressive (adaptive skipped)`() {
        // The gate is `== ANDROID_VR`, so the android fallback client is treated like iOS here.
        val decision = builder.decide(fallbackEligibleStreams(ExtractionClient.NEWPIPE_ANDROID))

        assertTrue("Expected Progressive but got: $decision", decision is SourceDecision.Progressive)
        assertEquals(
            "https://example.com/muxed18",
            (decision as SourceDecision.Progressive).videoUrl,
        )
    }

    @Test
    fun `same eligible fixture on ANDROID_VR → LocalDash (proves the client is the only difference)`() {
        // Contrast guard: identical tracks, only extractionClient differs. ANDROID_VR keeps adaptive.
        val decision = builder.decide(fallbackEligibleStreams(ExtractionClient.ANDROID_VR))

        assertTrue("ANDROID_VR must keep the adaptive MPD but got: $decision", decision is SourceDecision.LocalDash)
    }

    // =========================================================================
    // decide() — forceProgressive
    // =========================================================================

    @Test
    fun `VOD eligible-for-LocalDash + forceProgressive=true → Progressive not LocalDash`() {
        // Same fixture as the LocalDash test (≥2 video-only same-codec + audio), but with
        // forceProgressive=true the MPD attempt is skipped and we fall through to progressive.
        val resolved = streams(
            videoTracks = listOf(
                videoOnlyTrack(360, itag = 134),
                videoOnlyTrack(720, itag = 136),
                videoOnlyTrack(1080, itag = 137),
            ),
            audioTracks = listOf(audioTrack(itag = 140))
        )

        val decision = builder.decide(resolved, forceProgressive = true)

        assertTrue("Expected Progressive but got: $decision", decision is SourceDecision.Progressive)
        val prog = decision as SourceDecision.Progressive
        assertEquals(
            "No muxed → best video-only track (1080p itag 137) is the video url",
            "https://example.com/video137",
            prog.videoUrl
        )
        assertEquals(
            "Separate audio track must be paired in",
            "https://example.com/audio140",
            prog.audioUrl
        )
    }

    @Test
    fun `forceProgressive=true with muxed present → Progressive picks best-quality muxed`() {
        val muxed360 = muxedTrack(height = 360, itag = 18, url = "https://example.com/muxed18")
        val muxed720 = muxedTrack(height = 720, itag = 22, url = "https://example.com/muxed22")
        // Three video-only tracks would normally be LocalDash-eligible; forceProgressive skips that.
        val resolved = streams(
            videoTracks = listOf(
                videoOnlyTrack(360, itag = 134),
                videoOnlyTrack(720, itag = 136),
                videoOnlyTrack(1080, itag = 137),
                muxed360,
                muxed720,
            ),
            audioTracks = listOf(audioTrack(itag = 140))
        )

        val decision = builder.decide(resolved, forceProgressive = true)

        assertTrue("Expected Progressive but got: $decision", decision is SourceDecision.Progressive)
        val prog = decision as SourceDecision.Progressive
        assertEquals(
            "Must pick the highest-quality muxed track (720p itag 22)",
            muxed720.url,
            prog.videoUrl
        )
        assertNull("Muxed track needs no separate audio", prog.audioUrl)
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
    fun `subtitleMimeType webvtt → TEXT_VTT`() {
        assertEquals(MimeTypes.TEXT_VTT, builder.subtitleMimeType("webvtt"))
    }

    @Test
    fun `subtitleMimeType WEBVTT uppercase → TEXT_VTT`() {
        assertEquals(MimeTypes.TEXT_VTT, builder.subtitleMimeType("WEBVTT"))
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
    fun `subtitleMimeType srv3 → null (YouTube XML format, not parseable)`() {
        // srv1/2/3 are YouTube's XML caption format — mapping to SUBRIP would corrupt them.
        assertNull(builder.subtitleMimeType("srv3"))
    }

    @Test
    fun `subtitleMimeType srv1 → null (YouTube XML format, not parseable)`() {
        assertNull(builder.subtitleMimeType("srv1"))
    }

    @Test
    fun `subtitleMimeType srv2 → null (YouTube XML format, not parseable)`() {
        assertNull(builder.subtitleMimeType("srv2"))
    }

    @Test
    fun `subtitleMimeType SRV1 uppercase → null`() {
        assertNull(builder.subtitleMimeType("SRV1"))
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
