package com.albunyaan.tube.auth

/**
 * Plan C T5: client-side enum mirroring backend `UserStatus`.
 * Wire format matches the lowercase snake_case value from `AccountMeResponse.status`.
 *
 * Routing semantics in [com.albunyaan.tube.ui.SplashRouter]:
 *  - ACTIVE          → MainShell
 *  - PENDING_PROFILE → ProfileBootstrap
 *  - BLOCKED/DELETED → SignIn (with toast). Surfaced by Plan B's
 *                      AccountStatusInterceptor too; SplashRouter is the
 *                      cold-start path, the interceptor is the warm path.
 *
 * Unknown values map to PENDING_PROFILE as a conservative default: a status
 * the client doesn't recognize is treated as "needs bootstrap" so the user
 * gets a deterministic explicit flow rather than a silent allow into MainShell.
 */
enum class AccountStatus(val wire: String) {
    ACTIVE("active"),
    PENDING_PROFILE("pending_profile"),
    BLOCKED("blocked"),
    DELETED("deleted");

    companion object {
        fun fromWire(value: String?): AccountStatus =
            entries.firstOrNull { it.wire == value } ?: PENDING_PROFILE
    }
}
