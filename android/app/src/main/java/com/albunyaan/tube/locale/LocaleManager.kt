package com.albunyaan.tube.locale

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat
import com.albunyaan.tube.R
import com.albunyaan.tube.preferences.SettingsPreferences
import java.util.Locale

/**
 * LocaleManager handles locale selection and application.
 *
 * Responsibilities:
 * - Apply the selected locale via `AppCompatDelegate.setApplicationLocales`.
 * - Provide display name helpers for language selection UI.
 * - Handle "system" selection by resolving to best supported match (or English fallback).
 * - Coordinate save-and-apply operations (delegates storage to SettingsPreferences).
 *
 * Locale Model:
 * - "selection" is what user chose: "system", "en", "ar", "nl"
 * - "effective" is resolved: "en", "ar", "nl" (system resolves to best match)
 *
 * Locale Change Behavior:
 * - In-app change (user selects new language): Activity recreates immediately via
 *   AppCompatDelegate.setApplicationLocales(), applying the new locale.
 * - System language change (while app backgrounded with "system" selected): App
 *   language updates on next cold start when applyStoredLocale() re-resolves the
 *   system locale, not immediately while backgrounded.
 *
 * Storage: Uses SettingsPreferences (DataStore) as the persistence mechanism.
 */
object LocaleManager {

    /**
     * Native display names for each supported locale.
     * Keys are ISO 639-1 codes from SettingsPreferences.SUPPORTED_LOCALES.
     */
    private val LANGUAGE_NATIVE_NAMES = mapOf(
        "en" to "English",
        "ar" to "العربية", // Arabic
        "nl" to "Nederlands" // Dutch
    )

    /**
     * All available language selection keys in display order.
     * Use this for iteration, then call getLanguageDisplayName(context, key)
     * to get the properly localized display name for each key.
     *
     * Keys: "system", "en", "ar", "nl"
     */
    val LANGUAGE_SELECTION_KEYS: List<String> by lazy {
        listOf(SettingsPreferences.LOCALE_SYSTEM) + SettingsPreferences.SUPPORTED_LOCALES
    }

    /**
     * Get the native display name for a locale code.
     * Returns native names like "English", "العربية", "Nederlands".
     *
     * For "system" or unknown codes, returns the code in uppercase as fallback.
     * This is an internal helper - prefer getLanguageDisplayName(context, selection) for UI.
     */
    private fun getNativeDisplayName(localeCode: String): String {
        return LANGUAGE_NATIVE_NAMES[localeCode] ?: localeCode.uppercase(Locale.ROOT)
    }

    /**
     * Apply the stored locale preference synchronously from SharedPreferences cache.
     * Reads the effective locale (resolves "system" to actual language).
     *
     * Reads from a synchronous SharedPreferences cache (not DataStore) to avoid blocking
     * the main thread. The cache is updated whenever locale is changed via DataStore.
     * On first launch (cache miss), falls back to the system locale detection.
     * The async verification in onResume() will correct any stale cache values.
     */
    fun applyStoredLocale(context: Context) {
        applyStoredLocaleWithResult(context)
    }

    /**
     * Apply the stored locale preference synchronously from SharedPreferences cache,
     * returning the applied locale.
     *
     * Reads from a synchronous SharedPreferences cache (not DataStore) to avoid blocking
     * the main thread. The cache is updated whenever locale is changed via DataStore.
     * On first launch (cache miss), falls back to the system locale detection.
     * The async verification in onResume() will correct any stale cache values.
     *
     * @return The locale code that was applied (for later verification)
     */
    fun applyStoredLocaleWithResult(context: Context): String {
        // Read from synchronous cache (fast, no blocking)
        val effectiveLocale = SettingsPreferences.getCachedLocale(context)
            ?: SettingsPreferences.getSystemLocale()  // Fall back to detected system locale on cache miss

        applyLocale(effectiveLocale)
        return effectiveLocale
    }

