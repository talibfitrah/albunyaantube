package com.albunyaan.tube.data.extractor

import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.extractor.stream.DeliveryMethod

/**
 * Unit tests for SyntheticDashEligibility.
 *
 * Tests the eligibility checking logic (no HTTP/network calls, no extraction passes).
 * Covers edge cases for range validation, duration requirements, delivery method gating,
 * JSON escaping, and JSON array formatting. (33 tests total)
 */
class SyntheticDashEligibilityTest {

    // --- Video Stream Eligibility Tests ---

    @Test
    fun `video stream with all valid params is eligible`() {
        val data = createValidVideoStreamData()
        val result = SyntheticDashEligibility.checkVideoStreamEligibility(data)
        assertTrue("Valid video stream should be eligible", result.eligible)
        assertTrue("No failure reasons expected", result.failureReasons.isEmpty())
    }

    @Test
    fun `video stream with DASH delivery is not eligible`() {
        val data = createValidVideoStreamData().copy(deliveryMethod = DeliveryMethod.DASH)
        val result = SyntheticDashEligibility.checkVideoStreamEligibility(data)
        assertFalse("DASH stream should not be eligible", result.eligible)
        assertTrue("Should have NOT_PROGRESSIVE_HTTP reason",
            result.failureReasons.any { it.startsWith("NOT_PROGRESSIVE_HTTP") })
    }

    @Test
    fun `video stream with HLS delivery is not eligible`() {
        val data = createValidVideoStreamData().copy(deliveryMethod = DeliveryMethod.HLS)
        val result = SyntheticDashEligibility.checkVideoStreamEligibility(data)
        assertFalse("HLS stream should not be eligible", result.eligible)
    }

    @Test
    fun `muxed video stream is not eligible`() {
        val data = createValidVideoStreamData().copy(isVideoOnly = false)
        val result = SyntheticDashEligibility.checkVideoStreamEligibility(data)
        assertFalse("Muxed stream should not be eligible", result.eligible)
        assertTrue("Should have MUXED_STREAM reason",
            result.failureReasons.contains("MUXED_STREAM"))
    }

    @Test
    fun `video stream without itagItem is not eligible`() {
        val data = createValidVideoStreamData().copy(hasItagItem = false)
        val result = SyntheticDashEligibility.checkVideoStreamEligibility(data)
        assertFalse("Stream without itagItem should not be eligible", result.eligible)
        assertTrue("Should have NO_ITAG_ITEM reason",
            result.failureReasons.contains("NO_ITAG_ITEM"))
    }

    @Test
    fun `video stream with negative initStart is not eligible`() {
        val data = createValidVideoStreamData().copy(initStart = -1)
        val result = SyntheticDashEligibility.checkVideoStreamEligibility(data)
        assertFalse("Stream with negative initStart should not be eligible", result.eligible)
        assertTrue("Should have INVALID_INIT_RANGE reason",
            result.failureReasons.any { it.startsWith("INVALID_INIT_RANGE") })
    }

    @Test
    fun `video stream with negative initEnd is not eligible`() {
        val data = createValidVideoStreamData().copy(initEnd = -1)
        val result = SyntheticDashEligibility.checkVideoStreamEligibility(data)
        assertFalse("Stream with negative initEnd should not be eligible", result.eligible)
    }

    @Test
    fun `video stream with reversed init range is not eligible`() {
        val data = createValidVideoStreamData().copy(initStart = 100, initEnd = 50)
        val result = SyntheticDashEligibility.checkVideoStreamEligibility(data)
        assertFalse("Stream with reversed init range should not be eligible", result.eligible)
        assertTrue("Should have INVALID_INIT_RANGE reason",
            result.failureReasons.any { it.startsWith("INVALID_INIT_RANGE") })
    }

    @Test
    fun `video stream with negative indexStart is not eligible`() {
        val data = createValidVideoStreamData().copy(indexStart = -1)
        val result = SyntheticDashEligibility.checkVideoStreamEligibility(data)
        assertFalse("Stream with negative indexStart should not be eligible", result.eligible)
        assertTrue("Should have INVALID_INDEX_RANGE reason",
            result.failureReasons.any { it.startsWith("INVALID_INDEX_RANGE") })
    }

