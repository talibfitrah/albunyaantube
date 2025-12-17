package com.albunyaan.tube.data.extractor

import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.CreationException
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.DeliveryMethod

/**
 * PR6.1 Synthetic DASH eligibility checker.
 *
 * Determines whether a stream can be wrapped in a synthetic DASH manifest
 * using YoutubeProgressiveDashManifestCreator for improved seek/restart behavior.
 *
 * All functions in this object make no HTTP/network calls and no additional
 * extraction passes. The tryGenerateMpd() function generates MPD XML locally
 * from provided metadata by calling NewPipe's YoutubeProgressiveDashManifestCreator.
 */
object SyntheticDashEligibility {

    /**
     * Result of eligibility check with detailed failure reasons.
     */
    data class EligibilityResult(
        val eligible: Boolean,
        val failureReasons: List<String> = emptyList()
    ) {
        companion object {
            fun eligible() = EligibilityResult(eligible = true)
            fun ineligible(vararg reasons: String) = EligibilityResult(
                eligible = false,
                failureReasons = reasons.toList()
            )
        }
    }

    /**
     * Input data for eligibility check (decoupled from NewPipe types for testability).
     */
    data class StreamData(
        val deliveryMethod: DeliveryMethod,
        val isVideoOnly: Boolean,
        val hasItagItem: Boolean,
        val initStart: Long,
        val initEnd: Long,
        val indexStart: Long,
        val indexEnd: Long,
        val streamInfoDurationSec: Long?,
        val itagApproxDurationMs: Long?,
        val hasContent: Boolean
    )

    /**
     * Check if a video stream is eligible for synthetic DASH wrapping.
     *
     * Requirements (per NewPipe's YoutubeProgressiveDashManifestCreator):
     * - Delivery method must be PROGRESSIVE_HTTP (not DASH/OTF/HLS)
     * - Must be video-only (muxed streams stay as legacy progressive per NewPipe)
     * - Must have valid ItagItem (required for manifest creation)
     * - Must have valid init range (initStart <= initEnd, both >= 0)
     * - Must have valid index range (indexStart <= indexEnd, both >= 0)
     * - Must have usable duration (StreamInfo duration or ItagItem approxDurationMs)
     * - Must have non-blank content URL
     */
    fun checkVideoStreamEligibility(data: StreamData): EligibilityResult {
        val reasons = mutableListOf<String>()

        // Must have content
        if (!data.hasContent) {
            reasons.add("NO_CONTENT")
        }

        // Must be PROGRESSIVE_HTTP delivery
        if (data.deliveryMethod != DeliveryMethod.PROGRESSIVE_HTTP) {
            reasons.add("NOT_PROGRESSIVE_HTTP:${data.deliveryMethod.name}")
        }

        // Must be video-only (NewPipe keeps muxed as legacy progressive)
        if (!data.isVideoOnly) {
            reasons.add("MUXED_STREAM")
        }

        // Must have ItagItem for manifest creation
        if (!data.hasItagItem) {
            reasons.add("NO_ITAG_ITEM")
        }

        // Validate init range (must be non-negative and properly ordered)
        if (!isValidRange(data.initStart, data.initEnd)) {
            reasons.add("INVALID_INIT_RANGE:${data.initStart}-${data.initEnd}")
        }

        // Validate index range (must be non-negative and properly ordered)
        if (!isValidRange(data.indexStart, data.indexEnd)) {
            reasons.add("INVALID_INDEX_RANGE:${data.indexStart}-${data.indexEnd}")
        }

        // Must have usable duration
        val hasDuration = (data.streamInfoDurationSec != null && data.streamInfoDurationSec > 0) ||
            (data.itagApproxDurationMs != null && data.itagApproxDurationMs > 0)
        if (!hasDuration) {
            reasons.add("NO_DURATION")
        }

        return if (reasons.isEmpty()) {
            EligibilityResult.eligible()
        } else {
            EligibilityResult.ineligible(*reasons.toTypedArray())
        }
    }

