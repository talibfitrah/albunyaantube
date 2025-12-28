package com.albunyaan.tube.player

import com.albunyaan.tube.data.extractor.QualitySelectionOrigin
import com.albunyaan.tube.data.extractor.VideoTrack

/**
 * Production component for determining MediaSource cache-hit eligibility.
 *
 * This component encapsulates the decision logic for whether a prepared MediaSource
 * can be reused (cache-hit) or needs to be rebuilt (cache-miss).
 *
 * **Key Invariants:**
 * - SYNTH_ADAPTIVE: Cache-hit depends ONLY on (streamId, audioOnly). Quality cap changes
 *   do NOT require MediaSource rebuild because the MPD contains ALL quality levels.
 *   `factorySelectedVideoTrack` is intentionally null for ABR sources.
 *
 * - SYNTHETIC_DASH: Cache-hit requires comparing factory-selected track against requested
 *   track because the MPD contains a single representation.
 *
 * - HLS/DASH: Cache-hit compares prepared URL against adaptive URL.
 *
 * **Scope Limitation:**
 * This decider is designed for SYNTHETIC (SYNTH_ADAPTIVE, SYNTHETIC_DASH) cache-hit decisions.
 * The "standard" fallback branch (for non-synthetic HLS/DASH/progressive) performs simple
 * URL or key-match comparison. It is NOT a full replacement for PlayerFragment's progressive
 * quality selection logic, which handles selectionOrigin-based track comparisons, manual
 * progressive quality switches, and other non-synthetic scenarios. Do NOT refactor
 * PlayerFragment to use this decider for general progressive decisions without first
 * extending evaluate() to fully encode those rules.
 *
 * @see PlayerFragment.checkCacheHit (uses this component for synthetic types only)
 */
object CacheHitDecider {

    /**
     * Result of cache-hit evaluation.
     */
    sealed class Result {
        /**
         * Cache miss - MediaSource must be rebuilt.
         */
        object Miss : Result()

        /**
         * Cache hit - MediaSource can be reused.
         * @param qualityCapToApply The quality cap to apply via track selector (null = no cap)
         */
        data class Hit(val qualityCapToApply: Int?) : Result()
    }

    /**
     * Input state representing the currently prepared MediaSource.
     */
    data class PreparedState(
        val streamKey: Pair<String, Boolean>?, // (streamId, audioOnly)
        val streamUrl: String?,
        val adaptiveType: MediaSourceResult.AdaptiveType?,
        val qualityCapHeight: Int?,
        val factorySelectedVideoTrack: VideoTrack?
    )

    /**
     * Input state representing the requested playback.
     */
    data class RequestedState(
        val streamKey: Pair<String, Boolean>, // (streamId, audioOnly)
        val audioOnly: Boolean,
        val forceProgressive: Boolean,
        val qualityCapHeight: Int?,
        val selectionOrigin: QualitySelectionOrigin,
        val requestedVideoTrack: VideoTrack?,
        val wouldUseAdaptive: Boolean,
        val adaptiveUrl: String?
    )

    /**
     * Determine if the prepared MediaSource can be reused for the requested playback.
     *
     * This is the production implementation that PlayerFragment.checkCacheHit() delegates to.
     *
     * @param prepared The currently prepared MediaSource state
     * @param requested The requested playback state
     * @return Result.Hit if reusable (with optional cap to apply), Result.Miss if rebuild needed
     */
    fun evaluate(prepared: PreparedState, requested: RequestedState): Result {
        // Not the same key - must prepare
        if (prepared.streamKey != requested.streamKey || prepared.streamUrl == null) {
            return Result.Miss
        }

        // Handle SYNTH_ADAPTIVE (ABR with track selector constraints)
        if (prepared.adaptiveType == MediaSourceResult.AdaptiveType.SYNTH_ADAPTIVE &&
            !requested.audioOnly && !requested.forceProgressive
        ) {
            return evaluateSynthAdaptive(prepared, requested)
        }

        // Handle SYNTHETIC_DASH (single-rep, factory pre-selects track)
        if (prepared.adaptiveType == MediaSourceResult.AdaptiveType.SYNTHETIC_DASH &&
            !requested.audioOnly && !requested.forceProgressive
        ) {
            return evaluateSyntheticDash(prepared, requested)
        }

        // Standard adaptive (HLS/DASH) or progressive comparison
        return if (requested.wouldUseAdaptive) {
            // Would use adaptive - compare URLs
            if (prepared.streamUrl == requested.adaptiveUrl) {
                Result.Hit(null)
            } else {
                Result.Miss
            }
        } else {
            // Would use progressive - already checked key match above
            Result.Hit(null)
        }
    }

