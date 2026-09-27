package com.albunyaan.tube.auth

import com.albunyaan.tube.data.account.AccountMeResponseDto
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/**
 * Plan C T6: hot state for the account profile + suspend functions for
 * the /api/account/ endpoints. Singleton-scoped; UI layer reads [accountState]
 * to decide routing.
 */
interface AccountRepository {

    val accountState: StateFlow<AccountState>

    /**
     * Fetch the caller's profile from `/api/account/me`. Updates [accountState].
     * Retries up to 3 attempts total with linear backoff between attempts.
     */
    suspend fun fetchMe(): Result<AccountState.Loaded>

    /**
     * Cubic R7 P1 — bounded-retry variant.
     *
     * Cap the retry budget for callers (typically [SplashFragment]) that must
     * fast-fail rather than stall on a flaky network. Downstream screens
     * still call the default [fetchMe] with the full retry budget.
     *
     * Default-implemented in terms of [fetchMe] so existing test fakes don't
     * need to override; the production impl in [AccountRepositoryImpl]
     * overrides to actually honour the cap.
     */
    suspend fun fetchMe(maxAttempts: Int): Result<AccountState.Loaded> = fetchMe()

    /**
     * Submit `/api/account/profile`. On 422 AGE_INELIGIBLE returns
     * `Result.failure(AgeIneligibleError)`; on other failures returns the
     * underlying exception.
     */
    suspend fun completeProfile(
        displayName: String,
        dateOfBirth: LocalDate,
        phoneNumber: String?,
    ): Result<AccountState.Loaded>

    /**
     * Offline launch: publish the last successful /me persisted for [uid] as
     * [accountState] and return it, or null when this device has none for that
     * uid. Default-implemented (like [fetchMe] with a budget) so test fakes
     * need not override.
     */
    fun restoreLastKnown(uid: String): AccountState.Loaded? = null

    /**
     * Re-fetch /me once when the current account was restored offline, so a
     * server-side block or deletion takes effect after connectivity returns.
     * No-op for a server-confirmed session.
     */
    suspend fun revalidateRestored() {}

    /** Clears local state on sign-out. Does not call the network. */
    fun signOut()

    /**
     * Plan G A2 — optimistic state update after a successful profile save.
     * Emits a new [AccountState.Loaded] with [displayName] and [dateOfBirth]
     * replaced from [response]. Falls through silently when the current state
     * is not [AccountState.Loaded] (sign-out race; caller can ignore).
     */
    fun applyProfileUpdate(response: AccountMeResponseDto)
}

/** Sentinel error type for under-13 rejection. UI maps this to navigation. */
class AgeIneligibleError : RuntimeException("age-ineligible")

/**
 * Plan D T26 — synchronous read of the current uid for sync writes.
 * Returns the empty string when no user is signed in (anon-era sentinel).
 */
fun AccountRepository.currentUid(): String =
    (accountState.value as? AccountState.Loaded)?.uid ?: ""

/**
 * The server's verdict on this account — the only failure that may sign a user
 * out: a 401 on a request that CARRIED a Firebase Bearer. Same rule as iOS.
 *
 * Everything else keeps the account: a 401 on a request FirebaseAuthInterceptor
 * sent unsigned because the token mint failed (network / timeout), a bare 403
 * (proxy / WAF), 404 and other 4xx, 408, 429, 5xx, decode failures, no network.
 * The 403 lifecycle envelope acts on its own through AccountStatusInterceptor,
 * and an invalid user (disabled / deleted / revoked) is signed out by the
 * Firebase SDK itself when the mint fails (firebase-auth 24.2.0: the
 * getIdToken refresh callback calls FirebaseAuth.signOut() on 17011 / 17021 /
 * 17005) — both reach the app as a Firebase sign-out.
 *
 * `raw().request` is the request as actually sent, after the interceptor.
 */
fun Throwable.isTerminalAccountFailure(): Boolean =
    this is retrofit2.HttpException && code() == 401 &&
        response()?.raw()?.request?.header("Authorization") != null
