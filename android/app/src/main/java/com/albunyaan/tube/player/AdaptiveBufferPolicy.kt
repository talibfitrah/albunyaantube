package com.albunyaan.tube.player

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import androidx.core.content.getSystemService
import androidx.media3.exoplayer.DefaultLoadControl
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adaptive buffer policy that scales buffer sizes based on device memory class and type.
 *
 * This addresses the [MAJOR] review finding that aggressive buffering (minBuffer=30s,
 * maxBuffer=180s) can cause memory pressure, GC jank, or OOM on low-end devices.
 *
 * **Buffer sizing strategy:**
 * - Low-memory devices (≤128MB heap): Conservative buffers to prevent OOM
 * - Normal devices (128-256MB heap): Balanced buffers for good UX
 * - High-memory devices (>256MB heap): Stable buffers without over-buffering CDN streams
 * - TV/Set-top boxes: Use NORMAL profile even with high memory (slow eMMC storage)
 *
 * **TV/Set-top box handling:**
 * Many Android TV boxes and set-top boxes report high memory class (>256MB) but have
 * slow eMMC storage that causes buffering to disk to lag. These devices are detected
 * via UiModeManager and forced to use the NORMAL profile for better performance.
 *
 * **Memory class mapping:**
 * The Android memory class (ActivityManager.getMemoryClass()) returns the approximate
 * per-application memory limit in megabytes. This is a better indicator than total RAM
 * since it accounts for device density and manufacturer tuning.
 *
 * **Buffer parameters:**
 * - minBufferMs: Minimum buffer before playback starts degrading
 * - maxBufferMs: Maximum buffer to accumulate (memory trade-off)
 * - bufferForPlaybackMs: Buffer required before initial playback
 * - bufferForPlaybackAfterRebufferMs: Buffer required after a rebuffer event
 * - backBufferMs: Buffer kept behind playback position for seek-back
 */