    @Test
    fun `video stream with reversed index range is not eligible`() {
        val data = createValidVideoStreamData().copy(indexStart = 1000, indexEnd = 500)
        val result = SyntheticDashEligibility.checkVideoStreamEligibility(data)
        assertFalse("Stream with reversed index range should not be eligible", result.eligible)
    }

    @Test
    fun `video stream without duration is not eligible`() {
        val data = createValidVideoStreamData().copy(
            streamInfoDurationSec = null,
            itagApproxDurationMs = null
        )
        val result = SyntheticDashEligibility.checkVideoStreamEligibility(data)
        assertFalse("Stream without duration should not be eligible", result.eligible)
        assertTrue("Should have NO_DURATION reason",
            result.failureReasons.contains("NO_DURATION"))
    }

    @Test
    fun `video stream with zero duration is not eligible`() {
        val data = createValidVideoStreamData().copy(
            streamInfoDurationSec = 0L,
            itagApproxDurationMs = 0L
        )
        val result = SyntheticDashEligibility.checkVideoStreamEligibility(data)
        assertFalse("Stream with zero duration should not be eligible", result.eligible)
    }

    @Test
    fun `video stream with only streamInfoDuration is eligible`() {
        val data = createValidVideoStreamData().copy(
            streamInfoDurationSec = 120L,
            itagApproxDurationMs = null
        )
        val result = SyntheticDashEligibility.checkVideoStreamEligibility(data)
        assertTrue("Stream with StreamInfo duration should be eligible", result.eligible)
    }

    @Test
    fun `video stream with only itagApproxDuration is eligible`() {
        val data = createValidVideoStreamData().copy(
            streamInfoDurationSec = null,
            itagApproxDurationMs = 120000L
        )
        val result = SyntheticDashEligibility.checkVideoStreamEligibility(data)
        assertTrue("Stream with itag approxDuration should be eligible", result.eligible)
    }

    @Test
    fun `video stream without content is not eligible`() {
        val data = createValidVideoStreamData().copy(hasContent = false)
        val result = SyntheticDashEligibility.checkVideoStreamEligibility(data)
        assertFalse("Stream without content should not be eligible", result.eligible)
        assertTrue("Should have NO_CONTENT reason",
            result.failureReasons.contains("NO_CONTENT"))
    }

    @Test
    fun `video stream with zero-length init range is eligible`() {
        // Start == end is valid (single-byte range)
        val data = createValidVideoStreamData().copy(initStart = 0, initEnd = 0)
        val result = SyntheticDashEligibility.checkVideoStreamEligibility(data)
        assertTrue("Stream with zero-length init range should be eligible", result.eligible)
    }

    @Test
    fun `video stream accumulates multiple failure reasons`() {
        val data = createValidVideoStreamData().copy(
            deliveryMethod = DeliveryMethod.HLS,
            isVideoOnly = false,
            hasItagItem = false,
            initStart = -1,
            streamInfoDurationSec = null,
            itagApproxDurationMs = null
        )
        val result = SyntheticDashEligibility.checkVideoStreamEligibility(data)
        assertFalse(result.eligible)
        assertTrue("Should have multiple failure reasons", result.failureReasons.size >= 4)
    }

    // --- Audio Stream Eligibility Tests ---

    @Test
    fun `audio stream with all valid params is eligible`() {
        val data = createValidAudioStreamData()
        val result = SyntheticDashEligibility.checkAudioStreamEligibility(data)
        assertTrue("Valid audio stream should be eligible", result.eligible)
    }

    @Test
    fun `audio stream with DASH delivery is not eligible`() {
        val data = createValidAudioStreamData().copy(deliveryMethod = DeliveryMethod.DASH)
        val result = SyntheticDashEligibility.checkAudioStreamEligibility(data)
        assertFalse("DASH audio stream should not be eligible", result.eligible)
    }

    @Test
    fun `audio stream without itagItem is not eligible`() {
        val data = createValidAudioStreamData().copy(hasItagItem = false)
        val result = SyntheticDashEligibility.checkAudioStreamEligibility(data)
        assertFalse(result.eligible)
        assertTrue(result.failureReasons.contains("NO_ITAG_ITEM"))
    }

