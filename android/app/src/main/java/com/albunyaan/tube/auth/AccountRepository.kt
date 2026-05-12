package com.albunyaan.tube.auth

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
     * Submit `/api/account/profile`. On 422 AGE_INELIGIBLE returns
     * `Result.failure(AgeIneligibleError)`; on other failures returns the
     * underlying exception.
     */
    suspend fun completeProfile(displayName: String, dateOfBirth: LocalDate): Result<AccountState.Loaded>

    /** Clears local state on sign-out. Does not call the network. */
    fun signOut()
}

/** Sentinel error type for under-13 rejection. UI maps this to navigation. */
class AgeIneligibleError : RuntimeException("age-ineligible")

/**
 * Plan D T26 — synchronous read of the current uid for sync writes.
 * Returns the empty string when no user is signed in (anon-era sentinel).
 */
fun AccountRepository.currentUid(): String =
    (accountState.value as? AccountState.Loaded)?.uid ?: ""
