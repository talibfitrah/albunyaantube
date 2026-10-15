package com.albunyaan.tube.ui

import com.albunyaan.tube.R
import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.auth.AccountState
import com.albunyaan.tube.auth.AccountStatus
import com.albunyaan.tube.auth.AccountStatusEvent
import com.albunyaan.tube.auth.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Plan B (ANDROID-AUTH-01) T5 + Plan C T7: post-splash routing decision extracted
 * into a pure function so it can be unit-tested without spinning up a Fragment or
 * Robolectric.
 *
 *   - onboarding not done                              → onboarding (regardless of auth)
 *   - signed-out                                       → sign-in
 *   - signed-in + accountStatus=null (fetch failed)    → sign-in. With a saved record for the
 *                                                        uid, a non-terminal failure (anything but
 *                                                        a signed 401) routes on that record — see
 *                                                        [resolveAccount]; null means none.
 *   - signed-in + ACTIVE                               → main shell
 *   - signed-in + PENDING_PROFILE, password provider
 *     and email unverified                             → email verification (as iOS SplashRouter and
 *                                                        SignInFragment; bootstrap would 403 forever)
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
        hasPasswordProvider: Boolean,
        isEmailVerified: Boolean,
    ): Int = when {
        !onboardingCompleted -> R.id.action_splash_to_onboarding
        !signedIn -> R.id.action_splash_to_signIn
        accountStatus == null -> R.id.action_splash_to_signIn
        accountStatus == AccountStatus.ACTIVE -> R.id.action_splash_to_main
        accountStatus == AccountStatus.PENDING_PROFILE && hasPasswordProvider && !isEmailVerified ->
            R.id.action_splash_to_emailVerification
        accountStatus == AccountStatus.PENDING_PROFILE -> R.id.action_splash_to_bootstrap
        else -> R.id.action_splash_to_signIn  // BLOCKED, DELETED
    }

    /** Longest the launch waits for /me when a saved record exists for the uid. */
    const val LAUNCH_ME_BUDGET_MS = 3_000L

    /**
     * The account a signed-in launch routes on.
     *
     * No saved record for [uid]: one /me, else null (sign-out), as before.
     *
     * A saved record: it is published now and the launch /me runs as
     * [AccountRepository.revalidateRestored] in [appScope], so it outlives the
     * splash. Routing waits for it at most [LAUNCH_ME_BUDGET_MS], then goes on
     * the record. Whenever it lands it is applied the same way a reconnect
     * revalidation is: success updates the record, a signed 401 signs out (the
     * 403 envelope signs out through AccountStatusInterceptor), anything else
     * keeps the record. A sign-out shows up here as no Loaded account.
     */
    suspend fun resolveAccount(repo: AccountRepository, uid: String, appScope: CoroutineScope): AccountState.Loaded? {
        if (repo.restoreLastKnown(uid) == null) {
            return repo.fetchMe(maxAttempts = 1).getOrNull()
        }
        val me = appScope.launch { repo.revalidateRestored() }
        withTimeoutOrNull(LAUNCH_ME_BUDGET_MS) { me.join() }
        return (repo.accountState.value as? AccountState.Loaded)?.takeIf { it.uid == uid }
    }

    /**
     * What SplashFragment awaits before routing. Sync starts in [appScope]: it
     * never gates routing and is not cancelled when the splash goes away. It binds
     * only while Firebase ([signedInUid]) is still signed in as that account.
     */
    suspend fun launchAccountStatus(
        repo: AccountRepository,
        uid: String,
        appScope: CoroutineScope,
        signedInUid: () -> String?,
        bindSync: suspend (String) -> Unit,
    ): AccountStatus? {
        val loaded = resolveAccount(repo, uid, appScope) ?: return null
        // A 403 envelope signs Firebase out at once, but clears accountState only later.
        appScope.launch { if (signedInUid() == loaded.uid) bindSync(loaded.uid) }
        return loaded.status
    }

    /**
     * A process-death restore skips the splash, so nothing published the account: favourites
     * and subscriptions read uid "" and sync never bound. [restoring] is MainActivity's
     * saved-state restore; a launch that shows the splash leaves this to it. Same work as the
     * splash ([launchAccountStatus]: saved record, bounded /me, guarded bind), without routing.
     */
    fun restoreAccount(
        restoring: Boolean,
        repo: AccountRepository,
        appScope: CoroutineScope,
        signedInUid: () -> String?,
        bindSync: suspend (String) -> Unit,
    ) {
        if (!restoring || repo.accountState.value != AccountState.NotSignedIn) return
        val uid = signedInUid() ?: return
        appScope.launch { launchAccountStatus(repo, uid, appScope, signedInUid, bindSync) }
    }

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
