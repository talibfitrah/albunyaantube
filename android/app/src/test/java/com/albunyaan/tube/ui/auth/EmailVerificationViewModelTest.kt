package com.albunyaan.tube.ui.auth

import androidx.lifecycle.SavedStateHandle
import com.albunyaan.tube.auth.AuthRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.android.gms.tasks.Tasks
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
class EmailVerificationViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var auth: FirebaseAuth
    private lateinit var user: FirebaseUser
    private lateinit var authRepository: AuthRepository
    private lateinit var saved: SavedStateHandle

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        auth = mock()
        user = mock {
            on { email } doReturn "you@example.com"
            on { isEmailVerified } doReturn false
        }
        whenever(auth.currentUser).thenReturn(user)
        whenever(user.sendEmailVerification()).thenReturn(Tasks.forResult(null))
        whenever(user.reload()).thenReturn(Tasks.forResult(null))
        authRepository = mock()
        saved = SavedStateHandle()
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun newVm() = EmailVerificationViewModel(auth, authRepository, saved)

    @Test fun `enter sends verification once when lastSentAt is null`() = runTest(dispatcher) {
        val vm = newVm()
        advanceUntilIdle()
        verify(user, times(1)).sendEmailVerification()
        assertNotNull(vm.ui.value.lastSentAtMs)
    }

    @Test fun `enter does not resend when lastSentAt already in saved state`() = runTest(dispatcher) {
        saved["lastSentAtMs"] = 1_700_000_000_000L
        newVm()
        advanceUntilIdle()
        verify(user, never()).sendEmailVerification()
    }

    @Test fun `checkNow navigates to splash when verified`() = runTest(dispatcher) {
        val vm = newVm()
        advanceUntilIdle()
        whenever(user.isEmailVerified).thenReturn(true)
        vm.checkNow()
        advanceUntilIdle()
        assertEquals(EmailVerificationViewModel.Nav.NavigateToSplash, vm.nav.value)
    }

    @Test fun `checkNow surfaces NOT_YET_VERIFIED when still false`() = runTest(dispatcher) {
        val vm = newVm()
        advanceUntilIdle()
        vm.checkNow()
        advanceUntilIdle()
        assertEquals(EmailVerifyError.NOT_YET_VERIFIED, vm.ui.value.error)
    }

    @Test fun `resend respects 60s cooldown`() = runTest(dispatcher) {
        val vm = newVm()
        advanceUntilIdle()
        clearInvocations(user)
        vm.resend()
        advanceUntilIdle()
        verify(user, never()).sendEmailVerification()
        assertEquals(EmailVerifyError.RATE_LIMITED, vm.ui.value.error)
    }

    @Test fun `signOut clears state and navigates to signIn`() = runTest(dispatcher) {
        val vm = newVm()
        advanceUntilIdle()
        vm.signOut()
        advanceUntilIdle()
        verify(authRepository).signOut()
        assertEquals(EmailVerificationViewModel.Nav.NavigateToSignIn, vm.nav.value)
    }
}
