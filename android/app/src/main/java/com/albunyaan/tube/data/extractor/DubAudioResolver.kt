package com.albunyaan.tube.data.extractor

import javax.inject.Inject
import javax.inject.Singleton

/** A web-client audio stream candidate for one language (nsig + pot already applied by NewPipe). */
data class DubStreamCandidate(
    val languageCode: String,
    val bitrate: Int,
    val url: String,
    val mimeType: String?,
)

/**
 * Resolves ONE chosen dub language to a streamable audio [AudioTrack], via a
 * NewPipe web-client extraction (which applies nsig + the videoId-bound web
 * poToken). The VR video stays the playback spine — only the audio is sourced
 * here. See memory/player-dubs-phase2-spike.md.
 */
@Singleton
class DubAudioResolver @Inject constructor() {

    /**
     * Resolve the chosen language's audio stream. Returns null on any failure so
     * the caller can fall back to the VR original audio. Implemented in Task C2.
     */
    suspend fun resolveDubAudio(videoId: String, languageCode: String): AudioTrack? = null // STUB (Task C2)

    companion object {
        /** Pick the highest-bitrate candidate matching [languageCode], or null if absent. */
        fun selectAudioStream(
            candidates: List<DubStreamCandidate>,
            languageCode: String,
        ): DubStreamCandidate? =
            candidates.filter { it.languageCode == languageCode }.maxByOrNull { it.bitrate }
    }
}
