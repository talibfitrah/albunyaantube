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

    @Test fun `signInWithEmail with wrong password emits Error WRONG_PASSWORD`() = runTest {
        whenever(firebaseAuth.signInWithEmailAndPassword("a@b.com", "bad"))
            .thenReturn(Tasks.forException(FirebaseAuthException("ERROR_WRONG_PASSWORD", "")))

        val result = repository.signInWithEmail("a@b.com", "bad")

        assertTrue(result.isFailure)
        val state = repository.authState.value
        assertTrue("expected Error state, got $state", state is AuthState.Error)
        assertEquals(AuthErrorCode.WRONG_PASSWORD, (state as AuthState.Error).cause)
    }

    /** Plan A blocks users by disabling their FirebaseAuth record. */
    @Test fun `signInWithEmail when user disabled emits Error USER_DISABLED`() = runTest {
        whenever(firebaseAuth.signInWithEmailAndPassword("blocked@x.com", "secret"))
            .thenReturn(Tasks.forException(FirebaseAuthException("ERROR_USER_DISABLED", "")))

        val result = repository.signInWithEmail("blocked@x.com", "secret")

        assertTrue(result.isFailure)
        assertEquals(
            AuthErrorCode.USER_DISABLED,
            (repository.authState.value as AuthState.Error).cause,
        )
    }

    // --- signUpWithEmail ---------------------------------------------------

    @Test fun `signUpWithEmail success returns FirebaseUser`() = runTest {
        whenever(firebaseAuth.createUserWithEmailAndPassword("new@x.com", "secret"))
            .thenReturn(Tasks.forResult(authResult))

        val result = repository.signUpWithEmail("new@x.com", "secret")

        assertTrue(result.isSuccess)
        assertEquals(user, result.getOrNull())
    }

    @Test fun `signUpWithEmail when email already in use emits matching error`() = runTest {
        whenever(firebaseAuth.createUserWithEmailAndPassword("dup@x.com", "secret"))
            .thenReturn(Tasks.forException(FirebaseAuthException("ERROR_EMAIL_ALREADY_IN_USE", "")))

        val result = repository.signUpWithEmail("dup@x.com", "secret")

        assertTrue(result.isFailure)
        assertEquals(
            AuthErrorCode.EMAIL_ALREADY_IN_USE,
            (repository.authState.value as AuthState.Error).cause,
        )
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
    @Test fun `signInWithCredential failure emits Error INVALID_CREDENTIAL`() = runTest {
        val credential = mock<AuthCredential>()
        whenever(firebaseAuth.signInWithCredential(credential))
            .thenReturn(Tasks.forException(FirebaseAuthException("ERROR_INVALID_CREDENTIAL", "")))

        val result = repository.signInWithCredential(credential)

        assertTrue(result.isFailure)
        assertEquals(
            AuthErrorCode.INVALID_CREDENTIAL,
            (repository.authState.value as AuthState.Error).cause,
        )
    }

    // --- sendPasswordResetEmail -------------------------------------------

    @Test fun `sendPasswordResetEmail success returns Unit`() = runTest {
        whenever(firebaseAuth.sendPasswordResetEmail("a@b.com"))
            .thenReturn(Tasks.forResult(null))

        val result = repository.sendPasswordResetEmail("a@b.com")

        assertTrue(result.isSuccess)
    }

    @Test fun `sendPasswordResetEmail failure emits Error state`() = runTest {
        whenever(firebaseAuth.sendPasswordResetEmail("a@b.com"))
            .thenReturn(Tasks.forException(FirebaseAuthException("ERROR_USER_NOT_FOUND", "")))

        val result = repository.sendPasswordResetEmail("a@b.com")

        assertTrue(result.isFailure)
        assertTrue(repository.authState.value is AuthState.Error)
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
     * final observable state is SignedIn(user). Asserts the listener wins —
     * if AuthRepositoryImpl ever started double-writing SignedIn from inside
     * runAuth too, this test would still pass, but a subsequent listener-fire
     * on the same value is a no-op for StateFlow.
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
     * After a sign-in failure, state lands on Error and does NOT roll forward
     * to SigningIn (which would leave the spinner stuck) or to SignedIn.
     */
    @Test fun `signInWithEmail failure ends in Error not SigningIn`() = runTest {
        whenever(firebaseAuth.signInWithEmailAndPassword("a@b.com", "bad"))
            .thenReturn(Tasks.forException(FirebaseAuthException("ERROR_WRONG_PASSWORD", "")))

        repository.signInWithEmail("a@b.com", "bad")

        assertTrue(repository.authState.value is AuthState.Error)
    }
}
