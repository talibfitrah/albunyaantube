package com.albunyaan.tube.player

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.albunyaan.tube.data.extractor.VideoTrack
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 3: Hybrid Cold-Start Quality Chooser
 *
 * Determines the initial playback quality on cold start based on:
 * 1. Network type (WiFi → higher quality, Cellular → lower quality)
 * 2. Device screen size (no point playing 1080p on 720p screen)
 * 3. Last successful playback quality (persisted hint for user's typical choice)
 *
 * This replaces the hardcoded 720p default with an intelligent initial quality selection
 * that balances fast start times with appropriate quality for the context.
 *
 * **Key Principles:**
 * - Start conservative to minimize initial buffering (TTFF optimization)
 * - Use network and screen signals to inform the initial choice
 * - Persist successful playback quality to learn user preferences
 * - ABR/BufferHealthMonitor handles upgrades after playback starts
 *
 * **Quality Tiers (heights in pixels):**
 * - ULTRA: 2160 (4K) - Screen cap for large tablet/TV (ABR may upgrade to this)
 * - HIGH: 1080 - WiFi + tablet/large tablet (conservative start, ABR upgrades)
 * - MEDIUM: 720 - Default / WiFi + phone / Cellular + tablet
 * - LOW: 480 - Cellular / data saver / constrained
 * - MINIMUM: 360 - Very constrained (slow 3G, extreme data saver)
 *
 * Note: Initial recommendations are conservative to minimize TTFF. ABR/BufferHealthMonitor
 * handles quality upgrades after playback starts based on actual bandwidth.
 */
@Singleton
class ColdStartQualityChooser @Inject constructor() {

    companion object {
        private const val TAG = "ColdStartQuality"

        // SharedPreferences key for persisted quality hint
        private const val PREFS_NAME = "cold_start_quality"
        private const val KEY_LAST_SUCCESSFUL_HEIGHT = "last_successful_height"

        // Quality tiers (height in pixels)
        const val QUALITY_ULTRA = 2160
        const val QUALITY_HIGH = 1080
        const val QUALITY_MEDIUM = 720
        const val QUALITY_LOW = 480
        const val QUALITY_MINIMUM = 360

        // Screen size breakpoints (dp)
        private const val SCREEN_SMALL = 600   // < 600dp = phone
        private const val SCREEN_MEDIUM = 720  // 600-720dp = small tablet
        // >= 720dp = large tablet/TV
    }

    /**
     * Network type classification for quality decisions.
     */
    enum class NetworkType {
        WIFI,
        CELLULAR_FAST,   // LTE/5G
        CELLULAR_SLOW,   // 3G or unknown
        METERED,         // Any metered connection
        OFFLINE
    }

    /**
     * Screen size classification for quality decisions.
     */
    enum class ScreenClass {
        PHONE,           // < 600dp smallest width
        TABLET,          // 600-720dp
        LARGE_TABLET_TV  // >= 720dp
    }

    /**
     * Result of cold-start quality selection.
     */
    data class QualityChoice(
        val recommendedHeight: Int,
        val networkType: NetworkType,
        val screenClass: ScreenClass,
        val hasPersistedHint: Boolean,
        val persistedHintHeight: Int?
    ) {
        fun toLogString(): String = buildString {
            append("Cold-start: ${recommendedHeight}p")
            append(" (network=$networkType, screen=$screenClass")
            if (hasPersistedHint) {
                append(", hint=${persistedHintHeight}p")
            }
            append(")")
        }
    }

    /**
     * Choose the initial playback quality based on context.
     *
     * @param context Android context for system services
     * @return QualityChoice with recommended height and diagnostic info
     */
    fun chooseInitialQuality(context: Context): QualityChoice {
        val networkType = detectNetworkType(context)
        val screenClass = detectScreenClass(context)
        val persistedHint = getPersistedQualityHint(context)

        val recommendedHeight = calculateRecommendedHeight(
            networkType = networkType,
            screenClass = screenClass,
            persistedHint = persistedHint
        )

        val choice = QualityChoice(
            recommendedHeight = recommendedHeight,
            networkType = networkType,
            screenClass = screenClass,
            hasPersistedHint = persistedHint != null,
            persistedHintHeight = persistedHint
        )

        Log.d(TAG, choice.toLogString())
        return choice
    }

    /**
     * Find the best video track matching the cold-start quality choice.
     *
     * @param tracks Available video tracks
     * @param context Android context for quality calculation
     * @return Best matching track, or null if no tracks available
     */
    fun selectBestTrack(tracks: List<VideoTrack>, context: Context): VideoTrack? {
        if (tracks.isEmpty()) return null

        val choice = chooseInitialQuality(context)
        return findBestTrackForHeight(tracks, choice.recommendedHeight)
    }

    /**
     * Notify that playback was successful at a given quality.
     * Persists this as a hint for future cold starts.
     *
     * @param context Android context
     * @param height The video height that played successfully
     * @param networkType Current network type when playback succeeded (for logging)
     */
    fun recordSuccessfulPlayback(context: Context, height: Int, networkType: NetworkType) {
        val prefs = getPrefs(context)
        prefs.edit()
            .putInt(KEY_LAST_SUCCESSFUL_HEIGHT, height)
            .apply()
        Log.d(TAG, "Recorded successful playback: ${height}p on $networkType")
    }

    /**
     * Clear persisted quality hints (for testing or reset).
     */
    fun clearPersistedHints(context: Context) {
        getPrefs(context).edit().clear().apply()
        Log.d(TAG, "Cleared persisted quality hints")
    }

    // --- Private Implementation ---

    private fun detectNetworkType(context: Context): NetworkType {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkType.OFFLINE

        val network = cm.activeNetwork ?: return NetworkType.OFFLINE
        val caps = cm.getNetworkCapabilities(network) ?: return NetworkType.OFFLINE

        // Check for internet connectivity
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return NetworkType.OFFLINE
        }

        val isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        // WiFi detection - check metered status (mobile hotspot, paid WiFi)
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return if (isMetered) NetworkType.METERED else NetworkType.WIFI
        }

        // Cellular detection - classify by speed even if metered (most cellular is metered)
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            // Estimate speed from bandwidth (API 21+ provides this)
            val downBandwidth = caps.linkDownstreamBandwidthKbps

            // Handle LINK_BANDWIDTH_UNSPECIFIED (0 or Integer.MAX_VALUE when unknown)
            // When bandwidth is unknown, use conservative defaults based on metered status
            val isBandwidthUnknown = downBandwidth <= 0 || downBandwidth >= Integer.MAX_VALUE
            if (isBandwidthUnknown) {
                // Unknown bandwidth - treat conservatively
                return if (isMetered) NetworkType.METERED else NetworkType.CELLULAR_FAST
            }

            val isFast = downBandwidth >= 10_000 // >= 10 Mbps = fast cellular

            // Slow + metered = very conservative (METERED), otherwise classify by speed
            return when {
                !isFast && isMetered -> NetworkType.METERED
                isFast -> NetworkType.CELLULAR_FAST
                else -> NetworkType.CELLULAR_SLOW
            }
        }

        // Ethernet - treat like WiFi (typically unmetered, high-bandwidth)
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return if (isMetered) NetworkType.METERED else NetworkType.WIFI
        }

        // Default for other transport types (Bluetooth, VPN, etc.)
        return if (isMetered) NetworkType.METERED else NetworkType.WIFI
    }

    private fun detectScreenClass(context: Context): ScreenClass {
        // Primary approach: use resources.displayMetrics (always available)
        // Copy to avoid mutating the shared system instance
        val metrics = DisplayMetrics().apply {
            setTo(context.resources.displayMetrics)
        }

        // Fallback to WindowManager if resources metrics unavailable or zero
        if (metrics.density == 0f || metrics.widthPixels == 0) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm != null) {
                @Suppress("DEPRECATION")
                wm.defaultDisplay?.getMetrics(metrics)
            }
        }

        // Guard against division by zero if density is still 0
        val density = if (metrics.density > 0f) metrics.density else 1f

        // Calculate smallest width in dp (orientation-independent)
        val widthDp = metrics.widthPixels / density
        val heightDp = metrics.heightPixels / density
        val smallestWidthDp = minOf(widthDp, heightDp)

        return when {
            smallestWidthDp >= SCREEN_MEDIUM -> ScreenClass.LARGE_TABLET_TV
            smallestWidthDp >= SCREEN_SMALL -> ScreenClass.TABLET
            else -> ScreenClass.PHONE
        }
    }

    private fun getPersistedQualityHint(context: Context): Int? {
        val prefs = getPrefs(context)
        val height = prefs.getInt(KEY_LAST_SUCCESSFUL_HEIGHT, -1)
        return if (height > 0) height else null
    }

    private fun calculateRecommendedHeight(
        networkType: NetworkType,
        screenClass: ScreenClass,
        persistedHint: Int?
    ): Int {
        // Start with network-based recommendation
        val networkRecommendation = when (networkType) {
            NetworkType.WIFI -> when (screenClass) {
                ScreenClass.LARGE_TABLET_TV -> QUALITY_HIGH // 1080p for large screens on WiFi
                ScreenClass.TABLET -> QUALITY_HIGH          // 1080p for tablets on WiFi
                ScreenClass.PHONE -> QUALITY_MEDIUM         // 720p for phones on WiFi
            }
            NetworkType.CELLULAR_FAST -> when (screenClass) {
                ScreenClass.LARGE_TABLET_TV -> QUALITY_MEDIUM // 720p max for fast cellular
                ScreenClass.TABLET -> QUALITY_MEDIUM          // 720p for tablets on LTE
                ScreenClass.PHONE -> QUALITY_LOW              // 480p for phones on LTE
            }
            NetworkType.CELLULAR_SLOW, NetworkType.METERED -> QUALITY_LOW // 480p for slow/metered
            NetworkType.OFFLINE -> QUALITY_MINIMUM // 360p when offline (cached content only)
        }

        // Cap by screen's physical resolution (no point exceeding screen pixels)
        val screenCap = when (screenClass) {
            ScreenClass.LARGE_TABLET_TV -> QUALITY_ULTRA  // Can handle 4K
            ScreenClass.TABLET -> QUALITY_HIGH            // Cap at 1080p
            ScreenClass.PHONE -> QUALITY_HIGH             // Cap at 1080p (most modern phones)
        }

        // Apply screen cap
        val cappedRecommendation = minOf(networkRecommendation, screenCap)

        // Consider persisted hint (user's typical preference)
        // Use hint if it's lower than our recommendation (user prefers data saving)
        if (persistedHint != null && persistedHint < cappedRecommendation) {
            Log.d(TAG, "Using lower persisted hint: ${persistedHint}p < ${cappedRecommendation}p")
            return persistedHint
        }

        return cappedRecommendation
    }

    private fun findBestTrackForHeight(tracks: List<VideoTrack>, targetHeight: Int): VideoTrack? {
        // Find the best track at or below target height
        // Prefer: 1) Highest resolution <= target, 2) Muxed over video-only, 3) Highest bitrate
        val underTarget = tracks
            .filter { it.height != null && it.height <= targetHeight }
            .sortedWith(
                compareByDescending<VideoTrack> { it.height ?: 0 }
                    .thenBy { it.isVideoOnly } // prefer muxed
                    .thenByDescending { it.bitrate ?: 0 }
            )
            .firstOrNull()

        if (underTarget != null) return underTarget

        // No track at or below target; return lowest available
        return tracks
            .filter { it.height != null }
            .minByOrNull { it.height!! }
            ?: tracks.firstOrNull()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
