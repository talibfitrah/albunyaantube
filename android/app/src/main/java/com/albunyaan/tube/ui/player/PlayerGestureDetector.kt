package com.albunyaan.tube.ui.player

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Gesture detector for player controls:
 * - Double tap left: Seek backward 10 seconds
 * - Double tap right: Seek forward 10 seconds
 * - Double tap center (fullscreen only): Toggle resize mode (ZOOM/FIT)
 *
 * Brightness/volume gestures removed to reduce complexity and touch conflicts.
 * Users can control these via system UI.
 *
 * Note: Zone calculations use the actual touched view's width (passed via viewWidthProvider)
 * rather than screen width, which correctly handles split-screen, multi-window, tablets
 * with insets, and any case where the player view isn't full-width.
 */
@OptIn(UnstableApi::class)
class PlayerGestureDetector(
    private val context: Context,
    private val player: ExoPlayer?,
    /**
     * Callback for center double-tap. Returns true if the gesture was handled
     * (e.g., resize mode toggled in fullscreen), false if not handled (e.g., not in fullscreen).
     * When false is returned, the gesture is not consumed, avoiding a "dead zone".
     */
    private val onCenterDoubleTap: (() -> Boolean)? = null,
    /** Provider for the actual player view width - more accurate than screen width for gesture zones */
    private val viewWidthProvider: (() -> Int)? = null
) : GestureDetector.SimpleOnGestureListener() {

    private val seekIncrement = 10000L // 10 seconds in milliseconds

    /**
     * Get effective width for gesture zone calculations.
     * Prefers actual view width (handles split-screen, multi-window, insets) over screen width.
     */
    private val effectiveWidth: Int
        get() = viewWidthProvider?.invoke()?.takeIf { it > 0 }
            ?: context.resources.displayMetrics.widthPixels

    override fun onDown(e: MotionEvent): Boolean = true

    override fun onDoubleTap(e: MotionEvent): Boolean {
        val x = e.x

        // Calculate zones dynamically based on actual view width (handles split-screen, multi-window)
        val width = effectiveWidth
        val leftThird = width / 3
        val rightThird = width * 2 / 3

        // Center zone: Toggle resize mode (ZOOM/FIT) - handled by callback
        // Callback returns true if handled (in fullscreen), false if not (not in fullscreen)
        // When not handled, we return false to avoid consuming the event (no dead zone)
        if (x >= leftThird && x <= rightThird) {
            return onCenterDoubleTap?.invoke() ?: false
        }

        // Left/right zones: Seek backward/forward
        val player = this.player ?: return false
        val isLeftSide = x < leftThird

        if (isLeftSide) {
            // Left side: Seek backward
            val newPosition = (player.currentPosition - seekIncrement).coerceAtLeast(0)
            player.seekTo(newPosition)
        } else {
            // Right side: Seek forward
            val duration = player.duration
            if (duration > 0) {
                val newPosition = (player.currentPosition + seekIncrement).coerceAtMost(duration)
                player.seekTo(newPosition)
            }
        }

        return true
    }
}
