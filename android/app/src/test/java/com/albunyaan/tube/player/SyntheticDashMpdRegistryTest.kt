package com.albunyaan.tube.player

import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.VideoTrack
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for SyntheticDashMpdRegistry.
 *
 * Tests the Phase 2B MPD registry used for storing and retrieving
 * pre-generated synthetic DASH manifests.
 *
 * Phase 5 additions: Tests for registerWithMetadata, getEntry, and isRegisteredWithMetadata
 * for true cache hit support.
 */
class SyntheticDashMpdRegistryTest {

    companion object {
        /**
         * Expected max capacity of the registry. Must match SyntheticDashMpdRegistry.MAX_ENTRIES.
         * If the implementation capacity changes, update this constant.
         */
        private const val EXPECTED_MAX_CAPACITY = 5
    }

    private lateinit var registry: SyntheticDashMpdRegistry

    // Test fixtures for Phase 5 metadata tests
    private val testVideoTracks = listOf(
        VideoTrack(
            url = "https://example.com/video1",
            mimeType = "video/mp4",
            width = 1920,
            height = 1080,
            bitrate = 5000000,
            qualityLabel = "1080p",
            fps = 30,
            isVideoOnly = true,
            syntheticDashMetadata = null,
            codec = "avc1.64001f"
        ),
        VideoTrack(
            url = "https://example.com/video2",
            mimeType = "video/mp4",
            width = 1280,
            height = 720,
            bitrate = 2500000,
            qualityLabel = "720p",
            fps = 30,
            isVideoOnly = true,
            syntheticDashMetadata = null,
            codec = "avc1.4d401f"
        )
    )

    private val testAudioTrack = AudioTrack(
        url = "https://example.com/audio",
        mimeType = "audio/mp4",
        bitrate = 128000,
        codec = "mp4a.40.2",
        syntheticDashMetadata = null
    )

    /** Controllable test clock for deterministic eviction ordering */
    private var testTimeMs = 0L

    @Before
    fun setUp() {
        testTimeMs = 0L
        registry = SyntheticDashMpdRegistry()
        registry.setTestClock { testTimeMs }
    }

    /** Advance test clock for deterministic ordering */
    private fun advanceClock(millis: Long = 100) {
        testTimeMs += millis
    }

    // --- Basic Registration Tests ---

    @Test
    fun `register stores MPD content`() {
        val videoId = "video1"
        val mpdXml = "<MPD>test content</MPD>"

        registry.register(videoId, mpdXml)

        assertEquals(mpdXml, registry.getMpd(videoId))
        assertTrue(registry.isRegistered(videoId))
        assertEquals(1, registry.getRegisteredCount())
    }

    @Test
    fun `getMpd returns null for unregistered videoId`() {
        assertNull(registry.getMpd("unknown"))
        assertFalse(registry.isRegistered("unknown"))
    }

    @Test
    fun `isRegistered returns true for registered videoId`() {
        registry.register("video1", "<MPD/>")

        assertTrue(registry.isRegistered("video1"))
        assertFalse(registry.isRegistered("video2"))
    }

    // --- Update/Replace Tests ---

    @Test
    fun `register replaces existing MPD for same videoId`() {
        val videoId = "video1"
        val originalMpd = "<MPD>original</MPD>"
        val updatedMpd = "<MPD>updated</MPD>"

        registry.register(videoId, originalMpd)
        registry.register(videoId, updatedMpd)

        assertEquals(updatedMpd, registry.getMpd(videoId))
        assertEquals(1, registry.getRegisteredCount())
    }

    // --- Unregister Tests ---

    @Test
    fun `unregister removes MPD`() {
        registry.register("video1", "<MPD/>")

        registry.unregister("video1")

        assertNull(registry.getMpd("video1"))
        assertFalse(registry.isRegistered("video1"))
        assertEquals(0, registry.getRegisteredCount())
    }

    @Test
    fun `unregister for non-existent videoId does not crash`() {
        registry.unregister("nonexistent")

        assertEquals(0, registry.getRegisteredCount())
    }

    // --- Clear Tests ---

    @Test
    fun `clearAll removes all registered MPDs`() {
        registry.register("video1", "<MPD>1</MPD>")
        registry.register("video2", "<MPD>2</MPD>")
        registry.register("video3", "<MPD>3</MPD>")

        assertEquals(3, registry.getRegisteredCount())

        registry.clearAll()

        assertEquals(0, registry.getRegisteredCount())
        assertNull(registry.getMpd("video1"))
        assertNull(registry.getMpd("video2"))
        assertNull(registry.getMpd("video3"))
    }

    // --- Eviction Tests ---

