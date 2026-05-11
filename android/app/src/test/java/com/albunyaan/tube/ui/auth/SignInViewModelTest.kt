package com.albunyaan.tube.ui.auth

import com.albunyaan.tube.auth.AccountStatusEvent
import com.albunyaan.tube.auth.AuthErrorCode
import com.albunyaan.tube.auth.AuthRepository
import com.albunyaan.tube.auth.AuthState
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Plan B (ANDROID-AUTH-01) T4: covers state transitions of [SignInViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: AuthRepository
    private lateinit var viewModel: SignInViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mock()
        whenever(repository.authState).thenReturn(MutableStateFlow(AuthState.SignedOut))
        whenever(repository.accountStatusEvents).thenReturn(MutableSharedFlow())
        viewModel = SignInViewModel(repository)
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `initial state is sign-in mode with empty fields`() {
        val s = viewModel.ui.value
        assertEquals(SignInViewModel.Mode.SIGN_IN, s.mode)
        assertEquals("", s.email)
        assertEquals("", s.password)
        assertFalse(s.isLoading)
        assertNull(s.error)
    }

    @Test fun `onEmailChanged updates field and clears error`() {
        viewModel.surfaceError(AuthErrorCode.WRONG_PASSWORD)
        viewModel.onEmailChanged("a@b.com")

        assertEquals("a@b.com", viewModel.ui.value.email)
        assertNull(viewModel.ui.value.error)
    }

    @Test fun `toggleMode swaps sign-in and sign-up`() {
        viewModel.toggleMode()
        assertEquals(SignInViewModel.Mode.SIGN_UP, viewModel.ui.value.mode)
        viewModel.toggleMode()
        assertEquals(SignInViewModel.Mode.SIGN_IN, viewModel.ui.value.mode)
    }

    @Test fun `submit in sign-in mode delegates to signInWithEmail`() = runTest(dispatcher) {
        val user = mock<FirebaseUser>()
        whenever(repository.signInWithEmail("a@b.com", "secret"))
            .thenReturn(Result.success(user))

        viewModel.onEmailChanged("a@b.com")
        viewModel.onPasswordChanged("secret")
        viewModel.submit()
        advanceUntilIdle()

        verify(repository).signInWithEmail("a@b.com", "secret")
        verify(repository, never()).signUpWithEmail(org.mockito.kotlin.any(), org.mockito.kotlin.any())
        assertFalse(viewModel.ui.value.isLoading)
    }

    @Test fun `submit in sign-up mode delegates to signUpWithEmail`() = runTest(dispatcher) {
        val user = mock<FirebaseUser>()
        whenever(repository.signUpWithEmail("a@b.com", "secret"))
            .thenReturn(Result.success(user))

        viewModel.onEmailChanged("a@b.com")
        viewModel.onPasswordChanged("secret")
        viewModel.toggleMode()
        viewModel.submit()
        advanceUntilIdle()

        verify(repository).signUpWithEmail("a@b.com", "secret")
        verify(repository, never()).signInWithEmail(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test fun `submit during loading is no-op`() = runTest(dispatcher) {
        viewModel.setLoading(true)
        viewModel.submit()
        advanceUntilIdle()

        verify(repository, never()).signInWithEmail(org.mockito.kotlin.any(), org.mockito.kotlin.any())
        verify(repository, never()).signUpWithEmail(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test fun `submit failure surfaces mapped error`() = runTest(dispatcher) {
        whenever(repository.signInWithEmail("a@b.com", "bad"))
            .thenReturn(Result.failure(FirebaseAuthException("ERROR_WRONG_PASSWORD", "")))

        viewModel.onEmailChanged("a@b.com")
        viewModel.onPasswordChanged("bad")
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(AuthErrorCode.WRONG_PASSWORD, viewModel.ui.value.error)
        assertFalse(viewModel.ui.value.isLoading)
    }

    @Test fun `onCredential success clears error`() = runTest(dispatcher) {
        val credential = mock<AuthCredential>()
        val user = mock<FirebaseUser>()
        whenever(repository.signInWithCredential(credential))
            .thenReturn(Result.success(user))

        viewModel.surfaceError(AuthErrorCode.WRONG_PASSWORD)
        viewModel.onCredential(credential, AuthErrorCode.GOOGLE_SIGN_IN_FAILED)
        advanceUntilIdle()

        assertNull(viewModel.ui.value.error)
    }

    @Test fun `onCredential failure uses fallback when mapped is UNKNOWN`() = runTest(dispatcher) {
        val credential = mock<AuthCredential>()
        whenever(repository.signInWithCredential(credential))
            .thenReturn(Result.failure(IllegalStateException("provider hiccup")))

        viewModel.onCredential(credential, AuthErrorCode.GOOGLE_SIGN_IN_FAILED)
        advanceUntilIdle()

        assertEquals(AuthErrorCode.GOOGLE_SIGN_IN_FAILED, viewModel.ui.value.error)
    }

    @Test fun `onCredential failure uses mapped code when not UNKNOWN`() = runTest(dispatcher) {
        val credential = mock<AuthCredential>()
        whenever(repository.signInWithCredential(credential))
            .thenReturn(Result.failure(FirebaseAuthException("ERROR_INVALID_CREDENTIAL", "")))

        viewModel.onCredential(credential, AuthErrorCode.GOOGLE_SIGN_IN_FAILED)
        advanceUntilIdle()

        assertEquals(AuthErrorCode.INVALID_CREDENTIAL, viewModel.ui.value.error)
    }

    /**
     * Double-call onCredential safety: ActivityResultLauncher can double-deliver
     * a credential on rapid recreation around process death. The Job-based guard
     * in [SignInViewModel] must cancel the prior in-flight coroutine so only the
     * latest result lands on UiState. If two `viewModelScope.launch` blocks were
     * allowed to race, the writes would be order-dependent (and the slower one's
     * stale write could overwrite a fresh error or success).
     */
    @Test fun `onCredential called twice — only the latest result lands on UiState`() = runTest(dispatcher) {
        val credential1 = mock<AuthCredential>()
        val credential2 = mock<AuthCredential>()
        val user = mock<FirebaseUser>()
        // First call: will fail. Second call: will succeed. The cancellation
        // means the first call's failure write should never reach UiState —
        // only the second call's success (error=null) should be observable.
        whenever(repository.signInWithCredential(credential1))
            .thenReturn(Result.failure(FirebaseAuthException("ERROR_INVALID_CREDENTIAL", "")))
        whenever(repository.signInWithCredential(credential2))
            .thenReturn(Result.success(user))

        viewModel.onCredential(credential1, AuthErrorCode.GOOGLE_SIGN_IN_FAILED)
        // Second call enqueued before the first one resumes; the guard cancels
        // the first coroutine before its _ui.update lands.
        viewModel.onCredential(credential2, AuthErrorCode.GOOGLE_SIGN_IN_FAILED)
        advanceUntilIdle()

        // Latest call wins: no error, not loading.
        assertNull("first call's error must not leak through", viewModel.ui.value.error)
        assertFalse(viewModel.ui.value.isLoading)
    }

    @Test fun `forgotPassword with blank email surfaces INVALID_EMAIL`() = runTest(dispatcher) {
        viewModel.forgotPassword()
        advanceUntilIdle()

        assertEquals(AuthErrorCode.INVALID_EMAIL, viewModel.ui.value.error)
        verify(repository, never()).sendPasswordResetEmail(org.mockito.kotlin.any())
    }

    @Test fun `forgotPassword success sets passwordResetSent`() = runTest(dispatcher) {
        whenever(repository.sendPasswordResetEmail("a@b.com")).thenReturn(Result.success(Unit))

        viewModel.onEmailChanged("a@b.com")
        viewModel.forgotPassword()
        advanceUntilIdle()

        assertTrue(viewModel.ui.value.passwordResetSent)
        assertNull(viewModel.ui.value.error)
    }

    @Test fun `forgotPassword failure surfaces PASSWORD_RESET_FAILED`() = runTest(dispatcher) {
        whenever(repository.sendPasswordResetEmail("a@b.com"))
            .thenReturn(Result.failure(RuntimeException("smtp down")))

        viewModel.onEmailChanged("a@b.com")
        viewModel.forgotPassword()
        advanceUntilIdle()

        assertEquals(AuthErrorCode.PASSWORD_RESET_FAILED, viewModel.ui.value.error)
    }

    /**
     * Without an isLoading interlock, a rapid double-tap on "Forgot password?"
     * fires two `sendPasswordResetEmail` calls — Firebase rate-limits the second
     * with `TOO_MANY_REQUESTS`, surfacing as `PASSWORD_RESET_FAILED` to the user.
     * The guard short-circuits the second tap while the first is in flight.
     */
    @Test fun `forgotPassword while loading is no-op`() = runTest(dispatcher) {
        viewModel.setLoading(true)
        viewModel.onEmailChanged("a@b.com")
        viewModel.forgotPassword()
        advanceUntilIdle()

        verify(repository, never()).sendPasswordResetEmail(org.mockito.kotlin.any())
    }

    @Test fun `surfaceError sets error and clears loading`() {
        viewModel.setLoading(true)
        viewModel.surfaceError(AuthErrorCode.MICROSOFT_SIGN_IN_FAILED)

        assertEquals(AuthErrorCode.MICROSOFT_SIGN_IN_FAILED, viewModel.ui.value.error)
        assertFalse(viewModel.ui.value.isLoading)
    }

    @Test fun `clearError clears the error field`() {
        viewModel.surfaceError(AuthErrorCode.WRONG_PASSWORD)
        viewModel.clearError()
        assertNull(viewModel.ui.value.error)
    }
}
