package com.albunyaan.tube.player

import androidx.media3.exoplayer.source.MediaSource
import com.albunyaan.tube.data.extractor.VideoTrack

/**
 * Result of creating a MediaSource. Contains the source, type info, and actual URL used.
 */
data class MediaSourceResult(
    val source: MediaSource,
    val isAdaptive: Boolean,
    /** The actual manifest/video/audio URL used (for identity tracking). May be null for audio-only mode. */
    val actualSourceUrl: String?,
    /** Which adaptive type was used, if any */
    val adaptiveType: AdaptiveType = AdaptiveType.NONE,
    /**
     * The video track actually served for a progressive source — matched from the
     * [DashSourceBuilder] progressive decision's video URL. Used by cache-hit detection and
     * proactive downshift to know the true "current" quality (may differ from the requested
     * selection.video when adaptive was unavailable and a single progressive track was served).
     * Null for adaptive DASH/HLS (ABR handles quality) or audio-only mode.
     */
    val selectedVideoTrack: VideoTrack? = null
) {
    enum class AdaptiveType { NONE, HLS, DASH, SYNTHETIC_DASH, SYNTH_ADAPTIVE }
}
