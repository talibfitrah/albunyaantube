package com.albunyaan.tube.ui

import com.albunyaan.tube.R
import com.albunyaan.tube.auth.AccountStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plan B (ANDROID-AUTH-01) T5 + Plan C T7: post-splash routing decisions.
 *
 * Pure-function test — no Robolectric needed. Asserts the routing matrix
 * (onboarding-completed × signed-in × accountStatus) lands on the expected action.
 */
class SplashRouterTest {

    @Test fun `onboarding not done routes to onboarding regardless of auth`() {
        assertEquals(
            R.id.action_splash_to_onboarding,
            SplashRouter.decideSplashRoute(onboardingCompleted = false, signedIn = false, accountStatus = null),
        )
        assertEquals(
            R.id.action_splash_to_onboarding,
            SplashRouter.decideSplashRoute(onboardingCompleted = false, signedIn = true, accountStatus = AccountStatus.ACTIVE),
        )
    }

    @Test fun `onboarding done plus signed-in routes to main`() {
        assertEquals(
            R.id.action_splash_to_main,
            SplashRouter.decideSplashRoute(onboardingCompleted = true, signedIn = true, accountStatus = AccountStatus.ACTIVE),
        )
    }

    @Test fun `onboarding done plus signed-out routes to sign-in`() {
        assertEquals(
            R.id.action_splash_to_signIn,
            SplashRouter.decideSplashRoute(onboardingCompleted = true, signedIn = false, accountStatus = null),
        )
    }

    @Test fun `onboarding-fragment exit signed-in routes to main`() {
        assertEquals(R.id.action_onboarding_to_main, SplashRouter.decideOnboardingRoute(signedIn = true))
    }

    @Test fun `onboarding-fragment exit signed-out routes to sign-in`() {
        assertEquals(R.id.action_onboarding_to_signIn, SplashRouter.decideOnboardingRoute(signedIn = false))
    }

    // --- Plan C T7: accountStatus-aware routing ---

    @Test fun `signed-in PENDING_PROFILE routes to bootstrap`() {
        assertEquals(
            R.id.action_splash_to_bootstrap,
            SplashRouter.decideSplashRoute(
                onboardingCompleted = true, signedIn = true,
                accountStatus = AccountStatus.PENDING_PROFILE,
            ),
        )
    }

    @Test fun `signed-in BLOCKED routes to signIn`() {
        assertEquals(
            R.id.action_splash_to_signIn,
            SplashRouter.decideSplashRoute(
                onboardingCompleted = true, signedIn = true,
                accountStatus = AccountStatus.BLOCKED,
            ),
        )
    }

    @Test fun `signed-in DELETED routes to signIn`() {
        assertEquals(
            R.id.action_splash_to_signIn,
            SplashRouter.decideSplashRoute(
                onboardingCompleted = true, signedIn = true,
                accountStatus = AccountStatus.DELETED,
            ),
        )
    }

    @Test fun `signed-in null status (fetch failed) routes to signIn`() {
        // Failure to fetch /me means we don't know the status — route to signIn
        // rather than silently allow into MainShell with stale state.
        assertEquals(
            R.id.action_splash_to_signIn,
            SplashRouter.decideSplashRoute(
                onboardingCompleted = true, signedIn = true,
                accountStatus = null,
            ),
        )
    }
}