    @Test
    fun `oldest entry is evicted when max capacity reached`() {
        // Register entries up to max capacity with deterministic timestamps
        for (i in 1..EXPECTED_MAX_CAPACITY) {
            registry.register("video$i", "<MPD>$i</MPD>")
            advanceClock() // Advance clock for distinct timestamps
        }

        assertEquals(EXPECTED_MAX_CAPACITY, registry.getRegisteredCount())

        // Register one more entry - should evict oldest (video1)
        registry.register("video${EXPECTED_MAX_CAPACITY + 1}", "<MPD>${EXPECTED_MAX_CAPACITY + 1}</MPD>")

        assertEquals(EXPECTED_MAX_CAPACITY, registry.getRegisteredCount())
        assertFalse("Oldest entry should be evicted", registry.isRegistered("video1"))
        assertTrue("Newest entry should exist", registry.isRegistered("video${EXPECTED_MAX_CAPACITY + 1}"))
    }

    @Test
    fun `FIFO eviction maintains capacity`() {
        // Fill to capacity with deterministic timestamps
        for (i in 1..EXPECTED_MAX_CAPACITY) {
            registry.register("video$i", "<MPD>$i</MPD>")
            advanceClock() // Advance clock for distinct timestamps
        }

        // Add 3 more entries
        for (i in (EXPECTED_MAX_CAPACITY + 1)..(EXPECTED_MAX_CAPACITY + 3)) {
            registry.register("video$i", "<MPD>$i</MPD>")
            advanceClock()
        }

        assertEquals(EXPECTED_MAX_CAPACITY, registry.getRegisteredCount())

        // First 3 should be evicted
        assertFalse(registry.isRegistered("video1"))
        assertFalse(registry.isRegistered("video2"))
        assertFalse(registry.isRegistered("video3"))

        // Last 5 should remain
        assertTrue(registry.isRegistered("video4"))
        assertTrue(registry.isRegistered("video5"))
        assertTrue(registry.isRegistered("video6"))
        assertTrue(registry.isRegistered("video7"))
        assertTrue(registry.isRegistered("video8"))
    }

    // --- Multiple Operations Tests ---

    @Test
    fun `multiple videos can be registered independently`() {
        registry.register("video1", "<MPD>content1</MPD>")
        registry.register("video2", "<MPD>content2</MPD>")

        assertEquals("<MPD>content1</MPD>", registry.getMpd("video1"))
        assertEquals("<MPD>content2</MPD>", registry.getMpd("video2"))
        assertEquals(2, registry.getRegisteredCount())
    }

    @Test
    fun `getRegisteredCount returns accurate count`() {
        assertEquals(0, registry.getRegisteredCount())

        registry.register("video1", "<MPD/>")
        assertEquals(1, registry.getRegisteredCount())

        registry.register("video2", "<MPD/>")
        assertEquals(2, registry.getRegisteredCount())

        registry.unregister("video1")
        assertEquals(1, registry.getRegisteredCount())

        registry.clearAll()
        assertEquals(0, registry.getRegisteredCount())
    }

    // --- Phase 5: Metadata Registration Tests ---

    @Test
    fun `registerWithMetadata stores MPD and metadata`() {
        val videoId = "video1"
        val mpdXml = "<MPD>test content</MPD>"

        registry.registerWithMetadata(
            videoId = videoId,
            mpdXml = mpdXml,
            videoTracks = testVideoTracks,
            audioTrack = testAudioTrack,
            codecFamily = "H264"
        )

        assertEquals(mpdXml, registry.getMpd(videoId))
        assertTrue(registry.isRegistered(videoId))
        assertTrue(registry.isRegisteredWithMetadata(videoId))
        assertEquals(1, registry.getRegisteredCount())
    }

    @Test
    fun `getEntry returns full entry with metadata`() {
        val videoId = "video1"
        val mpdXml = "<MPD>test content</MPD>"

        registry.registerWithMetadata(
            videoId = videoId,
            mpdXml = mpdXml,
            videoTracks = testVideoTracks,
            audioTrack = testAudioTrack,
            codecFamily = "H264"
        )

        val entry = registry.getEntry(videoId)

        assertNotNull(entry)
        assertEquals(videoId, entry!!.videoId)
        assertEquals(mpdXml, entry.mpdXml)
        assertEquals(testVideoTracks, entry.videoTracks)
        assertEquals(testAudioTrack, entry.audioTrack)
        assertEquals("H264", entry.codecFamily)
        assertTrue(entry.hasMetadata())
    }

    @Test
    fun `getEntry returns null for unregistered videoId`() {
        assertNull(registry.getEntry("unknown"))
    }

