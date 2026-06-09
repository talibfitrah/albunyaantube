package com.albunyaan.tube.player

import android.content.Context
import com.albunyaan.tube.BuildConfig
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Unit tests for PlaybackFeatureFlags.
 *
 * Tests verify:
 * - Build-time defaults are used when no override is set
 * - Runtime overrides take precedence over build-time defaults
 * - Clearing overrides reverts to build-time defaults
 * - Diagnostics report correct state
 *
 * Uses Robolectric for SharedPreferences support. `mpd_prefetch` is the canonical
 * example flag for the generic override/clear/diagnostics mechanism tests.
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackFeatureFlagsTest {

    private lateinit var context: Context
    private lateinit var featureFlags: PlaybackFeatureFlags

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // Clear any existing preferences before each test
        context.getSharedPreferences("playback_feature_flags", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        featureFlags = PlaybackFeatureFlags(context)
    }

    // --- Build-time Default Tests ---

    @Test
    fun `mpd prefetch uses build-time default when no override set`() {
        assertEquals(
            "Should use build-time default",
            BuildConfig.ENABLE_MPD_PREFETCH,
            featureFlags.isMpdPrefetchEnabled
        )
    }

    @Test
    fun `ios fetch uses build-time default when no override set`() {
        assertEquals(
            "Should use build-time default",
            BuildConfig.ENABLE_NPE_IOS_FETCH,
            featureFlags.isIosFetchEnabled
        )
    }

    // --- Runtime Override Tests ---

    @Test
    fun `mpd prefetch override to true takes precedence`() {
        featureFlags.setMpdPrefetchEnabled(true)
        assertTrue("Should be enabled with override", featureFlags.isMpdPrefetchEnabled)
    }

    @Test
    fun `mpd prefetch override to false takes precedence`() {
        featureFlags.setMpdPrefetchEnabled(false)
        assertFalse("Should be disabled with override", featureFlags.isMpdPrefetchEnabled)
    }

    @Test
    fun `ios fetch override to true takes precedence`() {
        featureFlags.setIosFetchEnabled(true)
        assertTrue("Should be enabled with override", featureFlags.isIosFetchEnabled)
    }

    @Test
    fun `ios fetch override to false takes precedence`() {
        featureFlags.setIosFetchEnabled(false)
        assertFalse("Should be disabled with override", featureFlags.isIosFetchEnabled)
    }

    // --- Clear Override Tests ---

    @Test
    fun `setting override to null clears override`() {
        // First set an override
        featureFlags.setMpdPrefetchEnabled(!BuildConfig.ENABLE_MPD_PREFETCH)
        assertEquals(
            "Override should take effect",
            !BuildConfig.ENABLE_MPD_PREFETCH,
            featureFlags.isMpdPrefetchEnabled
        )

        // Now clear by setting to null
        featureFlags.setMpdPrefetchEnabled(null)
        assertEquals(
            "Should revert to build-time default after setting null",
            BuildConfig.ENABLE_MPD_PREFETCH,
            featureFlags.isMpdPrefetchEnabled
        )
    }

    @Test
    fun `clearAllOverrides reverts all flags to build-time defaults`() {
        // Set overrides for several flags
        featureFlags.setMpdPrefetchEnabled(!BuildConfig.ENABLE_MPD_PREFETCH)
        featureFlags.setIosFetchEnabled(!BuildConfig.ENABLE_NPE_IOS_FETCH)
        featureFlags.setGenerousCropBudgetEnabled(true)

        // Clear all
        featureFlags.clearAllOverrides()

        // Verify all reverted to build defaults
        assertEquals(
            "MPD prefetch should revert",
            BuildConfig.ENABLE_MPD_PREFETCH,
            featureFlags.isMpdPrefetchEnabled
        )
        assertEquals(
            "iOS fetch should revert",
            BuildConfig.ENABLE_NPE_IOS_FETCH,
            featureFlags.isIosFetchEnabled
        )
        // generous_crop_budget default is device-dependent (false in unit test env)
        assertFalse(
            "Generous crop budget should revert to device default",
            featureFlags.isGenerousCropBudgetEnabled
        )
    }

    // --- hasOverride Tests ---

    @Test
    fun `hasOverride returns false when no override set`() {
        assertFalse("Should not have override initially", featureFlags.hasOverride("mpd_prefetch"))
    }

    @Test
    fun `hasOverride returns true when override set`() {
        featureFlags.setMpdPrefetchEnabled(true)
        assertTrue("Should have override after setting", featureFlags.hasOverride("mpd_prefetch"))
    }

    @Test
    fun `hasOverride returns false after clearing override`() {
        featureFlags.setMpdPrefetchEnabled(true)
        assertTrue("Should have override", featureFlags.hasOverride("mpd_prefetch"))

        featureFlags.clearOverride("mpd_prefetch")
        assertFalse("Should not have override after clearing", featureFlags.hasOverride("mpd_prefetch"))
    }

    // --- Diagnostics Tests ---

    @Test
    fun `getDiagnostics returns all flags`() {
        val diagnostics = featureFlags.getDiagnostics()

        assertTrue("Should contain mpd_prefetch", diagnostics.containsKey("mpd_prefetch"))
        assertTrue("Should contain ios_fetch", diagnostics.containsKey("ios_fetch"))
        assertTrue("Should contain generous_crop_budget", diagnostics.containsKey("generous_crop_budget"))
        assertTrue("Should contain client_rotation", diagnostics.containsKey("client_rotation"))
        assertTrue("Should contain predictive_prefetch", diagnostics.containsKey("predictive_prefetch"))
        assertTrue("Should contain segment_preload", diagnostics.containsKey("segment_preload"))
        assertTrue("Should contain never_freeze_abr", diagnostics.containsKey("never_freeze_abr"))
        assertTrue("Should contain ttl_watcher", diagnostics.containsKey("ttl_watcher"))
        assertEquals("Should have 8 flags", 8, diagnostics.size)
    }

    // --- Generous Crop Budget Flag Tests ---
    // Note: default is device-dependent (isSamsungS25Ultra), not a BuildConfig constant.
    // In unit tests, Build.MODEL is empty/generic so default is false.

    @Test
    fun `generous crop budget uses device-based default when no override set`() {
        // In unit test environment, Build.MODEL is not SM-S938* so default is false
        assertFalse("Should default to false on non-S25-Ultra", featureFlags.isGenerousCropBudgetEnabled)
    }

    @Test
    fun `generous crop budget override to true takes precedence`() {
        featureFlags.setGenerousCropBudgetEnabled(true)
        assertTrue("Should be enabled with override", featureFlags.isGenerousCropBudgetEnabled)
    }

    @Test
    fun `generous crop budget override to false takes precedence`() {
        featureFlags.setGenerousCropBudgetEnabled(false)
        assertFalse("Should be disabled with override", featureFlags.isGenerousCropBudgetEnabled)
    }

    @Test
    fun `generous crop budget clear override reverts to device default`() {
        featureFlags.setGenerousCropBudgetEnabled(true)
        assertTrue("Should be enabled with override", featureFlags.isGenerousCropBudgetEnabled)

        featureFlags.clearOverride("generous_crop_budget")
        assertFalse("Should revert to device default (false in test env)", featureFlags.isGenerousCropBudgetEnabled)
    }

    @Test
    fun `getDiagnostics shows correct state without overrides`() {
        val diagnostics = featureFlags.getDiagnostics()

        val mpdState = diagnostics["mpd_prefetch"]!!
        assertEquals("effectiveValue should match build default", BuildConfig.ENABLE_MPD_PREFETCH, mpdState.effectiveValue)
        assertEquals("buildDefault should be correct", BuildConfig.ENABLE_MPD_PREFETCH, mpdState.buildDefault)
        assertNull("runtimeOverride should be null", mpdState.runtimeOverride)
    }

    @Test
    fun `getDiagnostics shows correct state with override`() {
        featureFlags.setMpdPrefetchEnabled(true)

        val diagnostics = featureFlags.getDiagnostics()
        val mpdState = diagnostics["mpd_prefetch"]!!

        assertTrue("effectiveValue should be true", mpdState.effectiveValue)
        assertEquals("buildDefault should still be build default", BuildConfig.ENABLE_MPD_PREFETCH, mpdState.buildDefault)
        assertEquals("runtimeOverride should be true", true, mpdState.runtimeOverride)
    }

    @Test
    fun `getDiagnostics shows disabled override correctly`() {
        featureFlags.setMpdPrefetchEnabled(false)

        val diagnostics = featureFlags.getDiagnostics()
        val mpdState = diagnostics["mpd_prefetch"]!!

        assertFalse("effectiveValue should be false", mpdState.effectiveValue)
        assertEquals("runtimeOverride should be false", false, mpdState.runtimeOverride)
    }

    // --- Persistence Tests ---

    @Test
    fun `overrides persist across new instances`() {
        // Set override in first instance
        featureFlags.setMpdPrefetchEnabled(!BuildConfig.ENABLE_MPD_PREFETCH)

        // Create new instance (simulates app restart)
        val newInstance = PlaybackFeatureFlags(context)

        assertEquals(
            "Override should persist to new instance",
            !BuildConfig.ENABLE_MPD_PREFETCH,
            newInstance.isMpdPrefetchEnabled
        )
    }

    @Test
    fun `clearAllOverrides persists across new instances`() {
        // Set override
        featureFlags.setMpdPrefetchEnabled(!BuildConfig.ENABLE_MPD_PREFETCH)

        // Clear all
        featureFlags.clearAllOverrides()

        // Create new instance (simulates app restart)
        val newInstance = PlaybackFeatureFlags(context)

        assertEquals(
            "Clear should persist - value should be build default",
            BuildConfig.ENABLE_MPD_PREFETCH,
            newInstance.isMpdPrefetchEnabled
        )
    }
}
