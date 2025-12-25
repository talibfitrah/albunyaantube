package com.albunyaan.tube.player

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for StreamRequestTelemetry.
 *
 * Tests 403 failure classification for various HTTP response patterns:
 * - URL_EXPIRED: Signature/token expiration patterns
 * - GEO_RESTRICTED: Geographic restriction patterns
 * - RATE_LIMITED: Rate limiting patterns (including Retry-After header)
 * - UNKNOWN_403: Unclassifiable 403 responses
 *
 * Also tests TTL estimation, preemptive refresh logic, and failure recording.
 */
class StreamRequestTelemetryTest {

    private lateinit var telemetry: StreamRequestTelemetry

    @Before
    fun setUp() {
        telemetry = StreamRequestTelemetry()
    }

    // --- URL_EXPIRED Classification Tests ---

    @Test
    fun `classifies 403 with 'signature expired' in body as URL_EXPIRED`() {
        val failureType = telemetry.recordFailure(
            videoId = "test123",
            streamType = "HLS",
            requestUrl = "https://rr1.googlevideo.com/videoplayback?expire=123",
            requestHeaders = mapOf("User-Agent" to "TestAgent"),
            responseCode = 403,
            responseHeaders = emptyMap(),
            responseBody = "Error: signature expired"
        )

        assertEquals(StreamRequestTelemetry.FailureType.URL_EXPIRED, failureType)
    }

    @Test
    fun `classifies 403 with 'expire' in body as URL_EXPIRED`() {
        val failureType = telemetry.recordFailure(
            videoId = "test123",
            streamType = "DASH",
            requestUrl = "https://rr1.googlevideo.com/videoplayback",
            requestHeaders = mapOf("User-Agent" to "TestAgent"),
            responseCode = 403,
            responseHeaders = emptyMap(),
            responseBody = "The URL has expired"
        )

        assertEquals(StreamRequestTelemetry.FailureType.URL_EXPIRED, failureType)
    }

    @Test
    fun `classifies 403 with 'invalid token' in body as URL_EXPIRED`() {
        val failureType = telemetry.recordFailure(
            videoId = "test123",
            streamType = "PROGRESSIVE",
            requestUrl = "https://rr1.googlevideo.com/videoplayback",
            requestHeaders = emptyMap(),
            responseCode = 403,
            responseHeaders = emptyMap(),
            responseBody = "Error: invalid token provided"
        )

        assertEquals(StreamRequestTelemetry.FailureType.URL_EXPIRED, failureType)
    }

    @Test
    fun `classifies 403 with 'invalid signature' in body as URL_EXPIRED`() {
        val failureType = telemetry.recordFailure(
            videoId = "test123",
            streamType = "HLS",
            requestUrl = "https://rr1.googlevideo.com/videoplayback",
            requestHeaders = emptyMap(),
            responseCode = 403,
            responseHeaders = emptyMap(),
            responseBody = "Error: invalid signature"
        )

        assertEquals(StreamRequestTelemetry.FailureType.URL_EXPIRED, failureType)
    }

    // --- GEO_RESTRICTED Classification Tests ---

    @Test
    fun `classifies 403 with 'playability UNPLAYABLE' pattern as GEO_RESTRICTED`() {
        // The regex pattern is "playability.*unplayable" and body is lowercased before matching
        val failureType = telemetry.recordFailure(
            videoId = "test123",
            streamType = "HLS",
            requestUrl = "https://rr1.googlevideo.com/videoplayback",
            requestHeaders = emptyMap(),
            responseCode = 403,
            responseHeaders = emptyMap(),
            responseBody = """{"playability": {"status": "UNPLAYABLE"}}"""
        )

        assertEquals(StreamRequestTelemetry.FailureType.GEO_RESTRICTED, failureType)
    }

    @Test
    fun `classifies 403 with 'country blocked' in body as GEO_RESTRICTED`() {
        val failureType = telemetry.recordFailure(
            videoId = "test123",
            streamType = "DASH",
            requestUrl = "https://rr1.googlevideo.com/videoplayback",
            requestHeaders = emptyMap(),
            responseCode = 403,
            responseHeaders = emptyMap(),
            responseBody = "This content is country blocked in your region"
        )

        assertEquals(StreamRequestTelemetry.FailureType.GEO_RESTRICTED, failureType)
    }

