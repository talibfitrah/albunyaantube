package com.albunyaan.tube.preferences

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import android.os.Build
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Settings preferences manager using DataStore.
 * Handles all app settings including locale, playback, downloads, and content filtering.
 */
class SettingsPreferences(private val context: Context) {

    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

        // SharedPreferences name for synchronous cache (used for fast startup reads)
        private const val CACHE_PREFS_NAME = "settings_cache"
        private const val CACHE_THEME_KEY = "cached_theme"
        private const val CACHE_LOCALE_KEY = "cached_locale"

        /**
         * Get the SharedPreferences used for synchronous cache reads.
         * This is separate from DataStore and used only for fast startup access.
         */
        fun getCachePrefs(context: Context): SharedPreferences {
            return context.getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE)
        }

        /**
         * Get the cached theme synchronously (for use in onCreate before super).
         * Returns null if no cached value exists yet.
         */
        fun getCachedTheme(context: Context): String? {
            return getCachePrefs(context).getString(CACHE_THEME_KEY, null)
        }

        /**
         * Get the cached locale synchronously (for use in onCreate before super).
         * Returns null if no cached value exists yet.
         */
        fun getCachedLocale(context: Context): String? {
            return getCachePrefs(context).getString(CACHE_LOCALE_KEY, null)
        }

        /**
         * Update the theme cache synchronously.
         * Called whenever theme is changed via DataStore.
         */
        internal fun updateThemeCache(context: Context, theme: String) {
            getCachePrefs(context).edit().putString(CACHE_THEME_KEY, theme).apply()
        }

        /**
         * Update the locale cache synchronously.
         * Called whenever locale is changed via DataStore.
         */
        internal fun updateLocaleCache(context: Context, locale: String) {
            getCachePrefs(context).edit().putString(CACHE_LOCALE_KEY, locale).apply()
        }

        // Locale
        val LOCALE_KEY = stringPreferencesKey("app_locale")

        // Playback preferences
        val AUDIO_ONLY_KEY = booleanPreferencesKey("audio_only")
        val BACKGROUND_PLAY_KEY = booleanPreferencesKey("background_play")

        // Download preferences
        val DOWNLOAD_QUALITY_KEY = stringPreferencesKey("download_quality")
        val WIFI_ONLY_KEY = booleanPreferencesKey("wifi_only_downloads")

        // Content preferences
        val SAFE_MODE_KEY = booleanPreferencesKey("safe_mode")

        // Theme preference (for future dark mode support)
        val THEME_KEY = stringPreferencesKey("theme")

        // Onboarding
        val ONBOARDING_COMPLETED_KEY = booleanPreferencesKey("onboarding_completed")

        // Supported languages - use listOf for explicit ordering (displayed in UI)
        val SUPPORTED_LOCALES = listOf("en", "ar", "nl")

        // Derived Set for O(1) membership checks (used in getSystemLocale)
        private val SUPPORTED_LOCALES_SET: Set<String> = SUPPORTED_LOCALES.toSet()

        // Special value for "follow system" locale
        const val LOCALE_SYSTEM = "system"

        // Theme values (centralized to reduce typo risk)
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        // Default values
        const val DEFAULT_LOCALE = LOCALE_SYSTEM  // Default to system on first run
        const val DEFAULT_THEME = THEME_SYSTEM    // Match system on first run
        const val DEFAULT_DOWNLOAD_QUALITY = "medium"

        /**
         * Get the effective locale based on device language preferences.
         *
         * Uses Resources.getSystem().configuration.locales to get the TRUE system locales,
         * not LocaleList.getDefault() which can reflect the app's overridden locale.
         *
         * Multi-locale priority: On devices with multiple preferred languages (e.g.,
         * French → Arabic → English), iterates in user's priority order and returns
         * the first match from supported locales (en, ar, nl). In this example,
         * Arabic would be returned since French isn't supported but Arabic is.
         *
         * Falls back to English if no supported locale is found in the preference list.
         */
        fun getSystemLocale(): String {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Use Resources.getSystem() to get true system locales, not app-overridden ones
                val systemLocales = android.content.res.Resources.getSystem().configuration.locales
                for (i in 0 until systemLocales.size()) {
                    val language = systemLocales[i].language
                    if (language in SUPPORTED_LOCALES_SET) {
                        return language
                    }
                }
            } else {
                // Fallback for older devices - use system Resources configuration
                @Suppress("DEPRECATION")
                val deviceLanguage = android.content.res.Resources.getSystem().configuration.locale.language
                if (deviceLanguage in SUPPORTED_LOCALES_SET) {
                    return deviceLanguage
                }
            }
            return "en" // Fallback to English
        }

        /**
         * Resolve the effective locale from a selection.
         * If selection is "system", returns the best matching system locale.
         * Otherwise returns the explicit selection.
         */
        fun resolveEffectiveLocale(selection: String): String {
            return if (selection == LOCALE_SYSTEM) {
                getSystemLocale()
            } else {
                selection
            }
        }

        /**
         * Detect the system theme based on device configuration.
         * Returns "dark" if system is in night mode, "light" otherwise.
         */
        fun getSystemTheme(context: Context): String {
            val nightModeFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return if (nightModeFlags == Configuration.UI_MODE_NIGHT_YES) THEME_DARK else THEME_LIGHT
        }

        const val DEFAULT_AUDIO_ONLY = false
        const val DEFAULT_BACKGROUND_PLAY = true
        const val DEFAULT_WIFI_ONLY = false
        const val DEFAULT_SAFE_MODE = true
    }

    /**
     * The user's locale selection ("system", "en", "ar", "nl").
     * Use this for showing the current selection in UI.
     */
    val localeSelection: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[LOCALE_KEY] ?: DEFAULT_LOCALE
    }

    /**
     * The effective locale code ("en", "ar", "nl") - resolved from selection.
     * Use this for actually applying the locale.
     */
    val effectiveLocale: Flow<String> = context.dataStore.data.map { preferences ->
        val selection = preferences[LOCALE_KEY] ?: DEFAULT_LOCALE
        resolveEffectiveLocale(selection)
    }

    /**
     * @deprecated Use localeSelection for UI display or effectiveLocale for application.
     * This property returns effectiveLocale (resolved locale code).
     */
    @Deprecated(
        message = "Use localeSelection for UI display or effectiveLocale for applying locale",
        replaceWith = ReplaceWith("effectiveLocale")
    )
    val locale: Flow<String> = effectiveLocale

    suspend fun setLocale(localeSelection: String) {
        context.dataStore.edit { preferences ->
            preferences[LOCALE_KEY] = localeSelection
        }
        // Update synchronous cache for fast startup reads (cache the effective locale, not selection)
        val effectiveLocale = resolveEffectiveLocale(localeSelection)
        updateLocaleCache(context, effectiveLocale)
    }

    // Audio-only mode
    val audioOnly: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUDIO_ONLY_KEY] ?: DEFAULT_AUDIO_ONLY
    }

    suspend fun setAudioOnly(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUDIO_ONLY_KEY] = enabled
        }
    }

    // Background playback
    val backgroundPlay: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[BACKGROUND_PLAY_KEY] ?: DEFAULT_BACKGROUND_PLAY
    }

    suspend fun setBackgroundPlay(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[BACKGROUND_PLAY_KEY] = enabled
        }
    }

    // Download quality
    val downloadQuality: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[DOWNLOAD_QUALITY_KEY] ?: DEFAULT_DOWNLOAD_QUALITY
    }

    suspend fun setDownloadQuality(quality: String) {
        context.dataStore.edit { preferences ->
            preferences[DOWNLOAD_QUALITY_KEY] = quality
        }
    }

    // WiFi-only downloads
    val wifiOnly: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[WIFI_ONLY_KEY] ?: DEFAULT_WIFI_ONLY
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[WIFI_ONLY_KEY] = enabled
        }
    }

    // Safe mode (family-friendly content filtering)
    val safeMode: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[SAFE_MODE_KEY] ?: DEFAULT_SAFE_MODE
    }

    suspend fun setSafeMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SAFE_MODE_KEY] = enabled
        }
    }

    // Theme
    val theme: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[THEME_KEY] ?: DEFAULT_THEME
    }

    suspend fun setTheme(theme: String) {
        context.dataStore.edit { preferences ->
            preferences[THEME_KEY] = theme
        }
        // Update synchronous cache for fast startup reads
        updateThemeCache(context, theme)
    }

    // Onboarding completion
    val onboardingCompleted: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[ONBOARDING_COMPLETED_KEY] ?: false
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[ONBOARDING_COMPLETED_KEY] = completed
        }
    }
}
