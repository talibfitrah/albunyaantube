package com.albunyaan.tube.ui.player

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Window
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs

/**
 * Gesture detector for player controls:
 * - Swipe up/down on left side: Brightness
 * - Swipe up/down on right side: Volume
 * - Double tap left/right: Seek backward/forward
 */
@OptIn(UnstableApi::class)
class PlayerGestureDetector(
    context: Context,
    private val player: ExoPlayer?,
    private val window: Window?
) : GestureDetector.SimpleOnGestureListener() {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val screenWidth = context.resources.displayMetrics.widthPixels
    private val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
    private val seekIncrement = 10000L // 10 seconds in milliseconds

    override fun onDown(e: MotionEvent): Boolean = true

    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ): Boolean {
        if (e1 == null) return false

        val deltaY = e1.y - e2.y
        val absDeltaY = abs(deltaY)

        // Only handle vertical swipes (more vertical than horizontal)
        if (absDeltaY < abs(distanceX)) return false

        // Determine if swipe is on left or right side
        val isLeftSide = e1.x < screenWidth / 2

        if (isLeftSide) {
            // Left side: Brightness control
            adjustBrightness(deltaY / 1000f)
        } else {
            // Right side: Volume control
            adjustVolume(deltaY.toInt() / 50)
        }

        return true
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        val player = this.player ?: return false

        // Determine if double tap is on left or right side
        val isLeftSide = e.x < screenWidth / 2

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

    private fun adjustBrightness(delta: Float) {
        val window = this.window ?: return
        val layoutParams = window.attributes

        // Current brightness (-1 = auto, 0-1 = manual)
        var brightness = layoutParams.screenBrightness
        if (brightness < 0) {
            // Get system brightness
            brightness = try {
                Settings.System.getInt(
                    window.context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS
                ) / 255f
            } catch (e: Exception) {
                0.5f
            }
        }

        // Adjust brightness (clamp between 0.01 and 1.0)
        brightness = (brightness + delta).coerceIn(0.01f, 1.0f)

        layoutParams.screenBrightness = brightness
        window.attributes = layoutParams
    }

    private fun adjustVolume(delta: Int) {
        val audioManager = this.audioManager ?: return

        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val newVolume = (currentVolume + delta).coerceIn(0, maxVolume)

        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            newVolume,
            0 // No UI flags
        )
    }
}
