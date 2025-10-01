package com.albunyaan.tube.locale

import android.content.Context
import androidx.core.os.LocaleListCompat

/**
 * LocaleManager placeholder for the Android skeleton.
 *
 * Responsibilities for the future implementation:
 * - Persist the selected locale using DataStore (key: `app_locale`).
 * - Expose helper methods to apply the locale via `AppCompatDelegate.setApplicationLocales`.
 * - Provide helpers for formatting numerals according to the active locale (Arabic Indic digits, etc.).
 * - Surface default locale detection and fallback order (device locale → English).
 */
object LocaleManager {

    fun detectDefaultLocale(): LocaleListCompat {
        return LocaleListCompat.getAdjustedDefault()
    }

    fun applyStoredLocale(context: Context) {
        // TODO: Read DataStore preference and call AppCompatDelegate.setApplicationLocales
    }
}
