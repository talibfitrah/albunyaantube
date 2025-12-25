package com.albunyaan.tube.player

import android.net.Uri
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Telemetry logger for stream request failures, particularly HTTP 403 errors.
 *
 * Captures detailed diagnostic information when stream requests fail:
 * - Request URL (host + path hash for privacy)
 * - Request headers (User-Agent, etc.)
 * - Response code and headers
 * - Timing information (request timestamp, stream age)
 * - Stream metadata (TTL remaining, source type)
 *
 * This data helps diagnose:
 * - URL expiration issues (YouTube stream URLs typically expire after ~6 hours)
 * - Geo-restriction blocks (403 with specific error patterns)
 * - Rate limiting (429 or 403 with retry-after)
 * - User-Agent mismatches causing 403s
 */
@Singleton
class StreamRequestTelemetry @Inject constructor() {

    companion object {
        private const val TAG = "StreamTelemetry"
        private const val MAX_RECENT_FAILURES = 50

        // Common patterns in YouTube 403 responses indicating specific causes
        // NOTE: These patterns are matched against lowercased body/headers, so use lowercase
        private val GEO_RESTRICTION_PATTERNS = listOf(
            "playability.*unplayable",
            "country.*blocked",
            "geo.?restrict",
            "not.*available.*country",
            "unavailable.*your.*location"
        )

        private val RATE_LIMIT_PATTERNS = listOf(
            "rate.*limit",
            "too.*many.*requests",
            "quota.*exceeded"
        )

        private val EXPIRED_PATTERNS = listOf(
            "signature.*expired",
            "expire",
            "invalid.*token",
            "invalid.*signature"
        )
    }

    /**
     * Failure classification based on response analysis.
     */
    enum class FailureType {
        /** URL/signature expired - refresh stream URLs */
        URL_EXPIRED,
        /** Geographic restriction - content unavailable in region */
        GEO_RESTRICTED,
        /** Rate limited - back off and retry later */
        RATE_LIMITED,
        /** Unknown 403 cause - may be transient */
        UNKNOWN_403,
        /** Other HTTP error (4xx, 5xx) */
        HTTP_ERROR,
        /** Network/connection error */
        NETWORK_ERROR
    }