    @Test
    fun `isRegisteredWithMetadata returns false for legacy registration`() {
        registry.register("video1", "<MPD/>")

        assertTrue(registry.isRegistered("video1"))
        assertFalse(registry.isRegisteredWithMetadata("video1"))
    }

    @Test
    fun `isRegisteredWithMetadata returns false for unregistered videoId`() {
        assertFalse(registry.isRegisteredWithMetadata("unknown"))
    }

    @Test
    fun `legacy registration entry hasMetadata returns false`() {
        registry.register("video1", "<MPD/>")

        val entry = registry.getEntry("video1")
        assertNotNull(entry)
        assertFalse(entry!!.hasMetadata())
        assertNull(entry.videoTracks)
        assertNull(entry.audioTrack)
        assertNull(entry.codecFamily)
    }

    @Test
    fun `registerWithMetadata replaces legacy registration`() {
        val videoId = "video1"

        // First: legacy registration (no metadata)
        registry.register(videoId, "<MPD>legacy</MPD>")
        assertFalse(registry.isRegisteredWithMetadata(videoId))

        // Second: registration with metadata
        registry.registerWithMetadata(
            videoId = videoId,
            mpdXml = "<MPD>with metadata</MPD>",
            videoTracks = testVideoTracks,
            audioTrack = testAudioTrack,
            codecFamily = "H264"
        )

        assertTrue(registry.isRegisteredWithMetadata(videoId))
        assertEquals("<MPD>with metadata</MPD>", registry.getMpd(videoId))
        assertEquals(1, registry.getRegisteredCount())
    }

    @Test
    fun `registerWithMetadata respects max capacity with eviction`() {
        // Fill to capacity with metadata entries
        for (i in 1..EXPECTED_MAX_CAPACITY) {
            registry.registerWithMetadata(
                videoId = "video$i",
                mpdXml = "<MPD>$i</MPD>",
                videoTracks = testVideoTracks,
                audioTrack = testAudioTrack,
                codecFamily = "H264"
            )
            advanceClock() // Advance clock for distinct timestamps
        }

        assertEquals(EXPECTED_MAX_CAPACITY, registry.getRegisteredCount())

        // Add one more - should evict oldest (video1)
        registry.registerWithMetadata(
            videoId = "video${EXPECTED_MAX_CAPACITY + 1}",
            mpdXml = "<MPD>${EXPECTED_MAX_CAPACITY + 1}</MPD>",
            videoTracks = testVideoTracks,
            audioTrack = testAudioTrack,
            codecFamily = "VP9"
        )

        assertEquals(EXPECTED_MAX_CAPACITY, registry.getRegisteredCount())
        assertFalse("Oldest entry should be evicted", registry.isRegistered("video1"))
        assertTrue("Newest entry should exist", registry.isRegisteredWithMetadata("video${EXPECTED_MAX_CAPACITY + 1}"))
    }

    // --- Validation Tests ---

