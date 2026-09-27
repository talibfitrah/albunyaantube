package com.albunyaan.tube.util

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Default-network callback that runs [onReconnect] once each time a network
 * becomes VALIDATED (actually reaches the internet) — not on bare onAvailable,
 * which also fires for captive portals and dead Wi-Fi, and not on every later
 * capability update (signal strength etc.) of a network already validated.
 */
class ReconnectCallback(private val onReconnect: () -> Unit) : ConnectivityManager.NetworkCallback() {

    @Volatile private var validatedNetwork: Network? = null

    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            if (network == validatedNetwork) validatedNetwork = null
            return
        }
        if (network == validatedNetwork) return
        validatedNetwork = network
        onReconnect()
    }

    override fun onLost(network: Network) {
        if (network == validatedNetwork) validatedNetwork = null
    }
}
