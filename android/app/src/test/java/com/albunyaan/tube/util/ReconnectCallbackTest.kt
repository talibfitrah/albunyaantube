package com.albunyaan.tube.util

import android.net.Network
import android.net.NetworkCapabilities
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/** The decision behind "connectivity is back → push dirty rows + re-check /me". */
@RunWith(RobolectricTestRunner::class)
class ReconnectCallbackTest {

    private var fired = 0
    private val callback = ReconnectCallback { fired++ }

    private fun caps(validated: Boolean): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also {
            if (validated) shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

    private val wifi: Network = ShadowNetwork.newInstance(1)
    private val cell: Network = ShadowNetwork.newInstance(2)

    @Test fun `a network that validates fires once, not on every capability update`() {
        callback.onAvailable(wifi)
        callback.onCapabilitiesChanged(wifi, caps(validated = true))
        callback.onCapabilitiesChanged(wifi, caps(validated = true))  // e.g. signal strength

        assertEquals(1, fired)
    }

    @Test fun `a captive portal fires only once it validates`() {
        callback.onAvailable(wifi)
        callback.onCapabilitiesChanged(wifi, caps(validated = false))
        assertEquals(0, fired)

        callback.onCapabilitiesChanged(wifi, caps(validated = true))
        assertEquals(1, fired)
    }

    @Test fun `switching to another validated network fires again`() {
        callback.onCapabilitiesChanged(wifi, caps(validated = true))
        callback.onCapabilitiesChanged(cell, caps(validated = true))

        assertEquals(2, fired)
    }

    @Test fun `the same network coming back after loss fires again`() {
        callback.onCapabilitiesChanged(wifi, caps(validated = true))
        callback.onLost(wifi)
        callback.onCapabilitiesChanged(wifi, caps(validated = true))

        assertEquals(2, fired)
    }
}
