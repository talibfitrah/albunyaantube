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
 * - High-memory devices (>256MB heap): Aggressive buffers for minimal rebuffering
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

        // Conservative profile (low-memory devices)
        private const val LOW_MIN_BUFFER_MS = 15_000      // 15s min buffer
        private const val LOW_MAX_BUFFER_MS = 60_000      // 1 minute max buffer
        private const val LOW_PLAYBACK_BUFFER_MS = 2_500  // 2.5s before playback
        private const val LOW_REBUFFER_BUFFER_MS = 5_000  // 5s after rebuffer
        private const val LOW_BACK_BUFFER_MS = 30_000     // 30s back buffer

        // Balanced profile (normal devices)
        private const val NORMAL_MIN_BUFFER_MS = 25_000   // 25s min buffer
        private const val NORMAL_MAX_BUFFER_MS = 120_000  // 2 minutes max buffer
        private const val NORMAL_PLAYBACK_BUFFER_MS = 2_000 // 2s before playback
        private const val NORMAL_REBUFFER_BUFFER_MS = 4_000 // 4s after rebuffer
        private const val NORMAL_BACK_BUFFER_MS = 45_000  // 45s back buffer

        // Aggressive profile (high-memory devices)
        private const val HIGH_MIN_BUFFER_MS = 30_000     // 30s min buffer
        private const val HIGH_MAX_BUFFER_MS = 180_000    // 3 minutes max buffer
        private const val HIGH_PLAYBACK_BUFFER_MS = 2_000 // 2s before playback
        private const val HIGH_REBUFFER_BUFFER_MS = 4_000 // 4s after rebuffer
        private const val HIGH_BACK_BUFFER_MS = 60_000    // 60s back buffer
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
            // High memory phones/tablets get aggressive profile
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
    fun getBufferConfig(): BufferConfig = cachedBufferConfig

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

    /**
     * Get diagnostic info about buffer policy.
     */
    fun getDiagnostics(): Map<String, Any> {
        val config = getBufferConfig()
        return mapOf(
            "memoryClass" to memoryClass,
            "isLowRamDevice" to isLowRamDevice,
            "isTvOrSetTopBox" to isTvOrSetTopBox,
            "profile" to config.profile.name,
            "minBufferMs" to config.minBufferMs,
            "maxBufferMs" to config.maxBufferMs,
            "bufferForPlaybackMs" to config.bufferForPlaybackMs,
            "bufferForPlaybackAfterRebufferMs" to config.bufferForPlaybackAfterRebufferMs,
            "backBufferMs" to config.backBufferMs
        )
    }
}
