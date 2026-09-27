package com.albunyaan.tube.ui.me.profile.edit

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class EditEmailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var auth: FirebaseAuth
    private lateinit var user: FirebaseUser
    private lateinit var accountService: com.albunyaan.tube.data.account.AccountService

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        auth = mock()
        user = mock { on { email } doReturn "old@example.com" }
        whenever(auth.currentUser).thenReturn(user)
        whenever(user.reauthenticate(any())).thenReturn(Tasks.forResult(null))
        whenever(user.verifyBeforeUpdateEmail(any())).thenReturn(Tasks.forResult(null))
        whenever(user.getIdToken(true)).thenReturn(Tasks.forResult(mock<com.google.firebase.auth.GetTokenResult>()))
        accountService = mock {
            onBlocking { sendChangeEmailVerification(mapOf("newEmail" to "new@example.com")) } doReturn
                retrofit2.Response.success(Unit)
        }
    }

    private fun backendAnswers(code: Int) = accountService.stub {
        onBlocking { sendChangeEmailVerification(any()) } doReturn
            retrofit2.Response.error(code, okhttp3.ResponseBody.Companion.run { "".toResponseBody() })
    }

    private fun submitNewEmail(vm: EditEmailViewModel) {
        vm.onCurrentPasswordChanged("pw")
        vm.onNewEmailChanged("new@example.com")
        vm.submit()
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `submit with malformed new email surfaces INVALID_EMAIL`() = runTest(dispatcher) {
        val vm = EditEmailViewModel(auth, accountService)
        vm.onCurrentPasswordChanged("pw")
        vm.onNewEmailChanged("not-an-email")
        vm.submit()
        advanceUntilIdle()
        assertEquals(EditEmailError.INVALID_EMAIL, vm.ui.value.error)
        verify(user, never()).reauthenticate(any<AuthCredential>())
    }

    @Test fun `submit wrong password surfaces WRONG_PASSWORD`() = runTest(dispatcher) {
        whenever(user.reauthenticate(any())).thenReturn(
            Tasks.forException(FirebaseAuthInvalidCredentialsException("ERROR_WRONG_PASSWORD", "bad"))
        )
        val vm = EditEmailViewModel(auth, accountService)
        vm.onCurrentPasswordChanged("pw")
        vm.onNewEmailChanged("new@example.com")
        vm.submit()
        advanceUntilIdle()
        assertEquals(EditEmailError.WRONG_PASSWORD, vm.ui.value.error)
        verify(user, never()).verifyBeforeUpdateEmail(any())
    }

    @Test fun `submit happy path is mailed by the backend and emits Done`() = runTest(dispatcher) {
        val vm = EditEmailViewModel(auth, accountService)
        submitNewEmail(vm)
        advanceUntilIdle()
        verify(user, never()).verifyBeforeUpdateEmail(any())
        assertEquals(EditEmailViewModel.Nav.Done, vm.nav.value)
    }

    @Test fun `submit falls back to Firebase when the backend has no mailer`() = runTest(dispatcher) {
        backendAnswers(503)
        val vm = EditEmailViewModel(auth, accountService)
        submitNewEmail(vm)
        advanceUntilIdle()
        verify(user).verifyBeforeUpdateEmail("new@example.com")
        assertEquals(EditEmailViewModel.Nav.Done, vm.nav.value)
    }

    @Test fun `submit falls back to Firebase when the backend is unreachable`() = runTest(dispatcher) {
        accountService.stub {
            onBlocking { sendChangeEmailVerification(any()) } doAnswer { throw java.io.IOException("offline") }
        }
        val vm = EditEmailViewModel(auth, accountService)
        submitNewEmail(vm)
        advanceUntilIdle()
        verify(user).verifyBeforeUpdateEmail("new@example.com")
        assertEquals(EditEmailViewModel.Nav.Done, vm.nav.value)
    }

    /** The backend refuses a token whose auth_time is over 5 minutes old; the cached token still
     *  carries the pre-reauth auth_time, so it must be re-minted between the two. */
    @Test fun `submit force-refreshes the ID token between reauthenticating and the backend call`() = runTest(dispatcher) {
        val vm = EditEmailViewModel(auth, accountService)
        submitNewEmail(vm)
        advanceUntilIdle()
        val order = inOrder(user, accountService)
        order.verify(user).reauthenticate(any())
        order.verify(user).getIdToken(true)
        order.verify(accountService).sendChangeEmailVerification(any())
    }

    @Test fun `submit maps REQUIRES_RECENT_LOGIN to the password prompt without falling back`() = runTest(dispatcher) {
        backendAnswers(401)
        val vm = EditEmailViewModel(auth, accountService)
        submitNewEmail(vm)
        advanceUntilIdle()
        assertEquals(EditEmailError.WRONG_PASSWORD, vm.ui.value.error)
        verify(user, never()).verifyBeforeUpdateEmail(any())
    }

    @Test fun `submit maps the backend 409 to EMAIL_IN_USE without falling back`() = runTest(dispatcher) {
        backendAnswers(409)
        val vm = EditEmailViewModel(auth, accountService)
        submitNewEmail(vm)
        advanceUntilIdle()
        assertEquals(EditEmailError.EMAIL_IN_USE, vm.ui.value.error)
        verify(user, never()).verifyBeforeUpdateEmail(any())
    }
}
