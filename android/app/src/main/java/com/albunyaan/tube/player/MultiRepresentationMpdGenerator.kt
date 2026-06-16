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
 * Multi-Representation Synthetic DASH MPD Generator — LibreTube-aligned "include everything".
 *
 * Builds ONE DASH MPD containing every usable adaptive stream, hands it to ExoPlayer, and lets
 * ExoPlayer's ABR / track-selection choose. This mirrors LibreTube's `DashHelper.createManifest`:
 *
 * - One video AdaptationSet **per container** (video/mp4 holds avc1 + av01; video/webm holds vp9),
 *   each containing ALL representations for that container. No codec-family selection, no
 *   "best container" narrowing, no minimum-representation gate.
 * - One audio AdaptationSet per (language, role, container) group, independent of the video
 *   container (DASH demuxes video + audio from separate AdaptationSets — mp4 video + webm/Opus
 *   audio plays fine, and vice-versa).
 * - DASH full profile (`urn:mpeg:dash:profile:full:2011`) — permits multiple AdaptationSets and
 *   mixed containers in one Period.
 *
 * Each Representation uses SegmentBase byte ranges (init + index) from [SyntheticDashMetadata], so
 * no extra network calls are needed. Per-track byte-range validity is the ONLY filter: a track must
 * carry valid ranges (videoOnly for video). OTF/muxed streams have range = -1 and are excluded by
 * [SyntheticDashMetadata.hasValidRanges].
 *
 * **Why include everything instead of picking one codec family:** narrowing to a single family +
 * container behind a `>= 2` rep gate meant any video whose chosen ladder didn't satisfy the gate
 * failed the WHOLE manifest and fell back to progressive 360p. LibreTube never narrows, so it never
 * fails this way — ExoPlayer simply selects among whatever is present. Removing the narrowing is
 * what lets high-res actually play instead of being capped at 360p.
 */
@Singleton
class MultiRepresentationMpdGenerator @Inject constructor() {