    @Test(expected = IllegalArgumentException::class)
    fun `register throws for blank videoId`() {
        registry.register("", "<MPD/>")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `register throws for empty mpdXml`() {
        registry.register("video1", "")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `registerWithMetadata throws for blank videoId`() {
        registry.registerWithMetadata(
            videoId = "  ",
            mpdXml = "<MPD/>",
            videoTracks = testVideoTracks,
            audioTrack = testAudioTrack,
            codecFamily = "H264"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `registerWithMetadata throws for empty mpdXml`() {
        registry.registerWithMetadata(
            videoId = "video1",
            mpdXml = "",
            videoTracks = testVideoTracks,
            audioTrack = testAudioTrack,
            codecFamily = "H264"
        )
    }

    // --- Phase 5: TTL Freshness Tests ---

    @Test
    fun `isFreshWithMetadata returns true within TTL`() {
        registry.registerWithMetadata(
            videoId = "video1",
            mpdXml = "<MPD>test</MPD>",
            videoTracks = testVideoTracks,
            audioTrack = testAudioTrack,
            codecFamily = "H264"
        )

        // Immediately after registration - should be fresh
        assertTrue(registry.isFreshWithMetadata("video1"))

        // Advance to just under TTL (2 minutes - 1 second)
        advanceClock(SyntheticDashMpdRegistry.MPD_TTL_MS - 1000)
        assertTrue("Should still be fresh just before TTL expires", registry.isFreshWithMetadata("video1"))
    }

    @Test
    fun `isFreshWithMetadata returns false after TTL expires`() {
        registry.registerWithMetadata(
            videoId = "video1",
            mpdXml = "<MPD>test</MPD>",
            videoTracks = testVideoTracks,
            audioTrack = testAudioTrack,
            codecFamily = "H264"
        )

        // Advance past TTL (2 minutes + 1 second)
        advanceClock(SyntheticDashMpdRegistry.MPD_TTL_MS + 1000)

        assertFalse("Should be stale after TTL expires", registry.isFreshWithMetadata("video1"))
    }

    @Test
    fun `isFreshWithMetadata returns false at exact TTL boundary plus one ms`() {
        registry.registerWithMetadata(
            videoId = "video1",
            mpdXml = "<MPD>test</MPD>",
            videoTracks = testVideoTracks,
            audioTrack = testAudioTrack,
            codecFamily = "H264"
        )

        // At exact TTL - should still be fresh (age <= TTL)
        advanceClock(SyntheticDashMpdRegistry.MPD_TTL_MS)
        assertTrue("Should be fresh at exact TTL boundary", registry.isFreshWithMetadata("video1"))

        // One millisecond past TTL - should be stale
        advanceClock(1)
        assertFalse("Should be stale just after TTL", registry.isFreshWithMetadata("video1"))
    }

    @Test
    fun `isFreshWithMetadata returns false for legacy registration`() {
        // Legacy registration has no metadata
        registry.register("video1", "<MPD>test</MPD>")

        assertFalse("Legacy registration should not be considered fresh", registry.isFreshWithMetadata("video1"))
    }

    @Test
    fun `isFreshWithMetadata returns false for unregistered videoId`() {
        assertFalse(registry.isFreshWithMetadata("unknown"))
    }

    @Test
    fun `getFreshEntry returns entry within TTL`() {
        registry.registerWithMetadata(
            videoId = "video1",
            mpdXml = "<MPD>test</MPD>",
            videoTracks = testVideoTracks,
            audioTrack = testAudioTrack,
            codecFamily = "H264"
        )

        val entry = registry.getFreshEntry("video1")
        assertNotNull("Should return entry when fresh", entry)
        assertEquals("video1", entry!!.videoId)
        assertEquals("<MPD>test</MPD>", entry.mpdXml)
    }

    @Test
    fun `getFreshEntry returns null after TTL expires`() {
        registry.registerWithMetadata(
            videoId = "video1",
            mpdXml = "<MPD>test</MPD>",
            videoTracks = testVideoTracks,
            audioTrack = testAudioTrack,
            codecFamily = "H264"
        )

        // Advance past TTL
        advanceClock(SyntheticDashMpdRegistry.MPD_TTL_MS + 1000)

        assertNull("Should return null when stale", registry.getFreshEntry("video1"))
    }

    @Test
    fun `getFreshEntry returns null for legacy registration`() {
        registry.register("video1", "<MPD>test</MPD>")

        assertNull("Should return null for legacy registration without metadata", registry.getFreshEntry("video1"))
    }

    @Test
    fun `getFreshEntry returns null for unregistered videoId`() {
        assertNull(registry.getFreshEntry("unknown"))
    }

    @Test
    fun `re-registration resets TTL`() {
        registry.registerWithMetadata(
            videoId = "video1",
            mpdXml = "<MPD>original</MPD>",
            videoTracks = testVideoTracks,
            audioTrack = testAudioTrack,
            codecFamily = "H264"
        )

        // Advance almost to TTL
        advanceClock(SyntheticDashMpdRegistry.MPD_TTL_MS - 1000)
        assertTrue("Should be fresh before TTL", registry.isFreshWithMetadata("video1"))

        // Re-register with new content (resets TTL)
        registry.registerWithMetadata(
            videoId = "video1",
            mpdXml = "<MPD>refreshed</MPD>",
            videoTracks = testVideoTracks,
            audioTrack = testAudioTrack,
            codecFamily = "H264"
        )

        // Advance another almost-TTL period
        advanceClock(SyntheticDashMpdRegistry.MPD_TTL_MS - 1000)

        // Should still be fresh because TTL was reset on re-registration
        assertTrue("Should be fresh after re-registration", registry.isFreshWithMetadata("video1"))
        assertEquals("<MPD>refreshed</MPD>", registry.getMpd("video1"))
    }

    @Test
    fun `unregister prevents getFreshEntry from returning stale entry`() {
        registry.registerWithMetadata(
            videoId = "video1",
            mpdXml = "<MPD>test</MPD>",
            videoTracks = testVideoTracks,
            audioTrack = testAudioTrack,
            codecFamily = "H264"
        )

        assertTrue(registry.isFreshWithMetadata("video1"))

        // Unregister explicitly
        registry.unregister("video1")

        // Should return null even though it would have been within TTL
        assertNull("Should return null after unregister", registry.getFreshEntry("video1"))
        assertFalse(registry.isFreshWithMetadata("video1"))
    }
}
