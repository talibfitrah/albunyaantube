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

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        auth = mock()
        user = mock { on { email } doReturn "old@example.com" }
        whenever(auth.currentUser).thenReturn(user)
        whenever(user.reauthenticate(any())).thenReturn(Tasks.forResult(null))
        whenever(user.verifyBeforeUpdateEmail(any())).thenReturn(Tasks.forResult(null))
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `submit with malformed new email surfaces INVALID_EMAIL`() = runTest(dispatcher) {
        val vm = EditEmailViewModel(auth)
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
        val vm = EditEmailViewModel(auth)
        vm.onCurrentPasswordChanged("pw")
        vm.onNewEmailChanged("new@example.com")
        vm.submit()
        advanceUntilIdle()
        assertEquals(EditEmailError.WRONG_PASSWORD, vm.ui.value.error)
        verify(user, never()).verifyBeforeUpdateEmail(any())
    }

    @Test fun `submit happy path emits Done`() = runTest(dispatcher) {
        val vm = EditEmailViewModel(auth)
        vm.onCurrentPasswordChanged("pw")
        vm.onNewEmailChanged("new@example.com")
        vm.submit()
        advanceUntilIdle()
        verify(user).verifyBeforeUpdateEmail("new@example.com")
        assertEquals(EditEmailViewModel.Nav.Done, vm.nav.value)
    }
}