    /**
     * Apply the locale to the app without persisting.
     * @param languageCode ISO 639-1 language code (e.g., "en", "ar", "nl")
     */
    fun applyLocale(languageCode: String) {
        val localeList = LocaleListCompat.forLanguageTags(languageCode)
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    /**
     * Save the selected locale to DataStore and apply it.
     * This is a suspend function to ensure persistence completes before returning.
     *
     * @param context Application context
     * @param selection The locale selection ("system", "en", "ar", "nl")
     */
    suspend fun saveAndApplyLocale(context: Context, selection: String) {
        val preferences = SettingsPreferences(context)

        // Save to DataStore (atomic - waits for completion)
        preferences.setLocale(selection)

        // Resolve and apply the effective locale
        val effectiveLocale = SettingsPreferences.resolveEffectiveLocale(selection)
        applyLocale(effectiveLocale)
    }

    /**
     * Get the current app locale.
     *
     * Resolution order:
     * 1. AppCompatDelegate.getApplicationLocales() - the per-app locale set via setApplicationLocales()
     * 2. Locale.getDefault() - system default as final fallback
     *
     * Note: After applyStoredLocale() is called on startup, getApplicationLocales() will
     * always return the applied locale. The fallback only applies during very early startup.
     */
    fun getCurrentLocale(): Locale {
        val locales = AppCompatDelegate.getApplicationLocales()
        return if (locales.isEmpty) {
            Locale.getDefault()
        } else {
            locales[0] ?: Locale.getDefault()
        }
    }

    /**
     * Get the current app locale with context-aware fallback.
     *
     * Resolution order:
     * 1. AppCompatDelegate.getApplicationLocales() - the per-app locale set via setApplicationLocales()
     * 2. ConfigurationCompat.getLocales() - respects per-app locale on Android 13+
     * 3. Locale.getDefault() - system default as final fallback
     *
     * Use this variant when you have a Context and need the most configuration-aware resolution,
     * particularly during early startup before applyStoredLocale() is called.
     */
    fun getCurrentLocale(context: Context): Locale {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        return if (!appLocales.isEmpty) {
            appLocales[0] ?: Locale.getDefault()
        } else {
            // Fall back to configuration locale.
            // TODO: Verify that ConfigurationCompat.getLocales() respects per-app locale on
            //  Android 13+ (API 33). See: https://developer.android.com/guide/topics/resources/app-languages
            val configLocales = ConfigurationCompat.getLocales(context.resources.configuration)
            if (configLocales.isEmpty) {
                Locale.getDefault()
            } else {
                configLocales[0] ?: Locale.getDefault()
            }
        }
    }

    /**
     * Get the localized display name for a language selection.
     * For "system", returns localized "System default" from string resources.
     * For specific languages, returns native name (English, العربية, Nederlands).
     *
     * @param context Context for accessing string resources
     * @param selection The locale selection ("system", "en", "ar", "nl")
     */
    fun getLanguageDisplayName(context: Context, selection: String): String {
        return if (selection == SettingsPreferences.LOCALE_SYSTEM) {
            context.getString(R.string.settings_language_system_default)
        } else {
            getNativeDisplayName(selection)
        }
    }

    /**
     * Get the localized display name with resolved locale for "system".
     * Example: "الافتراضي (English)" when system resolves to English in Arabic UI.
     *
     * @param context Context for accessing string resources
     * @param selection The locale selection ("system", "en", "ar", "nl")
     */
    fun getLanguageDisplayNameWithResolved(context: Context, selection: String): String {
        return if (selection == SettingsPreferences.LOCALE_SYSTEM) {
            val resolved = SettingsPreferences.getSystemLocale()
            val resolvedName = getNativeDisplayName(resolved)
            context.getString(R.string.settings_language_system_resolved, resolvedName)
        } else {
            getNativeDisplayName(selection)
        }
    }
}
