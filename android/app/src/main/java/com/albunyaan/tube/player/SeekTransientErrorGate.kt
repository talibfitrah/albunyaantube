package com.albunyaan.tube.player

import android.os.SystemClock

/**
 * Decides whether a playback error/refresh should be treated as a transient
 * **seek-induced** failure rather than a genuine playback failure.
 *
 * ## Why this exists
 * A 4K video on WiFi plays as a multi-representation synthetic DASH stream
 * (SYNTH_ADAPTIVE) built from YouTube's video-only `googlevideo` URLs. When the
 * user seeks, ExoPlayer issues a far-ahead byte-range request against those URLs,
 * which frequently returns HTTP 403. Each 403 used to consume the old
 * degrade-to-muxed refresh budget; after a handful of seeks the
 * budget exhausted and the player fell back to a single muxed 360p (itag 18)
 * progressive track — permanently, with no path back to adaptive. The visible
 * symptom: seek on a 4K video → video goes blurry and the quality menu collapses
 * to a single "360p" option for the rest of the session.
 *
 * A user seek hitting an expired/range-rejected segment URL is an *expected*,
 * *transient* event on a healthy connection — not a reason to permanently destroy
 * quality. For a multi-quality adaptive stream the correct response is to refresh
 * the URLs and rebuild the **same adaptive** manifest (ABR keeps every quality),
 * NOT to consume the degrade-to-muxed budget.
 *
 * This gate lets [com.albunyaan.tube.ui.player.PlayerFragment] route post-seek
 * errors on adaptive streams through a budget-free refresh, while still falling
 * back to normal degradation if the stream keeps failing past
 * [maxTransientRefreshes] (i.e. the failure is not actually seek-transient).
 *
 * Not thread-safe: all calls happen on the player's main thread.
 *
 * @param windowMs how long after a seek an error still counts as seek-induced.
 *   Covers the seek itself plus a refresh→rebuild→resume-seek cycle.
 * @param maxTransientRefreshes how many budget-free refreshes to allow per
 *   failure episode before deferring to normal degradation. Reset to 0 once
 *   playback resumes ([onPlaybackResumed]) or a new stream starts ([onNewStream]).
 * @param clock monotonic time source (injectable for tests).
 */
class SeekTransientErrorGate(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val maxTransientRefreshes: Int = DEFAULT_MAX_TRANSIENT_REFRESHES,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) {
    /** elapsedRealtime of the last observed seek, or null if none since the last reset. */
    private var lastSeekElapsedMs: Long? = null
    private var transientRefreshCount = 0

    /** Record that a seek (user or programmatic) just occurred. */
    fun onSeek() {
        lastSeekElapsedMs = clock()
    }

    /**
     * Playback resumed successfully — the failure episode is over. Clears the transient
     * count AND the armed seek timestamp: after a clean resume, a later error counts as
     * seek-transient only if a NEW user seek precedes it. Without clearing the timestamp,
     * the programmatic resume-seek (seekTo after a refresh) re-arms the gate and lets
     * recovery errors keep bypassing the degradation budget with no user seek involved.
     */
    fun onPlaybackResumed() {
        lastSeekElapsedMs = null
        transientRefreshCount = 0
    }

    /** A new stream is being prepared — clear all per-stream state. */
    fun onNewStream() {
        lastSeekElapsedMs = null
        transientRefreshCount = 0
    }

    /**
     * Attempt to claim a budget-free "seek transient" refresh for the current error.
     *
     * Returns true (and consumes one transient slot) when ALL hold:
     * - [adaptiveType] is a multi-quality adaptive source (SYNTH_ADAPTIVE / DASH / HLS),
     *   where rebuilding the manifest restores every quality. Single-track sources
     *   (SYNTHETIC_DASH single-rep, NONE/progressive) are excluded — for those, the
     *   muxed/step-down degradation path is the correct recovery.
     * - a seek happened within [windowMs] of now.
     * - fewer than [maxTransientRefreshes] transient refreshes have been claimed in
     *   this episode (so a stream that keeps failing eventually defers to normal
     *   degradation instead of looping forever).
     *
     * When this returns false, the caller should run the normal degradation path.
     */
    fun tryClaimSeekTransientRefresh(adaptiveType: MediaSourceResult.AdaptiveType): Boolean {
        if (!isMultiQualityAdaptive(adaptiveType)) return false
        val last = lastSeekElapsedMs ?: return false
        if (clock() - last > windowMs) return false
        if (transientRefreshCount >= maxTransientRefreshes) return false
        transientRefreshCount++
        return true
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 12_000L
        const val DEFAULT_MAX_TRANSIENT_REFRESHES = 3

        /**
         * Multi-rep adaptive sources whose manifest can be rebuilt to restore the
         * full quality ladder. SYNTHETIC_DASH (single representation) and NONE
         * (raw progressive) are deliberately excluded — they carry one quality, so
         * a rebuild can't restore others and normal degradation applies.
         */
        fun isMultiQualityAdaptive(adaptiveType: MediaSourceResult.AdaptiveType): Boolean =
            adaptiveType == MediaSourceResult.AdaptiveType.SYNTH_ADAPTIVE ||
                adaptiveType == MediaSourceResult.AdaptiveType.DASH ||
                adaptiveType == MediaSourceResult.AdaptiveType.HLS
    }
}
