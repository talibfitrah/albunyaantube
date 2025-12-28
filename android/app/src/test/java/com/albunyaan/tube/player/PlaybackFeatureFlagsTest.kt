package com.albunyaan.tube.player

import android.content.Context
import android.content.SharedPreferences
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
 * Uses Robolectric for SharedPreferences support.
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
    fun `synth adaptive uses build-time default when no override set`() {
        assertEquals(
            "Should use build-time default",
            BuildConfig.ENABLE_SYNTH_ADAPTIVE,
            featureFlags.isSynthAdaptiveEnabled
        )
    }

    @Test
    fun `mpd prefetch uses build-time default when no override set`() {
        assertEquals(
            "Should use build-time default",
            BuildConfig.ENABLE_MPD_PREFETCH,
            featureFlags.isMpdPrefetchEnabled
        )
    }

    @Test
    fun `degradation manager uses build-time default when no override set`() {
        assertEquals(
            "Should use build-time default",
            BuildConfig.ENABLE_DEGRADATION_MANAGER,
            featureFlags.isDegradationManagerEnabled
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
    fun `synth adaptive override to true takes precedence`() {
        featureFlags.setSynthAdaptiveEnabled(true)
        assertTrue("Should be enabled with override", featureFlags.isSynthAdaptiveEnabled)
    }

    @Test
    fun `synth adaptive override to false takes precedence`() {
        featureFlags.setSynthAdaptiveEnabled(false)
        assertFalse("Should be disabled with override", featureFlags.isSynthAdaptiveEnabled)
    }

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
    fun `degradation manager override to true takes precedence`() {
        featureFlags.setDegradationManagerEnabled(true)
        assertTrue("Should be enabled with override", featureFlags.isDegradationManagerEnabled)
    }

    @Test
    fun `degradation manager override to false takes precedence`() {
        featureFlags.setDegradationManagerEnabled(false)
        assertFalse("Should be disabled with override", featureFlags.isDegradationManagerEnabled)
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
        featureFlags.setSynthAdaptiveEnabled(!BuildConfig.ENABLE_SYNTH_ADAPTIVE)
        assertEquals(
            "Override should take effect",
            !BuildConfig.ENABLE_SYNTH_ADAPTIVE,
            featureFlags.isSynthAdaptiveEnabled
        )

        // Now clear by setting to null
        featureFlags.setSynthAdaptiveEnabled(null)
        assertEquals(
            "Should revert to build-time default after setting null",
            BuildConfig.ENABLE_SYNTH_ADAPTIVE,
            featureFlags.isSynthAdaptiveEnabled
        )
    }

    @Test
    fun `clearAllOverrides reverts all flags to build-time defaults`() {
        // Set overrides for all flags
        featureFlags.setSynthAdaptiveEnabled(!BuildConfig.ENABLE_SYNTH_ADAPTIVE)
        featureFlags.setMpdPrefetchEnabled(!BuildConfig.ENABLE_MPD_PREFETCH)
        featureFlags.setDegradationManagerEnabled(!BuildConfig.ENABLE_DEGRADATION_MANAGER)
        featureFlags.setIosFetchEnabled(!BuildConfig.ENABLE_NPE_IOS_FETCH)

        // Clear all
        featureFlags.clearAllOverrides()

        // Verify all reverted to build defaults
        assertEquals(
            "Synth adaptive should revert",
            BuildConfig.ENABLE_SYNTH_ADAPTIVE,
            featureFlags.isSynthAdaptiveEnabled
        )
        assertEquals(
            "MPD prefetch should revert",
            BuildConfig.ENABLE_MPD_PREFETCH,
            featureFlags.isMpdPrefetchEnabled
        )
        assertEquals(
            "Degradation manager should revert",
            BuildConfig.ENABLE_DEGRADATION_MANAGER,
            featureFlags.isDegradationManagerEnabled
        )
        assertEquals(
            "iOS fetch should revert",
            BuildConfig.ENABLE_NPE_IOS_FETCH,
            featureFlags.isIosFetchEnabled
        )
    }

    // --- hasOverride Tests ---

    @Test
    fun `hasOverride returns false when no override set`() {
        assertFalse("Should not have override initially", featureFlags.hasOverride("synth_adaptive"))
    }

    @Test
    fun `hasOverride returns true when override set`() {
        featureFlags.setSynthAdaptiveEnabled(true)
        assertTrue("Should have override after setting", featureFlags.hasOverride("synth_adaptive"))
    }

    @Test
    fun `hasOverride returns false after clearing override`() {
        featureFlags.setSynthAdaptiveEnabled(true)
        assertTrue("Should have override", featureFlags.hasOverride("synth_adaptive"))

        featureFlags.clearOverride("synth_adaptive")
        assertFalse("Should not have override after clearing", featureFlags.hasOverride("synth_adaptive"))
    }

    // --- Diagnostics Tests ---

    @Test
    fun `getDiagnostics returns all flags`() {
        val diagnostics = featureFlags.getDiagnostics()

        assertTrue("Should contain synth_adaptive", diagnostics.containsKey("synth_adaptive"))
        assertTrue("Should contain mpd_prefetch", diagnostics.containsKey("mpd_prefetch"))
        assertTrue("Should contain degradation_manager", diagnostics.containsKey("degradation_manager"))
        assertTrue("Should contain ios_fetch", diagnostics.containsKey("ios_fetch"))
        assertEquals("Should have 4 flags", 4, diagnostics.size)
    }

    @Test
    fun `getDiagnostics shows correct state without overrides`() {
        val diagnostics = featureFlags.getDiagnostics()

        val synthState = diagnostics["synth_adaptive"]!!
        assertEquals("effectiveValue should match build default", BuildConfig.ENABLE_SYNTH_ADAPTIVE, synthState.effectiveValue)
        assertEquals("buildDefault should be correct", BuildConfig.ENABLE_SYNTH_ADAPTIVE, synthState.buildDefault)
        assertNull("runtimeOverride should be null", synthState.runtimeOverride)
    }

    @Test
    fun `getDiagnostics shows correct state with override`() {
        featureFlags.setSynthAdaptiveEnabled(true)

        val diagnostics = featureFlags.getDiagnostics()
        val synthState = diagnostics["synth_adaptive"]!!

        assertTrue("effectiveValue should be true", synthState.effectiveValue)
        assertEquals("buildDefault should still be build default", BuildConfig.ENABLE_SYNTH_ADAPTIVE, synthState.buildDefault)
        assertEquals("runtimeOverride should be true", true, synthState.runtimeOverride)
    }

    @Test
    fun `getDiagnostics shows disabled override correctly`() {
        featureFlags.setSynthAdaptiveEnabled(false)

        val diagnostics = featureFlags.getDiagnostics()
        val synthState = diagnostics["synth_adaptive"]!!

        assertFalse("effectiveValue should be false", synthState.effectiveValue)
        assertEquals("runtimeOverride should be false", false, synthState.runtimeOverride)
    }

    // --- Persistence Tests ---

    @Test
    fun `overrides persist across new instances`() {
        // Set override in first instance
        featureFlags.setSynthAdaptiveEnabled(!BuildConfig.ENABLE_SYNTH_ADAPTIVE)

        // Create new instance (simulates app restart)
        val newInstance = PlaybackFeatureFlags(context)

        assertEquals(
            "Override should persist to new instance",
            !BuildConfig.ENABLE_SYNTH_ADAPTIVE,
            newInstance.isSynthAdaptiveEnabled
        )
    }

    @Test
    fun `clearAllOverrides persists across new instances`() {
        // Set override
        featureFlags.setSynthAdaptiveEnabled(!BuildConfig.ENABLE_SYNTH_ADAPTIVE)

        // Clear all
        featureFlags.clearAllOverrides()

        // Create new instance (simulates app restart)
        val newInstance = PlaybackFeatureFlags(context)

        assertEquals(
            "Clear should persist - value should be build default",
            BuildConfig.ENABLE_SYNTH_ADAPTIVE,
            newInstance.isSynthAdaptiveEnabled
        )
    }
}
