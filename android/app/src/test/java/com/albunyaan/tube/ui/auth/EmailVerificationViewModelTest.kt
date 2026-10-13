package com.albunyaan.tube.ui.auth

import androidx.lifecycle.SavedStateHandle
import com.albunyaan.tube.auth.AuthRepository
import com.albunyaan.tube.data.account.AccountService
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
    private lateinit var accountService: AccountService
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
        whenever(user.getIdToken(true)).thenReturn(Tasks.forResult(mock<com.google.firebase.auth.GetTokenResult>()))
        authRepository = mock()
        accountService = mock {
            onBlocking { sendVerificationEmail() } doReturn retrofit2.Response.success(Unit)
        }
        saved = SavedStateHandle()
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun newVm() = EmailVerificationViewModel(auth, authRepository, accountService, saved)

    @Test fun `enter sends verification once when lastSentAt is null`() = runTest(dispatcher) {
        val vm = newVm()
        advanceUntilIdle()
        verify(accountService, times(1)).sendVerificationEmail()
        assertNotNull(vm.ui.value.lastSentAtMs)
    }

    /** Cold start (splash route, autoSend=false): sign-up or sign-in already mailed, in an earlier process. */
    @Test fun `arriving from the splash sends nothing`() = runTest(dispatcher) {
        saved["autoSend"] = false
        newVm()
        advanceUntilIdle()
        verify(accountService, never()).sendVerificationEmail()
        verify(user, never()).sendEmailVerification()
    }

    @Test fun `enter does not resend when lastSentAt already in saved state`() = runTest(dispatcher) {
        saved["lastSentAtMs"] = 1_700_000_000_000L
        newVm()
        advanceUntilIdle()
        verify(accountService, never()).sendVerificationEmail()
    }

    @Test fun `checkNow navigates to splash when verified`() = runTest(dispatcher) {
        val vm = newVm()
        advanceUntilIdle()
        whenever(user.isEmailVerified).thenReturn(true)
        vm.checkNow()
        advanceUntilIdle()
        assertEquals(EmailVerificationViewModel.Nav.NavigateToSplash, vm.nav.value)
    }

    /** reload() refreshes the user record, not the cached ID token, whose email_verified claim every
     *  backend route but POST /api/account/profile trusts as minted. */
    @Test fun `checkNow force-refreshes the ID token after a verified reload`() = runTest(dispatcher) {
        val vm = newVm()
        advanceUntilIdle()
        whenever(user.isEmailVerified).thenReturn(true)
        vm.checkNow()
        advanceUntilIdle()
        val order = inOrder(user)
        order.verify(user).reload()
        order.verify(user).getIdToken(true)
        assertEquals(EmailVerificationViewModel.Nav.NavigateToSplash, vm.nav.value)
    }

    @Test fun `checkNow stays put when the token refresh fails`() = runTest(dispatcher) {
        val vm = newVm()
        advanceUntilIdle()
        whenever(user.isEmailVerified).thenReturn(true)
        whenever(user.getIdToken(true)).thenReturn(Tasks.forException(java.io.IOException("offline")))
        vm.checkNow()
        advanceUntilIdle()
        assertEquals(EmailVerificationViewModel.Nav.Idle, vm.nav.value)
        assertEquals(EmailVerifyError.NETWORK, vm.ui.value.error)
        assertFalse(vm.ui.value.isChecking)
    }

    @Test fun `checkNow surfaces NOT_YET_VERIFIED when still false`() = runTest(dispatcher) {
        val vm = newVm()
        advanceUntilIdle()
        vm.checkNow()
        advanceUntilIdle()
        assertEquals(EmailVerifyError.NOT_YET_VERIFIED, vm.ui.value.error)
        verify(user, never()).getIdToken(any())
    }

    @Test fun `resend respects 60s cooldown`() = runTest(dispatcher) {
        val vm = newVm()
        advanceUntilIdle()
        clearInvocations(accountService)
        vm.resend()
        advanceUntilIdle()
        verify(accountService, never()).sendVerificationEmail()
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

    @Test fun `backend failure falls back to Firebase sendEmailVerification`() = runTest(dispatcher) {
        accountService = mock {
            onBlocking { sendVerificationEmail() } doReturn
                retrofit2.Response.error<Unit>(500, okhttp3.ResponseBody.create(null, ""))
        }
        val vm = EmailVerificationViewModel(auth, authRepository, accountService, saved)
        advanceUntilIdle()
        verify(user, times(1)).sendEmailVerification()
        assertNotNull(vm.ui.value.lastSentAtMs)
    }
}
