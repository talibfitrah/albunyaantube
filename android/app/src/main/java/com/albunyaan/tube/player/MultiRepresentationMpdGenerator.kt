package com.albunyaan.tube.player

import android.util.Log
import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.SyntheticDashMetadata
import com.albunyaan.tube.data.extractor.VideoTrack
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 2A: Multi-Representation Synthetic DASH MPD Generator
 *
 * Creates a DASH MPD manifest containing multiple video representations (quality levels)
 * from progressive streams. This enables ExoPlayer's ABR (Adaptive Bitrate) logic to
 * dynamically switch between qualities based on network conditions.
 *
 * **Key difference from SyntheticDashMediaSourceFactory:**
 * - Original: Creates single-representation DASH per stream (no quality switching)
 * - This: Creates multi-representation DASH with all eligible qualities (ABR-capable)
 *
 * **How it works:**
 * 1. Filters video-only tracks with valid SyntheticDashMetadata
 * 2. Groups tracks by codec family (codec-safe ladder policy)
 * 3. Generates a DASH MPD with multiple <Representation> elements
 * 4. Uses byte-range requests for each representation (no extra network calls)
 *
 * **Codec-safe ladder policy:**
 * - Only streams with compatible codecs are grouped together
 * - Prevents codec-switching artifacts (e.g., AV1 to VP9)
 * - Codec families: H264/AVC, VP9, AV1 (ordered by preference)
 *
 * **Eligibility rules (SYNTH_ADAPTIVE):**
 * - Must have 2+ video-only tracks with valid SyntheticDashMetadata
 * - All tracks in the ladder must share compatible codec family
 * - Must have at least one audio track with valid SyntheticDashMetadata
 * - Duration must be available
 */
@Singleton
class MultiRepresentationMpdGenerator @Inject constructor() {

    companion object {
        private const val TAG = "MultiRepMpd"

        /**
         * Codec family groupings for ladder policy.
         * Tracks within the same family can switch without decode errors.
         */
        private val CODEC_FAMILIES = mapOf(
            "avc1" to "H264",
            "avc3" to "H264",
            "mp4a" to "AAC",  // Audio
            "vp9" to "VP9",
            "vp09" to "VP9",
            "av01" to "AV1",
            "opus" to "OPUS"  // Audio
        )

        /**
         * Codec family preference order (most compatible first).
         * When multiple codec families are available, prefer the most widely supported.
         */
        private val CODEC_PREFERENCE = listOf("H264", "VP9", "AV1")

        /**
         * Minimum number of representations required for multi-rep DASH.
         * With only 1 representation, use standard single-rep synthetic DASH instead.
         */
        private const val MIN_REPRESENTATIONS = 2
    }

    /**
     * Result of MPD generation attempt.
     */
    sealed class Result {
        /**
         * Successfully generated multi-representation MPD.
         * @param mpdXml The raw MPD XML content
         * @param mpdDataUri The data: URI for use with Media3
         * @param videoTracks The video tracks included in the MPD (ordered by height desc)
         * @param audioTrack The audio track included in the MPD
         * @param codecFamily The codec family used for video representations
         */
        data class Success(
            val mpdXml: String,
            val mpdDataUri: String,
            val videoTracks: List<VideoTrack>,
            val audioTrack: AudioTrack,
            val codecFamily: String
        ) : Result()

        /**
         * Failed to generate MPD.
         * @param reason Machine-readable failure reason
         */
        data class Failure(val reason: String) : Result()
    }

    /**
     * Check if resolved streams are eligible for multi-representation synthetic DASH.
     *
     * Eligibility criteria:
     * - 2+ video-only tracks with valid SyntheticDashMetadata in the same codec family
     * - 1+ audio track with valid SyntheticDashMetadata
     * - Duration available
     *
     * @param resolved The resolved streams from NewPipe
     * @return Pair of (eligible, reason) where reason explains ineligibility
     */
    fun checkEligibility(resolved: ResolvedStreams): Pair<Boolean, String> {
        // Check duration
        if (resolved.durationSeconds == null || resolved.durationSeconds <= 0) {
            return false to "NO_DURATION"
        }

        // Check audio tracks
        val eligibleAudioTracks = resolved.audioTracks.filter {
            it.syntheticDashMetadata?.hasValidRanges() == true
        }
        if (eligibleAudioTracks.isEmpty()) {
            return false to "NO_ELIGIBLE_AUDIO"
        }

        // Check video tracks
        val eligibleVideoTracks = resolved.videoTracks.filter {
            it.isVideoOnly && it.syntheticDashMetadata?.hasValidRanges() == true
        }
        if (eligibleVideoTracks.size < MIN_REPRESENTATIONS) {
            return false to "INSUFFICIENT_VIDEO_TRACKS:${eligibleVideoTracks.size}"
        }

        // Check codec grouping
        val codecGroups = groupByCodecFamily(eligibleVideoTracks)
        val bestFamily = selectBestCodecFamily(codecGroups)
        if (bestFamily == null) {
            return false to "NO_CODEC_FAMILY"
        }

        val tracksInFamily = codecGroups[bestFamily] ?: emptyList()
        if (tracksInFamily.size < MIN_REPRESENTATIONS) {
            return false to "INSUFFICIENT_SAME_CODEC:${tracksInFamily.size}"
        }

        return true to "ELIGIBLE:$bestFamily:${tracksInFamily.size}reps"
    }