    @Test
    fun `classifies 403 with 'geo-restricted' in body as GEO_RESTRICTED`() {
        val failureType = telemetry.recordFailure(
            videoId = "test123",
            streamType = "HLS",
            requestUrl = "https://rr1.googlevideo.com/videoplayback",
            requestHeaders = emptyMap(),
            responseCode = 403,
            responseHeaders = emptyMap(),
            responseBody = "This video is geo-restricted"
        )

        assertEquals(StreamRequestTelemetry.FailureType.GEO_RESTRICTED, failureType)
    }

    @Test
    fun `classifies 403 with 'georestrict' in body as GEO_RESTRICTED`() {
        val failureType = telemetry.recordFailure(
            videoId = "test123",
            streamType = "HLS",
            requestUrl = "https://rr1.googlevideo.com/videoplayback",
            requestHeaders = emptyMap(),
            responseCode = 403,
            responseHeaders = emptyMap(),
            responseBody = "georestricted content"
        )

        assertEquals(StreamRequestTelemetry.FailureType.GEO_RESTRICTED, failureType)
    }

    @Test
    fun `classifies 403 with 'not available in your country' as GEO_RESTRICTED`() {
        val failureType = telemetry.recordFailure(
            videoId = "test123",
            streamType = "HLS",
            requestUrl = "https://rr1.googlevideo.com/videoplayback",
            requestHeaders = emptyMap(),
            responseCode = 403,
            responseHeaders = emptyMap(),
            responseBody = "Video is not available in your country"
        )

        assertEquals(StreamRequestTelemetry.FailureType.GEO_RESTRICTED, failureType)
    }

    @Test
    fun `classifies 403 with 'unavailable in your location' as GEO_RESTRICTED`() {
        val failureType = telemetry.recordFailure(
            videoId = "test123",
            streamType = "HLS",
            requestUrl = "https://rr1.googlevideo.com/videoplayback",
            requestHeaders = emptyMap(),
            responseCode = 403,
            responseHeaders = emptyMap(),
            responseBody = "Content unavailable in your location"
        )

        assertEquals(StreamRequestTelemetry.FailureType.GEO_RESTRICTED, failureType)
    }

    // --- RATE_LIMITED Classification Tests ---

    @Test
    fun `classifies 403 with 'rate limit' in body as RATE_LIMITED`() {
        val failureType = telemetry.recordFailure(
            videoId = "test123",
            streamType = "HLS",
            requestUrl = "https://rr1.googlevideo.com/videoplayback",
            requestHeaders = emptyMap(),
            responseCode = 403,
            responseHeaders = emptyMap(),
            responseBody = "You have hit the rate limit"
        )

        assertEquals(StreamRequestTelemetry.FailureType.RATE_LIMITED, failureType)
    }

    @Test
    fun `classifies 403 with 'too many requests' in body as RATE_LIMITED`() {
        val failureType = telemetry.recordFailure(
            videoId = "test123",
            streamType = "HLS",
            requestUrl = "https://rr1.googlevideo.com/videoplayback",
            requestHeaders = emptyMap(),
            responseCode = 403,
            responseHeaders = emptyMap(),
            responseBody = "Error: too many requests"
        )

        assertEquals(StreamRequestTelemetry.FailureType.RATE_LIMITED, failureType)
    }

    @Test
    fun `classifies 403 with 'quota exceeded' in body as RATE_LIMITED`() {
        val failureType = telemetry.recordFailure(
            videoId = "test123",
            streamType = "DASH",
            requestUrl = "https://rr1.googlevideo.com/videoplayback",
            requestHeaders = emptyMap(),
            responseCode = 403,
            responseHeaders = emptyMap(),
            responseBody = "Your quota exceeded for this operation"
        )

        assertEquals(StreamRequestTelemetry.FailureType.RATE_LIMITED, failureType)
    }

