package com.albunyaan.tube.util

import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.Locale

/**
 * A JUnit test rule that sets the application locale for the duration of a test.
 *
 * This rule properly applies the locale to both the app's resources and the instrumentation
 * context, ensuring that UI tests correctly reflect the configured locale including RTL
 * layout direction.
 *
 * Usage:
 * ```kotlin
 * @get:Rule
 * val localeRule = LocaleTestRule(Locale("ar"))  // Arabic RTL
 *
 * // Or for default locale
 * @get:Rule
 * val localeRule = LocaleTestRule(Locale.ENGLISH)
 * ```
 *
 * The rule automatically restores the original locale after the test completes.
 *
 * @param locale The locale to use for the test
 */
@Suppress("DEPRECATION") // updateConfiguration is deprecated but necessary for test locale changes
class LocaleTestRule(private val locale: Locale) : TestRule {

    private lateinit var originalLocale: Locale
    private var originalLocaleList: LocaleList? = null

    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                try {
                    setLocale(locale)
                    base.evaluate()
                } finally {
                    restoreLocale()
                }
            }
        }
    }

    private fun setLocale(locale: Locale) {
        // Save original locale
        originalLocale = Locale.getDefault()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            originalLocaleList = LocaleList.getDefault()
        }

        // Set new default locale
        Locale.setDefault(locale)

        // Apply to instrumentation target context
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val targetConfig = Configuration(targetContext.resources.configuration)
        targetConfig.setLocale(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            targetConfig.setLocales(LocaleList(locale))
        }
        targetContext.resources.updateConfiguration(targetConfig, targetContext.resources.displayMetrics)

        // Apply to app context
        val appContext = targetContext.applicationContext
        val appConfig = Configuration(appContext.resources.configuration)
        appConfig.setLocale(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            appConfig.setLocales(LocaleList(locale))
        }
        appContext.resources.updateConfiguration(appConfig, appContext.resources.displayMetrics)
    }

    private fun restoreLocale() {
        // Restore default locale
        Locale.setDefault(originalLocale)

        // Restore instrumentation target context
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val targetConfig = Configuration(targetContext.resources.configuration)
        targetConfig.setLocale(originalLocale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            originalLocaleList?.let { targetConfig.setLocales(it) }
        }
        targetContext.resources.updateConfiguration(targetConfig, targetContext.resources.displayMetrics)

        // Restore app context
        val appContext = targetContext.applicationContext
        val appConfig = Configuration(appContext.resources.configuration)
        appConfig.setLocale(originalLocale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            originalLocaleList?.let { appConfig.setLocales(it) }
        }
        appContext.resources.updateConfiguration(appConfig, appContext.resources.displayMetrics)
    }

    companion object {
        /** Arabic locale for RTL testing */
        val ARABIC = Locale("ar")

        /** English locale for LTR testing */
        val ENGLISH = Locale.ENGLISH

        /** Dutch locale */
        val DUTCH = Locale("nl")
    }
}