@Singleton
class AdaptiveBufferPolicy @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AdaptiveBufferPolicy"

        // Memory class thresholds (in MB)
        private const val LOW_MEMORY_CLASS = 128
        private const val HIGH_MEMORY_CLASS = 256

        // Drip-style buffering (min == max). ExoPlayer's loader fills the forward
        // buffer to maxBufferMs, then pauses until it drains below minBufferMs. When
        // min < max this produces a bursty fill→drain→fill cycle whose drain phase
        // repeatedly grazes the rebuffer threshold (the observed mid-playback stalls).
        // Setting min == max makes the loader top up continuously at a steady level —
        // ExoPlayer's own default since v2.10 (50s/50s) and LibreTube's model. Buffer
        // SIZE was never the problem; the min≠max burstiness was.

        // Conservative profile (low-memory devices): smaller steady buffer to bound
        // memory on ≤128MB-heap devices while still being drip-style (no burst).
        private const val LOW_MIN_BUFFER_MS = 30_000      // 30s steady buffer
        private const val LOW_MAX_BUFFER_MS = 30_000      // == min (drip)
        private const val LOW_PLAYBACK_BUFFER_MS = 2_000  // 2s before playback (safer for slow eMMC)
        private const val LOW_REBUFFER_BUFFER_MS = 3_500  // 3.5s after rebuffer
        private const val LOW_BACK_BUFFER_MS = 30_000     // 30s back buffer

        // Balanced profile (normal devices): ExoPlayer's default 50s steady buffer.
        private const val NORMAL_MIN_BUFFER_MS = 50_000   // 50s steady buffer
        private const val NORMAL_MAX_BUFFER_MS = 50_000   // == min (drip)
        private const val NORMAL_PLAYBACK_BUFFER_MS = 1_500 // 1.5s before playback (fast TTFF)
        private const val NORMAL_REBUFFER_BUFFER_MS = 3_000 // 3s after rebuffer
        private const val NORMAL_BACK_BUFFER_MS = 45_000  // 45s back buffer

        // High-memory profile. Same 50s steady drip buffer — flagship devices gain
        // nothing from silently holding minutes of adaptive media (allocator churn,
        // recovery latency) and the steady level already prevents rebuffering.
        private const val HIGH_MIN_BUFFER_MS = 50_000     // 50s steady buffer
        private const val HIGH_MAX_BUFFER_MS = 50_000     // == min (drip)
        private const val HIGH_PLAYBACK_BUFFER_MS = 1_500 // 1.5s before playback (fast TTFF)
        private const val HIGH_REBUFFER_BUFFER_MS = 4_000 // 4s after rebuffer for stable resume
        private const val HIGH_BACK_BUFFER_MS = 45_000    // 45s back buffer
    }

    /**
     * Buffer configuration based on device capabilities.
     */
    data class BufferConfig(
        val minBufferMs: Int,
        val maxBufferMs: Int,
        val bufferForPlaybackMs: Int,
        val bufferForPlaybackAfterRebufferMs: Int,
        val backBufferMs: Int,
        val profile: BufferProfile
    )

    enum class BufferProfile {
        LOW_MEMORY,
        NORMAL,
        HIGH_MEMORY
    }

    private val memoryClass: Int by lazy {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        activityManager.memoryClass
    }

    private val isLowRamDevice: Boolean by lazy {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        activityManager.isLowRamDevice
    }

    /**
     * Detect if the device is an Android TV or set-top box.
     *
     * TV/set-top boxes often have high memory but slow eMMC storage, causing
     * aggressive buffering to lag. We detect these devices to use conservative buffers.
     */
    private val isTvOrSetTopBox: Boolean by lazy {
        val uiModeManager = context.getSystemService<UiModeManager>()
        val isTV = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        if (isTV) {
            Log.d(TAG, "Device detected as TV/set-top box - will use conservative buffers")
        }
        isTV
    }

    /**
     * Cached buffer configuration. Computed lazily once based on device capabilities.
     * This avoids recreating BufferConfig objects on every call to getBufferConfig().
     */
    private val cachedBufferConfig: BufferConfig by lazy {
        val config = when {
            // Low RAM devices always get conservative profile
            isLowRamDevice || memoryClass <= LOW_MEMORY_CLASS -> {
                BufferConfig(
                    minBufferMs = LOW_MIN_BUFFER_MS,
                    maxBufferMs = LOW_MAX_BUFFER_MS,
                    bufferForPlaybackMs = LOW_PLAYBACK_BUFFER_MS,
                    bufferForPlaybackAfterRebufferMs = LOW_REBUFFER_BUFFER_MS,
                    backBufferMs = LOW_BACK_BUFFER_MS,
                    profile = BufferProfile.LOW_MEMORY
                )
            }
            // TV/set-top boxes: Force NORMAL profile even with high memory
            // These devices often have slow eMMC storage that can't handle aggressive buffering
            isTvOrSetTopBox -> {
                Log.d(TAG, "TV/set-top box detected with ${memoryClass}MB memory - using NORMAL profile (not HIGH)")
                BufferConfig(
                    minBufferMs = NORMAL_MIN_BUFFER_MS,
                    maxBufferMs = NORMAL_MAX_BUFFER_MS,
                    bufferForPlaybackMs = NORMAL_PLAYBACK_BUFFER_MS,
                    bufferForPlaybackAfterRebufferMs = NORMAL_REBUFFER_BUFFER_MS,
                    backBufferMs = NORMAL_BACK_BUFFER_MS,
                    profile = BufferProfile.NORMAL
                )
            }
            // High memory phones/tablets get the stable high-memory profile
            memoryClass >= HIGH_MEMORY_CLASS -> {
                BufferConfig(
                    minBufferMs = HIGH_MIN_BUFFER_MS,
                    maxBufferMs = HIGH_MAX_BUFFER_MS,
                    bufferForPlaybackMs = HIGH_PLAYBACK_BUFFER_MS,
                    bufferForPlaybackAfterRebufferMs = HIGH_REBUFFER_BUFFER_MS,
                    backBufferMs = HIGH_BACK_BUFFER_MS,
                    profile = BufferProfile.HIGH_MEMORY
                )
            }
            // Normal memory devices
            else -> {
                BufferConfig(
                    minBufferMs = NORMAL_MIN_BUFFER_MS,
                    maxBufferMs = NORMAL_MAX_BUFFER_MS,
                    bufferForPlaybackMs = NORMAL_PLAYBACK_BUFFER_MS,
                    bufferForPlaybackAfterRebufferMs = NORMAL_REBUFFER_BUFFER_MS,
                    backBufferMs = NORMAL_BACK_BUFFER_MS,
                    profile = BufferProfile.NORMAL
                )
            }
        }

        Log.d(TAG, "Device memory class: ${memoryClass}MB, isLowRam: $isLowRamDevice, " +
            "isTV: $isTvOrSetTopBox, profile: ${config.profile}, maxBuffer: ${config.maxBufferMs / 1000}s")

        config
    }

    /**
     * Get the buffer configuration appropriate for this device.
     * Returns a cached configuration computed once based on device capabilities.
     */
    private fun getBufferConfig(): BufferConfig = cachedBufferConfig

    /**
     * Build a DefaultLoadControl with adaptive buffer configuration.
     */
    fun buildLoadControl(): DefaultLoadControl {
        val config = getBufferConfig()

        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                config.minBufferMs,
                config.maxBufferMs,
                config.bufferForPlaybackMs,
                config.bufferForPlaybackAfterRebufferMs
            )
            .setBackBuffer(config.backBufferMs, true)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }
}
