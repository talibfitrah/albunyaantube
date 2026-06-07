package com.albunyaan.tube.player

import android.util.Log
import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.AudioTrackKind
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.SyntheticDashMetadata
import com.albunyaan.tube.data.extractor.VideoTrack
import java.util.Base64
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
         * @param audioTracks All audio tracks included in the MPD (one per language group)
         * @param audioTrack The primary audio track (ORIGINAL if present, else highest bitrate).
         *                   Kept for backward-compat with existing callers.
         * @param codecFamily The codec family used for video representations
         */
        data class Success(
            val mpdXml: String,
            val mpdDataUri: String,
            val videoTracks: List<VideoTrack>,
            val audioTracks: List<AudioTrack>,
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
     * **Note:** This method may return ELIGIBLE even when no audio track's container is
     * compatible with the chosen video container (e.g., MP4 audio with WebM video).
     * In that case, [generateMpd] will return [Result.Failure] with reason `"NO_COMPATIBLE_AUDIO"`.
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

        // Within the chosen codec family, pick the container that has the
        // most tracks (and the highest top-end height as tiebreaker). This
        // avoids INCONSISTENT_CONTAINERS failures when AV1/VP9 are emitted
        // across both webm and mp4 wrappers — we keep one wrapper so the
        // MPD validates and ABR can switch between qualities.
        val familyTracks = codecGroups[bestFamily]!!
        val byContainer = familyTracks.groupBy { track ->
            track.mimeType?.let { normalizeContainerMimeType(it) }
                ?: inferVideoContainerFromCodec(track.codec ?: track.syntheticDashMetadata?.codec)
                ?: "unknown"
        }
        val bestContainer = byContainer.entries.maxWithOrNull(
            compareBy<Map.Entry<String, List<VideoTrack>>> { it.value.size }
                .thenBy { entry -> entry.value.maxOfOrNull { it.height ?: 0 } ?: 0 }
        )?.key ?: return Result.Failure("NO_CONTAINER_FAMILY")

        val videoTracksForMpd = byContainer[bestContainer]!!
            .sortedByDescending { it.height ?: 0 } // Highest quality first

        // If the container filtering left us below the min-rep threshold,
        // try the largest cross-container group as a secondary fallback.
        if (videoTracksForMpd.size < MIN_REPRESENTATIONS) {
            return Result.Failure("INSUFFICIENT_AFTER_CONTAINER_FILTER:${videoTracksForMpd.size}/${familyTracks.size}")
        }

        // Determine chosen video container (webm or not) so we can filter audio by compatibility.
        val chosenVideoMimeType = resolveVideoContainerMimeType(videoTracksForMpd)
            ?: return Result.Failure("NO_CONTAINER_FAMILY")
        val isChosenVideoWebm = chosenVideoMimeType == "video/webm"

        // Select eligible audio tracks: valid ranges AND container compatible with chosen video.
        // Resolve MIME once per track here so the filter and the XML builder share the same value.
        data class AudioRep(val track: AudioTrack, val mime: String)
        val eligibleAudioReps = resolved.audioTracks
            .filter { it.syntheticDashMetadata?.hasValidRanges() == true }
            .map { AudioRep(it, resolveAudioContainerMimeType(it)) }
            .filter { (_, mime) -> (mime == "audio/webm") == isChosenVideoWebm }
        if (eligibleAudioReps.isEmpty()) {
            return Result.Failure("NO_COMPATIBLE_AUDIO")
        }

        // Group by (language, trackType) so same-language tracks of different roles
        // (e.g. en ORIGINAL + en DESCRIPTIVE) each get their own AdaptationSet.
        // Within each group take the highest-bitrate track as the single representative,
        // then sort deterministically: ORIGINAL first, then by language.
        val audioRepsForMpd: List<AudioRep> = eligibleAudioReps
            .groupBy { Pair(it.track.language, it.track.trackType) }
            .map { (_, group) -> group.maxByOrNull { it.track.bitrate ?: 0 }!! }
            .sortedWith(compareBy({ roleOrder(it.track.trackType) }, { it.track.language ?: "" }))
        val audioTracksForMpd: List<AudioTrack> = audioRepsForMpd.map { it.track }

        // Derive the primary (legacy) audio track: prefer ORIGINAL, else highest bitrate.
        val primaryAudioTrack: AudioTrack =
            audioTracksForMpd.firstOrNull { it.trackType == AudioTrackKind.ORIGINAL }
                ?: audioTracksForMpd.maxByOrNull { it.bitrate ?: 0 }!!

        // Generate MPD XML — pass pre-resolved audio MIME types to avoid double resolution
        val mpdXml = try {
            buildMpdXml(videoTracksForMpd, audioRepsForMpd.map { Pair(it.track, it.mime) }, durationSeconds)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate MPD: ${e.javaClass.simpleName}")
            return Result.Failure("MPD_GENERATION_ERROR:${e.javaClass.simpleName}")
        }

        // buildMpdXml returns null if video tracks have inconsistent containers
        if (mpdXml == null) {
            return Result.Failure("INCONSISTENT_CONTAINERS")
        }

        // Create data: URI for Media3 — base64 encoded (works on JVM tests)
        val mpdDataUri = "data:application/dash+xml;base64," +
            Base64.getEncoder().encodeToString(mpdXml.toByteArray(Charsets.UTF_8))

        Log.d(TAG, "Generated multi-rep MPD: ${videoTracksForMpd.size} video reps ($bestFamily), ${audioTracksForMpd.size} audio lang(s)")

        return Result.Success(
            mpdXml = mpdXml,
            mpdDataUri = mpdDataUri,
            videoTracks = videoTracksForMpd,
            audioTracks = audioTracksForMpd,
            audioTrack = primaryAudioTrack,
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
     * Sort order for audio track roles (ORIGINAL first for deterministic output).
     */
    private fun roleOrder(kind: AudioTrackKind?): Int = when (kind) {
        AudioTrackKind.ORIGINAL -> 0
        AudioTrackKind.DUBBED -> 1
        AudioTrackKind.DUBBED_AUTO -> 2
        AudioTrackKind.DESCRIPTIVE -> 3
        AudioTrackKind.UNKNOWN, null -> 4
    }

    /**
     * Select the best codec family from available groups.
     *
     * Strategy:
     *  1. Pick the family with the highest top-end resolution among families
     *     that have at least [MIN_REPRESENTATIONS] tracks. YouTube typically
     *     publishes 1080p+ only in VP9 or AV1; H.264 caps at 720p for most
     *     videos. Picking H.264 first (the old behaviour) silently capped
     *     adaptive playback at 720p even on devices that decode VP9/AV1
     *     in hardware.
     *  2. Tie-break by [CODEC_PREFERENCE] so H.264 still wins when families
     *     reach the same height — preserves device-compat fallbacks.
     */
    private fun selectBestCodecFamily(codecGroups: Map<String, List<VideoTrack>>): String? {
        val candidates = codecGroups.entries
            .filter { it.value.size >= MIN_REPRESENTATIONS }
        if (candidates.isEmpty()) return null

        val familyRank: (String) -> Int = { name ->
            val idx = CODEC_PREFERENCE.indexOf(name)
            if (idx < 0) Int.MAX_VALUE else idx
        }

        return candidates.maxWithOrNull(
            // Higher top-end first; lower rank (more preferred) wins ties.
            compareBy<Map.Entry<String, List<VideoTrack>>> { entry ->
                entry.value.maxOfOrNull { it.height ?: 0 } ?: 0
            }.thenComparing(
                compareByDescending { entry -> familyRank(entry.key) }
            )
        )?.key
    }

    /**
     * Build the DASH MPD XML content.
     *
     * Structure:
     * - Period
     *   - AdaptationSet (video, with multiple Representations)
     *   - AdaptationSet per language group (audio, one Representation each)
     *
     * Container types are derived from track.mimeType (source of truth from extraction),
     * not inferred from codec strings to avoid mismatch (e.g., AV1 can be in MP4 or WebM).
     *
     * DASH profiles:
     * - "urn:mpeg:dash:profile:isoff-on-demand:2011" for ISO-BMFF (MP4) containers
     * - "urn:mpeg:dash:profile:webm-on-demand:2012" for WebM containers
     *
     * @return The MPD XML string, or null if video tracks have inconsistent containers
     */
    private fun buildMpdXml(
        videoTracks: List<VideoTrack>,
        audioTracks: List<Pair<AudioTrack, String>>,
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

        val isVideoWebm = videoMimeType == "video/webm"

        // Select DASH profile based on container type.
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

            // One audio AdaptationSet per (language, role) group
            for ((track, audioMimeType) in audioTracks) {
                val langAttr = if (track.language != null) """ lang="${escapeXml(track.language)}"""" else ""
                appendLine("""    <AdaptationSet mimeType="$audioMimeType"$langAttr segmentAlignment="true" subsegmentAlignment="true" subsegmentStartsWithSAP="1">""")
                // Emit <Role> only when trackType is ORIGINAL, DUBBED, DUBBED_AUTO, or DESCRIPTIVE.
                // UNKNOWN and null → omit the element entirely.
                val roleValue = when (track.trackType) {
                    AudioTrackKind.ORIGINAL -> "main"
                    AudioTrackKind.DUBBED, AudioTrackKind.DUBBED_AUTO -> "dub"
                    AudioTrackKind.DESCRIPTIVE -> "description"
                    AudioTrackKind.UNKNOWN, null -> null
                }
                if (roleValue != null) {
                    appendLine("""      <Role schemeIdUri="urn:mpeg:dash:role:2011" value="${escapeXml(roleValue)}"/>""")
                }
                appendAudioRepresentation(track, durationSeconds)
                appendLine("""    </AdaptationSet>""")
            }

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
