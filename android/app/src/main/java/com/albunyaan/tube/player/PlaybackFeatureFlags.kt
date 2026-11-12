package com.albunyaan.tube.player

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.albunyaan.tube.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime feature flags for playback subsystem.
 *
 * This class provides a runtime toggle mechanism for playback features.
 * Each flag has a build-time default (from BuildConfig) that can be overridden
 * at runtime via SharedPreferences.
 *
 * **Scope: Per-Device Only**
 * These toggles are stored in local SharedPreferences and apply only to the current device.
 * They are intended for:
 * - Debug/QA testing of features on specific devices
 * - Developer troubleshooting during incident investigation
 * - Local A/B testing during development
 *
 * These are NOT fleet-wide kill switches. For production-grade remote feature control,
 * integrate with a remote config service (Firebase Remote Config, etc.) that can write
 * to these SharedPreferences on app startup, or implement a separate remote-driven layer.
 *
 * **Flag resolution order:**
 * 1. Runtime override (SharedPreferences) if set
 * 2. Build-time default (BuildConfig)
 *
 * **Runtime override persistence:**
 * - Overrides are stored in SharedPreferences and survive app restarts
 * - Use [clearOverride] to revert to build-time default
 * - Use [clearAllOverrides] to reset all flags
 *
 * **Thread safety:**
 * - All read operations are atomic (single SharedPreferences read)
 * - Write operations update the in-memory cache immediately with asynchronous disk persistence via apply()
 *
 * @see DeveloperSettingsDialog (hidden UI for toggling these flags)
 */
