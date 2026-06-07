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
     * The video track actually selected by the factory for progressive/synthetic DASH sources.
     * Used by proactive downshift to know the true "current" quality (may differ from selection.video
     * when factory applies cold-start quality selection in AUTO mode).
     * Null for adaptive HLS/DASH (ABR handles quality) or audio-only mode.
     */
    val selectedVideoTrack: VideoTrack? = null
) {
    enum class AdaptiveType { NONE, HLS, DASH, SYNTHETIC_DASH, SYNTH_ADAPTIVE }
}
