package com.albunyaan.tube.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
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
 * are fully covered by [DashSourceBuilderTest].  Only [build] touches runtime APIs.
 *
 * This class is intentionally **not wired into any Fragment/ViewModel yet** — that happens
 * in a later task.
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
     * This function is **pure** — it makes no I/O calls, uses no Android APIs,
     * and can be exercised directly in JVM unit tests.
     */
    fun decide(resolved: ResolvedStreams): SourceDecision {
        if (resolved.isLive) {
            return when {
                resolved.dashUrl != null -> SourceDecision.ServerDash(resolved.dashUrl)
                resolved.hlsUrl != null  -> SourceDecision.Hls(resolved.hlsUrl)
                else                     -> SourceDecision.None("LIVE_NO_MANIFEST")
            }
        }

        // VOD — try multi-rep MPD first
        val mpdResult = mpdGenerator.generateMpd(resolved)
        if (mpdResult is MultiRepresentationMpdGenerator.Result.Success) {
            return SourceDecision.LocalDash(mpdResult.mpdDataUri)
        }

        // Progressive fallback — prefer a muxed track (has built-in audio: itag 18/22)
        val muxed = resolved.videoTracks.firstOrNull { !it.isVideoOnly }
        if (muxed != null) {
            return SourceDecision.Progressive(
                videoUrl  = muxed.url,
                videoMime = muxed.mimeType,
                audioUrl  = null,
                audioMime = null,
            )
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
        format.equals("ttml", ignoreCase = true)    -> MimeTypes.APPLICATION_TTML
        format.equals("srt", ignoreCase = true)     -> MimeTypes.APPLICATION_SUBRIP
        format.startsWith("srv", ignoreCase = true) -> MimeTypes.APPLICATION_SUBRIP
        else                                        -> null
    }

    // -------------------------------------------------------------------------
    // Integration: build a MediaSource (not unit-tested — needs Media3 runtime)
    // -------------------------------------------------------------------------

    /**
     * Build a Media3 [MediaSource] for [resolved], or `null` if no playable source exists.
     *
     * Subtitles are embedded directly in the [MediaItem] `SubtitleConfiguration` list;
     * Media3's source factories pick them up automatically.  Tracks whose format yields
     * a `null` MIME from [subtitleMimeType] are silently skipped.
     *
     * This method is **not unit-tested** — it depends on Android/Media3 runtime.
     */
    fun build(resolved: ResolvedStreams): MediaSource? {
        val factory = dataSourceFactoryProvider.forStreams(resolved)

        val subs: List<MediaItem.SubtitleConfiguration> = resolved.subtitleTracks.mapNotNull { track ->
            val mime = subtitleMimeType(track.format) ?: return@mapNotNull null
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.url))
                .setMimeType(mime)
                .setLanguage(track.languageCode)
                .build()
        }

        val decision = decide(resolved)
        if (decision is SourceDecision.Progressive) {
            Log.d(TAG, "MPD generation failed or ineligible, falling back to progressive (audioUrl=${decision.audioUrl != null})")
        }
        return when (decision) {
            is SourceDecision.LocalDash -> {
                Log.d(TAG, "Building LocalDash MediaSource (MPD data URI)")
                DashMediaSource.Factory(factory)
                    .createMediaSource(mediaItem(decision.dataUri, MimeTypes.APPLICATION_MPD, subs))
            }

            is SourceDecision.ServerDash -> {
                Log.d(TAG, "Building ServerDash MediaSource: ${decision.url}")
                DashMediaSource.Factory(factory)
                    .createMediaSource(mediaItem(decision.url, MimeTypes.APPLICATION_MPD, subs))
            }

            is SourceDecision.Hls -> {
                Log.d(TAG, "Building HLS MediaSource: ${decision.url}")
                HlsMediaSource.Factory(factory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(mediaItem(decision.url, MimeTypes.APPLICATION_M3U8, subs))
            }

            is SourceDecision.Progressive -> {
                Log.d(TAG, "Building Progressive MediaSource (audio=${decision.audioUrl != null})")
                val videoSource = ProgressiveMediaSource.Factory(factory)
                    .createMediaSource(mediaItem(decision.videoUrl, decision.videoMime, subs))

                if (decision.audioUrl != null) {
                    val audioSource = ProgressiveMediaSource.Factory(factory)
                        .createMediaSource(mediaItem(decision.audioUrl, decision.audioMime, emptyList()))
                    MergingMediaSource(videoSource, audioSource)
                } else {
                    videoSource
                }
            }

            is SourceDecision.None -> {
                Log.w(TAG, "No playable source: ${decision.reason}")
                null
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private helper
    // -------------------------------------------------------------------------

    private fun mediaItem(
        uri: String,
        mime: String?,
        subs: List<MediaItem.SubtitleConfiguration>,
    ): MediaItem = MediaItem.Builder()
        .setUri(Uri.parse(uri))
        .apply { if (mime != null) setMimeType(mime) }
        .setSubtitleConfigurations(subs)
        .build()
}
