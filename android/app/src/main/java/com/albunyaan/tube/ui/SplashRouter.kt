package com.albunyaan.tube.ui

import com.albunyaan.tube.R
import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.auth.AccountState
import com.albunyaan.tube.auth.AccountStatus
import com.albunyaan.tube.auth.AccountStatusEvent
import com.albunyaan.tube.auth.AuthState
import com.albunyaan.tube.auth.isTerminalAccountFailure

/**
 * Plan B (ANDROID-AUTH-01) T5 + Plan C T7: post-splash routing decision extracted
 * into a pure function so it can be unit-tested without spinning up a Fragment or
 * Robolectric.
 *
 *   - onboarding not done                              → onboarding (regardless of auth)
 *   - signed-out                                       → sign-in
 *   - signed-in + accountStatus=null (fetch failed)    → sign-in. A non-terminal failure
 *                                                        (anything but a signed 401) routes on the
 *                                                        last /me for that uid instead — see
 *                                                        [accountForRoute]; null means none.
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

    /**
     * The account to route on. A failed /me falls back to the last one this
     * device saw for the signed-in uid ([lastKnown]) ONLY when the failure is
     * non-terminal (see [isTerminalAccountFailure]). A signed 401 is the
     * server's verdict and must never be overridden by what the device remembers.
     */
    fun accountForRoute(
        fetched: Result<AccountState.Loaded>,
        lastKnown: () -> AccountState.Loaded?,
    ): AccountState.Loaded? = fetched.getOrElse { if (it.isTerminalAccountFailure()) null else lastKnown() }

    /** What SplashFragment runs for a signed-in user: one /me, else the last-known record. */
    suspend fun resolveAccount(repo: AccountRepository, uid: String): AccountState.Loaded? =
        accountForRoute(repo.fetchMe(maxAttempts = 1)) { repo.restoreLastKnown(uid) }

    /** Cold-start equivalent of AccountStatusInterceptor's terminal event. */
    fun terminalEvent(signedIn: Boolean, accountStatus: AccountStatus?): AccountStatusEvent? = when {
        !signedIn -> null
        accountStatus == AccountStatus.DELETED -> AccountStatusEvent.Deleted
        accountStatus == AccountStatus.BLOCKED -> AccountStatusEvent.Blocked
        else -> null
    }

    /**
     * Mid-session sign-out from any source — a 401 on revalidation, a Firebase
     * force sign-out of a disabled user, a 403 envelope — must not leave the
     * user on content. All content lives under mainShellFragment; pre-auth
     * screens (incl. ageIneligible, which signs out and must stay visible) stay.
     */
    fun leaveForSignIn(signedIn: Boolean, currentDestination: Int?): Boolean =
        !signedIn && currentDestination == R.id.mainShellFragment

    /**
     * An actual SignedIn → SignedOut transition. A fresh activity's first value
     * (previous == null) never counts: a signed-out launch or rotation must not
     * poke PlaybackService or initialise CastContext on the main thread.
     */
    fun isSignOut(previous: AuthState?, current: AuthState): Boolean =
        previous is AuthState.SignedIn && current is AuthState.SignedOut

    /** Onboarding has only two real terminations: to main if signed in, else to sign-in. */
    fun decideOnboardingRoute(signedIn: Boolean): Int =
        if (signedIn) R.id.action_onboarding_to_main else R.id.action_onboarding_to_signIn
}
