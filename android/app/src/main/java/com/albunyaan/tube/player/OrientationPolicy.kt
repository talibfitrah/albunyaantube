package com.albunyaan.tube.player

import android.content.res.Configuration
import android.os.Build

/**
 * Whether the platform will actually act on [android.app.Activity.setRequestedOrientation].
 *
 * Android 16 behavior change, "Ignore orientation, resizability, and aspect ratio
 * restrictions" (applies to apps targeting API 36+ — this app since ANDROID-SDK36-01):
 *
 * > For apps targeting Android 16 (API level 36), orientation, resizability, and aspect
 * > ratio restrictions no longer apply on displays with smallest width >= 600dp. Apps
 * > fill the entire display window, regardless of aspect ratio or a user's preferred
 * > orientation.
 *
 * https://developer.android.com/about/versions/16/behavior-changes-16
 *
 * Callers use this to avoid waiting for a rotation that will never arrive. The player's
 * fullscreen enter/exit both request an orientation and then park state until the
 * resulting `onConfigurationChanged()` lands; on a large screen running Android 16 that
 * callback never fires, so the parked state has to be settled inline instead.
 *
 * Pure function with no state, so it is trivially unit-testable — same shape as
 * [AspectPolicy].
 */
object OrientationPolicy {

    /**
     * Smallest-width threshold above which Android 16 ignores orientation requests.
     * Matches the platform's own `sw600dp` cutoff (and the app's `layout-sw600dp/`
     * qualifier), so the tablet layouts and this policy always agree on "large screen".
     */
    const val LARGE_SCREEN_MIN_WIDTH_DP = 600

    /**
     * @param sdkInt device API level ([Build.VERSION.SDK_INT])
     * @param smallestScreenWidthDp [Configuration.smallestScreenWidthDp] of the current
     *        window — not the physical display, so split-screen/freeform windows narrower
     *        than 600dp correctly report that orientation requests still apply.
     * @return true when [android.app.Activity.setRequestedOrientation] will be acted on.
     *
     * ponytail: does not model the two documented escape hatches — `appCategory=game`
     * (this app is not a game) and the per-app "aspect ratio" device setting a user can
     * flip to restore the old behavior. A user who flips that setting makes this return
     * false when the request is in fact honored; every call site is written so the
     * mispredicted branch still converges, because the rotation then really does arrive
     * and `onConfigurationChanged()` re-runs the same UI update. Revisit only if a call
     * site is ever added where a wrong answer does not self-correct.
     */
    fun honorsRequestedOrientation(sdkInt: Int, smallestScreenWidthDp: Int): Boolean =
        sdkInt < Build.VERSION_CODES.BAKLAVA ||
            smallestScreenWidthDp < LARGE_SCREEN_MIN_WIDTH_DP

    /** Convenience overload for the live window configuration. */
    fun honorsRequestedOrientation(configuration: Configuration): Boolean =
        honorsRequestedOrientation(
            Build.VERSION.SDK_INT,
            configuration.smallestScreenWidthDp
        )
}