    /**
     * Captured telemetry data for a failed request.
     */
    data class FailureRecord(
        val timestamp: Long,
        val videoId: String?,
        val streamType: String,
        val requestUrlHost: String,
        val requestUrlPathHash: String,
        val requestHeaders: Map<String, String>,
        val responseCode: Int,
        val responseHeaders: Map<String, List<String>>,
        val responseBody: String?,
        val failureType: FailureType,
        val streamAgeMs: Long?,
        val estimatedTtlRemainingMs: Long?,
        val playbackPositionMs: Long?
    ) {
        fun toLogString(): String {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }
            val timestampStr = dateFormat.format(Date(timestamp))

            val sb = StringBuilder()
            sb.appendLine("=== Stream Request Failure ===")
            sb.appendLine("Time: $timestampStr")
            sb.appendLine("Video: ${videoId ?: "unknown"}")
            sb.appendLine("Stream Type: $streamType")
            sb.appendLine("Host: $requestUrlHost")
            sb.appendLine("Path Hash: $requestUrlPathHash")
            sb.appendLine("Response: $responseCode (${failureType.name})")

            if (streamAgeMs != null) {
                val ageMins = streamAgeMs / 60_000
                sb.appendLine("Stream Age: ${ageMins}m")
            }

            if (estimatedTtlRemainingMs != null) {
                val ttlMins = estimatedTtlRemainingMs / 60_000
                sb.appendLine("Est. TTL Remaining: ${ttlMins}m")
            }

            if (playbackPositionMs != null) {
                val posSecs = playbackPositionMs / 1000
                sb.appendLine("Playback Position: ${posSecs}s")
            }

            sb.appendLine("Request Headers:")
            requestHeaders.forEach { (k, v) ->
                // Redact auth tokens for privacy
                val value = if (k.lowercase().contains("auth") || k.lowercase().contains("cookie")) {
                    "[REDACTED]"
                } else {
                    v
                }
                sb.appendLine("  $k: $value")
            }

            sb.appendLine("Response Headers:")
            responseHeaders.entries.take(10).forEach { (k, v) ->
                sb.appendLine("  $k: ${v.firstOrNull()}")
            }

            if (!responseBody.isNullOrBlank()) {
                val preview = responseBody.take(500)
                sb.appendLine("Response Body (preview): $preview")
            }

            sb.appendLine("==============================")
            return sb.toString()
        }
    }

    private val recentFailures = mutableListOf<FailureRecord>()
    private val lock = Any()

    /** Tracks stream resolution timestamps for TTL estimation */
    private val streamResolutionTimes = ConcurrentHashMap<String, Long>()

    /** Estimated YouTube stream URL TTL (6 hours conservative) */
    private val estimatedTtlMs = 6 * 60 * 60 * 1000L

    /**
     * Record when a stream was resolved (URLs obtained).
     * Used to calculate stream age and estimate TTL remaining.
     */
    fun onStreamResolved(videoId: String) {
        streamResolutionTimes[videoId] = System.currentTimeMillis()
    }

    /**
     * Record a stream request failure with full diagnostic context.
     *
     * @param videoId Video ID if known
     * @param streamType Type of stream (HLS, DASH, PROGRESSIVE, etc.)
     * @param requestUrl Full request URL
     * @param requestHeaders Headers sent with the request
     * @param responseCode HTTP response code
     * @param responseHeaders Response headers from server
     * @param responseBody Response body (if available, limited)
     * @param playbackPositionMs Current playback position when error occurred
     * @return Classified failure type for handling
     */
    fun recordFailure(
        videoId: String?,
        streamType: String,
        requestUrl: String,
        requestHeaders: Map<String, String>,
        responseCode: Int,
        responseHeaders: Map<String, List<String>>,
        responseBody: String?,
        playbackPositionMs: Long? = null
    ): FailureType {
        val now = System.currentTimeMillis()

        // Parse URL for logging (privacy-conscious)
        val uri = runCatching { Uri.parse(requestUrl) }.getOrNull()
        val host = uri?.host ?: "unknown"
        val pathHash = uri?.path?.hashCode()?.let { Integer.toHexString(it) } ?: "unknown"

        // Calculate stream age and TTL
        val resolutionTime = videoId?.let { streamResolutionTimes[it] }
        val streamAgeMs = resolutionTime?.let { now - it }
        val estimatedTtlRemainingMs = resolutionTime?.let { estimatedTtlMs - (now - it) }

        // Classify the failure
        val failureType = classifyFailure(responseCode, responseHeaders, responseBody)

        val record = FailureRecord(
            timestamp = now,
            videoId = videoId,
            streamType = streamType,
            requestUrlHost = host,
            requestUrlPathHash = pathHash,
            requestHeaders = requestHeaders,
            responseCode = responseCode,
            responseHeaders = responseHeaders,
            responseBody = responseBody?.take(1000),
            failureType = failureType,
            streamAgeMs = streamAgeMs,
            estimatedTtlRemainingMs = estimatedTtlRemainingMs,
            playbackPositionMs = playbackPositionMs
        )

        // Store and log
        synchronized(lock) {
            recentFailures.add(record)
            if (recentFailures.size > MAX_RECENT_FAILURES) {
                recentFailures.removeAt(0)
            }
        }

        // Log with appropriate severity
        when (failureType) {
            FailureType.GEO_RESTRICTED -> {
                Log.w(TAG, "Geo-restriction detected for $videoId")
                Log.d(TAG, record.toLogString())
            }
            FailureType.URL_EXPIRED -> {
                Log.w(TAG, "Stream URL expired for $videoId (age: ${streamAgeMs?.let { it / 60_000 }}m)")
                Log.d(TAG, record.toLogString())
            }
            FailureType.RATE_LIMITED -> {
                Log.w(TAG, "Rate limit hit for $videoId")
                Log.d(TAG, record.toLogString())
            }
            else -> {
                Log.e(TAG, "Stream request failed: $responseCode for $videoId")
                Log.d(TAG, record.toLogString())
            }
        }

        return failureType
    }

    /**
     * Classify the failure based on response code and content.
     */
    private fun classifyFailure(
        responseCode: Int,
        responseHeaders: Map<String, List<String>>,
        responseBody: String?
    ): FailureType {
        return when (responseCode) {
            403 -> {
                val bodyLower = responseBody?.lowercase() ?: ""
                val headerStr = responseHeaders.entries.joinToString { "${it.key}: ${it.value}" }.lowercase()

                when {
                    // Check for geo-restriction indicators
                    GEO_RESTRICTION_PATTERNS.any { pattern ->
                        bodyLower.contains(Regex(pattern)) || headerStr.contains(Regex(pattern))
                    } -> FailureType.GEO_RESTRICTED

                    // Check for rate limiting
                    RATE_LIMIT_PATTERNS.any { pattern ->
                        bodyLower.contains(Regex(pattern)) || headerStr.contains(Regex(pattern))
                    } || responseHeaders.keys.any { it.equals("retry-after", ignoreCase = true) } -> FailureType.RATE_LIMITED

                    // Check for expiration
                    EXPIRED_PATTERNS.any { pattern ->
                        bodyLower.contains(Regex(pattern)) || headerStr.contains(Regex(pattern))
                    } -> FailureType.URL_EXPIRED

                    // Default 403
                    else -> FailureType.UNKNOWN_403
                }
            }
            429 -> FailureType.RATE_LIMITED
            in 400..499 -> FailureType.HTTP_ERROR
            in 500..599 -> FailureType.HTTP_ERROR
            else -> FailureType.NETWORK_ERROR
        }
    }

    /**
     * Check if the stream URLs for a video are likely expired based on age.
     *
     * @param videoId Video ID
     * @param safeMarginMs Safety margin before actual expiration (default 30min)
     * @return true if URLs should be refreshed preemptively
     */
    fun shouldRefreshPreemptively(videoId: String, safeMarginMs: Long = 30 * 60 * 1000L): Boolean {
        val resolutionTime = streamResolutionTimes[videoId] ?: return false
        val age = System.currentTimeMillis() - resolutionTime
        val ttlRemaining = estimatedTtlMs - age

        return ttlRemaining < safeMarginMs
    }

    /**
     * Get estimated TTL remaining for a video's stream URLs.
     *
     * @param videoId Video ID
     * @return Remaining TTL in milliseconds, or null if unknown
     */
    fun getEstimatedTtlRemainingMs(videoId: String): Long? {
        val resolutionTime = streamResolutionTimes[videoId] ?: return null
        val age = System.currentTimeMillis() - resolutionTime
        return (estimatedTtlMs - age).coerceAtLeast(0)
    }

    /**
     * Clear resolution time for a video (call after refreshing URLs).
     */
    fun clearResolutionTime(videoId: String) {
        streamResolutionTimes.remove(videoId)
    }

    /**
     * Get recent failure records for debugging.
     */
    fun getRecentFailures(): List<FailureRecord> {
        synchronized(lock) {
            return recentFailures.toList()
        }
    }

    /**
     * Get failure statistics for a video.
     */
    fun getFailureStats(videoId: String): Map<FailureType, Int> {
        synchronized(lock) {
            return recentFailures
                .filter { it.videoId == videoId }
                .groupingBy { it.failureType }
                .eachCount()
        }
    }

    /**
     * Clear all telemetry data.
     */
    fun clear() {
        synchronized(lock) {
            recentFailures.clear()
        }
        streamResolutionTimes.clear()
    }
}
