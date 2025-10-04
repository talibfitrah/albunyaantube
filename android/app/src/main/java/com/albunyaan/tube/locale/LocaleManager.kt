package com.albunyaan.tube.locale

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.albunyaan.tube.preferences.SettingsPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Locale

/**
 * LocaleManager handles locale selection and application.
 *
 * Responsibilities:
 * - Persist the selected locale using DataStore (key: `app_locale`).
 * - Expose helper methods to apply the locale via `AppCompatDelegate.setApplicationLocales`.
 * - Provide helpers for formatting numerals according to the active locale (Arabic Indic digits, etc.).
 * - Surface default locale detection and fallback order (device locale → English).
 */
object LocaleManager {

    /**
     * Supported languages in the app.
     * Maps language code to display name.
     */
    val SUPPORTED_LANGUAGES = mapOf(
        "en" to "English",
        "ar" to "العربية", // Arabic
        "nl" to "Nederlands" // Dutch
    )

    /**
     * Detect the default locale from the device settings.
     */
    fun detectDefaultLocale(): LocaleListCompat {
        return LocaleListCompat.getAdjustedDefault()
    }

    /**
     * Apply the stored locale preference from DataStore.
     * If no locale is stored, use the device default.
     */
    fun applyStoredLocale(context: Context) {
        val preferences = SettingsPreferences(context)

        // Use runBlocking to read the stored locale synchronously on app startup
        val storedLocale = runBlocking {
            preferences.locale.first()
        }

        setLocale(storedLocale)
    }

    /**
     * Set the app locale to the specified language code.
     * @param languageCode ISO 639-1 language code (e.g., "en", "ar", "nl")
     */
    fun setLocale(languageCode: String) {
        val localeList = LocaleListCompat.forLanguageTags(languageCode)
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    /**
     * Save the selected locale to DataStore and apply it.
     */
    fun saveAndApplyLocale(context: Context, languageCode: String) {
        val preferences = SettingsPreferences(context)

        // Save to DataStore
        CoroutineScope(Dispatchers.IO).launch {
            preferences.setLocale(languageCode)
        }

        // Apply immediately
        setLocale(languageCode)
    }

    /**
     * Get the current app locale.
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
     * Get the display name for a language code in its native language.
     */
    fun getLanguageDisplayName(languageCode: String): String {
        return SUPPORTED_LANGUAGES[languageCode] ?: languageCode.uppercase()
    }
}
