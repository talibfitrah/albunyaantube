package com.albunyaan.tube.auth

import com.google.firebase.auth.FirebaseUser

/**
 * Plan B (ANDROID-AUTH-01): observable auth state of the current user.
 *
 * Mirrors [com.google.firebase.auth.FirebaseAuth.AuthStateListener] one-to-one:
 * the only two states are SignedOut (no [com.google.firebase.auth.FirebaseAuth.currentUser])
 * and SignedIn (one exists). Operation-level error/loading state lives on the
 * caller's local UI state, never here — see [AuthRepositoryImpl] for the
 * invariant. Lifetime is the app process; the singleton [AuthRepository]
 * keeps a `StateFlow<AuthState>`.
 */
sealed interface AuthState {
    /** No FirebaseUser cached locally. */
    data object SignedOut : AuthState

    /** A FirebaseUser is signed in. The Firebase SDK guarantees a usable ID token. */
    data class SignedIn(val user: FirebaseUser, val uid: String) : AuthState
}

/**
 * Mapped subset of [com.google.firebase.auth.FirebaseAuthException] codes the
 * sign-in / sign-up UI needs to surface, plus a few synthetic codes for things
 * that are not Firebase exceptions (`NETWORK`, `GOOGLE_SIGN_IN_FAILED`, etc).
 *
 * String → enum mapping lives in [AuthErrorMapper]; all unknown codes map to
 * [UNKNOWN] so the UI never crashes on a new Firebase error.
 */
enum class AuthErrorCode {
    INVALID_EMAIL,
    WRONG_PASSWORD,
    USER_NOT_FOUND,
    USER_DISABLED,
    EMAIL_ALREADY_IN_USE,
    WEAK_PASSWORD,
    NETWORK,
    TOO_MANY_REQUESTS,
    INVALID_CREDENTIAL,
    GOOGLE_SIGN_IN_FAILED,
    MICROSOFT_SIGN_IN_FAILED,
    PASSWORD_RESET_FAILED,
    UNKNOWN,
}
