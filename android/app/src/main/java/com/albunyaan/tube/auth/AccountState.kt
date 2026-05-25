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
    /**
     * Fetch failed after retries.
     *
     * Cubic R7 P1 — store a lightweight value, not the raw Throwable.
     *
     * Pre-fix [Failed] held the raw HttpException, which retains the OkHttp
     * Response (and ResponseBody) for the lifetime of this state. With
     * [AccountState] held in a hot StateFlow, the response sat live until
     * the next fetchMe — the body buffer was never closed, the connection
     * pool slot stayed pinned, and on poor networks this could stall
     * subsequent requests. The lightweight shape carries only the HTTP
     * code and a short message; the Throwable is preserved on [cause] only
     * when it does NOT wrap a network response (IOException retry exhaustion),
     * matching what consumers actually need (UI message + retry decision).
     */
    data class Failed(
        val httpCode: Int?,
        val message: String?,
        val cause: Throwable? = null,
    ) : AccountState
    /** Fetch succeeded. */
    data class Loaded(
        val uid: String,
        val email: String?,
        val displayName: String?,
        val dateOfBirth: String?,
        val phoneNumber: String?,
        val status: AccountStatus,
        val role: String,
    ) : AccountState
}