    @Test
    fun `classifies 403 with Retry-After header as RATE_LIMITED`() {
        val failureType = telemetry.recordFailure(
            videoId = "test123",
            streamType = "HLS",
            requestUrl = "https://rr1.googlevideo.com/videoplayback",
            requestHeaders = emptyMap(),
            responseCode = 403,
            responseHeaders = mapOf("Retry-After" to listOf("60")),
            responseBody = null
        )

        assertEquals(StreamRequestTelemetry.FailureType.RATE_LIMITED, failureType)
    }

    @Test
    fun `classifies 403 with lowercase retry-after header as RATE_LIMITED`() {
        val failureType = telemetry.recordFailure(
            videoId = "test123",
            streamType = "HLS",
            requestUrl = "https://rr1.googlevideo.com/videoplayback",
            requestHeaders = emptyMap(),
            responseCode = 403,
            responseHeaders = mapOf("retry-after" to listOf("120")),
            responseBody = null
        )

        assertEquals(StreamRequestTelemetry.FailureType.RATE_LIMITED, failureType)
    }

    @Test
    fun `classifies HTTP 429 as RATE_LIMITED`() {
        val failureType = telemetry.recordFailure(
            videoId = "test123",
            streamType = "HLS",
            requestUrl = "https://rr1.googlevideo.com/videoplayback",
            requestHeaders = emptyMap(),
            responseCode = 429,
            responseHeaders = emptyMap(),
            responseBody = null
        )

        assertEquals(StreamRequestTelemetry.FailureType.RATE_LIMITED, failureType)
    }

    // --- UNKNOWN_403 Classification Tests ---

    @Test
    fun `classifies 403 with no matching pattern as UNKNOWN_403`() {
        val failureType = telemetry.recordFailure(
            videoId = "test123",
            streamType = "HLS",
            requestUrl = "https://rr1.googlevideo.com/videoplayback",
            requestHeaders = emptyMap(),
            responseCode = 403,
            responseHeaders = emptyMap(),
            responseBody = "Forbidden"
        )

        assertEquals(StreamRequestTelemetry.FailureType.UNKNOWN_403, failureType)
    }

    @Test
    fun `classifies 403 with empty body as UNKNOWN_403`() {
        val failureType = telemetry.recordFailure(
            videoId = "test123",
            streamType = "HLS",
            requestUrl = "https://rr1.googlevideo.com/videoplayback",
            requestHeaders = emptyMap(),
            responseCode = 403,
            responseHeaders = emptyMap(),
            responseBody = ""
        )

        assertEquals(StreamRequestTelemetry.FailureType.UNKNOWN_403, failureType)
    }

    @Test
    fun `classifies 403 with null body as UNKNOWN_403`() {
        val failureType = telemetry.recordFailure(
            videoId = "test123",
            streamType = "HLS",
            requestUrl = "https://rr1.googlevideo.com/videoplayback",
            requestHeaders = emptyMap(),
            responseCode = 403,
            responseHeaders = emptyMap(),
            responseBody = null
        )

        assertEquals(StreamRequestTelemetry.FailureType.UNKNOWN_403, failureType)
    }

    // --- HTTP_ERROR Classification Tests ---

    @Test
    fun `classifies 4xx errors other than 403 and 429 as HTTP_ERROR`() {
        val codes = listOf(400, 401, 404, 405, 410, 451)
        for (code in codes) {
            val failureType = telemetry.recordFailure(
                videoId = "test123",
                streamType = "HLS",
                requestUrl = "https://rr1.googlevideo.com/videoplayback",
                requestHeaders = emptyMap(),
                responseCode = code,
                responseHeaders = emptyMap(),
                responseBody = null
            )
            assertEquals("HTTP $code should be classified as HTTP_ERROR",
                StreamRequestTelemetry.FailureType.HTTP_ERROR, failureType)
        }
    }

    @Test
    fun `classifies 5xx errors as HTTP_ERROR`() {
        val codes = listOf(500, 502, 503, 504)
        for (code in codes) {
            val failureType = telemetry.recordFailure(
                videoId = "test123",
                streamType = "DASH",
                requestUrl = "https://rr1.googlevideo.com/videoplayback",
                requestHeaders = emptyMap(),
                responseCode = code,
                responseHeaders = emptyMap(),
                responseBody = null
            )
            assertEquals("HTTP $code should be classified as HTTP_ERROR",
                StreamRequestTelemetry.FailureType.HTTP_ERROR, failureType)
        }
    }

