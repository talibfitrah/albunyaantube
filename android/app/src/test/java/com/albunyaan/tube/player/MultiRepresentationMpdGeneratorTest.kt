package com.albunyaan.tube.player

import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.AudioTrackKind
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.SyntheticDashMetadata
import com.albunyaan.tube.data.extractor.VideoTrack
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * Unit tests for MultiRepresentationMpdGenerator.
 *
 * Tests multi-language audio (multi AdaptationSet), base64 data URI encoding,
 * and backward-compat with single-audio / no-language videos.
 */
class MultiRepresentationMpdGeneratorTest {

    private lateinit var generator: MultiRepresentationMpdGenerator

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Valid SyntheticDashMetadata that passes hasValidRanges(). */
    private fun validMeta(itag: Int, codec: String? = null) = SyntheticDashMetadata(
        itag = itag,
        initStart = 0,
        initEnd = 100,
        indexStart = 101,
        indexEnd = 200,
        approxDurationMs = 60_000L,
        codec = codec
    )

    /** video/mp4 H264 video-only track with valid ranges. */
    private fun videoTrack(height: Int, itag: Int, bitrate: Int = height * 4000) = VideoTrack(
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

    /** audio/mp4 track with valid ranges and optional language/trackType. */
    private fun audioTrack(
        itag: Int,
        bitrate: Int = 128_000,
        language: String? = null,
        trackType: AudioTrackKind? = null
    ) = AudioTrack(
        url = "https://example.com/audio$itag",
        mimeType = "audio/mp4",
        bitrate = bitrate,
        codec = "mp4a.40.2",
        syntheticDashMetadata = validMeta(itag),
        language = language,
        trackType = trackType
    )

    /** Count occurrences of a substring in a string. */
    private fun String.countOccurrences(sub: String): Int {
        var count = 0
        var idx = 0
        while (true) {
            idx = indexOf(sub, idx)
            if (idx == -1) break
            count++
            idx += sub.length
        }
        return count
    }

    /** Minimal valid ResolvedStreams. Duration is required by checkEligibility. */
    private fun streams(
        videoTracks: List<VideoTrack>,
        audioTracks: List<AudioTrack>,
        durationSeconds: Int = 120
    ) = ResolvedStreams(
        streamId = "test-stream",
        videoTracks = videoTracks,
        audioTracks = audioTracks,
        durationSeconds = durationSeconds
    )

    @Before
    fun setUp() {
        generator = MultiRepresentationMpdGenerator()
    }

    // =========================================================================
    // Test 1 — Two languages → two audio AdaptationSets with lang + Role
    // =========================================================================

    @Test
    fun `two language audio tracks produce two audio AdaptationSets with lang and Role`() {
        val resolved = streams(
            videoTracks = listOf(
                videoTrack(360, itag = 134),
                videoTrack(720, itag = 136),
                videoTrack(1080, itag = 137)
            ),
            audioTracks = listOf(
                audioTrack(itag = 140, language = "en", trackType = AudioTrackKind.ORIGINAL),
                audioTrack(itag = 256, language = "ar", trackType = AudioTrackKind.DUBBED)
            )
        )

        val result = generator.generateMpd(resolved)

        assertTrue("Expected Success but got: $result", result is MultiRepresentationMpdGenerator.Result.Success)
        val success = result as MultiRepresentationMpdGenerator.Result.Success

        val xml = success.mpdXml

        // Exactly 2 audio AdaptationSets (match on the shared prefix so the assertion
        // is not fooled by partial substrings or container variants like audio/webm)
        assertEquals(
            "Expected exactly 2 audio AdaptationSets",
            2,
            xml.countOccurrences("""<AdaptationSet mimeType="audio/""")
        )

        // English: lang="en" and role value="main" (ORIGINAL → main)
        assertTrue("""MPD must contain lang="en"""", xml.contains("""lang="en""""))
        assertTrue("MPD must contain value=\"main\" for English (ORIGINAL)", xml.contains("""value="main""""))

        // Arabic: lang="ar" and role value="dub" (DUBBED → dub)
        assertTrue("""MPD must contain lang="ar"""", xml.contains("""lang="ar""""))
        assertTrue("MPD must contain value=\"dub\" for Arabic (DUBBED)", xml.contains("""value="dub""""))

        // Both language audio tracks are in the result
        assertEquals("audioTracks must contain 2 entries", 2, success.audioTracks.size)
    }

    // =========================================================================
    // Test 2 — Single audio, no language → one AdaptationSet, no lang, no Role
    // =========================================================================

    @Test
    fun `single audio track with null language produces one AdaptationSet without lang or Role`() {
        val singleAudio = audioTrack(itag = 140, language = null, trackType = null)

        val resolved = streams(
            videoTracks = listOf(
                videoTrack(360, itag = 134),
                videoTrack(720, itag = 136)
            ),
            audioTracks = listOf(singleAudio)
        )

        val result = generator.generateMpd(resolved)

        assertTrue("Expected Success but got: $result", result is MultiRepresentationMpdGenerator.Result.Success)
        val success = result as MultiRepresentationMpdGenerator.Result.Success

        val xml = success.mpdXml

        // Exactly 1 audio AdaptationSet
        assertEquals(
            "Expected exactly 1 audio AdaptationSet",
            1,
            xml.countOccurrences("""mimeType="audio/mp4"""")
        )

        // No lang= attribute anywhere in the MPD
        assertFalse("Audio AdaptationSet must NOT contain lang= for null language", xml.contains("lang="))

        // No <Role element anywhere in the MPD
        assertFalse("Audio AdaptationSet must NOT contain <Role for null trackType", xml.contains("<Role"))

        // audioTracks list has exactly 1 entry
        assertEquals("audioTracks must contain 1 entry", 1, success.audioTracks.size)

        // Legacy audioTrack field equals that single track
        assertEquals("audioTrack (legacy) must equal the single track", singleAudio, success.audioTrack)
    }

    // =========================================================================
    // Test 3 — Base64 data URI round-trip
    // =========================================================================

    @Test
    fun `mpdDataUri uses base64 encoding and round-trips to mpdXml`() {
        val resolved = streams(
            videoTracks = listOf(
                videoTrack(360, itag = 134),
                videoTrack(720, itag = 136)
            ),
            audioTracks = listOf(audioTrack(itag = 140))
        )

        val result = generator.generateMpd(resolved)

        assertTrue("Expected Success but got: $result", result is MultiRepresentationMpdGenerator.Result.Success)
        val success = result as MultiRepresentationMpdGenerator.Result.Success

        // Must start with base64 prefix, NOT charset=utf-8
        assertTrue(
            "mpdDataUri must start with 'data:application/dash+xml;base64,'",
            success.mpdDataUri.startsWith("data:application/dash+xml;base64,")
        )

        // Decode and compare
        val base64Part = success.mpdDataUri.removePrefix("data:application/dash+xml;base64,")
        val decoded = String(Base64.getDecoder().decode(base64Part), Charsets.UTF_8)
        assertEquals("Decoded base64 must equal mpdXml verbatim", success.mpdXml, decoded)
    }

    // =========================================================================
    // Test 4 — Ineligibility preserved: 1 video-only track → Failure
    // =========================================================================

    @Test
    fun `single video track returns Failure (unchanged eligibility behavior)`() {
        val resolved = streams(
            videoTracks = listOf(
                videoTrack(720, itag = 136)
            ),
            audioTracks = listOf(audioTrack(itag = 140))
        )

        val result = generator.generateMpd(resolved)

        assertTrue("Expected Failure for single video track but got: $result", result is MultiRepresentationMpdGenerator.Result.Failure)
    }

    // =========================================================================
    // Extra coverage: DUBBED_AUTO → "dub", DESCRIPTIVE → "description"
    // =========================================================================

    @Test
    fun `DUBBED_AUTO trackType produces Role value dub`() {
        val resolved = streams(
            videoTracks = listOf(
                videoTrack(360, itag = 134),
                videoTrack(720, itag = 136)
            ),
            audioTracks = listOf(
                audioTrack(itag = 140, language = "en", trackType = AudioTrackKind.ORIGINAL),
                audioTrack(itag = 258, language = "fr", trackType = AudioTrackKind.DUBBED_AUTO)
            )
        )

        val result = generator.generateMpd(resolved)

        assertTrue("Expected Success", result is MultiRepresentationMpdGenerator.Result.Success)
        val xml = (result as MultiRepresentationMpdGenerator.Result.Success).mpdXml
        assertTrue("""MPD must contain lang="fr"""", xml.contains("""lang="fr""""))
        // DUBBED_AUTO maps to "dub" — there must be at least one Role value="dub"
        assertTrue("MPD must contain value=\"dub\" for DUBBED_AUTO", xml.contains("""value="dub""""))
    }

    @Test
    fun `DESCRIPTIVE trackType produces Role value description`() {
        val resolved = streams(
            videoTracks = listOf(
                videoTrack(360, itag = 134),
                videoTrack(720, itag = 136)
            ),
            audioTracks = listOf(
                audioTrack(itag = 140, language = "en", trackType = AudioTrackKind.ORIGINAL),
                audioTrack(itag = 258, language = "en-desc", trackType = AudioTrackKind.DESCRIPTIVE)
            )
        )

        val result = generator.generateMpd(resolved)

        assertTrue("Expected Success", result is MultiRepresentationMpdGenerator.Result.Success)
        val xml = (result as MultiRepresentationMpdGenerator.Result.Success).mpdXml
        assertTrue("MPD must contain value=\"description\" for DESCRIPTIVE", xml.contains("""value="description""""))
    }

    @Test
    fun `UNKNOWN trackType omits Role element`() {
        val resolved = streams(
            videoTracks = listOf(
                videoTrack(360, itag = 134),
                videoTrack(720, itag = 136)
            ),
            audioTracks = listOf(
                audioTrack(itag = 140, language = "en", trackType = AudioTrackKind.UNKNOWN)
            )
        )

        val result = generator.generateMpd(resolved)

        assertTrue("Expected Success", result is MultiRepresentationMpdGenerator.Result.Success)
        val xml = (result as MultiRepresentationMpdGenerator.Result.Success).mpdXml
        assertFalse("UNKNOWN trackType must not produce a <Role element", xml.contains("<Role"))
    }

    // =========================================================================
    // Extra coverage: legacy audioTrack field is primary (ORIGINAL preferred)
    // =========================================================================

    @Test
    fun `legacy audioTrack field prefers ORIGINAL track when both languages present`() {
        val enTrack = audioTrack(itag = 140, language = "en", trackType = AudioTrackKind.ORIGINAL)
        val arTrack = audioTrack(itag = 256, language = "ar", trackType = AudioTrackKind.DUBBED)

        val resolved = streams(
            videoTracks = listOf(
                videoTrack(360, itag = 134),
                videoTrack(720, itag = 136)
            ),
            audioTracks = listOf(enTrack, arTrack)
        )

        val result = generator.generateMpd(resolved)

        assertTrue("Expected Success", result is MultiRepresentationMpdGenerator.Result.Success)
        val success = result as MultiRepresentationMpdGenerator.Result.Success

        // Legacy audioTrack must be the ORIGINAL track
        assertEquals("Legacy audioTrack must be the ORIGINAL (en) track", enTrack, success.audioTrack)
    }

    // =========================================================================
    // m2 — WebM video + MP4 audio → NO_COMPATIBLE_AUDIO
    // =========================================================================

    /** video/webm VP9 video-only track with valid ranges. */
    private fun webmVideoTrack(height: Int, itag: Int, bitrate: Int = height * 4000) = VideoTrack(
        url = "https://example.com/video$itag",
        mimeType = "video/webm",
        width = height * 16 / 9,
        height = height,
        bitrate = bitrate,
        qualityLabel = "${height}p",
        fps = 30,
        isVideoOnly = true,
        syntheticDashMetadata = validMeta(itag, "vp9"),
        codec = "vp9"
    )

    @Test
    fun `webm video with mp4 audio returns Failure NO_COMPATIBLE_AUDIO`() {
        val resolved = streams(
            videoTracks = listOf(
                webmVideoTrack(360, itag = 243),
                webmVideoTrack(720, itag = 247)
            ),
            audioTracks = listOf(
                audioTrack(itag = 140, language = "en", trackType = AudioTrackKind.ORIGINAL)
            )
        )

        val result = generator.generateMpd(resolved)

        assertTrue(
            "Expected Failure but got: $result",
            result is MultiRepresentationMpdGenerator.Result.Failure
        )
        assertEquals(
            "Failure reason must be NO_COMPATIBLE_AUDIO",
            "NO_COMPATIBLE_AUDIO",
            (result as MultiRepresentationMpdGenerator.Result.Failure).reason
        )
    }

    // =========================================================================
    // C1 — same language, different role → two AdaptationSets
    // =========================================================================

    @Test
    fun `same language different trackType produces two audio AdaptationSets`() {
        val resolved = streams(
            videoTracks = listOf(
                videoTrack(360, itag = 134),
                videoTrack(720, itag = 136)
            ),
            audioTracks = listOf(
                audioTrack(itag = 140, language = "en", trackType = AudioTrackKind.ORIGINAL),
                audioTrack(itag = 258, language = "en", trackType = AudioTrackKind.DESCRIPTIVE)
            )
        )

        val result = generator.generateMpd(resolved)

        assertTrue("Expected Success but got: $result", result is MultiRepresentationMpdGenerator.Result.Success)
        val success = result as MultiRepresentationMpdGenerator.Result.Success

        // Must have 2 audio AdaptationSets, not 1 (same language but different roles)
        assertEquals("audioTracks must contain 2 entries", 2, success.audioTracks.size)

        val xml = success.mpdXml
        assertEquals(
            "Expected exactly 2 audio AdaptationSets",
            2,
            xml.countOccurrences("""<AdaptationSet mimeType="audio/""")
        )
        assertTrue("MPD must contain value=\"main\" for ORIGINAL", xml.contains("""value="main""""))
        assertTrue("MPD must contain value=\"description\" for DESCRIPTIVE", xml.contains("""value="description""""))
    }

    // =========================================================================
    // C2 — deterministic order: ORIGINAL before DUBBED regardless of input order
    // =========================================================================

    @Test
    fun `audio AdaptationSets are ordered ORIGINAL first regardless of input order`() {
        // Input order: DUBBED ar first, then ORIGINAL en — output must be reversed
        val resolved = streams(
            videoTracks = listOf(
                videoTrack(360, itag = 134),
                videoTrack(720, itag = 136)
            ),
            audioTracks = listOf(
                audioTrack(itag = 256, language = "ar", trackType = AudioTrackKind.DUBBED),
                audioTrack(itag = 140, language = "en", trackType = AudioTrackKind.ORIGINAL)
            )
        )

        val result = generator.generateMpd(resolved)

        assertTrue("Expected Success but got: $result", result is MultiRepresentationMpdGenerator.Result.Success)
        val xml = (result as MultiRepresentationMpdGenerator.Result.Success).mpdXml

        val enPos = xml.indexOf("""lang="en"""")
        val arPos = xml.indexOf("""lang="ar"""")
        assertTrue(
            "ORIGINAL 'en' AdaptationSet must appear before DUBBED 'ar' in MPD XML",
            enPos in 0 until arPos
        )
    }
}
