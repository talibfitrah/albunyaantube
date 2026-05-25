package com.albunyaan.tube.ui.me.profile.edit

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class EditPasswordViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var auth: FirebaseAuth
    private lateinit var user: FirebaseUser

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        auth = mock()
        user = mock { on { email } doReturn "you@example.com" }
        whenever(auth.currentUser).thenReturn(user)
        whenever(user.reauthenticate(any())).thenReturn(Tasks.forResult(null))
        whenever(user.updatePassword(any())).thenReturn(Tasks.forResult(null))
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `mismatch surfaces PASSWORD_MISMATCH`() = runTest(dispatcher) {
        val vm = EditPasswordViewModel(auth)
        vm.onCurrentChanged("old")
        vm.onNewChanged("12345678")
        vm.onConfirmChanged("87654321")
        vm.submit()
        advanceUntilIdle()
        assertEquals(EditPasswordError.PASSWORD_MISMATCH, vm.ui.value.error)
    }

    @Test fun `too-short new surfaces WEAK_PASSWORD`() = runTest(dispatcher) {
        val vm = EditPasswordViewModel(auth)
        vm.onCurrentChanged("old")
        vm.onNewChanged("short")
        vm.onConfirmChanged("short")
        vm.submit()
        advanceUntilIdle()
        assertEquals(EditPasswordError.WEAK_PASSWORD, vm.ui.value.error)
    }

    @Test fun `happy path emits Done`() = runTest(dispatcher) {
        val vm = EditPasswordViewModel(auth)
        vm.onCurrentChanged("old")
        vm.onNewChanged("newpassword1")
        vm.onConfirmChanged("newpassword1")
        vm.submit()
        advanceUntilIdle()
        verify(user).updatePassword("newpassword1")
        assertEquals(EditPasswordViewModel.Nav.Done, vm.nav.value)
    }

    @Test fun `wrong current password surfaces WRONG_PASSWORD`() = runTest(dispatcher) {
        whenever(user.reauthenticate(any())).thenReturn(
            Tasks.forException(FirebaseAuthInvalidCredentialsException("ERROR_WRONG_PASSWORD", "bad"))
        )
        val vm = EditPasswordViewModel(auth)
        vm.onCurrentChanged("old")
        vm.onNewChanged("newpassword1")
        vm.onConfirmChanged("newpassword1")
        vm.submit()
        advanceUntilIdle()
        assertEquals(EditPasswordError.WRONG_PASSWORD, vm.ui.value.error)
        verify(user, never()).updatePassword(any())
    }
}
