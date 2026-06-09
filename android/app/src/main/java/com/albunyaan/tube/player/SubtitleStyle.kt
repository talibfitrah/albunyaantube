package com.albunyaan.tube.player

import android.text.Layout
import android.util.TypedValue
import androidx.media3.common.text.Cue
import androidx.media3.ui.SubtitleView

/**
 * Caption rendering config for side-loaded subtitles, shared by the main player
 * and Shorts. Fixes the render-layer bugs found on-device (SM-S938U, logcat
 * `W/SubtitlePainter: Skipped drawing subtitle cue (insufficient space)`) plus
 * the vertical-placement follow-ups:
 *
 * 1. **Oversized text.** ExoPlayer's default caption size is FRACTIONAL
 *    (~5.3% of the view height). On a full-height Shorts [SubtitleView] that is
 *    ~44sp — captions render huge, and because the text size feeds
 *    `SubtitlePainter`'s available-width check, oversized text alone can push it
 *    to skip cues. [applyCaptionStyle] pins a fixed, readable size.
 * 2. **Auto-generated cue geometry.** YouTube auto-gen cues carry
 *    `align:start position:0%`; that geometry drives the computed cue width <= 0
 *    so `SubtitlePainter` skips drawing them ("insufficient space"). [normalizeSubtitleCue]
 *    strips the embedded horizontal geometry and pins every cue to full width.
 * 3. **Vertical placement.** The cue's `line` MUST stay an explicit fraction —
 *    `DIMEN_UNSET` makes [SubtitleView] drop the cue, and auto-gen cues arrive
 *    top-anchored. The fraction is parameterised because the two players have
 *    different bottom chrome: the 16:9 player only needs to clear the screen
 *    edge ([PLAYER_CAPTION_LINE_FRACTION]); Shorts must clear its taller nav +
 *    title overlay band ([captionLineFractionForClearance]).
 */

/** Fixed caption text size. Predictable across the 16:9 player and full-height Shorts. */
private const val CAPTION_TEXT_SIZE_SP = 18f

/**
 * Default bottom line fraction for the 16:9 player: caption bottom at 85% down,
 * leaving a 15% margin so captions never touch the bottom edge in landscape
 * fullscreen (the previous 0.92 read as "touching").
 */
const val PLAYER_CAPTION_LINE_FRACTION = 0.85f

/** Used when a SubtitleView hasn't been measured yet (height == 0). */
private const val FALLBACK_LINE_FRACTION = 0.75f

/** Clamp window: never above the upper third, never flush against the bottom edge. */
private const val MIN_LINE_FRACTION = 0.55f
private const val MAX_LINE_FRACTION = 0.92f

/**
 * Configure a [SubtitleView] for readable, consistent captions: a fixed text size
 * (overriding the oversized fractional default) and embedded styles off (so the
 * auto-gen karaoke highlight / embedded font sizes don't override it).
 */
fun SubtitleView.applyCaptionStyle() {
    setApplyEmbeddedStyles(false)
    setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, CAPTION_TEXT_SIZE_SP)
}

/**
 * Pin a decoded [cue] to full width at the given [lineFraction] from the top
 * (anchored at the cue's bottom edge), discarding the track's embedded horizontal
 * geometry (`position`/`size`/`align`). YouTube auto-gen cues use
 * `align:start position:0%`, which — combined with a large text size — makes
 * `SubtitlePainter` compute a non-positive width and skip the cue. Forcing centre
 * + full width guarantees room to draw; the caller supplies [lineFraction] so each
 * player can clear its own bottom chrome.
 *
 * Centre alignment is deliberate for caption cues and matches YouTube's own
 * rendering. It is intentionally exempt from the app's `viewStart` RTL rule, which
 * governs UI chrome — not video captions — and it does not reverse Arabic bidi
 * text: [SubtitleView]/[Layout] still resolve glyph direction within the cue.
 */
fun normalizeSubtitleCue(cue: Cue, lineFraction: Float = PLAYER_CAPTION_LINE_FRACTION): Cue =
    cue.buildUpon()
        .setLine(lineFraction, Cue.LINE_TYPE_FRACTION)
        .setLineAnchor(Cue.ANCHOR_TYPE_END)
        .setPosition(0.5f)
        .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
        .setSize(1f)
        .setTextAlignment(Layout.Alignment.ALIGN_CENTER)
        .build()

/**
 * Bottom-anchored line fraction that reserves [bottomClearancePx] of space below
 * the caption, scaled to the actual [viewHeightPx]. Lets Shorts captions clear the
 * app nav bar + title overlay band on any device height instead of a fixed
 * fraction. Falls back to [FALLBACK_LINE_FRACTION] before the view is measured and
 * clamps to [MIN_LINE_FRACTION]..[MAX_LINE_FRACTION] so the cue is always on-screen.
 *
 * Edge case: when the requested clearance exceeds the view height (a very short
 * Shorts viewport, e.g. split-screen landscape), the lower clamp wins and the
 * caption sits at [MIN_LINE_FRACTION]. That is a degraded "stay on-screen and
 * readable" fallback — NOT a guarantee that the nav/title band is cleared.
 */
fun captionLineFractionForClearance(viewHeightPx: Int, bottomClearancePx: Int): Float =
    if (viewHeightPx <= 0) {
        FALLBACK_LINE_FRACTION
    } else {
        (1f - bottomClearancePx.toFloat() / viewHeightPx).coerceIn(MIN_LINE_FRACTION, MAX_LINE_FRACTION)
    }