    // --- NETWORK_ERROR Classification Tests ---

    @Test
    fun `classifies non-HTTP error codes as NETWORK_ERROR`() {
        val codes = listOf(0, -1, 200) // 200 is unexpected here but should not be 403/4xx/5xx
        for (code in codes) {
            val failureType = telemetry.recordFailure(
                videoId = "test123",
                streamType = "HLS",
                requestUrl = "https://rr1.googlevideo.com/videoplayback",
                requestHeaders = emptyMap(),
                responseCode = code,
                responseHeaders = emptyMap(),
                responseBody = null
            )
            assertEquals("Response code $code should be classified as NETWORK_ERROR",
                StreamRequestTelemetry.FailureType.NETWORK_ERROR, failureType)
        }
    }

    // --- Pattern Priority Tests ---

    @Test
    fun `GEO_RESTRICTED takes precedence over URL_EXPIRED when both patterns present`() {
        // Body contains both patterns - geo-restriction should be checked first
        val failureType = telemetry.recordFailure(
            videoId = "test123",
            streamType = "HLS",
            requestUrl = "https://rr1.googlevideo.com/videoplayback",
            requestHeaders = emptyMap(),
            responseCode = 403,
            responseHeaders = emptyMap(),
            responseBody = "This country blocked content has an expired signature"
        )

        assertEquals(StreamRequestTelemetry.FailureType.GEO_RESTRICTED, failureType)
    }

    @Test
    fun `RATE_LIMITED takes precedence over URL_EXPIRED via Retry-After header`() {
        val failureType = telemetry.recordFailure(
            videoId = "test123",
            streamType = "HLS",
            requestUrl = "https://rr1.googlevideo.com/videoplayback",
            requestHeaders = emptyMap(),
            responseCode = 403,
            responseHeaders = mapOf("Retry-After" to listOf("30")),
            responseBody = "signature expired"
        )

        // Rate limit patterns are checked second, so if Retry-After header is present,
        // it classifies as RATE_LIMITED
        assertEquals(StreamRequestTelemetry.FailureType.RATE_LIMITED, failureType)
    }

    // --- Case Insensitivity Tests ---

    @Test
    fun `classification is case insensitive for body patterns`() {
        val variations = listOf(
            "SIGNATURE EXPIRED",
            "Signature Expired",
            "SIGNATURE expired",
            "signature EXPIRED"
        )

        for (body in variations) {
            val failureType = telemetry.recordFailure(
                videoId = "test123",
                streamType = "HLS",
                requestUrl = "https://rr1.googlevideo.com/videoplayback",
                requestHeaders = emptyMap(),
                responseCode = 403,
                responseHeaders = emptyMap(),
                responseBody = body
            )
            assertEquals("Body '$body' should be classified as URL_EXPIRED",
                StreamRequestTelemetry.FailureType.URL_EXPIRED, failureType)
        }
    }

    // --- Stream Resolution Tracking Tests ---

    @Test
    fun `onStreamResolved records resolution time`() {
        telemetry.onStreamResolved("video1")

        // TTL should be approximately 6 hours (close to full TTL)
        val ttl = telemetry.getEstimatedTtlRemainingMs("video1")
        assertNotNull(ttl)
        assertTrue("TTL should be close to 6 hours", ttl!! > 5 * 60 * 60 * 1000L)
    }

    @Test
    fun `getEstimatedTtlRemainingMs returns null for unknown video`() {
        val ttl = telemetry.getEstimatedTtlRemainingMs("unknown")
        assertNull(ttl)
    }

    @Test
    fun `shouldRefreshPreemptively returns false for fresh stream`() {
        telemetry.onStreamResolved("video1")

        // Just resolved - should not need refresh
        assertFalse(telemetry.shouldRefreshPreemptively("video1"))
    }

    @Test
    fun `shouldRefreshPreemptively returns false for unknown video`() {
        assertFalse(telemetry.shouldRefreshPreemptively("unknown"))
    }

