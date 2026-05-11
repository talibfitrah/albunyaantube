package com.albunyaan.tube.auth

import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plan B (ANDROID-AUTH-01) T2: production [AuthRepository] over [FirebaseAuth].
 *
 * Lifecycle: app-process singleton. The [FirebaseAuth.AuthStateListener] is
 * registered in [init] and never unregistered — repository lifetime == process
 * lifetime, and unregistering on process death adds nothing but a chance of an
 * NPE if the SDK has already torn itself down.
 */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val firebaseAuth: FirebaseAuth,
) : AuthRepository, AccountStatusEmitter {

    private val _authState = MutableStateFlow<AuthState>(initialState())
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    /**
     * DROP_OLDEST buffer policy: emit must never block — it's called from
     * [AccountStatusInterceptor], which runs on OkHttp's dispatcher thread.
     * A backlog accumulating here means the UI has stopped collecting (already
     * navigated away, for example); dropping events is the right call there.
     */
    private val _accountStatusEvents = MutableSharedFlow<AccountStatusEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val accountStatusEvents: SharedFlow<AccountStatusEvent> =
        _accountStatusEvents.asSharedFlow()

    init {
        firebaseAuth.addAuthStateListener { auth ->
            _authState.value = auth.currentUser?.let { AuthState.SignedIn(it, it.uid) }
                ?: AuthState.SignedOut
        }
    }

    private fun initialState(): AuthState =
        firebaseAuth.currentUser?.let { AuthState.SignedIn(it, it.uid) } ?: AuthState.SignedOut

    // AuthStateListener (registered in init) is the *sole* source of truth for
    // [_authState]. We never push SigningIn/Error into the StateFlow from an
    // operation path: a failed sign-in attempt does NOT change Firebase's
    // currentUser, so the global StateFlow shouldn't lie about it. Operation
    // errors are returned via Result.failure and surfaced on the ViewModel's
    // local UI state, not the process-wide auth state. (Bug fix: a Microsoft
    // sign-in cancellation was pushing Error into _authState even though
    // currentUser was still valid, hiding the Settings → Account section.)
    override suspend fun signInWithEmail(email: String, password: String): Result<FirebaseUser> =
        runCatching { firebaseAuth.signInWithEmailAndPassword(email, password).await().requireUser() }

    override suspend fun signUpWithEmail(email: String, password: String): Result<FirebaseUser> =
        runCatching { firebaseAuth.createUserWithEmailAndPassword(email, password).await().requireUser() }

    override suspend fun signInWithCredential(credential: AuthCredential): Result<FirebaseUser> =
        runCatching { firebaseAuth.signInWithCredential(credential).await().requireUser() }

    override suspend fun sendPasswordResetEmail(email: String): Result<Unit> = runCatching {
        firebaseAuth.sendPasswordResetEmail(email).await()
        Unit
    }

    /** suspend reserved for future server-side token revocation. */
    override suspend fun signOut() {
        firebaseAuth.signOut()
        // AuthStateListener will flip _authState to SignedOut; we do not need
        // to set it here. Setting it twice can race with the listener.
    }

    override fun emit(event: AccountStatusEvent) {
        _accountStatusEvents.tryEmit(event)
    }

    private fun com.google.firebase.auth.AuthResult.requireUser(): FirebaseUser =
        user ?: throw IllegalStateException(
            "Firebase returned a successful AuthResult with no user — should not happen"
        )
}