    /**
     * Generate a multi-representation DASH MPD from resolved streams.
     *
     * @param resolved The resolved streams from NewPipe
     * @param qualityCapHeight Optional maximum height to include (null = include all)
     * @return Result.Success with MPD content, or Result.Failure with reason
     */
    fun generateMpd(
        resolved: ResolvedStreams,
        qualityCapHeight: Int? = null
    ): Result {
        // Validate eligibility
        val (eligible, reason) = checkEligibility(resolved)
        if (!eligible) {
            return Result.Failure(reason)
        }

        val durationSeconds = resolved.durationSeconds!!.toLong()

        // Get eligible video tracks
        val eligibleVideoTracks = resolved.videoTracks.filter {
            it.isVideoOnly && it.syntheticDashMetadata?.hasValidRanges() == true
        }

        // Apply quality cap if specified
        val cappedVideoTracks = if (qualityCapHeight != null) {
            eligibleVideoTracks.filter { (it.height ?: Int.MAX_VALUE) <= qualityCapHeight }
        } else {
            eligibleVideoTracks
        }

        if (cappedVideoTracks.size < MIN_REPRESENTATIONS) {
            return Result.Failure("INSUFFICIENT_AFTER_CAP:${cappedVideoTracks.size}")
        }

        // Group by codec family and select best family
        val codecGroups = groupByCodecFamily(cappedVideoTracks)
        val bestFamily = selectBestCodecFamily(codecGroups)
            ?: return Result.Failure("NO_CODEC_FAMILY_AFTER_CAP")

        val videoTracksForMpd = codecGroups[bestFamily]!!
            .sortedByDescending { it.height ?: 0 } // Highest quality first

        // Get best audio track
        val audioTrack = resolved.audioTracks
            .filter { it.syntheticDashMetadata?.hasValidRanges() == true }
            .maxByOrNull { it.bitrate ?: 0 }
            ?: return Result.Failure("NO_AUDIO_TRACK")

        // Generate MPD XML
        val mpdXml = try {
            buildMpdXml(videoTracksForMpd, audioTrack, durationSeconds)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate MPD: ${e.javaClass.simpleName}")
            return Result.Failure("MPD_GENERATION_ERROR:${e.javaClass.simpleName}")
        }

        // buildMpdXml returns null if tracks have inconsistent containers
        if (mpdXml == null) {
            return Result.Failure("INCONSISTENT_CONTAINERS")
        }

        // Create data: URI for Media3
        val mpdDataUri = "data:application/dash+xml;charset=utf-8," +
            URLEncoder.encode(mpdXml, "UTF-8")

        Log.d(TAG, "Generated multi-rep MPD: ${videoTracksForMpd.size} video reps ($bestFamily), 1 audio rep")

        return Result.Success(
            mpdXml = mpdXml,
            mpdDataUri = mpdDataUri,
            videoTracks = videoTracksForMpd,
            audioTrack = audioTrack,
            codecFamily = bestFamily
        )
    }

    /**
     * Group video tracks by codec family.
     */
    private fun groupByCodecFamily(tracks: List<VideoTrack>): Map<String, List<VideoTrack>> {
        return tracks.groupBy { track ->
            getCodecFamily(track.codec ?: track.syntheticDashMetadata?.codec)
        }.filterKeys { it != null }.mapKeys { it.key!! }
    }

    /**
     * Get the codec family for a codec string.
     * Returns null for unknown codecs.
     */
    private fun getCodecFamily(codec: String?): String? {
        if (codec == null) return null

        // Extract prefix (e.g., "avc1.64001f" -> "avc1")
        val prefix = codec.substringBefore('.').lowercase()
        return CODEC_FAMILIES[prefix]
    }

    /**
     * Select the best codec family from available groups.
     * Prefers H264 > VP9 > AV1 based on device compatibility.
     * Returns null if no valid family has enough tracks.
     */
    private fun selectBestCodecFamily(codecGroups: Map<String, List<VideoTrack>>): String? {
        for (family in CODEC_PREFERENCE) {
            val tracks = codecGroups[family]
            if (tracks != null && tracks.size >= MIN_REPRESENTATIONS) {
                return family
            }
        }
        // Fall back to any family with enough tracks
        return codecGroups.entries
            .filter { it.value.size >= MIN_REPRESENTATIONS }
            .maxByOrNull { it.value.size }
            ?.key
    }

