package com.albunyaan.tube.ui

import com.albunyaan.tube.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plan B (ANDROID-AUTH-01) T5: post-splash routing decisions.
 *
 * Pure-function test — no Robolectric needed. Asserts the three-way matrix
 * (onboarding-completed × signed-in) lands on the expected action.
 */
class SplashRouterTest {

    @Test fun `onboarding not done routes to onboarding regardless of auth`() {
        assertEquals(
            R.id.action_splash_to_onboarding,
            SplashRouter.decideSplashRoute(onboardingCompleted = false, signedIn = false),
        )
        assertEquals(
            R.id.action_splash_to_onboarding,
            SplashRouter.decideSplashRoute(onboardingCompleted = false, signedIn = true),
        )
    }

    @Test fun `onboarding done plus signed-in routes to main`() {
        assertEquals(
            R.id.action_splash_to_main,
            SplashRouter.decideSplashRoute(onboardingCompleted = true, signedIn = true),
        )
    }

    @Test fun `onboarding done plus signed-out routes to sign-in`() {
        assertEquals(
            R.id.action_splash_to_signIn,
            SplashRouter.decideSplashRoute(onboardingCompleted = true, signedIn = false),
        )
    }

    @Test fun `onboarding-fragment exit signed-in routes to main`() {
        assertEquals(R.id.action_onboarding_to_main, SplashRouter.decideOnboardingRoute(signedIn = true))
    }

    @Test fun `onboarding-fragment exit signed-out routes to sign-in`() {
        assertEquals(R.id.action_onboarding_to_signIn, SplashRouter.decideOnboardingRoute(signedIn = false))
    }
}
