package com.albunyaan.tube.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import com.albunyaan.tube.BuildConfig
import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.AudioTrackSource
import com.albunyaan.tube.data.extractor.ResolvedStreams
import com.albunyaan.tube.data.extractor.SubtitleTrack
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Describes which Media3 source strategy to use for a given [ResolvedStreams].
 *
 * Returned by the pure [DashSourceBuilder.decide] function, which can be unit-tested
 * without any Android or Media3 runtime.
 */
sealed interface SourceDecision {
    /** VOD primary: multi-rep MPD as a base64 `data:` URI. */
    data class LocalDash(val dataUri: String) : SourceDecision

    /** Live: YouTube server-side DASH manifest URL. */
    data class ServerDash(val url: String) : SourceDecision

    /** Live fallback: HLS manifest URL. */
    data class Hls(val url: String) : SourceDecision

    /**
     * Progressive (muxed or separate video + audio).
     * [audioUrl] is null when [videoUrl] is a muxed stream that already contains audio,
     * or when no audio track is available.
     */
    data class Progressive(
        val videoUrl: String,
        val videoMime: String?,
        val audioUrl: String?,
        val audioMime: String?,
    ) : SourceDecision

    /** No playable source could be derived. [reason] is a machine-readable tag for logs. */
    data class None(val reason: String) : SourceDecision
}

/**
 * Turns a [ResolvedStreams] into a Media3 [MediaSource] using a single, clean strategy:
 *
 * - **VOD**: Generate a multi-representation MPD (LibreTube style) via [MultiRepresentationMpdGenerator].
 *   Falls back to progressive (muxed or video-only + audio) when MPD generation fails.
 * - **Live**: Use the server-side DASH manifest; fall back to HLS.
 *
 * The [decide] and [subtitleMimeType] functions are **pure** (no Android/Media3 deps) and
 * are fully covered by [DashSourceBuilderTest].  Only [build] / [buildAudioOnly] touch runtime APIs.
 *
 * This is the single source-construction path used by `PlayerFragment` (replacing the
 * legacy multi-strategy media-source factory, now removed).
 */
