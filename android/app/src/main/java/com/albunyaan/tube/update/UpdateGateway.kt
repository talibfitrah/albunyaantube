package com.albunyaan.tube.update

import android.app.Activity
import androidx.lifecycle.LifecycleOwner

/**
 * The only thing shared code (`src/main`) knows about self-updating.
 *
 * ANDROID-FLAVOR-01. Google Play's Device and Network Abuse policy forbids an app that
 * downloads and installs an APK on its own, so the `play` flavor must not *contain* the
 * updater — not merely decline to run it. Everything that fetches from GitHub, writes an
 * APK, or drives PackageInstaller lives in `app/src/sideload/java/.../update`; this
 * interface is the seam that keeps SplashFragment and SettingsFragment compiling for both
 * flavors.
 *
 * Bindings (one per flavor, both `@InstallIn(SingletonComponent::class)`):
 *  - sideload → [UpdatePromptFlow], the real thing.
 *  - play     → `NoUpdateGateway`, inert.
 *
 * The choice is made by which source set is on the compile path — never by a runtime or
 * remote flag. Gating review-visible behaviour on a flag is itself a Play policy
 * violation (Deceptive Behavior / review evasion), so do not "simplify" this into a
 * BuildConfig branch inside a single shared implementation.
 */
interface UpdateGateway {

    /**
     * False when this build ships no self-updater at all (the `play` flavor).
     *
     * Callers use it to hide update affordances. It is a compile-time constant per
     * flavor, NOT an install-source check — Settings additionally consults
     * [InstallSource] so a sideload build that was nevertheless installed from Play
     * keeps hiding the same rows it hides today.
     */
    val hasUpdater: Boolean

    /**
     * Bounded probe for the splash cold-start gate. Returns the available [UpdateInfo],
     * or null on no-update / failure / timeout / "already prompted this process".
     * Never throws: the splash must route regardless.
     */
    suspend fun checkForUpdate(): UpdateInfo?

    /**
     * Shows the "update available" dialog and suspends until the user dismisses it, so
     * the prompt fronts the sign-in screen instead of racing it.
     */
    suspend fun showUpdateDialogAndAwait(
        activity: Activity,
        lifecycleOwner: LifecycleOwner,
        info: UpdateInfo,
    )

    /**
     * Manual "Check for updates" entry point from Settings. Always surfaces a result
     * toast, because the user explicitly asked.
     */
    fun runCheck(activity: Activity, lifecycleOwner: LifecycleOwner)
}
