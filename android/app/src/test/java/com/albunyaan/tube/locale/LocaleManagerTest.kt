package com.albunyaan.tube.locale

import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.albunyaan.tube.preferences.SettingsPreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLocaleManager
import java.util.Locale

/**
 * Unit tests for LocaleManager.getCurrentLocale(context) fallback ordering, and for
 * resolving "System default" to the device language (see the tests at the end).
 *
 * Tests verify the resolution order:
 * 1. AppCompatDelegate.getApplicationLocales() - per-app locale
 * 2. ConfigurationCompat.getLocales() - configuration locale
 * 3. Locale.getDefault() - system default fallback
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class LocaleManagerTest {

    private lateinit var originalDefaultLocale: Locale

    @Before
    fun setUp() {
        originalDefaultLocale = Locale.getDefault()
        // Clear any previously set application locales
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @After
    fun tearDown() {
        // Restore original state
        Locale.setDefault(originalDefaultLocale)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @Test
    fun `getCurrentLocale with context returns non-null locale`() {
        val context = RuntimeEnvironment.getApplication()
        val result = LocaleManager.getCurrentLocale(context)
        assertNotNull("getCurrentLocale should never return null", result)
    }

    @Test
    fun `getCurrentLocale uses AppCompatDelegate locale when set`() {
        val context = RuntimeEnvironment.getApplication()

        // Set Arabic as the per-app locale
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ar"))

        val result = LocaleManager.getCurrentLocale(context)

        assertEquals(
            "Should use AppCompatDelegate locale when set",
            "ar",
            result.language
        )
    }

    @Test
    fun `getCurrentLocale uses AppCompatDelegate locale with region`() {
        val context = RuntimeEnvironment.getApplication()

        // Set Dutch (Netherlands) as the per-app locale
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("nl-NL"))

        val result = LocaleManager.getCurrentLocale(context)

        assertEquals(
            "Should use AppCompatDelegate locale language",
            "nl",
            result.language
        )
    }

    @Test
    fun `getCurrentLocale falls back to configuration locale when AppCompatDelegate is empty`() {
        // Ensure AppCompatDelegate has no locale set
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())

        // Set a distinct default locale that differs from config to verify priority
        Locale.setDefault(Locale.FRENCH)

        // Create a context with German configuration locale
        val baseContext = RuntimeEnvironment.getApplication()
        val germanLocale = Locale.GERMANY
        val config = Configuration(baseContext.resources.configuration)
        config.setLocale(germanLocale)
        val configuredContext = baseContext.createConfigurationContext(config)

        // When using the configured context, should get German (from config)
        // not French (from Locale.getDefault())
        val result = LocaleManager.getCurrentLocale(configuredContext)

        assertEquals(
            "Should use configuration locale from context when AppCompatDelegate is empty",
            "de",
            result.language
        )
    }

    @Test
    fun `getCurrentLocale returns valid locale when AppCompatDelegate is empty`() {
        // Clear AppCompatDelegate locales
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())

        // Use the base application context
        val context = RuntimeEnvironment.getApplication()
        val result = LocaleManager.getCurrentLocale(context)

        // Should return a valid locale from either ConfigurationCompat or Locale.getDefault()
        // The exact locale depends on the test environment, but it must never be null
        assertNotNull("Should return a valid locale from the fallback chain", result)
        assertTrue(
            "Language code should be non-empty",
            result.language.isNotEmpty()
        )
    }

    @Test
    fun `getCurrentLocale prioritizes AppCompatDelegate over default locale`() {
        val context = RuntimeEnvironment.getApplication()

        // Set default to French
        Locale.setDefault(Locale.FRENCH)

        // Set AppCompatDelegate to Arabic
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ar"))

        val result = LocaleManager.getCurrentLocale(context)

        assertEquals(
            "AppCompatDelegate locale should take priority over Locale.getDefault()",
            "ar",
            result.language
        )
    }

    @Test
    fun `getCurrentLocale without context returns valid locale`() {
        // Test the no-context overload
        val result = LocaleManager.getCurrentLocale()
        assertNotNull("getCurrentLocale() should never return null", result)
    }

    @Test
    fun `getCurrentLocale without context uses AppCompatDelegate when set`() {
        // Set Dutch as the per-app locale
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("nl"))

        val result = LocaleManager.getCurrentLocale()

        assertEquals(
            "getCurrentLocale() should use AppCompatDelegate locale when set",
            "nl",
            result.language
        )
    }

    @Test
    fun `getCurrentLocale without context falls back to default when AppCompatDelegate empty`() {
        // Ensure AppCompatDelegate is empty
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())

        // Set a specific default
        Locale.setDefault(Locale.GERMAN)

        val result = LocaleManager.getCurrentLocale()

        assertEquals(
            "getCurrentLocale() should fall back to Locale.getDefault() when AppCompatDelegate is empty",
            "de",
            result.language
        )
    }

    /**
     * On API 33+ the platform folds the per-app language into the process configuration,
     * so Resources.getSystem() reports the app's language, not the device's. Only
     * LocaleManager.getSystemLocales() keeps the device list. Robolectric's stock shadow
     * reads Resources.getSystem() for both, so this shadow pins the device list to nl-NL.
     * Dutch, not English, so a fall-through to the "en" fallback cannot pass the tests.
     */
    @Implements(value = android.app.LocaleManager::class, minSdk = Build.VERSION_CODES.TIRAMISU)
    class DutchDeviceLocaleManager : ShadowLocaleManager() {
        @Implementation
        override fun getSystemLocales(): LocaleList = LocaleList.forLanguageTags("nl-NL")
    }

    /** Device nl-NL, app currently in Arabic, as after picking Arabic in the picker. */
    private fun <T> withAppInArabic(block: () -> T): T {
        val system = Resources.getSystem()
        val original = Configuration(system.configuration)
        val arabic = Configuration(original).apply { setLocales(LocaleList.forLanguageTags("ar")) }
        @Suppress("DEPRECATION")
        system.updateConfiguration(arabic, system.displayMetrics)
        try {
            return block()
        } finally {
            @Suppress("DEPRECATION")
            system.updateConfiguration(original, system.displayMetrics)
        }
    }

    @Test
    @Config(sdk = [35], shadows = [DutchDeviceLocaleManager::class])
    fun `system default label names the device language, not the app language`() {
        val context = RuntimeEnvironment.getApplication()
        val label = withAppInArabic {
            LocaleManager.getLanguageDisplayNameWithResolved(context, SettingsPreferences.LOCALE_SYSTEM)
        }
        assertEquals("System default (Nederlands)", label)
    }

    @Test
    @Config(sdk = [35], shadows = [DutchDeviceLocaleManager::class])
    fun `choosing system default after Arabic caches the device language`() {
        val context = RuntimeEnvironment.getApplication()
        withAppInArabic {
            runBlocking { LocaleManager.saveAndApplyLocale(context, SettingsPreferences.LOCALE_SYSTEM) }
        }
        // cached_locale is what the next cold start applies (applyStoredLocaleWithResult).
        assertEquals("nl", SettingsPreferences.getCachedLocale(context))
    }
}
