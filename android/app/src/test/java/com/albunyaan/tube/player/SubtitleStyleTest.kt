package com.albunyaan.tube.player

import android.text.Layout
import androidx.media3.common.text.Cue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks the cue-geometry normalisation that fixes captions silently failing to
 * render and the vertical placement that keeps them clear of each player's
 * bottom chrome.
 *
 * Horizontal: ExoPlayer's `SubtitlePainter` skips a cue ("insufficient space")
 * when the computed width is <= 0 — YouTube auto-generated cues carry
 * `align:start position:0%`, which drives that width negative.
 * [normalizeSubtitleCue] forces every cue to full-width / centre so it always
 * has room.
 *
 * Vertical: the cue's `line` MUST be an explicit fraction (UNSET makes
 * SubtitleView drop the cue, and auto-gen cues arrive top-anchored). The
 * fraction is parameterised so the 16:9 player can sit just off the bottom edge
 * while Shorts clears its taller nav + title overlay band
 * ([captionLineFractionForClearance]).
 *
 * Robolectric is required: `Cue.Builder` + `Layout.Alignment` are Android types.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SubtitleStyleTest {

    @Test
    fun `normalizeSubtitleCue pins an auto-generated cue to full width at the given line`() {
        // Mirrors a YouTube ASR cue: top-aligned, position 0%, start-anchored, narrow.
        val input = Cue.Builder()
            .setText("Oh Allah, strengthen Islam")
            .setLine(0f, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_START)
            .setPosition(0f)
            .setPositionAnchor(Cue.ANCHOR_TYPE_START)
            .setSize(0.3f)
            .setTextAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()

        val out = normalizeSubtitleCue(input, 0.70f)

        assertEquals("text is preserved", "Oh Allah, strengthen Islam", out.text.toString())
        assertEquals("line uses the supplied fraction", 0.70f, out.line, 0.0001f)
        assertEquals(Cue.LINE_TYPE_FRACTION, out.lineType)
        assertEquals(Cue.ANCHOR_TYPE_END, out.lineAnchor)
        assertEquals("centred horizontally", 0.5f, out.position, 0.0001f)
        assertEquals(Cue.ANCHOR_TYPE_MIDDLE, out.positionAnchor)
        assertEquals("full width — always room to draw", 1f, out.size, 0.0001f)
        assertEquals(Layout.Alignment.ALIGN_CENTER, out.textAlignment)
    }

    @Test
    fun `normalizeSubtitleCue default lifts the caption off the very bottom edge`() {
        // The 16:9 player default must leave a margin so captions don't touch the
        // bottom edge in landscape fullscreen (was 0.92 → "touching").
        val out = normalizeSubtitleCue(Cue.Builder().setText("x").build())

        assertEquals(PLAYER_CAPTION_LINE_FRACTION, out.line, 0.0001f)
        assertEquals(Cue.ANCHOR_TYPE_END, out.lineAnchor)
        // Regression guard: the default must clear the edge.
        assert(out.line <= 0.88f) { "default line fraction ${out.line} too close to the bottom edge" }
    }

    @Test
    fun `normalizeSubtitleCue keeps a plain manual cue renderable`() {
        val input = Cue.Builder().setText("اللهم أعزَّ الإسلام").build()

        val out = normalizeSubtitleCue(input, 0.80f)

        assertEquals("اللهم أعزَّ الإسلام", out.text.toString())
        assertEquals(0.5f, out.position, 0.0001f)
        assertEquals(1f, out.size, 0.0001f)
        assertEquals(Cue.ANCHOR_TYPE_END, out.lineAnchor)
    }

    @Test
    fun `captionLineFractionForClearance reserves the clearance below the caption`() {
        // 250px clearance out of a 1000px-tall view → caption bottom at 75% down.
        assertEquals(0.75f, captionLineFractionForClearance(1000, 250), 0.0001f)
    }

    @Test
    fun `captionLineFractionForClearance falls back when the view is unmeasured`() {
        // height 0 (not laid out yet) must not divide-by-zero / produce NaN.
        val f = captionLineFractionForClearance(0, 216)
        assert(f in 0.5f..0.92f) { "fallback fraction $f out of range" }
    }

    @Test
    fun `captionLineFractionForClearance clamps an over-large clearance on a short view`() {
        // clearance >= height would push the fraction <= 0; must clamp to the floor.
        val f = captionLineFractionForClearance(200, 320)
        assertEquals(0.55f, f, 0.0001f)
    }

    @Test
    fun `captionLineFractionForClearance clamps a tiny clearance to the ceiling`() {
        // clearance tiny → fraction ~0.99; must clamp to the ceiling so a cue never
        // sits flush against the very bottom edge.
        val f = captionLineFractionForClearance(1000, 5)
        assertEquals(0.92f, f, 0.0001f)
    }
}
