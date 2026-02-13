package com.albunyaan.tube.player

import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.SyntheticDashMetadata
import com.albunyaan.tube.data.extractor.VideoTrack
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for Phase 5 MPD pre-generation components.
 *
 * Tests the MPD generation and registry integration that enables
 * pre-generating synthetic DASH MPDs during prefetch.
 *
 * Note: Full integration tests for DefaultStreamPrefetchService require
 * mocking GlobalStreamResolver, which is a final class. These tests
 * focus on the components that can be tested in isolation.
 */
class StreamPrefetchServiceTest {

    private lateinit var mpdGenerator: MultiRepresentationMpdGenerator
    private lateinit var mpdRegistry: SyntheticDashMpdRegistry

    @Before
    fun setUp() {
        mpdGenerator = MultiRepresentationMpdGenerator()
        mpdRegistry = SyntheticDashMpdRegistry()
    }

    // --- MPD Generation Eligibility Tests ---

    @Test
    fun `checkEligibility returns true for eligible streams`() {
        val resolved = createEligibleResolvedStreams()

        val (eligible, reason) = mpdGenerator.checkEligibility(resolved)

        assertTrue("Should be eligible", eligible)
        assertTrue("Reason should indicate eligible", reason.startsWith("ELIGIBLE"))
    }

    @Test
    fun `checkEligibility returns false when no duration`() {
        val resolved = createEligibleResolvedStreams().copy(durationSeconds = null)

        val (eligible, reason) = mpdGenerator.checkEligibility(resolved)

        assertFalse("Should not be eligible without duration", eligible)
        assertEquals("NO_DURATION", reason)
    }

    @Test
    fun `checkEligibility returns false when insufficient video tracks`() {
        val resolved = createResolvedStreamsWithOneVideoTrack()

        val (eligible, reason) = mpdGenerator.checkEligibility(resolved)

        assertFalse("Should not be eligible with only 1 video track", eligible)
        assertTrue("Reason should indicate insufficient tracks", reason.startsWith("INSUFFICIENT_VIDEO_TRACKS"))
    }

    @Test
    fun `checkEligibility returns false when no eligible audio`() {
        val resolved = createResolvedStreamsWithIneligibleAudio()

        val (eligible, reason) = mpdGenerator.checkEligibility(resolved)

        assertFalse("Should not be eligible without audio", eligible)
        assertEquals("NO_ELIGIBLE_AUDIO", reason)
    }

    @Test
    fun `checkEligibility returns false when video tracks lack DASH metadata`() {
        val resolved = createResolvedStreamsWithoutDashMetadata()

        val (eligible, reason) = mpdGenerator.checkEligibility(resolved)

        assertFalse("Should not be eligible without DASH metadata", eligible)
        // Tracks without DASH metadata are filtered out, resulting in 0 eligible tracks
        assertTrue(
            "Reason should indicate insufficient tracks, got: $reason",
            reason.startsWith("INSUFFICIENT_VIDEO_TRACKS") || reason.startsWith("INSUFFICIENT_SAME_CODEC")
        )
    }

    // --- MPD Generation Tests ---

    @Test
    fun `generateMpd returns success for eligible streams`() {
        val resolved = createEligibleResolvedStreams()
        val result = mpdGenerator.generateMpd(resolved)

        assertTrue("MPD generation should succeed", result is MultiRepresentationMpdGenerator.Result.Success)
        result as MultiRepresentationMpdGenerator.Result.Success

        assertTrue("MPD XML should not be empty", result.mpdXml.isNotEmpty())
        assertTrue("Should have multiple video tracks", result.videoTracks.size >= 2)
    }

    @Test
    fun `generateMpd with quality cap filters tracks`() {
        val resolved = createEligibleResolvedStreams()

        val result = mpdGenerator.generateMpd(resolved, qualityCapHeight = 720)

        require(result is MultiRepresentationMpdGenerator.Result.Success) { "Should return success" }
        result.videoTracks.forEach { track ->
            assertTrue("Track height should be <= 720", (track.height ?: 0) <= 720)
        }
    }

    @Test
    fun `generateMpd returns failure for ineligible streams`() {
        val resolved = createResolvedStreamsWithOneVideoTrack()

        val result = mpdGenerator.generateMpd(resolved)

        assertTrue("Should return failure", result is MultiRepresentationMpdGenerator.Result.Failure)
    }

    // --- Registry Integration Tests ---

    @Test
    fun `MPD can be registered and retrieved`() {
        val resolved = createEligibleResolvedStreams()
        val result = mpdGenerator.generateMpd(resolved)
        require(result is MultiRepresentationMpdGenerator.Result.Success) { "Should return success" }

        mpdRegistry.register("video1", result.mpdXml)

        assertTrue("Should be registered", mpdRegistry.isRegistered("video1"))
        assertEquals("Should retrieve same content", result.mpdXml, mpdRegistry.getMpd("video1"))
    }

