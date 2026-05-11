package com.albunyaan.tube.auth

import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Plan B (ANDROID-AUTH-01) T2: Façade over [com.google.firebase.auth.FirebaseAuth].
 *
 * All sign-in entry points funnel through [signInWithCredential] — Google,
 * Microsoft, and (future) other OAuth providers build their [AuthCredential]
 * in the ViewModel layer where an `Activity` reference is available, then
 * hand the credential to the repository. The repository itself is `@Singleton`
 * and must not see Activities (would leak them).
 *
 * Email/password is kept as separate calls because Firebase's API distinguishes
 * sign-in (existing account) from sign-up (new account) at the call-site.
 *
 * All `suspend` methods return [Result] so callers can branch on success/failure
 * without try/catch. Errors carry a mapped [AuthErrorCode] inside the exception
 * — see [AuthErrorMapper].
 */
interface AuthRepository {

    /** Hot observable of the latest [AuthState]. */
    val authState: StateFlow<AuthState>

    /** One-shot signals from [AccountStatusInterceptor]. */
    val accountStatusEvents: SharedFlow<AccountStatusEvent>

    suspend fun signInWithEmail(email: String, password: String): Result<FirebaseUser>

    suspend fun signUpWithEmail(email: String, password: String): Result<FirebaseUser>

    /**
     * Completes any OAuth sign-in (Google, Microsoft, …). Caller builds the
     * provider-specific [AuthCredential] in the ViewModel layer.
     */
    suspend fun signInWithCredential(credential: AuthCredential): Result<FirebaseUser>

    suspend fun sendPasswordResetEmail(email: String): Result<Unit>

    suspend fun signOut()
}

/**
 * Internal-only sink for [AccountStatusEvent]. Bound separately by Hilt so the
 * [AccountStatusInterceptor] (T3) can call `emit()` without the UI-facing
 * [AuthRepository] interface exposing it. Prevents fragments from forging
 * sign-out by calling `repository.emitAccountStatus(Deleted)`.
 */
interface AccountStatusEmitter {
    fun emit(event: AccountStatusEvent)
}
