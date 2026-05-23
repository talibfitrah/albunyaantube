package com.albunyaan.tube.ui.bootstrap

import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.auth.AccountState
import com.albunyaan.tube.auth.AccountStatus
import com.albunyaan.tube.auth.AgeIneligibleError
import com.google.firebase.auth.FirebaseAuth
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
import org.mockito.kotlin.*

import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileBootstrapViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: AccountRepository
    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var viewModel: ProfileBootstrapViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mock()
        whenever(repository.accountState).thenReturn(MutableStateFlow(AccountState.NotSignedIn))
        // Path B: ViewModel needs FirebaseAuth to call updatePassword when
        // passwordRequired is true. None of the existing tests exercise
        // that path (they all leave passwordRequired=false), so the mock
        // only needs to satisfy the constructor.
        firebaseAuth = mock()
        viewModel = ProfileBootstrapViewModel(repository, firebaseAuth)
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `initial state has empty fields`() {
        val s = viewModel.ui.value
        assertEquals("", s.displayName)
        assertNull(s.dateOfBirth)
        assertFalse(s.isLoading)
        assertNull(s.error)
    }

    @Test fun `onDisplayNameChanged updates field and clears error`() {
        viewModel.surfaceError(BootstrapError.SAVE_FAILED)
        viewModel.onDisplayNameChanged("Alice")
        assertEquals("Alice", viewModel.ui.value.displayName)
        assertNull(viewModel.ui.value.error)
    }

    @Test fun `submit with blank name surfaces INVALID_NAME`() = runTest(dispatcher) {
        viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
        viewModel.submit()
        advanceUntilIdle()
        assertEquals(BootstrapError.INVALID_NAME, viewModel.ui.value.error)
        verify(repository, never()).completeProfile(any(), any())
    }

    @Test fun `submit with missing dob surfaces INVALID_DOB`() = runTest(dispatcher) {
        viewModel.onDisplayNameChanged("Alice")
        viewModel.submit()
        advanceUntilIdle()
        assertEquals(BootstrapError.INVALID_DOB, viewModel.ui.value.error)
    }

    @Test fun `submit happy path transitions to NavigateToMain`() = runTest(dispatcher) {
        whenever(repository.completeProfile("Alice", LocalDate.of(2000, 1, 1)))
            .thenReturn(Result.success(AccountState.Loaded(
                uid = "uid-1", email = "a@b.com", displayName = "Alice",
                dateOfBirth = null, status = AccountStatus.ACTIVE, role = "user")))

        viewModel.onDisplayNameChanged("Alice")
        viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(BootstrapNav.NavigateToMain, viewModel.nav.value)
    }

    @Test fun `submit 422 AGE_INELIGIBLE transitions to NavigateToAgeIneligible`() = runTest(dispatcher) {
        whenever(repository.completeProfile(any(), any()))
            .thenReturn(Result.failure(AgeIneligibleError()))

        viewModel.onDisplayNameChanged("Kid")
        viewModel.onDobChanged(LocalDate.of(2020, 1, 1))
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(BootstrapNav.NavigateToAgeIneligible, viewModel.nav.value)
    }

    @Test fun `submit network error surfaces SAVE_FAILED`() = runTest(dispatcher) {
        whenever(repository.completeProfile(any(), any()))
            .thenReturn(Result.failure(java.io.IOException("offline")))

        viewModel.onDisplayNameChanged("Alice")
        viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(BootstrapError.SAVE_FAILED, viewModel.ui.value.error)
    }

    @Test fun `submit during loading is no-op`() = runTest(dispatcher) {
        viewModel.setLoading(true)
        viewModel.onDisplayNameChanged("Alice")
        viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
        viewModel.submit()
        advanceUntilIdle()
        verify(repository, never()).completeProfile(any(), any())
    }
}