    @Test
    fun `clearResolutionTime removes video tracking`() {
        telemetry.onStreamResolved("video1")
        assertNotNull(telemetry.getEstimatedTtlRemainingMs("video1"))

        telemetry.clearResolutionTime("video1")
        assertNull(telemetry.getEstimatedTtlRemainingMs("video1"))
    }

    // --- Failure Record Storage Tests ---

    @Test
    fun `recordFailure stores failure record`() {
        assertTrue(telemetry.getRecentFailures().isEmpty())

        telemetry.recordFailure(
            videoId = "video1",
            streamType = "HLS",
            requestUrl = "https://example.com/stream",
            requestHeaders = mapOf("User-Agent" to "Test"),
            responseCode = 403,
            responseHeaders = emptyMap(),
            responseBody = "Forbidden"
        )

        assertEquals(1, telemetry.getRecentFailures().size)
    }

    @Test
    fun `failure records include playback position`() {
        telemetry.recordFailure(
            videoId = "video1",
            streamType = "HLS",
            requestUrl = "https://example.com/stream",
            requestHeaders = emptyMap(),
            responseCode = 403,
            responseHeaders = emptyMap(),
            responseBody = null,
            playbackPositionMs = 12345L
        )

        val record = telemetry.getRecentFailures().first()
        assertEquals(12345L, record.playbackPositionMs)
    }

    @Test
    fun `failure records track stream age when resolved`() {
        telemetry.onStreamResolved("video1")

        // Small delay to ensure age > 0
        Thread.sleep(10)

        telemetry.recordFailure(
            videoId = "video1",
            streamType = "HLS",
            requestUrl = "https://example.com/stream",
            requestHeaders = emptyMap(),
            responseCode = 403,
            responseHeaders = emptyMap(),
            responseBody = null
        )

        val record = telemetry.getRecentFailures().first()
        assertNotNull(record.streamAgeMs)
        assertTrue("Stream age should be positive", record.streamAgeMs!! >= 0)
    }

    @Test
    fun `getFailureStats returns correct counts by type`() {
        // Record multiple failures for same video
        telemetry.recordFailure(
            videoId = "video1", streamType = "HLS",
            requestUrl = "https://example.com/stream",
            requestHeaders = emptyMap(),
            responseCode = 403, responseHeaders = emptyMap(),
            responseBody = "signature expired" // URL_EXPIRED
        )
        telemetry.recordFailure(
            videoId = "video1", streamType = "HLS",
            requestUrl = "https://example.com/stream",
            requestHeaders = emptyMap(),
            responseCode = 403, responseHeaders = emptyMap(),
            responseBody = "country blocked" // GEO_RESTRICTED
        )
        telemetry.recordFailure(
            videoId = "video1", streamType = "HLS",
            requestUrl = "https://example.com/stream",
            requestHeaders = emptyMap(),
            responseCode = 403, responseHeaders = emptyMap(),
            responseBody = "signature expired" // URL_EXPIRED again
        )

        val stats = telemetry.getFailureStats("video1")
        assertEquals(2, stats[StreamRequestTelemetry.FailureType.URL_EXPIRED])
        assertEquals(1, stats[StreamRequestTelemetry.FailureType.GEO_RESTRICTED])
    }

    @Test
    fun `getFailureStats returns empty map for unknown video`() {
        val stats = telemetry.getFailureStats("unknown")
        assertTrue(stats.isEmpty())
    }

    @Test
    fun `clear removes all data`() {
        telemetry.onStreamResolved("video1")
        telemetry.recordFailure(
            videoId = "video1", streamType = "HLS",
            requestUrl = "https://example.com/stream",
            requestHeaders = emptyMap(),
            responseCode = 403, responseHeaders = emptyMap(),
            responseBody = null
        )

        telemetry.clear()

        assertTrue(telemetry.getRecentFailures().isEmpty())
        assertNull(telemetry.getEstimatedTtlRemainingMs("video1"))
    }

    // --- FailureRecord.toLogString Tests ---

