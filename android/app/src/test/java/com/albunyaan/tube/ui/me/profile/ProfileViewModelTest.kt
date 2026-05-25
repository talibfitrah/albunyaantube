package com.albunyaan.tube.ui.me.profile

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun loadedState(
        displayName: String? = "Alice",
        dateOfBirth: String? = "1990-01-15",
        email: String? = "alice@example.com",
    ) = AccountState.Loaded(
        uid = "uid1",
        email = email,
        displayName = displayName,
        dateOfBirth = dateOfBirth,
        phoneNumber = null,
        status = AccountStatus.ACTIVE,
        role = "USER",
    )

    private fun fakeResponse(
        displayName: String? = "Alice",
        dateOfBirth: String? = "1990-01-15",
    ) = AccountMeResponseDto(
        uid = "uid1",
        email = "alice@example.com",
        displayName = displayName,
        dateOfBirth = dateOfBirth,
        phoneNumber = null,
        status = "ACTIVE",
        role = "USER",
        profileCompletedAt = null,
    )

    private fun makeAccountRepo(
        state: AccountState = loadedState(),
    ): AccountRepository {
        val repo: AccountRepository = mock()
        whenever(repo.accountState).thenReturn(MutableStateFlow(state))
        return repo
    }

    // ── P1: load → Editing ────────────────────────────────────────────────────

    @Test fun `load transitions to Editing with values from AccountState`() = runTest(dispatcher) {
        val accountRepo = makeAccountRepo()
        val updateRepo: AccountUpdateRepository = mock()

        val vm = ProfileViewModel(accountRepo, updateRepo)

        val state = vm.uiState.value
        assertTrue(state is ProfileUiState.Editing)
        val editing = state as ProfileUiState.Editing
        assertEquals("Alice", editing.draft.displayName)
        assertEquals("1990-01-15", editing.draft.dateOfBirth)
        assertEquals("alice@example.com", editing.draft.emailReadOnly)
        assertFalse(editing.isDirty)
    }

    @Test fun `load stays Loading when AccountState is not Loaded`() = runTest(dispatcher) {
        val accountRepo: AccountRepository = mock()
        whenever(accountRepo.accountState).thenReturn(MutableStateFlow(AccountState.Loading))
        val updateRepo: AccountUpdateRepository = mock()

        val vm = ProfileViewModel(accountRepo, updateRepo)

        assertTrue(vm.uiState.value is ProfileUiState.Loading)
    }

    // ── P2: draft mutations ────────────────────────────────────────────────────

    @Test fun `onDisplayNameChange updates draft and sets isDirty`() = runTest(dispatcher) {
        val vm = ProfileViewModel(makeAccountRepo(), mock())

        vm.onDisplayNameChange("Bob")

        val editing = vm.uiState.value as ProfileUiState.Editing
        assertEquals("Bob", editing.draft.displayName)
        assertEquals("Alice", editing.original.displayName)   // original unchanged
        assertTrue(editing.isDirty)
    }

    @Test fun `onDateOfBirthChange updates draft`() = runTest(dispatcher) {
        val vm = ProfileViewModel(makeAccountRepo(), mock())

        vm.onDateOfBirthChange("2000-06-01")

        val editing = vm.uiState.value as ProfileUiState.Editing
        assertEquals("2000-06-01", editing.draft.dateOfBirth)
        assertTrue(editing.isDirty)
    }

    @Test fun `mutating draft clears existing error`() = runTest(dispatcher) {
        val accountRepo = makeAccountRepo()
        val updateRepo: AccountUpdateRepository = mock()
        whenever(updateRepo.updateProfile(any())).thenReturn(ProfileUpdateResult.NetworkError)

        val vm = ProfileViewModel(accountRepo, updateRepo)
        vm.onDisplayNameChange("Bob")
        vm.save()
        advanceUntilIdle()

        // error set → now mutate
        vm.onDisplayNameChange("Carol")

        val editing = vm.uiState.value as ProfileUiState.Editing
        assertNull(editing.error)
    }

    // ── P3: save — no-op when not dirty ───────────────────────────────────────

    @Test fun `save does nothing when not dirty`() = runTest(dispatcher) {
        val updateRepo: AccountUpdateRepository = mock()
        val vm = ProfileViewModel(makeAccountRepo(), updateRepo)

        vm.save()
        advanceUntilIdle()

        verify(updateRepo, never()).updateProfile(any())
        assertTrue(vm.uiState.value is ProfileUiState.Editing)
    }

    // ── P4: save — success ────────────────────────────────────────────────────

    @Test fun `save success calls applyProfileUpdate and resets isDirty`() = runTest(dispatcher) {
        val response = fakeResponse(displayName = "Bob")
        val accountRepo = makeAccountRepo()
        val updateRepo: AccountUpdateRepository = mock()
        whenever(updateRepo.updateProfile(any())).thenReturn(ProfileUpdateResult.Success(response))

        val vm = ProfileViewModel(accountRepo, updateRepo)
        vm.onDisplayNameChange("Bob")
        vm.save()
        advanceUntilIdle()

        verify(accountRepo).applyProfileUpdate(response)

        val editing = vm.uiState.value as ProfileUiState.Editing
        assertEquals("Bob", editing.original.displayName)
        assertEquals("Bob", editing.draft.displayName)
        assertFalse(editing.isDirty)
        assertNull(editing.error)
    }

    @Test fun `save sends only changed fields in request`() = runTest(dispatcher) {
        val accountRepo = makeAccountRepo()
        val updateRepo: AccountUpdateRepository = mock()
        whenever(updateRepo.updateProfile(any())).thenReturn(ProfileUpdateResult.Success(fakeResponse(displayName = "Bob")))

        val vm = ProfileViewModel(accountRepo, updateRepo)
        vm.onDisplayNameChange("Bob")  // only name changed; DOB unchanged
        vm.save()
        advanceUntilIdle()

        verify(updateRepo).updateProfile(UpdateProfileRequestDto(displayName = "Bob", dateOfBirth = null))
    }

    // ── P5: save — AgeIneligible → dialog-trigger state, signOut deferred ─

    @Test fun `save AgeIneligible stops at Editing+error and does NOT sign out yet`() = runTest(dispatcher) {
        // save() must stop at Editing(error=AgeIneligible) so the dialog
        // renders before sign-out — StateFlow conflation would otherwise
        // skip past it to SignedOut. Fragment calls
        // confirmAgeIneligibleSignOut on OK to finish the flow.
        val accountRepo = makeAccountRepo()
        val updateRepo: AccountUpdateRepository = mock()
        whenever(updateRepo.updateProfile(any())).thenReturn(ProfileUpdateResult.AgeIneligible)

        val vm = ProfileViewModel(accountRepo, updateRepo)
        vm.onDisplayNameChange("Bob")
        vm.save()
        advanceUntilIdle()

        val editing = vm.uiState.value as ProfileUiState.Editing
        assertEquals(ProfileError.AgeIneligible, editing.error)
        assertFalse(editing.saving)
        verify(accountRepo, never()).signOut()
    }

    @Test fun `confirmAgeIneligibleSignOut signs out and emits SignedOut`() = runTest(dispatcher) {
        val accountRepo = makeAccountRepo()
        val updateRepo: AccountUpdateRepository = mock()

        val vm = ProfileViewModel(accountRepo, updateRepo)
        vm.confirmAgeIneligibleSignOut()

        verify(accountRepo).signOut()
        assertTrue(vm.uiState.value is ProfileUiState.SignedOut)
    }

    // ── P6: save — RateLimited ────────────────────────────────────────────────

    @Test fun `save RateLimited emits Editing with RateLimited error, draft preserved`() = runTest(dispatcher) {
        val accountRepo = makeAccountRepo()
        val updateRepo: AccountUpdateRepository = mock()
        whenever(updateRepo.updateProfile(any())).thenReturn(ProfileUpdateResult.RateLimited(retryAfterSec = 60L))

        val vm = ProfileViewModel(accountRepo, updateRepo)
        vm.onDisplayNameChange("Bob")
        vm.save()
        advanceUntilIdle()

        val editing = vm.uiState.value as ProfileUiState.Editing
        assertFalse(editing.saving)
        assertEquals("Bob", editing.draft.displayName)  // draft preserved
        val error = editing.error as ProfileError.RateLimited
        assertEquals(60L, error.retryAfterSec)
    }

    // ── P7: save — ValidationFailed ───────────────────────────────────────────

    @Test fun `save ValidationFailed emits Validation error`() = runTest(dispatcher) {
        val accountRepo = makeAccountRepo()
        val updateRepo: AccountUpdateRepository = mock()
        whenever(updateRepo.updateProfile(any()))
            .thenReturn(ProfileUpdateResult.ValidationFailed("displayName", "Name too short"))

        val vm = ProfileViewModel(accountRepo, updateRepo)
        vm.onDisplayNameChange("B")
        vm.save()
        advanceUntilIdle()

        val editing = vm.uiState.value as ProfileUiState.Editing
        val error = editing.error as ProfileError.Validation
        assertEquals("displayName", error.field)
        assertEquals("Name too short", error.message)
    }

    // ── P8: save — NetworkError ───────────────────────────────────────────────

    @Test fun `save NetworkError emits Network error`() = runTest(dispatcher) {
        val accountRepo = makeAccountRepo()
        val updateRepo: AccountUpdateRepository = mock()
        whenever(updateRepo.updateProfile(any())).thenReturn(ProfileUpdateResult.NetworkError)

        val vm = ProfileViewModel(accountRepo, updateRepo)
        vm.onDisplayNameChange("Bob")
        vm.save()
        advanceUntilIdle()

        val editing = vm.uiState.value as ProfileUiState.Editing
        assertTrue(editing.error is ProfileError.Network)
        assertFalse(editing.saving)
    }

    // ── P9: save — Unknown ────────────────────────────────────────────────────

    @Test fun `save Unknown emits Unknown error`() = runTest(dispatcher) {
        val accountRepo = makeAccountRepo()
        val updateRepo: AccountUpdateRepository = mock()
        whenever(updateRepo.updateProfile(any())).thenReturn(ProfileUpdateResult.Unknown(code = 503))

        val vm = ProfileViewModel(accountRepo, updateRepo)
        vm.onDisplayNameChange("Bob")
        vm.save()
        advanceUntilIdle()

        val editing = vm.uiState.value as ProfileUiState.Editing
        assertTrue(editing.error is ProfileError.Unknown)
        assertFalse(editing.saving)
    }

    // ── P10: save — no double-submit ──────────────────────────────────────────

    @Test fun `save while saving is no-op`() = runTest(dispatcher) {
        // updateProfile never completes, so saving stays true
        val accountRepo = makeAccountRepo()
        val updateRepo: AccountUpdateRepository = mock()
        // suspend that never completes inline — use a real answer but check call count
        whenever(updateRepo.updateProfile(any())).thenReturn(ProfileUpdateResult.NetworkError)

        val vm = ProfileViewModel(accountRepo, updateRepo)
        vm.onDisplayNameChange("Bob")
        // First save
        vm.save()
        // Second save before coroutine runs (saving = true, guard fires)
        vm.save()
        advanceUntilIdle()

        // Only one API call despite two save() calls
        verify(updateRepo, org.mockito.kotlin.times(1)).updateProfile(any())
    }
}