    /**
     * Evaluate cache-hit for SYNTH_ADAPTIVE sources.
     *
     * **Critical invariant:** SYNTH_ADAPTIVE cache-hit NEVER depends on factorySelectedVideoTrack.
     * The MPD includes ALL quality levels (full ladder), so quality cap changes do NOT require
     * MediaSource rebuild. Quality is enforced ONLY via track selector (CAP/LOCK mode).
     *
     * This prevents the perpetual cache-miss loop that occurred when MANUAL selection
     * tried to compare against null factorySelectedVideoTrack.
     */
    private fun evaluateSynthAdaptive(
        prepared: PreparedState,
        requested: RequestedState
    ): Result {
        // SYNTH_ADAPTIVE cache-hit logic:
        // The MPD includes ALL quality levels (full ladder), so quality cap changes
        // do NOT require MediaSource rebuild. This enables instant quality switching.
        //
        // Quality cap is enforced ONLY via track selector (CAP/LOCK mode):
        // - CAP (AUTO/AUTO_RECOVERY): ExoPlayer can select any quality up to cap
        // - LOCK (MANUAL): ExoPlayer locked to specific quality
        //
        // Cache-hit always returns Hit with the cap to apply (may be different from prepared).
        // The caller re-applies track selector constraints with correct mode.
        //
        // NOTE: factorySelectedVideoTrack is intentionally IGNORED here because
        // it's null by design for ABR sources (ABR selects at playback time).

        val capToApply = if (requested.qualityCapHeight != prepared.qualityCapHeight) {
            // Cap changed - still a Hit, but apply new cap to track selector
            requested.qualityCapHeight
        } else if (requested.qualityCapHeight != null) {
            // Same cap but might need mode change (LOCK↔CAP)
            requested.qualityCapHeight
        } else {
            // No cap - clear constraints
            null
        }
        return Result.Hit(capToApply)
    }

    /**
     * Evaluate cache-hit for SYNTHETIC_DASH sources (single-rep).
     *
     * Unlike SYNTH_ADAPTIVE, SYNTHETIC_DASH has a single representation and the factory
     * pre-selects a specific video track. Cache-hit requires comparing the factory-selected
     * track against the requested track.
     *
     * **Important:** Returns Hit(null) on cache-hit because track selector constraints
     * should NOT be applied to single-rep sources (there's only one representation).
     * Applying constraints to a single-rep source could accidentally exclude the only
     * playable track.
     */
    private fun evaluateSyntheticDash(
        prepared: PreparedState,
        requested: RequestedState
    ): Result {
        // Quality cap change - factory uses this for track selection, must rebuild
        if (requested.qualityCapHeight != prepared.qualityCapHeight) {
            return Result.Miss
        }

        // MANUAL or AUTO_RECOVERY origin detection:
        // The factory selects a video-only track (stored in factorySelectedVideoTrack)
        // which differs from selection.video (often a muxed track). We can't compare URLs directly.
        when (requested.selectionOrigin) {
            QualitySelectionOrigin.MANUAL -> {
                // MANUAL: User explicitly selected a resolution. Compare heights only - the user selected
                // a muxed track (e.g., 720p muxed) but factory uses video-only (720p video-only). These have
                // different bitrates by nature, so bitrate comparison would cause rebuild loops.
                val requestedVideo = requested.requestedVideoTrack ?: return Result.Miss
                val preparedVideo = prepared.factorySelectedVideoTrack ?: return Result.Miss
                val requestedHeight = requestedVideo.height
                val preparedHeight = preparedVideo.height
                // If heights differ or either is null (missing track info), must rebuild for safety.
                if (requestedHeight == null || preparedHeight == null || requestedHeight != preparedHeight) {
                    return Result.Miss
                }
                // Heights match - MANUAL selection at same resolution is idempotent
            }
            QualitySelectionOrigin.AUTO_RECOVERY -> {
                // AUTO_RECOVERY: System downshift via QualityStepDownHelper. This may select same-height/
                // lower-bitrate tracks, so we must compare both height AND bitrate to detect the change.
                val requestedVideo = requested.requestedVideoTrack ?: return Result.Miss
                val preparedVideo = prepared.factorySelectedVideoTrack ?: return Result.Miss
                val requestedHeight = requestedVideo.height
                val preparedHeight = preparedVideo.height
                if (requestedHeight == null || preparedHeight == null || requestedHeight != preparedHeight) {
                    return Result.Miss
                }
                // Also compare bitrate for AUTO_RECOVERY - only if both are valid (>0).
                // If either is null/0 (missing bitrate info), treat as match to avoid unnecessary rebuilds.
                // This matches prior inline logic behavior.
                val requestedBitrate = requestedVideo.bitrate
                val preparedBitrate = preparedVideo.bitrate
                if (requestedBitrate != null && requestedBitrate > 0 &&
                    preparedBitrate != null && preparedBitrate > 0 &&
                    requestedBitrate != preparedBitrate
                ) {
                    return Result.Miss
                }
            }
            QualitySelectionOrigin.AUTO -> {
                // AUTO: No specific track requested, use whatever is prepared
            }
        }

        // SYNTHETIC_DASH is single-rep: return Hit(null) to prevent track selector
        // constraints being applied (would risk excluding the only playable track).
        return Result.Hit(null)
    }
}