    @Test
    fun `audio stream with invalid ranges is not eligible`() {
        val data = createValidAudioStreamData().copy(
            initStart = -1,
            indexEnd = -1
        )
        val result = SyntheticDashEligibility.checkAudioStreamEligibility(data)
        assertFalse(result.eligible)
    }

    // --- JSON Escape Tests ---

    @Test
    fun `jsonEscape handles null`() {
        assertEquals("null", SyntheticDashEligibility.jsonEscape(null))
    }

    @Test
    fun `jsonEscape handles simple string`() {
        assertEquals("\"vp9\"", SyntheticDashEligibility.jsonEscape("vp9"))
    }

    @Test
    fun `jsonEscape handles quotes`() {
        assertEquals("\"test\\\"value\"", SyntheticDashEligibility.jsonEscape("test\"value"))
    }

    @Test
    fun `jsonEscape handles backslashes`() {
        assertEquals("\"test\\\\value\"", SyntheticDashEligibility.jsonEscape("test\\value"))
    }

    @Test
    fun `jsonEscape handles newlines`() {
        assertEquals("\"line1\\nline2\"", SyntheticDashEligibility.jsonEscape("line1\nline2"))
    }

    @Test
    fun `jsonEscape handles tabs`() {
        assertEquals("\"col1\\tcol2\"", SyntheticDashEligibility.jsonEscape("col1\tcol2"))
    }

    @Test
    fun `jsonEscape handles control characters`() {
        val input = "test\u0001value"
        val result = SyntheticDashEligibility.jsonEscape(input)
        assertTrue("Should escape control char", result.contains("\\u0001"))
    }

    @Test
    fun `jsonEscape handles codec strings correctly`() {
        // Real-world codec strings
        assertEquals("\"avc1.4d401f\"", SyntheticDashEligibility.jsonEscape("avc1.4d401f"))
        assertEquals("\"vp09.00.31.08\"", SyntheticDashEligibility.jsonEscape("vp09.00.31.08"))
        assertEquals("\"mp4a.40.2\"", SyntheticDashEligibility.jsonEscape("mp4a.40.2"))
        assertEquals("\"opus\"", SyntheticDashEligibility.jsonEscape("opus"))
    }

    // --- JSON Array Tests ---

    @Test
    fun `jsonArray handles empty list`() {
        assertEquals("[]", SyntheticDashEligibility.jsonArray(emptyList()))
    }

    @Test
    fun `jsonArray handles single element`() {
        assertEquals("[\"MUXED_STREAM\"]", SyntheticDashEligibility.jsonArray(listOf("MUXED_STREAM")))
    }

    @Test
    fun `jsonArray handles multiple elements`() {
        val reasons = listOf("NOT_PROGRESSIVE_HTTP:DASH", "MUXED_STREAM", "NO_ITAG_ITEM")
        assertEquals(
            "[\"NOT_PROGRESSIVE_HTTP:DASH\",\"MUXED_STREAM\",\"NO_ITAG_ITEM\"]",
            SyntheticDashEligibility.jsonArray(reasons)
        )
    }

    @Test
    fun `jsonArray escapes special characters in elements`() {
        val reasons = listOf("ERROR:\"quoted\"", "PATH:\\backslash")
        assertEquals(
            "[\"ERROR:\\\"quoted\\\"\",\"PATH:\\\\backslash\"]",
            SyntheticDashEligibility.jsonArray(reasons)
        )
    }

    // --- Helper Methods ---

    private fun createValidVideoStreamData() = SyntheticDashEligibility.StreamData(
        deliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP,
        isVideoOnly = true,
        hasItagItem = true,
        initStart = 0,
        initEnd = 740,
        indexStart = 741,
        indexEnd = 1228,
        streamInfoDurationSec = 212L,
        itagApproxDurationMs = 212000L,
        hasContent = true
    )

    private fun createValidAudioStreamData() = SyntheticDashEligibility.StreamData(
        deliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP,
        isVideoOnly = false, // Not applicable for audio; checkAudioStreamEligibility ignores this field
        hasItagItem = true,
        initStart = 0,
        initEnd = 631,
        indexStart = 632,
        indexEnd = 867,
        streamInfoDurationSec = 212L,
        itagApproxDurationMs = 212000L,
        hasContent = true
    )
}