    /**
     * Build the DASH MPD XML content.
     *
     * Structure:
     * - Period
     *   - AdaptationSet (video, with multiple Representations)
     *   - AdaptationSet (audio, with single Representation)
     *
     * Container types are derived from track.mimeType (source of truth from extraction),
     * not inferred from codec strings to avoid mismatch (e.g., AV1 can be in MP4 or WebM).
     *
     * DASH profiles:
     * - "urn:mpeg:dash:profile:isoff-on-demand:2011" for ISO-BMFF (MP4) containers
     * - "urn:mpeg:dash:profile:webm-on-demand:2012" for WebM containers
     *
     * @return The MPD XML string, or null if tracks have inconsistent containers
     */
    private fun buildMpdXml(
        videoTracks: List<VideoTrack>,
        audioTrack: AudioTrack,
        durationSeconds: Long
    ): String? {
        val durationPT = "PT${durationSeconds}S"

        // Determine video container from track.mimeType (source of truth from extraction).
        // All video tracks must share the same container for ABR switching to work.
        val videoMimeType = resolveVideoContainerMimeType(videoTracks)
        if (videoMimeType == null) {
            Log.w(TAG, "buildMpdXml: video tracks have inconsistent or missing containers")
            return null
        }

        // Determine audio container from track.mimeType
        val audioMimeType = resolveAudioContainerMimeType(audioTrack)

        // Validate container compatibility: video and audio must use the same container family.
        // Mixed containers (e.g., video/webm + audio/mp4) may not work with a single DASH profile.
        val isVideoWebm = videoMimeType == "video/webm"
        val isAudioWebm = audioMimeType == "audio/webm"
        if (isVideoWebm != isAudioWebm) {
            Log.w(TAG, "buildMpdXml: mixed containers (video=$videoMimeType, audio=$audioMimeType) - not supported")
            return null
        }

        // Select DASH profile based on container type.
        // WebM uses a different profile (urn:mpeg:dash:profile:webm-on-demand:2012).
        val dashProfile = if (isVideoWebm) {
            "urn:mpeg:dash:profile:webm-on-demand:2012"
        } else {
            "urn:mpeg:dash:profile:isoff-on-demand:2011"
        }

        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="$dashProfile" type="static" minBufferTime="PT1.5S" mediaPresentationDuration="$durationPT">""")
            appendLine("""  <Period duration="$durationPT">""")

            // Video AdaptationSet with correct container mime type
            appendLine("""    <AdaptationSet mimeType="$videoMimeType" segmentAlignment="true" subsegmentAlignment="true" subsegmentStartsWithSAP="1">""")
            for (track in videoTracks) {
                appendVideoRepresentation(track, durationSeconds)
            }
            appendLine("""    </AdaptationSet>""")

            // Audio AdaptationSet with correct container mime type
            appendLine("""    <AdaptationSet mimeType="$audioMimeType" segmentAlignment="true" subsegmentAlignment="true" subsegmentStartsWithSAP="1">""")
            appendAudioRepresentation(audioTrack, durationSeconds)
            appendLine("""    </AdaptationSet>""")

            appendLine("""  </Period>""")
            appendLine("""</MPD>""")
        }
    }

    /**
     * Resolve video container MIME type from tracks.
     *
     * Uses track.mimeType as the source of truth (from NewPipe extraction).
     * Falls back to codec-based inference only if mimeType is unavailable.
     * Returns null if tracks have inconsistent containers.
     */
    private fun resolveVideoContainerMimeType(tracks: List<VideoTrack>): String? {
        if (tracks.isEmpty()) return null

        // Collect container types from all tracks
        val containers = tracks.map { track ->
            // Primary: use track.mimeType from extraction (most accurate)
            val fromMimeType = track.mimeType?.let { normalizeContainerMimeType(it) }
            // Fallback: infer from codec if mimeType is missing
            fromMimeType ?: inferVideoContainerFromCodec(track.codec ?: track.syntheticDashMetadata?.codec)
        }.distinct()

        // All tracks must share the same container for ABR switching
        if (containers.size > 1) {
            Log.w(TAG, "resolveVideoContainerMimeType: inconsistent containers across tracks: $containers")
            return null
        }

        return containers.firstOrNull()
    }

    /**
     * Resolve audio container MIME type from track.
     *
     * Uses track.mimeType as the source of truth, falling back to codec-based inference.
     */
    private fun resolveAudioContainerMimeType(track: AudioTrack): String {
        // Primary: use track.mimeType from extraction
        val fromMimeType = track.mimeType?.let { normalizeContainerMimeType(it) }
        if (fromMimeType != null) return fromMimeType

        // Fallback: infer from codec
        return inferAudioContainerFromCodec(track.codec ?: track.syntheticDashMetadata?.codec)
    }

    /**
     * Normalize container MIME type by stripping parameters (e.g., "video/mp4; codecs=..." -> "video/mp4").
     */
    private fun normalizeContainerMimeType(mimeType: String): String {
        return mimeType.substringBefore(';').trim().lowercase()
    }

    /**
     * Fallback: infer video container MIME type from codec string.
     * Only used when track.mimeType is unavailable.
     */
    private fun inferVideoContainerFromCodec(codec: String?): String {
        if (codec == null) return "video/mp4" // Default to MP4

        val prefix = codec.substringBefore('.').lowercase()
        return when (prefix) {
            // Note: This is a fallback heuristic. VP9/AV1 can be in either MP4 or WebM.
            // YouTube often uses WebM for VP9/Opus but may use MP4 for AV1.
            // The track.mimeType should be preferred when available.
            "vp9", "vp09" -> "video/webm"
            "av01" -> "video/mp4" // Changed: AV1 on YouTube is typically MP4, not WebM
            else -> "video/mp4" // H264/AVC and unknown codecs use MP4
        }
    }

    /**
     * Fallback: infer audio container MIME type from codec string.
     * Only used when track.mimeType is unavailable.
     */
    private fun inferAudioContainerFromCodec(codec: String?): String {
        if (codec == null) return "audio/mp4" // Default to MP4

        val prefix = codec.substringBefore('.').lowercase()
        return when (prefix) {
            "opus" -> "audio/webm"
            else -> "audio/mp4" // AAC and unknown codecs use MP4
        }
    }

    /**
     * Append a video Representation element.
     *
     * Note: We use SyntheticDashMetadata directly for byte ranges (SegmentBase).
     * ItagItem gating was removed because it's not actually used and could silently
     * drop reps, causing the generator to return Success with <2 reps.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun StringBuilder.appendVideoRepresentation(track: VideoTrack, durationSeconds: Long) {
        val metadata = track.syntheticDashMetadata!!
        val codec = track.codec ?: metadata.codec ?: "avc1.64001f" // Default to H264 high profile
        val width = track.width ?: 1920
        val height = track.height ?: 1080
        val bitrate = track.bitrate ?: 1000000
        val fps = track.fps ?: 30

        // Generate representation ID from itag and escape for XML safety
        val repId = escapeXml("video_${metadata.itag}")
        val escapedCodec = escapeXml(codec)

        appendLine("""      <Representation id="$repId" bandwidth="$bitrate" codecs="$escapedCodec" width="$width" height="$height" frameRate="$fps">""")
        appendSegmentBase(track.url, metadata)
        appendLine("""      </Representation>""")
    }

    /**
     * Append an audio Representation element.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun StringBuilder.appendAudioRepresentation(track: AudioTrack, durationSeconds: Long) {
        val metadata = track.syntheticDashMetadata!!
        val codec = track.codec ?: metadata.codec ?: "mp4a.40.2" // Default to AAC-LC
        val bitrate = track.bitrate ?: 128000

        // Audio sample rate - YouTube typically uses 44100 Hz for AAC and 48000 Hz for Opus.
        // We infer from codec since AudioTrack doesn't carry sampleRate.
        val sampleRate = when {
            codec.startsWith("opus", ignoreCase = true) -> 48000
            else -> 44100 // Default for AAC and other codecs
        }

        // Generate representation ID from itag and escape for XML safety
        val repId = escapeXml("audio_${metadata.itag}")
        val escapedCodec = escapeXml(codec)

        appendLine("""      <Representation id="$repId" bandwidth="$bitrate" codecs="$escapedCodec" audioSamplingRate="$sampleRate">""")
        appendSegmentBase(track.url, metadata)
        appendLine("""      </Representation>""")
    }

    /**
     * Append SegmentBase with initialization and index ranges.
     *
     * Uses byte-range requests to fetch segments without a separate segment list.
     */
    private fun StringBuilder.appendSegmentBase(url: String, metadata: SyntheticDashMetadata) {
        val escapedUrl = escapeXml(url)

        // SegmentBase with byte ranges
        appendLine("""        <BaseURL>$escapedUrl</BaseURL>""")
        appendLine("""        <SegmentBase indexRange="${metadata.indexStart}-${metadata.indexEnd}">""")
        appendLine("""          <Initialization range="${metadata.initStart}-${metadata.initEnd}"/>""")
        appendLine("""        </SegmentBase>""")
    }

    /**
     * Escape special XML characters.
     */
    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
