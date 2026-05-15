package com.albunyaan.tube.ui.settings

import com.albunyaan.tube.R

/**
 * Cubic R7 P2 — central registry for Activity-level NavHostFragment IDs.
 *
 * Pre-fix [SettingsFragment] hardcoded `R.id.nav_host_fragment` for the
 * cross-fragment "sign out → SignInFragment" navigation. Any future Activity
 * layout rename had to chase every call site through grep. This object is
 * the single point of update.
 */
internal object NavHostIds {
    /** Root NavHostFragment id in [com.albunyaan.tube.MainActivity]. */
    val ROOT: Int = R.id.nav_host_fragment
}
