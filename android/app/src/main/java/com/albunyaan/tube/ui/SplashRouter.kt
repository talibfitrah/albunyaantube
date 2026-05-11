package com.albunyaan.tube.ui

import com.albunyaan.tube.R
import com.albunyaan.tube.auth.AccountStatus

/**
 * Plan B (ANDROID-AUTH-01) T5 + Plan C T7: post-splash routing decision extracted
 * into a pure function so it can be unit-tested without spinning up a Fragment or
 * Robolectric.
 *
 *   - onboarding not done                              → onboarding (regardless of auth)
 *   - signed-out                                       → sign-in
 *   - signed-in + accountStatus=null (fetch failed)    → sign-in (don't trust stale state)
 *   - signed-in + ACTIVE                               → main shell
 *   - signed-in + PENDING_PROFILE                      → profile bootstrap (Plan C T8 surface)
 *   - signed-in + BLOCKED / DELETED                    → sign-in (warm-path AccountStatusInterceptor
 *                                                        handles status changes during a session;
 *                                                        SplashRouter is the cold-start equivalent)
 */
internal object SplashRouter {

    fun decideSplashRoute(
        onboardingCompleted: Boolean,
        signedIn: Boolean,
        accountStatus: AccountStatus?,
    ): Int = when {
        !onboardingCompleted -> R.id.action_splash_to_onboarding
        !signedIn -> R.id.action_splash_to_signIn
        accountStatus == null -> R.id.action_splash_to_signIn
        accountStatus == AccountStatus.ACTIVE -> R.id.action_splash_to_main
        accountStatus == AccountStatus.PENDING_PROFILE -> R.id.action_splash_to_bootstrap
        else -> R.id.action_splash_to_signIn  // BLOCKED, DELETED
    }

    /** Onboarding has only two real terminations: to main if signed in, else to sign-in. */
    fun decideOnboardingRoute(signedIn: Boolean): Int =
        if (signedIn) R.id.action_onboarding_to_main else R.id.action_onboarding_to_signIn
}
