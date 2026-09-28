package com.albunyaan.tube.ui.me.profile.edit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.auth.AccountState
import com.albunyaan.tube.auth.AccountStatus
import com.albunyaan.tube.data.account.AccountMeResponseDto
import com.albunyaan.tube.data.account.AccountUpdateRepository
import com.albunyaan.tube.data.account.ProfileUpdateResult
import com.albunyaan.tube.data.account.dto.UpdateProfileRequestDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EditPhoneViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var ctx: Context
    private lateinit var updateRepo: AccountUpdateRepository
    private lateinit var accountRepo: AccountRepository

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        ctx = ApplicationProvider.getApplicationContext()
        updateRepo = mock()
        accountRepo = mock()
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `submit with invalid number surfaces INVALID_PHONE`() = runTest(dispatcher) {
        val vm = EditPhoneViewModel(ctx, updateRepo, accountRepo)
        vm.onCountryChanged("NL")
        vm.onNumberChanged("12345")
        vm.submit()
        advanceUntilIdle()
        assertEquals(EditPhoneError.INVALID_PHONE, vm.ui.value.error)
        verifyNoInteractions(updateRepo)
    }

    @Test fun `submit happy path calls updateProfile and emits Done`() = runTest(dispatcher) {
        val response = AccountMeResponseDto(
            uid = "u1", email = "a@b.co", displayName = "Alice",
            dateOfBirth = null, phoneNumber = "+31612345678",
            status = "active", role = "user", profileCompletedAt = null)
        whenever(updateRepo.updateProfile(UpdateProfileRequestDto(phoneNumber = "+31612345678")))
            .thenReturn(ProfileUpdateResult.Success(response))

        val vm = EditPhoneViewModel(ctx, updateRepo, accountRepo)
        vm.onCountryChanged("NL")
        vm.onNumberChanged("612345678")
        vm.submit()
        advanceUntilIdle()

        verify(accountRepo).applyProfileUpdate(response)
        assertEquals(EditPhoneViewModel.Nav.Done, vm.nav.value)
    }

    /** "" is the server's "remove the saved phone" (null means "no change"). */
    @Test fun `removePhone sends an empty phoneNumber and emits Removed`() = runTest(dispatcher) {
        val response = AccountMeResponseDto(
            uid = "u1", email = "a@b.co", displayName = "Alice",
            dateOfBirth = null, phoneNumber = null,
            status = "active", role = "user", profileCompletedAt = null)
        whenever(updateRepo.updateProfile(UpdateProfileRequestDto(phoneNumber = "")))
            .thenReturn(ProfileUpdateResult.Success(response))

        val vm = EditPhoneViewModel(ctx, updateRepo, accountRepo)
        vm.seed("NL", "612345678")
        vm.removePhone()
        advanceUntilIdle()

        verify(updateRepo).updateProfile(UpdateProfileRequestDto(phoneNumber = ""))
        verify(accountRepo).applyProfileUpdate(response)
        assertFalse(vm.ui.value.saving)
        assertEquals(EditPhoneViewModel.Nav.Removed, vm.nav.value)
    }

    @Test fun `removePhone failure keeps the sheet open with the error`() = runTest(dispatcher) {
        whenever(updateRepo.updateProfile(UpdateProfileRequestDto(phoneNumber = "")))
            .thenReturn(ProfileUpdateResult.NetworkError)

        val vm = EditPhoneViewModel(ctx, updateRepo, accountRepo)
        vm.removePhone()
        advanceUntilIdle()

        assertEquals(EditPhoneError.NETWORK, vm.ui.value.error)
        assertEquals(EditPhoneViewModel.Nav.Idle, vm.nav.value)
        verify(accountRepo, never()).applyProfileUpdate(any())
    }

    @Test fun `hasSavedPhone follows the signed-in account`() {
        fun loaded(phone: String?) = AccountState.Loaded(
            uid = "u1", email = "a@b.co", displayName = "Alice", dateOfBirth = null,
            phoneNumber = phone, status = AccountStatus.ACTIVE, role = "user")
        whenever(accountRepo.accountState).thenReturn(MutableStateFlow(loaded("+31612345678")))
        assertTrue(EditPhoneViewModel(ctx, updateRepo, accountRepo).hasSavedPhone)
        whenever(accountRepo.accountState).thenReturn(MutableStateFlow(loaded(null)))
        assertFalse(EditPhoneViewModel(ctx, updateRepo, accountRepo).hasSavedPhone)
    }
}
