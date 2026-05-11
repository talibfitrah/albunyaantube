package com.albunyaan.tube.auth

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Plan B (ANDROID-AUTH-01) T2: behavioural tests for [AuthRepositoryImpl].
 *
 * Mocks [FirebaseAuth] entirely. Uses [Tasks.forResult] / [Tasks.forException]
 * to feed pre-completed Firebase Tasks into the suspending await() bridge.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthRepositoryImplTest {

    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var user: FirebaseUser
    private lateinit var authResult: AuthResult
    private lateinit var repository: AuthRepositoryImpl

    @Before
    fun setUp() {
        firebaseAuth = mock()
        user = mock { whenever(it.uid).thenReturn("uid-123") }
        authResult = mock { whenever(it.user).thenReturn(user) }
        repository = AuthRepositoryImpl(firebaseAuth)
    }

    // --- signInWithEmail ---------------------------------------------------

    @Test fun `signInWithEmail success returns FirebaseUser`() = runTest {
        whenever(firebaseAuth.signInWithEmailAndPassword("a@b.com", "secret"))
            .thenReturn(Tasks.forResult(authResult))

        val result = repository.signInWithEmail("a@b.com", "secret")

        assertTrue(result.isSuccess)
        assertEquals(user, result.getOrNull())
    }

    @Test fun `signInWithEmail with wrong password returns failure mapped to WRONG_PASSWORD`() = runTest {
        whenever(firebaseAuth.signInWithEmailAndPassword("a@b.com", "bad"))
            .thenReturn(Tasks.forException(FirebaseAuthException("ERROR_WRONG_PASSWORD", "")))

        val result = repository.signInWithEmail("a@b.com", "bad")

        assertTrue(result.isFailure)
        assertEquals(AuthErrorCode.WRONG_PASSWORD, result.exceptionOrNull()?.toAuthErrorCode())
        // Operation failure must NOT touch authState — Firebase's currentUser is unchanged,
        // so the StateFlow stays at its construction-time value (SignedOut).
        assertTrue(repository.authState.value is AuthState.SignedOut)
    }

    /** Plan A blocks users by disabling their FirebaseAuth record. */
    @Test fun `signInWithEmail when user disabled returns failure mapped to USER_DISABLED`() = runTest {
        whenever(firebaseAuth.signInWithEmailAndPassword("blocked@x.com", "secret"))
            .thenReturn(Tasks.forException(FirebaseAuthException("ERROR_USER_DISABLED", "")))

        val result = repository.signInWithEmail("blocked@x.com", "secret")

        assertTrue(result.isFailure)
        assertEquals(AuthErrorCode.USER_DISABLED, result.exceptionOrNull()?.toAuthErrorCode())
        assertTrue(repository.authState.value is AuthState.SignedOut)
    }

    // --- signUpWithEmail ---------------------------------------------------

    @Test fun `signUpWithEmail success returns FirebaseUser`() = runTest {
        whenever(firebaseAuth.createUserWithEmailAndPassword("new@x.com", "secret"))
            .thenReturn(Tasks.forResult(authResult))

        val result = repository.signUpWithEmail("new@x.com", "secret")

        assertTrue(result.isSuccess)
        assertEquals(user, result.getOrNull())
    }

    @Test fun `signUpWithEmail when email already in use returns failure mapped to EMAIL_ALREADY_IN_USE`() = runTest {
        whenever(firebaseAuth.createUserWithEmailAndPassword("dup@x.com", "secret"))
            .thenReturn(Tasks.forException(FirebaseAuthException("ERROR_EMAIL_ALREADY_IN_USE", "")))

        val result = repository.signUpWithEmail("dup@x.com", "secret")

        assertTrue(result.isFailure)
        assertEquals(AuthErrorCode.EMAIL_ALREADY_IN_USE, result.exceptionOrNull()?.toAuthErrorCode())
        assertTrue(repository.authState.value is AuthState.SignedOut)
    }

    // --- signInWithCredential (Google / Microsoft) -------------------------

    @Test fun `signInWithCredential success returns FirebaseUser`() = runTest {
        val credential = mock<AuthCredential>()
        whenever(firebaseAuth.signInWithCredential(credential))
            .thenReturn(Tasks.forResult(authResult))

        val result = repository.signInWithCredential(credential)

        assertTrue(result.isSuccess)
        assertEquals(user, result.getOrNull())
    }

    /** Realistic Google/Microsoft failure: token expired / wrong audience. */
    @Test fun `signInWithCredential failure returns failure mapped to INVALID_CREDENTIAL`() = runTest {
        val credential = mock<AuthCredential>()
        whenever(firebaseAuth.signInWithCredential(credential))
            .thenReturn(Tasks.forException(FirebaseAuthException("ERROR_INVALID_CREDENTIAL", "")))

        val result = repository.signInWithCredential(credential)

        assertTrue(result.isFailure)
        assertEquals(AuthErrorCode.INVALID_CREDENTIAL, result.exceptionOrNull()?.toAuthErrorCode())
        assertTrue(repository.authState.value is AuthState.SignedOut)
    }

    // --- sendPasswordResetEmail -------------------------------------------

    @Test fun `sendPasswordResetEmail success returns Unit`() = runTest {
        whenever(firebaseAuth.sendPasswordResetEmail("a@b.com"))
            .thenReturn(Tasks.forResult(null))

        val result = repository.sendPasswordResetEmail("a@b.com")

        assertTrue(result.isSuccess)
    }

    @Test fun `sendPasswordResetEmail failure returns failure without touching authState`() = runTest {
        whenever(firebaseAuth.sendPasswordResetEmail("a@b.com"))
            .thenReturn(Tasks.forException(FirebaseAuthException("ERROR_USER_NOT_FOUND", "")))

        val result = repository.sendPasswordResetEmail("a@b.com")

        assertTrue(result.isFailure)
        // sendPasswordResetEmail never changes Firebase's currentUser; authState stays SignedOut.
        assertTrue(repository.authState.value is AuthState.SignedOut)
    }

    // --- signOut -----------------------------------------------------------

    @Test fun `signOut delegates to FirebaseAuth`() = runTest {
        repository.signOut()
        verify(firebaseAuth).signOut()
    }

    // --- account-status events --------------------------------------------

    @Test fun `emit reaches accountStatusEvents flow`() = runTest {
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { repository.accountStatusEvents.first() }
        }
        repository.emit(AccountStatusEvent.Blocked)
        assertEquals(AccountStatusEvent.Blocked, collected.await())
    }

    // --- construction-time state -----------------------------------------

    @Test fun `repository constructed when user already signed-in starts in SignedIn`() {
        whenever(firebaseAuth.currentUser).thenReturn(user)
        val repo = AuthRepositoryImpl(firebaseAuth)
        val state = repo.authState.value
        assertTrue("expected SignedIn, got $state", state is AuthState.SignedIn)
        assertEquals("uid-123", (state as AuthState.SignedIn).uid)
    }

    // --- authState ordering ----------------------------------------------

    /**
     * After a successful sign-in plus the FirebaseAuth listener firing, the
     * final observable state is SignedIn(user). The AuthStateListener is the
     * sole writer of [AuthRepositoryImpl.authState]; operation paths never
     * mutate it (see bug fix in AuthRepositoryImpl KDoc).
     */
    @Test fun `signInWithEmail success ends in SignedIn after listener fires`() = runTest {
        val listenerCaptor = argumentCaptor<com.google.firebase.auth.FirebaseAuth.AuthStateListener>()
        val repo = AuthRepositoryImpl(firebaseAuth)
        verify(firebaseAuth, org.mockito.kotlin.atLeastOnce()).addAuthStateListener(listenerCaptor.capture())

        whenever(firebaseAuth.signInWithEmailAndPassword("a@b.com", "secret"))
            .thenReturn(Tasks.forResult(authResult))
        whenever(firebaseAuth.currentUser).thenReturn(user)

        repo.signInWithEmail("a@b.com", "secret")
        // FirebaseAuth fires the listener on a real device — we drive it manually here.
        listenerCaptor.lastValue.onAuthStateChanged(firebaseAuth)

        val state = repo.authState.value
        assertTrue("expected SignedIn, got $state", state is AuthState.SignedIn)
        assertEquals("uid-123", (state as AuthState.SignedIn).uid)
    }

    /**
     * After a sign-in failure, authState stays at SignedOut — operation errors
     * must NOT mutate the global StateFlow because Firebase's currentUser is
     * unchanged. The failed Result carries the mapped error code; the
     * ViewModel is responsible for surfacing it on local UI state.
     */
    @Test fun `signInWithEmail failure leaves authState SignedOut`() = runTest {
        whenever(firebaseAuth.signInWithEmailAndPassword("a@b.com", "bad"))
            .thenReturn(Tasks.forException(FirebaseAuthException("ERROR_WRONG_PASSWORD", "")))

        val result = repository.signInWithEmail("a@b.com", "bad")

        assertTrue(result.isFailure)
        assertTrue(repository.authState.value is AuthState.SignedOut)
    }
}
