package com.albunyaan.tube.player

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.albunyaan.tube.BuildConfig
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
 *
 * **Version Migration:**
 * When the app is updated, persisted quality hints from older versions may conflict with
 * new player configurations. We track the app version that wrote the preferences and
 * clear stale data on version upgrade to ensure a fresh start.
 */
@Singleton
class ColdStartQualityChooser @Inject constructor() {

    companion object {
        private const val TAG = "ColdStartQuality"

        // SharedPreferences key for persisted quality hint
        private const val PREFS_NAME = "cold_start_quality"
        private const val KEY_LAST_SUCCESSFUL_HEIGHT = "last_successful_height"
        private const val KEY_PREFS_VERSION = "prefs_version_code"

        // Quality tiers (height in pixels)
        const val QUALITY_ULTRA = 2160
        const val QUALITY_HIGH = 1080
        const val QUALITY_MEDIUM = 720
        const val QUALITY_LOW = 480
        const val QUALITY_MINIMUM = 360

        // Cellular bitrate ceilings (bits per second). These bound ABR so it cannot
        // climb into a bitrate the cellular link can't actually sustain, which is the
        // root cause of the "plays 2-3s then stalls" loop on congested 4G.
        //
        // We MUST cap by network class rather than the reported link speed:
        // NetworkCapabilities.linkDownstreamBandwidthKbps is a *technology* estimate
        // (LTE/5G almost always reports tens of Mbps regardless of real throughput on
        // a busy cell), so it is useless for protecting playback.
        const val CELLULAR_FAST_MAX_BITRATE_BPS = 2_500_000   // ~720p H.264 ceiling
        const val CELLULAR_SLOW_MAX_BITRATE_BPS = 1_200_000   // ~480p H.264 ceiling

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
     * A hard ceiling applied to the track selector on constrained networks.
     *
     * Unlike the cold-start [QualityChoice] (which only seeds the *initial* pick and
     * does nothing for HLS/DASH ABR), this ceiling bounds adaptive bitrate selection
     * for the whole session so ABR cannot climb back into a stall after a lull.
     *
     * @param maxHeight Maximum video height (pixels) ABR may select.
     * @param maxBitrateBps Maximum video bitrate (bits/sec) ABR may select.
     */
    data class Ceiling(
        val maxHeight: Int,
        val maxBitrateBps: Int
    )

    /**
     * Choose the initial playback quality based on context.
     *
     * @param context Android context for system services
     * @return QualityChoice with recommended height and diagnostic info
     */
    fun chooseInitialQuality(context: Context): QualityChoice {
        // Migrate preferences if app was updated
        migratePreferencesIfNeeded(context)

        val networkType = detectNetworkType(context)
        val screenClass = detectScreenClass(context)
        val persistedHint = getPersistedQualityHint(context)

        // persistedHint is intentionally NOT used to clamp the cold-start height —
        // clamping kept users stuck at 360-480p after switching to fast WiFi. It is
        // still recorded on QualityChoice below (logging); manual quality picks win at
        // runtime regardless. See recommendedHeightFor.
        val recommendedHeight = recommendedHeightFor(networkType, screenClass)

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
            .putInt(KEY_PREFS_VERSION, BuildConfig.VERSION_CODE)
            .apply()
        Log.d(TAG, "Recorded successful playback: ${height}p on $networkType (version ${BuildConfig.VERSION_CODE})")
    }

    /**
     * Clear persisted quality hints (for testing or reset).
     */
    fun clearPersistedHints(context: Context) {
        getPrefs(context).edit().clear().apply()
        Log.d(TAG, "Cleared persisted quality hints")
    }

    // --- Private Implementation ---

    /**
     * Migrate preferences when app version changes.
     *
     * When the app is updated (installed on top of an older version), persisted quality
     * hints may conflict with new player configurations. This clears stale preferences
     * to ensure a fresh start with the new player logic.
     *
     * This fixes issues where devices have lags/freezes after updating because old
     * quality hints force inappropriate quality selections for the new player.
     */
    private fun migratePreferencesIfNeeded(context: Context) {
        val prefs = getPrefs(context)
        val storedVersion = prefs.getInt(KEY_PREFS_VERSION, 0)
        val currentVersion = BuildConfig.VERSION_CODE

        if (storedVersion == 0) {
            // First time or preferences from before versioning was added
            // Clear any legacy preferences and start fresh
            if (prefs.contains(KEY_LAST_SUCCESSFUL_HEIGHT)) {
                Log.i(TAG, "Migrating legacy preferences (no version) -> clearing for fresh start")
                prefs.edit()
                    .clear()
                    .putInt(KEY_PREFS_VERSION, currentVersion)
                    .apply()
            } else {
                // No existing preferences, just set version
                prefs.edit()
                    .putInt(KEY_PREFS_VERSION, currentVersion)
                    .apply()
            }
        } else if (storedVersion != currentVersion) {
            // App was updated or downgraded - clear preferences to avoid conflicts with player logic
            Log.i(TAG, "App version changed ($storedVersion -> $currentVersion) - clearing quality hints for fresh start")
            prefs.edit()
                .clear()
                .putInt(KEY_PREFS_VERSION, currentVersion)
                .apply()
        }
        // If storedVersion == currentVersion, no migration needed
    }

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

    /**
     * Pure network+screen → initial-height policy (no Android context, unit-testable).
     *
     * On WiFi we still aim high and let ABR run free (a deliberate choice: starting
     * low on WiFi meant playback never visibly upgraded on stable links).
     *
     * On cellular we start conservative. The link-speed signal cannot be trusted, and
     * for progressive / single-representation streams there is NO in-stream ABR to
     * rescue an over-ambitious start, so an optimistic cellular start = guaranteed
     * stall. Starting at 480p (phone) / 720p (tablet) gives a fast, stutter-free start;
     * ABR climbs from there only when *measured* throughput supports it.
     */
    internal fun recommendedHeightFor(networkType: NetworkType, screenClass: ScreenClass): Int {
        val networkRecommendation = when (networkType) {
            NetworkType.WIFI -> when (screenClass) {
                ScreenClass.LARGE_TABLET_TV -> QUALITY_ULTRA  // 4K on TV/large tablet
                ScreenClass.TABLET -> QUALITY_HIGH            // 1080p on tablet
                ScreenClass.PHONE -> QUALITY_HIGH             // 1080p on phone WiFi
            }
            NetworkType.CELLULAR_FAST -> when (screenClass) {
                ScreenClass.LARGE_TABLET_TV -> QUALITY_HIGH   // 1080p on LTE/5G TV/large tablet
                ScreenClass.TABLET -> QUALITY_MEDIUM          // 720p on LTE/5G tablet
                ScreenClass.PHONE -> QUALITY_LOW              // 480p start on phone LTE/5G
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

        return minOf(networkRecommendation, screenCap)
    }

    /**
     * Pure network → ceiling policy (no Android context, unit-testable).
     *
     * Returns null on WiFi/offline (no ceiling — ABR runs free / cached). On cellular
     * it returns a hard height+bitrate cap matched to what the link class can sustain.
     */
    internal fun ceilingFor(networkType: NetworkType): Ceiling? = when (networkType) {
        NetworkType.CELLULAR_FAST ->
            Ceiling(QUALITY_MEDIUM, CELLULAR_FAST_MAX_BITRATE_BPS)   // ≤720p / 2.5 Mbps
        NetworkType.CELLULAR_SLOW, NetworkType.METERED ->
            Ceiling(QUALITY_LOW, CELLULAR_SLOW_MAX_BITRATE_BPS)      // ≤480p / 1.2 Mbps
        NetworkType.WIFI, NetworkType.OFFLINE -> null
    }

    /**
     * Resolve the current cellular ceiling for [context], or null on WiFi/offline.
     * Applied to the track selector so adaptive ABR cannot climb into a stall.
     */
    fun chooseCeiling(context: Context): Ceiling? = ceilingFor(detectNetworkType(context))

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