@OptIn(UnstableApi::class)
@Singleton
class DashSourceBuilder @Inject constructor(
    private val mpdGenerator: MultiRepresentationMpdGenerator,
    private val dataSourceFactoryProvider: SegmentDataSourceFactoryProvider,
) {
    companion object {
        private const val TAG = "DashSourceBuilder"

        /** True when [resolved] carries a resolved web-sourced dub audio track to merge onto VR. */
        fun isWebDubMerge(resolved: ResolvedStreams): Boolean =
            resolved.audioTracks.any { it.source == AudioTrackSource.WEB_DUB && it.url.isNotEmpty() }
    }

    // -------------------------------------------------------------------------
    // Pure decision function
    // -------------------------------------------------------------------------

    /**
     * Determine the best [SourceDecision] for [resolved].
     *
     * When [forceProgressive] is true (sticky-fallback retry after adaptive failed), the VOD
     * branch SKIPS the LocalDash (MPD-generation) attempt and goes straight to the progressive
     * derivation. The live branch is unaffected by [forceProgressive].
     *
     * This function is **pure** — it makes no I/O calls, uses no Android APIs,
     * and can be exercised directly in JVM unit tests.
     */
    fun decide(resolved: ResolvedStreams, forceProgressive: Boolean = false): SourceDecision {
        if (resolved.isLive) {
            // Prefer server-side DASH for live streams — follows LibreTube's OnlinePlayerService
            // which uses the server DASH MPD for live, giving better segment availability and timing.
            return when {
                resolved.dashUrl != null -> SourceDecision.ServerDash(resolved.dashUrl)
                resolved.hlsUrl != null  -> SourceDecision.Hls(resolved.hlsUrl)
                else                     -> SourceDecision.None("LIVE_NO_MANIFEST")
            }
        }

        // VOD — try the multi-representation adaptive MPD first unless the caller forced progressive.
        //
        // This was gated to ExtractionClient.ANDROID_VR while that client was the only one whose
        // adaptive segments sustained: everything else 403'd after the initial range, so an
        // adaptive build elsewhere played ~60s and died. That premise INVERTED on 2026-08-18, when
        // YouTube extended its GVS poToken requirement to ANDROID_VR too and the VR resolve path
        // was removed (see NewPipeExtractorClient.resolveStreams). NewPipeExtractor 0.26.5's own
        // adaptive video-only segments were then re-verified to sustain — HTTP 206 on a range
        // request at t+70s with no poToken provider registered at all, then 1:49+ on-device.
        //
        // Leaving the old gate in place after the VR removal silently pinned every video to the
        // muxed 360p itag-18 stream: no HD ladder, no quality switching, no dub swapping. Verified
        // on-device before/after (SyntheticDASH summary reported mpdOKVideo=12 up to 1080p while
        // the builder was still returning PROGRESSIVE).
        if (!forceProgressive) {
            when (val mpdResult = mpdGenerator.generateMpd(resolved)) {
                is MultiRepresentationMpdGenerator.Result.Success ->
                    return SourceDecision.LocalDash(mpdResult.mpdDataUri)
                is MultiRepresentationMpdGenerator.Result.Failure -> {
                    // Diagnostic: surface WHY the multi-rep MPD couldn't be built (and the track
                    // shape behind it) so progressive-fallback regressions are debuggable from logs.
                    val voRanged = resolved.videoTracks.count {
                        it.isVideoOnly && it.syntheticDashMetadata?.hasValidRanges() == true
                    }
                    val aMp4 = resolved.audioTracks.count {
                        (it.mimeType ?: "").startsWith("audio/mp4") && it.syntheticDashMetadata?.hasValidRanges() == true
                    }
                    val aWebm = resolved.audioTracks.count {
                        (it.mimeType ?: "").startsWith("audio/webm") && it.syntheticDashMetadata?.hasValidRanges() == true
                    }
                    Log.d(
                        TAG,
                        "MPD gen failed reason=${mpdResult.reason} videoOnlyRanged=$voRanged " +
                            "audioMp4Ranged=$aMp4 audioWebmRanged=$aWebm videoTotal=${resolved.videoTracks.size} " +
                            "audioTotal=${resolved.audioTracks.size} client=${resolved.extractionClient}"
                    )
                }
            }
        }

        // Progressive fallback — prefer best-quality muxed track (has built-in audio: itag 18/22).
        // Use maxByOrNull { height } rather than firstOrNull to avoid silently picking 360p itag 18
        // over 720p itag 22 when both are present.
        val muxed = resolved.videoTracks
            .filter { !it.isVideoOnly }
            .maxByOrNull { it.height ?: 0 }
        if (muxed != null) {
            return SourceDecision.Progressive(
                videoUrl  = muxed.url,
                videoMime = muxed.mimeType,
                audioUrl  = null,
                audioMime = null,
            )
        }

        // No video tracks at all — distinct from having tracks that are unusable.
        if (resolved.videoTracks.isEmpty()) {
            return SourceDecision.None("NO_VIDEO_TRACK")
        }

        // No muxed track available (e.g. forceProgressive after an MPD-gen failure): best
        // video-only + best audio. The client gate that used to reject this combination for
        // non-VR clients was removed with ANDROID_VR itself — these segments sustain now, for
        // the same re-verified reason as the adaptive MPD above.
        val bestVideo = resolved.videoTracks
            .filter { it.isVideoOnly }
            .maxByOrNull { it.height ?: 0 }
        val bestAudio = resolved.audioTracks.maxByOrNull { it.bitrate ?: 0 }

        return when {
            bestVideo != null && bestAudio != null -> SourceDecision.Progressive(
                videoUrl  = bestVideo.url,
                videoMime = bestVideo.mimeType,
                audioUrl  = bestAudio.url,
                audioMime = bestAudio.mimeType,
            )
            bestVideo != null -> SourceDecision.Progressive(
                videoUrl  = bestVideo.url,
                videoMime = bestVideo.mimeType,
                audioUrl  = null,
                audioMime = null,
            )
            else -> SourceDecision.None("NO_PLAYABLE_STREAM")
        }
    }

    // -------------------------------------------------------------------------
    // Pure MIME helper
    // -------------------------------------------------------------------------

    /**
     * Map a subtitle format string to the corresponding Media3 MIME type constant.
     * Returns `null` for unknown/unsupported formats — callers should skip those tracks.
     *
     * This function is **pure** and covered by unit tests.
     */
    fun subtitleMimeType(format: String?): String? = when {
        format == null                               -> null
        format.equals("vtt", ignoreCase = true)     -> MimeTypes.TEXT_VTT
        format.equals("webvtt", ignoreCase = true)  -> MimeTypes.TEXT_VTT
        format.equals("ttml", ignoreCase = true)    -> MimeTypes.APPLICATION_TTML
        format.equals("srt", ignoreCase = true)     -> MimeTypes.APPLICATION_SUBRIP
        // srv1/srv2/srv3 are YouTube's XML caption format — no standard MIME, cannot be parsed
        // by Media3's subtitle pipeline. Return null so callers skip these tracks.
        format.startsWith("srv", ignoreCase = true) -> null
        else                                        -> null
    }

    // -------------------------------------------------------------------------
    // Integration: build a MediaSource (not unit-tested — needs Media3 runtime)
    // -------------------------------------------------------------------------

    /** The built source plus the decision that produced it, so callers can map metadata. */
    data class BuiltSource(val source: MediaSource, val decision: SourceDecision)

    /**
     * Build a Media3 [MediaSource] for [resolved] (wrapped in a [BuiltSource] alongside the
     * [SourceDecision] that produced it), or `null` if no playable source exists.
     *
     * When [forceProgressive] is true the decision skips LocalDash MPD generation (see [decide]).
     *
     * **Subtitle side-loading**: `MediaItem.setSubtitleConfigurations` is silently ignored by
     * `DashMediaSource.Factory`, `HlsMediaSource.Factory`, and `ProgressiveMediaSource.Factory`
     * — only `DefaultMediaSourceFactory` merges side-load subtitle sources automatically.
     * Because we use the specific factories here, we build [SingleSampleMediaSource] per subtitle
     * track and merge them with the main source via [MergingMediaSource], mirroring the approach
     * in the legacy factory (now removed).  Tracks whose format maps to
     * `null` from [subtitleMimeType] (e.g. srv1/srv2/srv3) are silently skipped.
     *
     * This method is **not unit-tested** — it depends on Android/Media3 runtime.
     */
    fun build(resolved: ResolvedStreams, forceProgressive: Boolean = false): BuiltSource? {
        // Web-sourced dub audio (different client UA + pot) can't live in the synthetic MPD — build
        // it as a separate progressive source and MergingMediaSource it onto the video+audio source.
        // The fragment selects it via setPreferredAudioLanguage(dub). The default path is untouched.
        if (isWebDubMerge(resolved)) {
            // Architecture B: if the dub carries DASH SegmentBase ranges, inject it into the synthetic
            // MPD (one DASH source, unified buffering — no MergingMediaSource stall/flap, seekable).
            // Fall back to the progressive merge when the dub lacks ranges or MPD generation fails.
            val dub = resolved.audioTracks.firstOrNull {
                it.source == AudioTrackSource.WEB_DUB && it.url.isNotEmpty()
            }
            if (!forceProgressive && dub?.syntheticDashMetadata?.hasValidRanges() == true) {
                buildDubViaMpd(resolved)?.let { return it }
            }
            return buildWithWebDubAudio(resolved, forceProgressive)
        }
        val factory = dataSourceFactoryProvider.forStreams(resolved)

        val decision = decide(resolved, forceProgressive)
        if (decision is SourceDecision.Progressive) {
            if (forceProgressive) {
                Log.d(TAG, "Building progressive by request (forceProgressive; MPD skipped) (audioUrl=${decision.audioUrl != null})")
            } else {
                Log.d(TAG, "MPD generation failed or ineligible, falling back to progressive (audioUrl=${decision.audioUrl != null})")
            }
        }

        val mainSource: MediaSource = when (decision) {
            is SourceDecision.LocalDash -> {
                Log.d(TAG, "Building LocalDash MediaSource (MPD data URI)")
                // Known gap (tracked follow-up, intentionally not handled here):
                // this synchronous LocalDash path serves the MPD inline as a
                // base64 `data:` URI and does NOT register it in
                // SyntheticDashMpdRegistry. As a result the proactive
                // MpdTtlWatcher only arms when StreamPrefetchService already
                // pre-registered this videoId (the common path — prefetch is on
                // by default). A cold, non-prefetched open has no registry entry,
                // so it falls back to reactive 403 re-resolve when segment URLs
                // expire. Registering here is deferred; do not implement it in
                // this branch.
                DashMediaSource.Factory(factory)
                    .createMediaSource(mediaItem(decision.dataUri, MimeTypes.APPLICATION_MPD))
            }

            is SourceDecision.ServerDash -> {
                if (BuildConfig.DEBUG) Log.d(TAG, "Building ServerDash MediaSource: ${decision.url}")
                DashMediaSource.Factory(factory)
                    .createMediaSource(mediaItem(decision.url, MimeTypes.APPLICATION_MPD))
            }

            is SourceDecision.Hls -> {
                if (BuildConfig.DEBUG) Log.d(TAG, "Building HLS MediaSource: ${decision.url}")
                HlsMediaSource.Factory(factory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(mediaItem(decision.url, MimeTypes.APPLICATION_M3U8))
            }

            is SourceDecision.Progressive -> {
                Log.d(TAG, "Building Progressive MediaSource (audio=${decision.audioUrl != null})")
                val videoSource = ProgressiveMediaSource.Factory(factory)
                    .createMediaSource(mediaItem(decision.videoUrl, decision.videoMime))

                if (decision.audioUrl != null) {
                    val audioSource = ProgressiveMediaSource.Factory(factory)
                        .createMediaSource(mediaItem(decision.audioUrl, decision.audioMime))
                    MergingMediaSource(videoSource, audioSource)
                } else {
                    videoSource
                }
            }

            is SourceDecision.None -> {
                Log.w(TAG, "No playable source: ${decision.reason}")
                return null
            }
        }

        return BuiltSource(
            // Live streams: never side-load captions. Merging a SingleSampleMediaSource
            // (fixed length, C.TIME_UNSET) into a dynamic/live timeline risks
            // IllegalMergeException. Since the ANDROID_VR removal every resolve is a NewPipe
            // one, so this guards the main path's live streams that expose captionTracks.
            // Passing emptyList makes wrapWithSideLoadSubtitles return primary unwrapped.
            source = wrapWithSideLoadSubtitles(
                mainSource,
                if (resolved.isLive) emptyList() else resolved.subtitleTracks,
                factory,
            ),
            decision = decision,
        )
    }

    /**
     * Merge a web-sourced dub audio track onto the VR video+audio source. The VR source is built by
     * recursing with the WEB_DUB tracks filtered out (so it takes the normal MPD path); the dub is a
     * separate web-UA progressive source. Returns null if the VR source can't be built.
     */
    private fun buildWithWebDubAudio(resolved: ResolvedStreams, forceProgressive: Boolean): BuiltSource? {
        val webDub = resolved.audioTracks.first { it.source == AudioTrackSource.WEB_DUB && it.url.isNotEmpty() }
        val vrResolved = resolved.copy(
            audioTracks = resolved.audioTracks.filter { it.source != AudioTrackSource.WEB_DUB }
        )
        val vrBuilt = build(vrResolved, forceProgressive) ?: return null
        val dubAudio = ProgressiveMediaSource.Factory(dataSourceFactoryProvider.forWebDub())
            .createMediaSource(mediaItem(webDub.url, webDub.mimeType))
        Log.d("DubFlow", "Merging web dub audio lang=${webDub.language} urlLen=${webDub.url.length} onto VR source")
        // adjustPeriodTimeOffsets + clipDurations tolerate slight video/audio duration mismatch.
        val merged = MergingMediaSource(true, true, vrBuilt.source, dubAudio)
        return BuiltSource(merged, vrBuilt.decision)
    }

    /**
     * Architecture B: build ONE DASH source whose MPD already contains the dub as a language-tagged
     * audio AdaptationSet (next to VR video + VR original audio). [MultiRepresentationMpdGenerator]
     * includes every audio track with valid SegmentBase ranges, so the resolved dub — kept IN
     * resolved.audioTracks here (the merge path strips it) — becomes its own `<AdaptationSet lang="…">`.
     * One timeline, one buffer ⇒ no MergingMediaSource stall/flap, and it's seekable. Segment UAs are
     * routed per `c=` param by [SegmentDataSourceFactoryProvider.forDubMpd]; the fragment's
     * `setPreferredAudioLanguage(dubLang)` selects the dub set. Returns null (→ caller falls back to the
     * progressive merge) if MPD generation isn't possible.
     */
    private fun buildDubViaMpd(resolved: ResolvedStreams): BuiltSource? {
        val decision = decide(resolved, forceProgressive = false)
        if (decision !is SourceDecision.LocalDash) {
            Log.d(TAG, "dub MPD injection ineligible (decision=$decision); falling back to merge")
            return null
        }
        Log.d("DubFlow", "dub via MPD injection (architecture B) — single DASH source")
        val factory = dataSourceFactoryProvider.forDubMpd(resolved)
        val source = DashMediaSource.Factory(factory)
            .createMediaSource(mediaItem(decision.dataUri, MimeTypes.APPLICATION_MPD))
        return BuiltSource(
            wrapWithSideLoadSubtitles(
                source,
                if (resolved.isLive) emptyList() else resolved.subtitleTracks,
                factory,
            ),
            decision,
        )
    }

    /** Build an audio-only progressive source (background/audio-only mode) using the UA-correct factory. */
    fun buildAudioOnly(resolved: ResolvedStreams): MediaSource? {
        val audio = resolved.audioTracks.maxByOrNull { it.bitrate ?: 0 }
            ?: resolved.audioTracks.firstOrNull()
            ?: return null
        val factory = dataSourceFactoryProvider.forStreams(resolved)
        return ProgressiveMediaSource.Factory(factory)
            .createMediaSource(mediaItem(audio.url, audio.mimeType))
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Wrap [primary] with side-loaded subtitle [SingleSampleMediaSource]s so ExoPlayer
     * actually sees them.
     *
     * `DashMediaSource.Factory`, `HlsMediaSource.Factory`, and `ProgressiveMediaSource.Factory`
     * all ignore `MediaItem.SubtitleConfiguration`; only `DefaultMediaSourceFactory` does the
     * merge automatically.  We replicate that merge here, matching
     * the legacy factory's side-load merge exactly (now removed).
     *
     * Tracks whose format has no Media3 MIME (srv1/srv2/srv3 or unknown) are skipped — the
     * subtitle pipeline cannot parse them without a known hint.
     *
     * Role flags: human-authored = [C.ROLE_FLAG_SUBTITLE]; auto-generated also gets
     * [C.ROLE_FLAG_DESCRIBES_VIDEO].
     */
    private fun wrapWithSideLoadSubtitles(
        primary: MediaSource,
        subtitleTracks: List<SubtitleTrack>,
        factory: androidx.media3.datasource.DataSource.Factory,
    ): MediaSource {
        if (subtitleTracks.isEmpty()) return primary
        val subtitleSources = subtitleTracks.mapNotNull { track ->
            // Skip unparseable formats (srv1/srv2/srv3 or unknown) before building anything.
            val mimeType = subtitleMimeType(track.format) ?: return@mapNotNull null
            val roleFlags = if (track.isAutoGenerated) {
                C.ROLE_FLAG_SUBTITLE or C.ROLE_FLAG_DESCRIBES_VIDEO
            } else {
                C.ROLE_FLAG_SUBTITLE
            }
            val config = MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.url))
                .setMimeType(mimeType)
                .setLanguage(track.languageCode)
                .setLabel(track.languageName)
                .setRoleFlags(roleFlags)
                .build()
            SingleSampleMediaSource.Factory(factory)
                // A side-loaded caption that fails to load (expired/404/garbage
                // timedtext URL) must never become a fatal player error — captions
                // are non-essential. Treat load errors as end-of-stream so a bad
                // track silently shows nothing instead of killing playback. This
                // matters because captions are populated for the primary path, so
                // this merge is hot for most videos.
                .setTreatLoadErrorsAsEndOfStream(true)
                .createMediaSource(config, C.TIME_UNSET)
        }
        return if (subtitleSources.isEmpty()) primary
        else MergingMediaSource(primary, *subtitleSources.toTypedArray())
    }

    private fun mediaItem(
        uri: String,
        mime: String?,
    ): MediaItem = MediaItem.Builder()
        .setUri(Uri.parse(uri))
        .apply { if (mime != null) setMimeType(mime) }
        .build()
}