    companion object {
        private const val TAG = "MultiRepMpd"
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
         * @param audioTracks All audio tracks included in the MPD (one per language/role/container group)
         * @param audioTrack The primary audio track (ORIGINAL if present, else highest bitrate).
         *                   Kept for backward-compat with existing callers.
         * @param codecFamily Descriptive label of the video containers emitted (e.g.
         *                    "video/mp4+video/webm"). Kept non-null for registry callers; nothing
         *                    branches on its value.
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
     * Minimal LibreTube-style eligibility — presence, not narrowing:
     * - Duration available
     * - 1+ audio track with valid SyntheticDashMetadata ranges
     * - 1+ video-only track with valid SyntheticDashMetadata ranges
     *
     * @return Pair of (eligible, reason) where reason explains ineligibility
     */
    fun checkEligibility(resolved: ResolvedStreams): Pair<Boolean, String> {
        if (resolved.durationSeconds == null || resolved.durationSeconds <= 0) {
            return false to "NO_DURATION"
        }

        val eligibleAudio = resolved.audioTracks.count {
            it.syntheticDashMetadata?.hasValidRanges() == true && it.url.isNotEmpty()
        }
        if (eligibleAudio == 0) {
            return false to "NO_ELIGIBLE_AUDIO"
        }

        val eligibleVideo = resolved.videoTracks.count {
            it.isVideoOnly && it.syntheticDashMetadata?.hasValidRanges() == true
        }
        if (eligibleVideo == 0) {
            return false to "NO_ELIGIBLE_VIDEO"
        }

        return true to "ELIGIBLE:${eligibleVideo}v/${eligibleAudio}a"
    }

    /**
     * Generate a multi-representation DASH MPD from resolved streams.
     *
     * @param resolved The resolved streams from NewPipe / ANDROID_VR
     * @param qualityCapHeight Optional maximum height to include (null = include all)
     * @return Result.Success with MPD content, or Result.Failure with reason
     */
    fun generateMpd(
        resolved: ResolvedStreams,
        qualityCapHeight: Int? = null
    ): Result {
        val (eligible, reason) = checkEligibility(resolved)
        if (!eligible) {
            return Result.Failure(reason)
        }

        val durationSeconds = resolved.durationSeconds!!.toLong()

        // Per-track eligibility: video-only + valid byte ranges. OTF/muxed (ranges = -1) are
        // excluded by hasValidRanges() — the same per-track gate LibreTube applies (videoOnly +
        // indexEnd > 0), just expressed through the range validity check.
        val eligibleVideoTracks = resolved.videoTracks.filter {
            it.isVideoOnly && it.syntheticDashMetadata?.hasValidRanges() == true
        }

        // Optional quality cap (used by the adaptive cold-start / cap path).
        val cappedVideoTracks = if (qualityCapHeight != null) {
            eligibleVideoTracks.filter { (it.height ?: Int.MAX_VALUE) <= qualityCapHeight }
        } else {
            eligibleVideoTracks
        }
        if (cappedVideoTracks.isEmpty()) {
            return Result.Failure("NO_VIDEO_AFTER_CAP:0")
        }

        // INCLUDE EVERYTHING: one AdaptationSet per container, every codec inside it. No family
        // pick, no "best container", no min-rep gate (LibreTube DashHelper parity). groupBy keeps
        // first-seen container order; reps within a container are sorted highest-quality first.
        val videoByContainer: Map<String, List<VideoTrack>> = cappedVideoTracks
            .groupBy { resolveVideoContainerForTrack(it) }
            .mapValues { (_, tracks) -> tracks.sortedByDescending { it.height ?: 0 } }

        // Flat, height-desc list for Result metadata / legacy callers.
        val videoTracksForMpd = videoByContainer.values.flatten()
            .sortedByDescending { it.height ?: 0 }

        // Audio is INDEPENDENT of the video container in DASH (separate AdaptationSets). Keep ALL
        // valid-range audio of every container — LibreTube never matches audio to the video wrapper.
        // Resolve each track's MIME once so the grouping key and the XML builder agree.
        data class AudioRep(val track: AudioTrack, val mime: String)
        val allAudioReps = resolved.audioTracks
            // url.isNotEmpty(): a dub deselected during a switch is kept as a URL-less lazy placeholder
            // (for the picker) but retains its syntheticDashMetadata — without this guard it would emit a
            // phantom AdaptationSet with an empty <BaseURL>.
            .filter { it.syntheticDashMetadata?.hasValidRanges() == true && it.url.isNotEmpty() }
            .map { AudioRep(it, resolveAudioContainerMimeType(it)) }
        if (allAudioReps.isEmpty()) {
            return Result.Failure("NO_AUDIO_WITH_RANGES")
        }

        // Group by (language, role, container) so each distinct audio AdaptationSet survives:
        // same-language different-role (en ORIGINAL vs en DESCRIPTIVE) AND same-language-same-role
        // different-container (Opus/webm vs AAC/mp4) each get their own set. Within a group, take
        // the highest-bitrate track as the single representative. Deterministic order: role, then
        // language, then container.
        val audioRepsForMpd = allAudioReps
            .groupBy { Triple(it.track.language, it.track.trackType, it.mime) }
            .map { (_, group) -> group.maxByOrNull { it.track.bitrate ?: 0 }!! }
            .sortedWith(
                compareBy({ roleOrder(it.track.trackType) }, { it.track.language ?: "" }, { it.mime })
            )
        val audioTracksForMpd: List<AudioTrack> = audioRepsForMpd.map { it.track }

        // Primary (legacy) audio track: prefer ORIGINAL, else highest bitrate.
        val primaryAudioTrack: AudioTrack =
            audioTracksForMpd.firstOrNull { it.trackType == AudioTrackKind.ORIGINAL }
                ?: audioTracksForMpd.maxByOrNull { it.bitrate ?: 0 }!!

        val mpdXml = try {
            buildMpdXml(videoByContainer, audioRepsForMpd.map { Pair(it.track, it.mime) }, durationSeconds)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate MPD: ${e.javaClass.simpleName}")
            return Result.Failure("MPD_GENERATION_ERROR:${e.javaClass.simpleName}")
        }

        // Create data: URI for Media3 — base64 encoded (works on JVM tests)
        val mpdDataUri = "data:application/dash+xml;base64," +
            Base64.getEncoder().encodeToString(mpdXml.toByteArray(Charsets.UTF_8))

        val containersLabel = videoByContainer.keys.joinToString("+")
        Log.d(
            TAG,
            "Generated multi-rep MPD: ${videoTracksForMpd.size} video reps across [$containersLabel], " +
                "${audioTracksForMpd.size} audio set(s)"
        )

        return Result.Success(
            mpdXml = mpdXml,
            mpdDataUri = mpdDataUri,
            videoTracks = videoTracksForMpd,
            audioTracks = audioTracksForMpd,
            audioTrack = primaryAudioTrack,
            codecFamily = containersLabel
        )
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
     * Build the DASH MPD XML content.
     *
     * Structure:
     * - Period
     *   - One AdaptationSet per video container (each with all its Representations)
     *   - One AdaptationSet per audio (language, role, container) group
     *
     * Container types come from track.mimeType (source of truth from extraction), falling back to
     * codec inference. Always emits the DASH full profile (matches LibreTube), which permits
     * multiple AdaptationSets and mixed containers in one Period.
     */
    private fun buildMpdXml(
        videoByContainer: Map<String, List<VideoTrack>>,
        audioTracks: List<Pair<AudioTrack, String>>,
        durationSeconds: Long
    ): String {
        val durationPT = "PT${durationSeconds}S"
        val dashProfile = "urn:mpeg:dash:profile:full:2011"

        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="$dashProfile" type="static" minBufferTime="PT1.5S" mediaPresentationDuration="$durationPT">""")
            appendLine("""  <Period duration="$durationPT">""")

            // One video AdaptationSet per container; every codec / representation included.
            for ((containerMime, tracks) in videoByContainer) {
                appendLine("""    <AdaptationSet mimeType="${escapeXml(containerMime)}" segmentAlignment="true" subsegmentAlignment="true" subsegmentStartsWithSAP="1">""")
                for (track in tracks) {
                    appendVideoRepresentation(track)
                }
                appendLine("""    </AdaptationSet>""")
            }

            // One audio AdaptationSet per (language, role, container) group
            for ((track, audioMimeType) in audioTracks) {
                val langAttr = if (track.language != null) """ lang="${escapeXml(track.language)}"""" else ""
                appendLine("""    <AdaptationSet mimeType="${escapeXml(audioMimeType)}"$langAttr segmentAlignment="true" subsegmentAlignment="true" subsegmentStartsWithSAP="1">""")
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
                appendAudioRepresentation(track)
                appendLine("""    </AdaptationSet>""")
            }

            appendLine("""  </Period>""")
            appendLine("""</MPD>""")
        }
    }

    /**
     * Resolve a single video track's container MIME type (never null).
     * Prefers track.mimeType from extraction, falls back to codec inference.
     */
    private fun resolveVideoContainerForTrack(track: VideoTrack): String {
        val fromMimeType = track.mimeType?.let { normalizeContainerMimeType(it) }
        return fromMimeType
            ?: inferVideoContainerFromCodec(track.codec ?: track.syntheticDashMetadata?.codec)
    }

    /**
     * Resolve audio container MIME type from track.
     *
     * Uses track.mimeType as the source of truth, falling back to codec-based inference.
     */
    private fun resolveAudioContainerMimeType(track: AudioTrack): String {
        val fromMimeType = track.mimeType?.let { normalizeContainerMimeType(it) }
        if (fromMimeType != null) return fromMimeType
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
            "av01" -> "video/mp4" // AV1 on YouTube is typically MP4, not WebM
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
     * Uses SyntheticDashMetadata directly for byte ranges (SegmentBase).
     */
    private fun StringBuilder.appendVideoRepresentation(track: VideoTrack) {
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
    private fun StringBuilder.appendAudioRepresentation(track: AudioTrack) {
        val metadata = track.syntheticDashMetadata!!
        val codec = track.codec ?: metadata.codec ?: "mp4a.40.2" // Default to AAC-LC
        val bitrate = track.bitrate ?: 128000

        // Audio sample rate - YouTube typically uses 44100 Hz for AAC and 48000 Hz for Opus.
        // We infer from codec since AudioTrack doesn't carry sampleRate.
        val sampleRate = when {
            codec.startsWith("opus", ignoreCase = true) -> 48000
            else -> 44100 // Default for AAC and other codecs
        }

        // Generate representation ID from itag PLUS language/role, then escape for XML safety.
        // YouTube serves every dub language under the SAME itag (e.g. 140/251), so an
        // itag-only id (`audio_140`) collides across the per-language AdaptationSets in one
        // Period — a DASH `@id`-uniqueness violation. Disambiguate with language + trackType.
        val langTag = track.language?.takeIf { it.isNotBlank() } ?: "und"
        val roleTag = track.trackType?.name?.lowercase() ?: "default"
        val repId = escapeXml("audio_${metadata.itag}_${langTag}_${roleTag}")
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
