package com.albunyaan.tube.ui.me.profile.edit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.data.account.AccountMeResponseDto
import com.albunyaan.tube.data.account.AccountUpdateRepository
import com.albunyaan.tube.data.account.ProfileUpdateResult
import com.albunyaan.tube.data.account.dto.UpdateProfileRequestDto
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
}