    /**
     * Check if an audio stream is eligible for synthetic DASH wrapping.
     *
     * Same requirements as video except isVideoOnly check (audio streams are always "audio-only").
     */
    fun checkAudioStreamEligibility(data: StreamData): EligibilityResult {
        val reasons = mutableListOf<String>()

        // Must have content
        if (!data.hasContent) {
            reasons.add("NO_CONTENT")
        }

        // Must be PROGRESSIVE_HTTP delivery
        if (data.deliveryMethod != DeliveryMethod.PROGRESSIVE_HTTP) {
            reasons.add("NOT_PROGRESSIVE_HTTP:${data.deliveryMethod.name}")
        }

        // Must have ItagItem for manifest creation
        if (!data.hasItagItem) {
            reasons.add("NO_ITAG_ITEM")
        }

        // Validate init range
        if (!isValidRange(data.initStart, data.initEnd)) {
            reasons.add("INVALID_INIT_RANGE:${data.initStart}-${data.initEnd}")
        }

        // Validate index range
        if (!isValidRange(data.indexStart, data.indexEnd)) {
            reasons.add("INVALID_INDEX_RANGE:${data.indexStart}-${data.indexEnd}")
        }

        // Must have usable duration
        val hasDuration = (data.streamInfoDurationSec != null && data.streamInfoDurationSec > 0) ||
            (data.itagApproxDurationMs != null && data.itagApproxDurationMs > 0)
        if (!hasDuration) {
            reasons.add("NO_DURATION")
        }

        return if (reasons.isEmpty()) {
            EligibilityResult.eligible()
        } else {
            EligibilityResult.ineligible(*reasons.toTypedArray())
        }
    }

    /**
     * Validate that a byte range is valid:
     * - Both start and end must be >= 0
     * - Start must be <= end (inclusive range; single-byte ranges where start == end are valid)
     */
    private fun isValidRange(start: Long, end: Long): Boolean {
        return start >= 0 && end >= 0 && start <= end
    }

    /**
     * Escape a string for JSON output (handles quotes, backslashes, control chars).
     */
    fun jsonEscape(value: String?): String {
        if (value == null) return "null"
        return buildString {
            append('"')
            for (char in value) {
                when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char.code < 32) {
                        append("\\u${char.code.toString(16).padStart(4, '0')}")
                    } else {
                        append(char)
                    }
                }
            }
            append('"')
        }
    }

    /**
     * Format a list of strings as a JSON array with proper escaping.
     * Example: ["MUXED_STREAM", "NO_ITAG_ITEM"]
     */
    fun jsonArray(values: List<String>): String {
        if (values.isEmpty()) return "[]"
        return values.joinToString(",", "[", "]") { jsonEscape(it) }
    }

    /**
     * Result of MPD generation attempt.
     */
    data class MpdGenerationResult(
        val success: Boolean,
        val errorMessage: String? = null
    )

    /**
     * Attempt to generate a synthetic DASH MPD from a progressive stream.
     *
     * This validates that YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl()
     * actually succeeds with the given parameters. This is a no-network call - it only
     * generates the MPD XML string from the provided metadata.
     *
     * IMPORTANT: This should only be called for streams that pass eligibility checks,
     * as it requires a valid ItagItem.
     *
     * @param streamUrl The progressive stream URL (content URL)
     * @param itagItem The ItagItem from the stream (must not be null)
     * @param durationSecondsFallback Fallback duration in seconds if not in ItagItem
     * @return MpdGenerationResult indicating success or failure with error code (no message text to avoid URL leaks)
     */
    fun tryGenerateMpd(
        streamUrl: String,
        itagItem: ItagItem,
        durationSecondsFallback: Long
    ): MpdGenerationResult {
        return try {
            val mpd = YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(
                streamUrl,
                itagItem,
                durationSecondsFallback
            )
            // Verify we got a non-empty MPD
            if (mpd.isNullOrBlank()) {
                MpdGenerationResult(success = false, errorMessage = "EMPTY_MPD")
            } else {
                MpdGenerationResult(success = true)
            }
        } catch (e: CreationException) {
            // Only log exception class name, NOT message (may contain URLs/tokens)
            MpdGenerationResult(success = false, errorMessage = "CREATION_EXCEPTION")
        } catch (e: Exception) {
            // Only log exception class name, NOT message (may contain URLs/tokens)
            MpdGenerationResult(success = false, errorMessage = "UNEXPECTED:${e.javaClass.simpleName}")
        }
    }
}