@Singleton
class PlaybackFeatureFlags @Inject constructor(
    @ApplicationContext context: Context
) {
    companion object {
        private const val TAG = "PlaybackFeatureFlags"
        private const val PREFS_NAME = "playback_feature_flags"

        // Preference keys for runtime overrides (null means use build-time default)
        // Made public for API consistency with clearOverride()
        const val KEY_MPD_PREFETCH = "mpd_prefetch"
        const val KEY_GENEROUS_CROP_BUDGET = "generous_crop_budget"
        const val KEY_PREDICTIVE_PREFETCH = "predictive_prefetch"
        const val KEY_SEGMENT_PRELOAD = "segment_preload"
        const val KEY_NEVER_FREEZE_ABR = "never_freeze_abr"
        const val KEY_TTL_WATCHER = "ttl_watcher"

        /** Every flag and its build-time default: the one list clear/validate/diagnostics read. */
        private val BUILD_DEFAULTS: Map<String, () -> Boolean> = linkedMapOf(
            KEY_MPD_PREFETCH to { BuildConfig.ENABLE_MPD_PREFETCH },
            KEY_GENEROUS_CROP_BUDGET to ::isSamsungS25Ultra,
            KEY_PREDICTIVE_PREFETCH to { BuildConfig.ENABLE_PREDICTIVE_PREFETCH },
            KEY_SEGMENT_PRELOAD to { BuildConfig.ENABLE_SEGMENT_PRELOAD },
            KEY_NEVER_FREEZE_ABR to { BuildConfig.ENABLE_NEVER_FREEZE_ABR },
            KEY_TTL_WATCHER to { BuildConfig.ENABLE_TTL_WATCHER }
        )

        /** Set of all valid override keys for validation */
        private val VALID_KEYS = BUILD_DEFAULTS.keys

        /**
         * Detect Samsung Galaxy S25 Ultra by model number prefix.
         * SM-S938 covers all regional variants (SM-S938B, SM-S938U, SM-S938N, etc.).
         */
        private fun isSamsungS25Ultra(): Boolean =
            android.os.Build.MODEL.contains("SM-S938", ignoreCase = true)

        /**
         * Special value indicating "use build-time default".
         * We use Int instead of Boolean to distinguish "false override" from "no override".
         */
        private const val VALUE_USE_DEFAULT = -1
        private const val VALUE_DISABLED = 0
        private const val VALUE_ENABLED = 1

        /** Key for tracking the app version that wrote the preferences */
        private const val KEY_PREFS_VERSION = "prefs_version_code"
    }

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // Migrate preferences if app was updated
        migratePreferencesIfNeeded()
    }

    /**
     * Migrate preferences when app version changes.
     *
     * When the app is updated (installed on top of an older version), feature flag
     * overrides from older versions may conflict with new player configurations.
     * This clears stale overrides to ensure a fresh start with new defaults.
     */
    private fun migratePreferencesIfNeeded() {
        val storedVersion = prefs.getInt(KEY_PREFS_VERSION, 0)
        val currentVersion = BuildConfig.VERSION_CODE

        if (storedVersion == 0) {
            // First time or preferences from before versioning was added
            // Check if there are any overrides set
            val hasAnyOverride = VALID_KEYS.any { prefs.contains(it) }
            if (hasAnyOverride) {
                Log.i(TAG, "Migrating legacy preferences (no version) -> clearing overrides for fresh start")
                clearAllOverridesInternal()
            }
            prefs.edit().putInt(KEY_PREFS_VERSION, currentVersion).apply()
        } else if (storedVersion < currentVersion) {
            // App was updated - clear overrides to avoid conflicts with new defaults
            Log.i(TAG, "App updated ($storedVersion -> $currentVersion) - clearing feature flag overrides")
            clearAllOverridesInternal()
            prefs.edit().putInt(KEY_PREFS_VERSION, currentVersion).apply()
        }
        // If storedVersion == currentVersion, no migration needed
    }

    /**
     * Internal method to clear overrides without logging (used during migration).
     */
    private fun clearAllOverridesInternal() {
        val editor = prefs.edit()
        VALID_KEYS.forEach { editor.remove(it) }
        editor.apply()
    }

    /**
     * Whether MPD pre-generation during prefetch is enabled.
     *
     * When enabled, pre-generates DASH MPD when user taps video to reduce
     * first-frame latency.
     *
     * Build-time default: [BuildConfig.ENABLE_MPD_PREFETCH]
     */
    val isMpdPrefetchEnabled: Boolean
        get() = resolveFlag(KEY_MPD_PREFETCH)

    /**
     * Whether generous crop budget (20%) is used for fullscreen aspect ratio.
     *
     * When enabled, AspectPolicy uses [AspectPolicy.GENEROUS_CROP_BUDGET] (20%) instead
     * of [AspectPolicy.DEFAULT_CROP_BUDGET] (5%), allowing more zoom-to-fill for standard
     * 16:9 content on ultra-wide screens.
     *
     * Build-time default: `true` on Samsung S25 Ultra (SM-S938*), `false` on all other devices.
     * Runtime override via developer options or remote config for fleet-wide control.
     */
    val isGenerousCropBudgetEnabled: Boolean
        get() = resolveFlag(KEY_GENEROUS_CROP_BUDGET)

    val isPredictivePrefetchEnabled: Boolean
        get() = resolveFlag(KEY_PREDICTIVE_PREFETCH)

    val isSegmentPreloadEnabled: Boolean
        get() = resolveFlag(KEY_SEGMENT_PRELOAD)

    val isNeverFreezeAbrEnabled: Boolean
        get() = resolveFlag(KEY_NEVER_FREEZE_ABR)

    val isTtlWatcherEnabled: Boolean
        get() = resolveFlag(KEY_TTL_WATCHER)

    /**
     * Set a runtime override for MPD prefetch.
     * @param enabled true to enable, false to disable, null to use build-time default
     */
    fun setMpdPrefetchEnabled(enabled: Boolean?) {
        setOverride(KEY_MPD_PREFETCH, enabled)
        Log.i(TAG, "MPD_PREFETCH override set to: $enabled (effective: $isMpdPrefetchEnabled)")
    }

    /**
     * Set a runtime override for generous crop budget.
     * @param enabled true to enable, false to disable, null to use device-based default
     */
    fun setGenerousCropBudgetEnabled(enabled: Boolean?) {
        setOverride(KEY_GENEROUS_CROP_BUDGET, enabled)
        Log.i(TAG, "GENEROUS_CROP_BUDGET override set to: $enabled (effective: $isGenerousCropBudgetEnabled)")
    }

    fun setPredictivePrefetchEnabled(enabled: Boolean?) {
        setOverride(KEY_PREDICTIVE_PREFETCH, enabled)
        Log.i(TAG, "PREDICTIVE_PREFETCH override set to: $enabled (effective: $isPredictivePrefetchEnabled)")
    }

    fun setSegmentPreloadEnabled(enabled: Boolean?) {
        setOverride(KEY_SEGMENT_PRELOAD, enabled)
        Log.i(TAG, "SEGMENT_PRELOAD override set to: $enabled (effective: $isSegmentPreloadEnabled)")
    }

    fun setNeverFreezeAbrEnabled(enabled: Boolean?) {
        setOverride(KEY_NEVER_FREEZE_ABR, enabled)
        Log.i(TAG, "NEVER_FREEZE_ABR override set to: $enabled (effective: $isNeverFreezeAbrEnabled)")
    }

    fun setTtlWatcherEnabled(enabled: Boolean?) {
        setOverride(KEY_TTL_WATCHER, enabled)
        Log.i(TAG, "TTL_WATCHER override set to: $enabled (effective: $isTtlWatcherEnabled)")
    }

    /**
     * Clear a specific flag's runtime override, reverting to build-time default.
     * @throws IllegalArgumentException if key is not a valid feature flag key
     */
    fun clearOverride(key: String) {
        require(key in VALID_KEYS) { "Invalid feature flag key: $key. Valid keys: $VALID_KEYS" }
        prefs.edit().remove(key).apply()
        Log.i(TAG, "Override cleared for $key")
    }

    /**
     * Clear all runtime overrides, reverting to build-time defaults.
     * Only clears known feature flag keys, leaving any other preferences untouched.
     */
    fun clearAllOverrides() {
        clearAllOverridesInternal()
        Log.i(TAG, "All overrides cleared - reverting to build-time defaults")
    }

    /**
     * Check if a specific flag has a runtime override set.
     * @throws IllegalArgumentException if key is not a valid feature flag key
     */
    fun hasOverride(key: String): Boolean {
        require(key in VALID_KEYS) { "Invalid feature flag key: $key. Valid keys: $VALID_KEYS" }
        return prefs.getInt(key, VALUE_USE_DEFAULT) != VALUE_USE_DEFAULT
    }

    /**
     * Get a diagnostic dump of all flag states.
     * Useful for debugging and logging.
     */
    fun getDiagnostics(): Map<String, FlagState> =
        BUILD_DEFAULTS.mapValues { (key, buildDefault) -> getFlagState(key, buildDefault()) }

    /**
     * Log current feature flag states for debugging.
     */
    fun logCurrentState() {
        val diagnostics = getDiagnostics()
        Log.i(TAG, "=== Playback Feature Flags ===")
        for ((key, state) in diagnostics) {
            Log.i(TAG, "  $key: effective=${state.effectiveValue} " +
                "(buildDefault=${state.buildDefault}, override=${state.runtimeOverride})")
        }
        Log.i(TAG, "==============================")
    }

    /**
     * Represents the full state of a feature flag.
     */
    data class FlagState(
        /** The value actually in use */
        val effectiveValue: Boolean,
        /** The build-time default from BuildConfig */
        val buildDefault: Boolean,
        /** The runtime override, if any (null = using build default) */
        val runtimeOverride: Boolean?
    )

    // --- Private helpers ---

    private fun resolveFlag(key: String): Boolean =
        getFlagState(key, BUILD_DEFAULTS.getValue(key)()).effectiveValue

    private fun setOverride(key: String, enabled: Boolean?) {
        val value = when (enabled) {
            true -> VALUE_ENABLED
            false -> VALUE_DISABLED
            null -> VALUE_USE_DEFAULT
        }
        if (value == VALUE_USE_DEFAULT) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putInt(key, value).apply()
        }
    }

    private fun getFlagState(key: String, buildDefault: Boolean): FlagState {
        val override = prefs.getInt(key, VALUE_USE_DEFAULT)
        // Compute both runtimeOverride and effectiveValue from the same read
        val runtimeOverride: Boolean? = when (override) {
            VALUE_ENABLED -> true
            VALUE_DISABLED -> false
            else -> null
        }
        val effectiveValue = when (override) {
            VALUE_ENABLED -> true
            VALUE_DISABLED -> false
            else -> buildDefault
        }
        return FlagState(
            effectiveValue = effectiveValue,
            buildDefault = buildDefault,
            runtimeOverride = runtimeOverride
        )
    }
}
