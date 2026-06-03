package com.albunyaan.tube.player

import com.albunyaan.tube.player.MediaSourceResult.AdaptiveType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the 4K-seek quality collapse (ANDROID-PLAYBACK-02).
 *
 * The bug: seeking on a 4K WiFi video (a SYNTH_ADAPTIVE multi-rep synthetic DASH stream)
 * 403'd on the video-only segment URLs; each 403 consumed PlaybackDegradationManager's
 * refresh budget; after a handful of seeks the budget exhausted and the player fell back to
 * a single muxed 360p (itag 18) progressive track — permanently, with the quality menu
 * collapsed to one option.
 *
 * This gate decides when a post-seek error on a multi-quality adaptive stream should be a
 * budget-free transient refresh (rebuild the same adaptive manifest) instead of marching
 * toward the muxed fallback. These tests pin that decision.
 */
class SeekTransientErrorGateTest {

    private var now = 1_000L
    private fun gate(window: Long = 12_000L, maxTransient: Int = 3) =
        SeekTransientErrorGate(windowMs = window, maxTransientRefreshes = maxTransient, clock = { now })

    // --- core behaviour: seek-correlated error on adaptive stream is treated as transient ---

    @Test
    fun `error right after seek on synth-adaptive claims a transient refresh`() {
        val g = gate()
        g.onSeek()
        now += 500 // 403 fires shortly after the seek
        assertTrue(g.tryClaimSeekTransientRefresh(AdaptiveType.SYNTH_ADAPTIVE))
    }

    @Test
    fun `DASH and HLS are also treated as multi-quality adaptive`() {
        gate().let { g -> g.onSeek(); now += 100; assertTrue(g.tryClaimSeekTransientRefresh(AdaptiveType.DASH)) }
        gate().let { g -> g.onSeek(); now += 100; assertTrue(g.tryClaimSeekTransientRefresh(AdaptiveType.HLS)) }
    }

    // --- exclusions: single-quality sources must NOT be diverted (muxed/step-down is correct there) ---

    @Test
    fun `raw progressive NONE never claims a transient refresh`() {
        val g = gate()
        g.onSeek()
        now += 100
        assertFalse(g.tryClaimSeekTransientRefresh(AdaptiveType.NONE))
    }

    @Test
    fun `single-rep SYNTHETIC_DASH never claims a transient refresh`() {
        val g = gate()
        g.onSeek()
        now += 100
        assertFalse(g.tryClaimSeekTransientRefresh(AdaptiveType.SYNTHETIC_DASH))
    }

    // --- not seek-correlated ---

    @Test
    fun `error with no preceding seek is not transient`() {
        val g = gate()
        assertFalse(g.tryClaimSeekTransientRefresh(AdaptiveType.SYNTH_ADAPTIVE))
    }

    @Test
    fun `error long after the seek (outside window) is not transient`() {
        val g = gate(window = 12_000L)
        g.onSeek()
        now += 12_001 // just past the window
        assertFalse(g.tryClaimSeekTransientRefresh(AdaptiveType.SYNTH_ADAPTIVE))
    }

    // --- bound: stop diverting after maxTransientRefreshes so a genuinely broken stream degrades ---

    @Test
    fun `stops claiming after maxTransientRefreshes within one failure episode`() {
        val g = gate(maxTransient = 2)
        g.onSeek()
        now += 100
        assertTrue(g.tryClaimSeekTransientRefresh(AdaptiveType.SYNTH_ADAPTIVE))  // 1
        now += 100
        assertTrue(g.tryClaimSeekTransientRefresh(AdaptiveType.SYNTH_ADAPTIVE))  // 2
        now += 100
        // 3rd within the same episode (no successful playback between) defers to degradation
        assertFalse(g.tryClaimSeekTransientRefresh(AdaptiveType.SYNTH_ADAPTIVE))
    }

    // --- resets ---

    @Test
    fun `successful playback resets the transient allowance`() {
        val g = gate(maxTransient = 1)
        g.onSeek()
        now += 100
        assertTrue(g.tryClaimSeekTransientRefresh(AdaptiveType.SYNTH_ADAPTIVE))
        now += 100
        assertFalse(g.tryClaimSeekTransientRefresh(AdaptiveType.SYNTH_ADAPTIVE)) // exhausted

        g.onPlaybackResumed() // playback recovered; episode over
        g.onSeek()            // a new seek
        now += 100
        assertTrue(g.tryClaimSeekTransientRefresh(AdaptiveType.SYNTH_ADAPTIVE)) // allowed again
    }

    @Test
    fun `successful resume disarms the gate so a programmatic resume-seek cannot re-arm it`() {
        // After a clean resume, an error is seek-transient ONLY if a NEW user seek precedes it.
        // The programmatic seekTo(resumePosition) after a refresh records via onSeek(); if the
        // gate stayed armed across resume, recovery errors would keep bypassing degradation with
        // no user seek. onPlaybackResumed() must clear lastSeekElapsedMs.
        val g = gate()
        g.onSeek()
        now += 100
        assertTrue(g.tryClaimSeekTransientRefresh(AdaptiveType.SYNTH_ADAPTIVE))

        g.onPlaybackResumed()          // clean resume — gate disarms
        now += 100
        // No new user seek: a subsequent error is NOT seek-transient (defers to degradation).
        assertFalse(g.tryClaimSeekTransientRefresh(AdaptiveType.SYNTH_ADAPTIVE))
    }

    @Test
    fun `new stream clears a stale seek so it cannot leak across videos`() {
        val g = gate()
        g.onSeek()
        g.onNewStream() // navigated to a different video
        now += 100
        assertFalse(g.tryClaimSeekTransientRefresh(AdaptiveType.SYNTH_ADAPTIVE))
    }

    @Test
    fun `isMultiQualityAdaptive classifies only multi-rep adaptive types`() {
        assertTrue(SeekTransientErrorGate.isMultiQualityAdaptive(AdaptiveType.SYNTH_ADAPTIVE))
        assertTrue(SeekTransientErrorGate.isMultiQualityAdaptive(AdaptiveType.DASH))
        assertTrue(SeekTransientErrorGate.isMultiQualityAdaptive(AdaptiveType.HLS))
        assertFalse(SeekTransientErrorGate.isMultiQualityAdaptive(AdaptiveType.SYNTHETIC_DASH))
        assertFalse(SeekTransientErrorGate.isMultiQualityAdaptive(AdaptiveType.NONE))
    }
}