    @Test
    fun `pre-generation workflow simulates prefetch behavior`() {
        val videoId = "video123"
        val resolved = createEligibleResolvedStreams()

        // Step 1: Check eligibility (as prefetch would)
        val (eligible, _) = mpdGenerator.checkEligibility(resolved)
        assertTrue("Should be eligible", eligible)

        // Step 2: Generate MPD (as prefetch would)
        val result = mpdGenerator.generateMpd(resolved)
        assertTrue("Should generate successfully", result is MultiRepresentationMpdGenerator.Result.Success)
        result as MultiRepresentationMpdGenerator.Result.Success

        // Step 3: Register in registry (as prefetch would)
        mpdRegistry.register(videoId, result.mpdXml)

        // Step 4: Verify MPD is available (as factory would check)
        assertTrue("Factory should find pre-generated MPD", mpdRegistry.isRegistered(videoId))
        assertNotNull("Factory should get MPD content", mpdRegistry.getMpd(videoId))
    }

    @Test
    fun `documents MPD registry and cache bypass behavior`() {
        val videoId = "video123"
        val resolved = createEligibleResolvedStreams()

        // Simulate prefetch pre-generating MPD
        val result = mpdGenerator.generateMpd(resolved)
        require(result is MultiRepresentationMpdGenerator.Result.Success) { "MPD generation should succeed" }
        mpdRegistry.register(videoId, result.mpdXml)

        // Verify MPD is registered and can be retrieved
        assertTrue("MPD should be registered", mpdRegistry.isRegistered(videoId))
        assertNotNull("MPD content should be retrievable", mpdRegistry.getMpd(videoId))

        // Document expected cache bypass behavior:
        // When quality cap is present, the factory should bypass cache and regenerate
        // because the pre-generated MPD contains all qualities. The factory needs to
        // regenerate with the cap applied. This logic is in MultiRepSyntheticDashMediaSourceFactory:
        //   val cachedEntry = if (qualityCapHeight == null) mpdRegistry.getEntry(videoId) else null
        //
        // This behavior should be tested via factory integration tests when
        // GlobalStreamResolver can be properly mocked.
    }

    // --- Test Fixtures ---

    private fun createEligibleResolvedStreams(): ResolvedStreams {
        val videoTracks = listOf(
            VideoTrack(
                url = "https://example.com/video_1080p",
                mimeType = "video/mp4",
                width = 1920,
                height = 1080,
                bitrate = 5000000,
                qualityLabel = "1080p",
                fps = 30,
                isVideoOnly = true,
                syntheticDashMetadata = SyntheticDashMetadata(
                    itag = 137,
                    initStart = 0,
                    initEnd = 1000,
                    indexStart = 1001,
                    indexEnd = 2000,
                    approxDurationMs = 180000,
                    codec = "avc1.64001f"
                ),
                codec = "avc1.64001f"
            ),
            VideoTrack(
                url = "https://example.com/video_720p",
                mimeType = "video/mp4",
                width = 1280,
                height = 720,
                bitrate = 2500000,
                qualityLabel = "720p",
                fps = 30,
                isVideoOnly = true,
                syntheticDashMetadata = SyntheticDashMetadata(
                    itag = 136,
                    initStart = 0,
                    initEnd = 800,
                    indexStart = 801,
                    indexEnd = 1600,
                    approxDurationMs = 180000,
                    codec = "avc1.4d401f"
                ),
                codec = "avc1.4d401f"
            ),
            VideoTrack(
                url = "https://example.com/video_480p",
                mimeType = "video/mp4",
                width = 854,
                height = 480,
                bitrate = 1000000,
                qualityLabel = "480p",
                fps = 30,
                isVideoOnly = true,
                syntheticDashMetadata = SyntheticDashMetadata(
                    itag = 135,
                    initStart = 0,
                    initEnd = 600,
                    indexStart = 601,
                    indexEnd = 1200,
                    approxDurationMs = 180000,
                    codec = "avc1.4d401e"
                ),
                codec = "avc1.4d401e"
            )
        )

        val audioTracks = listOf(
            AudioTrack(
                url = "https://example.com/audio",
                mimeType = "audio/mp4",
                bitrate = 128000,
                codec = "mp4a.40.2",
                syntheticDashMetadata = SyntheticDashMetadata(
                    itag = 140,
                    initStart = 0,
                    initEnd = 500,
                    indexStart = 501,
                    indexEnd = 1000,
                    approxDurationMs = 180000,
                    codec = "mp4a.40.2"
                )
            )
        )

        return ResolvedStreams(
            streamId = "video1",
            videoTracks = videoTracks,
            audioTracks = audioTracks,
            durationSeconds = 180,
            urlGeneratedAt = 0
        )
    }

