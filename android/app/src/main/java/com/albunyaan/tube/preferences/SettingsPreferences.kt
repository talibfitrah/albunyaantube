package com.albunyaan.tube.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Settings preferences manager using DataStore.
 * Handles all app settings including locale, playback, downloads, and content filtering.
 */
class SettingsPreferences(private val context: Context) {

    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

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

        // Default values
        const val DEFAULT_LOCALE = "en"
        const val DEFAULT_DOWNLOAD_QUALITY = "medium"
        const val DEFAULT_THEME = "system"
        const val DEFAULT_AUDIO_ONLY = false
        const val DEFAULT_BACKGROUND_PLAY = true
        const val DEFAULT_WIFI_ONLY = false
        const val DEFAULT_SAFE_MODE = true
    }

    // Locale
    val locale: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[LOCALE_KEY] ?: DEFAULT_LOCALE
    }

    suspend fun setLocale(locale: String) {
        context.dataStore.edit { preferences ->
            preferences[LOCALE_KEY] = locale
        }
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
    }
}

