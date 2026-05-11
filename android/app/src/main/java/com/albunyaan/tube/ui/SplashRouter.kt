package com.albunyaan.tube.ui

import com.albunyaan.tube.R

/**
 * Plan B (ANDROID-AUTH-01) T5: post-splash routing decision extracted into a
 * pure function so it can be unit-tested without spinning up a Fragment or
 * Robolectric. [SplashFragment] and [OnboardingFragment] both delegate here.
 *
 *   - onboarding not done → onboarding (regardless of auth state)
 *   - onboarding done + signed-in → main shell
 *   - onboarding done + signed-out → sign-in
 */
internal object SplashRouter {

    fun decideSplashRoute(onboardingCompleted: Boolean, signedIn: Boolean): Int = when {
        !onboardingCompleted -> R.id.action_splash_to_onboarding
        signedIn -> R.id.action_splash_to_main
        else -> R.id.action_splash_to_signIn
    }

    /** Onboarding has only two real terminations: to main if signed in, else to sign-in. */
    fun decideOnboardingRoute(signedIn: Boolean): Int =
        if (signedIn) R.id.action_onboarding_to_main else R.id.action_onboarding_to_signIn
}