    private fun createResolvedStreamsWithOneVideoTrack(): ResolvedStreams {
        val videoTracks = listOf(
            VideoTrack(
                url = "https://example.com/video_720p",
                mimeType = "video/mp4",
                width = 1280,
                height = 720,
                bitrate = 2500000,
                qualityLabel = "720p",
                fps = 30,
                isVideoOnly = true,
                syntheticDashMetadata = SyntheticDashMetadata(
                    itag = 136,
                    initStart = 0,
                    initEnd = 800,
                    indexStart = 801,
                    indexEnd = 1600,
                    approxDurationMs = 180000,
                    codec = "avc1.4d401f"
                ),
                codec = "avc1.4d401f"
            )
        )

        val audioTracks = listOf(
            AudioTrack(
                url = "https://example.com/audio",
                mimeType = "audio/mp4",
                bitrate = 128000,
                codec = "mp4a.40.2",
                syntheticDashMetadata = SyntheticDashMetadata(
                    itag = 140,
                    initStart = 0,
                    initEnd = 500,
                    indexStart = 501,
                    indexEnd = 1000,
                    approxDurationMs = 180000,
                    codec = "mp4a.40.2"
                )
            )
        )

        return ResolvedStreams(
            streamId = "video1",
            videoTracks = videoTracks,
            audioTracks = audioTracks,
            durationSeconds = 180,
            urlGeneratedAt = 0
        )
    }

    /**
     * Creates ResolvedStreams with video tracks but an ineligible audio track
     * (missing DASH metadata). Used to test that eligibility check fails when
     * audio tracks lack required metadata.
     */
    private fun createResolvedStreamsWithIneligibleAudio(): ResolvedStreams {
        val videoTracks = listOf(
            VideoTrack(
                url = "https://example.com/video_1080p",
                mimeType = "video/mp4",
                width = 1920,
                height = 1080,
                bitrate = 5000000,
                qualityLabel = "1080p",
                fps = 30,
                isVideoOnly = true,
                syntheticDashMetadata = SyntheticDashMetadata(
                    itag = 137,
                    initStart = 0,
                    initEnd = 1000,
                    indexStart = 1001,
                    indexEnd = 2000,
                    approxDurationMs = 180000,
                    codec = "avc1.64001f"
                ),
                codec = "avc1.64001f"
            ),
            VideoTrack(
                url = "https://example.com/video_720p",
                mimeType = "video/mp4",
                width = 1280,
                height = 720,
                bitrate = 2500000,
                qualityLabel = "720p",
                fps = 30,
                isVideoOnly = true,
                syntheticDashMetadata = SyntheticDashMetadata(
                    itag = 136,
                    initStart = 0,
                    initEnd = 800,
                    indexStart = 801,
                    indexEnd = 1600,
                    approxDurationMs = 180000,
                    codec = "avc1.4d401f"
                ),
                codec = "avc1.4d401f"
            )
        )

        // Audio track without DASH metadata (ineligible for synthetic DASH)
        val audioTracks = listOf(
            AudioTrack(
                url = "https://example.com/audio",
                mimeType = "audio/mp4",
                bitrate = 128000,
                codec = "mp4a.40.2",
                syntheticDashMetadata = null // No DASH metadata - makes this audio ineligible
            )
        )

        return ResolvedStreams(
            streamId = "video1",
            videoTracks = videoTracks,
            audioTracks = audioTracks,
            durationSeconds = 180,
            urlGeneratedAt = 0
        )
    }

    private fun createResolvedStreamsWithoutDashMetadata(): ResolvedStreams {
        val videoTracks = listOf(
            VideoTrack(
                url = "https://example.com/video_1080p",
                mimeType = "video/mp4",
                width = 1920,
                height = 1080,
                bitrate = 5000000,
                qualityLabel = "1080p",
                fps = 30,
                isVideoOnly = true,
                syntheticDashMetadata = null, // No DASH metadata
                codec = "avc1.64001f"
            ),
            VideoTrack(
                url = "https://example.com/video_720p",
                mimeType = "video/mp4",
                width = 1280,
                height = 720,
                bitrate = 2500000,
                qualityLabel = "720p",
                fps = 30,
                isVideoOnly = true,
                syntheticDashMetadata = null, // No DASH metadata
                codec = "avc1.4d401f"
            )
        )

        val audioTracks = listOf(
            AudioTrack(
                url = "https://example.com/audio",
                mimeType = "audio/mp4",
                bitrate = 128000,
                codec = "mp4a.40.2",
                syntheticDashMetadata = SyntheticDashMetadata(
                    itag = 140,
                    initStart = 0,
                    initEnd = 500,
                    indexStart = 501,
                    indexEnd = 1000,
                    approxDurationMs = 180000,
                    codec = "mp4a.40.2"
                )
            )
        )

        return ResolvedStreams(
            streamId = "video1",
            videoTracks = videoTracks,
            audioTracks = audioTracks,
            durationSeconds = 180,
            urlGeneratedAt = 0
        )
    }
}
