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
import com.albunyaan.tube.data.extractor.ExtractionClient
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

        // VOD — try multi-rep adaptive MPD first, UNLESS forced to progressive or the streams came
        // from the NewPipe poToken fallback. The ANDROID_VR primary client's adaptive segments
        // sustain full playback; the fallback clients' adaptive segments do NOT. YouTube only honors
        // our WebView (web-context) poToken for the *initial* range of iOS/android-client GVS
        // segments — sustained adaptive streaming needs native client attestation we can't produce on
        // Android — so an adaptive build on the fallback path plays ~60s then 403s on every later
        // segment (verified on-device + via yt-dlp for One4kids "Zaky's Learning Club", and even for a
        // normal control video). The muxed itag-18/22 progressive stream, always present via the
        // ANDROID client NewPipe fetches, DOES sustain a full ad-free download. So for fallback
        // resolves skip the doomed adaptive manifest and serve progressive directly (360p) — it plays
        // immediately instead of a 60s false-start + 403 recovery loop. ANDROID_VR (the common path)
        // is unaffected and keeps its full HD/4K adaptive ladder.
        val adaptiveCanSustain = resolved.extractionClient == ExtractionClient.ANDROID_VR
        if (!forceProgressive && adaptiveCanSustain) {
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

        // No muxed — best video-only + best audio
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
                Log.d(TAG, "Building ServerDash MediaSource: ${decision.url}")
                DashMediaSource.Factory(factory)
                    .createMediaSource(mediaItem(decision.url, MimeTypes.APPLICATION_MPD))
            }

            is SourceDecision.Hls -> {
                Log.d(TAG, "Building HLS MediaSource: ${decision.url}")
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
            source = wrapWithSideLoadSubtitles(mainSource, resolved.subtitleTracks, factory),
            decision = decision,
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
     * Tracks with a null MIME (srv1/srv2/srv3 or unknown) are skipped — the subtitle pipeline
     * cannot parse them without a known hint.
     */
    private fun wrapWithSideLoadSubtitles(
        primary: MediaSource,
        subtitleTracks: List<SubtitleTrack>,
        factory: androidx.media3.datasource.DataSource.Factory,
    ): MediaSource {
        if (subtitleTracks.isEmpty()) return primary
        val subtitleSources = buildSubtitleConfigurations(subtitleTracks).mapNotNull { config ->
            val mime = config.mimeType
            if (mime.isNullOrBlank()) return@mapNotNull null
            SingleSampleMediaSource.Factory(factory)
                .createMediaSource(config, C.TIME_UNSET)
        }
        return if (subtitleSources.isEmpty()) primary
        else MergingMediaSource(primary, *subtitleSources.toTypedArray())
    }

    /**
     * Build [MediaItem.SubtitleConfiguration] objects for each [SubtitleTrack], including
     * role flags and a human-readable label — matching the legacy factory's behaviour (now removed).
     *
     * Role flags:
     * - Human-authored: [C.ROLE_FLAG_SUBTITLE]
     * - Auto-generated: [C.ROLE_FLAG_SUBTITLE] | [C.ROLE_FLAG_DESCRIBES_VIDEO]
     *
     * Tracks with a null MIME are included in the list (with null mimeType) so the
     * [wrapWithSideLoadSubtitles] filter can skip them cleanly.
     */
    private fun buildSubtitleConfigurations(
        subtitleTracks: List<SubtitleTrack>,
    ): List<MediaItem.SubtitleConfiguration> = subtitleTracks.map { track ->
        val mimeType = subtitleMimeType(track.format)
        val roleFlags = if (track.isAutoGenerated) {
            C.ROLE_FLAG_SUBTITLE or C.ROLE_FLAG_DESCRIBES_VIDEO
        } else {
            C.ROLE_FLAG_SUBTITLE
        }
        MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.url))
            .setMimeType(mimeType)
            .setLanguage(track.languageCode)
            .setLabel(track.languageName)
            .setRoleFlags(roleFlags)
            .build()
    }

    private fun mediaItem(
        uri: String,
        mime: String?,
    ): MediaItem = MediaItem.Builder()
        .setUri(Uri.parse(uri))
        .apply { if (mime != null) setMimeType(mime) }
        .build()
}
