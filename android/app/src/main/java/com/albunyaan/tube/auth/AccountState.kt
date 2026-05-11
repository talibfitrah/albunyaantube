package com.albunyaan.tube.auth

/**
 * Plan C T5: hot state for the account profile. [AccountRepository] (T6) holds a
 * StateFlow of this. SplashRouter reads the latest emission to make routing
 * decisions.
 */
sealed interface AccountState {
    /** Initial / between sign-out events. */
    data object NotSignedIn : AccountState
    /** Fetch in flight. */
    data object Loading : AccountState
    /** Fetch failed after retries. */
    data class Failed(val cause: Throwable) : AccountState
    /** Fetch succeeded. */
    data class Loaded(
        val uid: String,
        val email: String?,
        val displayName: String?,
        val status: AccountStatus,
    ) : AccountState
}
