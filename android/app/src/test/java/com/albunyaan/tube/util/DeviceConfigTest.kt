package com.albunyaan.tube.util

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Unit tests for DeviceConfig utility.
 *
 * Tests device type detection and data limit logic using Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class DeviceConfigTest {

    @Test
    fun `getHomeDataLimit returns 10 for phone`() {
        val context = RuntimeEnvironment.getApplication()
        setDeviceMode(context, Configuration.UI_MODE_TYPE_NORMAL)

        val limit = DeviceConfig.getHomeDataLimit(context)

        assertEquals(10, limit)
    }

    @Test
    fun `getHomeDataLimit returns 20 for TV`() {
        val context = RuntimeEnvironment.getApplication()
        setDeviceMode(context, Configuration.UI_MODE_TYPE_TELEVISION)

        val limit = DeviceConfig.getHomeDataLimit(context)

        assertEquals(20, limit)
    }

    @Test
    fun `isTV returns false for normal device`() {
        val context = RuntimeEnvironment.getApplication()
        setDeviceMode(context, Configuration.UI_MODE_TYPE_NORMAL)

        assertFalse(DeviceConfig.isTV(context))
    }

    @Test
    fun `isTV returns true for television`() {
        val context = RuntimeEnvironment.getApplication()
        setDeviceMode(context, Configuration.UI_MODE_TYPE_TELEVISION)

        assertTrue(DeviceConfig.isTV(context))
    }

    @Test
    fun `isTV returns false for car mode`() {
        val context = RuntimeEnvironment.getApplication()
        setDeviceMode(context, Configuration.UI_MODE_TYPE_CAR)

        assertFalse(DeviceConfig.isTV(context))
    }

    @Test
    fun `isTV returns false for watch mode`() {
        val context = RuntimeEnvironment.getApplication()
        setDeviceMode(context, Configuration.UI_MODE_TYPE_WATCH)

        assertFalse(DeviceConfig.isTV(context))
    }

    private fun setDeviceMode(context: Context, modeType: Int) {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        shadowOf(uiModeManager).currentModeType = modeType
    }
}