    @Test
    fun `toLogString redacts auth-related headers`() {
        telemetry.recordFailure(
            videoId = "video1",
            streamType = "HLS",
            requestUrl = "https://example.com/stream",
            requestHeaders = mapOf(
                "User-Agent" to "TestAgent",
                "Authorization" to "Bearer secret123",
                "Cookie" to "session=abc123"
            ),
            responseCode = 403,
            responseHeaders = emptyMap(),
            responseBody = null
        )

        val record = telemetry.getRecentFailures().first()
        val logString = record.toLogString()

        assertTrue("User-Agent should be visible", logString.contains("TestAgent"))
        assertFalse("Authorization value should be redacted", logString.contains("secret123"))
        assertFalse("Cookie value should be redacted", logString.contains("abc123"))
        assertTrue("Should contain [REDACTED]", logString.contains("[REDACTED]"))
    }

    @Test
    fun `toLogString includes failure type`() {
        telemetry.recordFailure(
            videoId = "video1",
            streamType = "HLS",
            requestUrl = "https://example.com/stream",
            requestHeaders = emptyMap(),
            responseCode = 403,
            responseHeaders = emptyMap(),
            responseBody = "country blocked"
        )

        val record = telemetry.getRecentFailures().first()
        val logString = record.toLogString()

        assertTrue("Should include failure type", logString.contains("GEO_RESTRICTED"))
    }

    // --- Max Recent Failures Tests ---

    @Test
    fun `limits stored failures to MAX_RECENT_FAILURES`() {
        // Record 55 failures (exceeds MAX_RECENT_FAILURES of 50)
        repeat(55) { i ->
            telemetry.recordFailure(
                videoId = "video$i",
                streamType = "HLS",
                requestUrl = "https://example.com/stream$i",
                requestHeaders = emptyMap(),
                responseCode = 403,
                responseHeaders = emptyMap(),
                responseBody = null
            )
        }

        // Should only keep most recent 50
        assertEquals(50, telemetry.getRecentFailures().size)
    }

    // --- Header Pattern Matching Tests ---

    @Test
    fun `matches patterns in headers as well as body`() {
        val failureType = telemetry.recordFailure(
            videoId = "test123",
            streamType = "HLS",
            requestUrl = "https://rr1.googlevideo.com/videoplayback",
            requestHeaders = emptyMap(),
            responseCode = 403,
            responseHeaders = mapOf("X-Error-Reason" to listOf("rate limit exceeded")),
            responseBody = "Generic error"
        )

        assertEquals(StreamRequestTelemetry.FailureType.RATE_LIMITED, failureType)
    }

    // --- Edge Cases ---

    @Test
    fun `handles malformed URL gracefully`() {
        val failureType = telemetry.recordFailure(
            videoId = "test123",
            streamType = "HLS",
            requestUrl = "not a valid url",
            requestHeaders = emptyMap(),
            responseCode = 403,
            responseHeaders = emptyMap(),
            responseBody = "error"
        )

        // Should not crash and should return UNKNOWN_403
        assertEquals(StreamRequestTelemetry.FailureType.UNKNOWN_403, failureType)

        val record = telemetry.getRecentFailures().first()
        assertEquals("unknown", record.requestUrlHost)
    }

    @Test
    fun `handles null video ID gracefully`() {
        val failureType = telemetry.recordFailure(
            videoId = null,
            streamType = "HLS",
            requestUrl = "https://example.com/stream",
            requestHeaders = emptyMap(),
            responseCode = 403,
            responseHeaders = emptyMap(),
            responseBody = null
        )

        // Should not crash
        assertEquals(StreamRequestTelemetry.FailureType.UNKNOWN_403, failureType)

        val record = telemetry.getRecentFailures().first()
        assertNull(record.videoId)
    }

    @Test
    fun `truncates long response body in record`() {
        val longBody = "x".repeat(2000)

        telemetry.recordFailure(
            videoId = "test123",
            streamType = "HLS",
            requestUrl = "https://example.com/stream",
            requestHeaders = emptyMap(),
            responseCode = 403,
            responseHeaders = emptyMap(),
            responseBody = longBody
        )

        val record = telemetry.getRecentFailures().first()
        // Body should be truncated to 1000 characters
        assertEquals(1000, record.responseBody?.length)
    }
}
