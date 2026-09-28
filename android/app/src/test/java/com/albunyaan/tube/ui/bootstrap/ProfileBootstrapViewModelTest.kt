package com.albunyaan.tube.ui.bootstrap

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner

import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ProfileBootstrapViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: AccountRepository
    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var ctx: Context
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
        ctx = ApplicationProvider.getApplicationContext()
        viewModel = ProfileBootstrapViewModel(repository, firebaseAuth, ctx)
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
        verify(repository, never()).completeProfile(any(), any(), any())
    }

    @Test fun `submit with missing dob surfaces INVALID_DOB`() = runTest(dispatcher) {
        viewModel.onDisplayNameChanged("Alice")
        viewModel.submit()
        advanceUntilIdle()
        assertEquals(BootstrapError.INVALID_DOB, viewModel.ui.value.error)
    }

    @Test fun `submit happy path transitions to NavigateToMain`() = runTest(dispatcher) {
        whenever(repository.completeProfile("Alice", LocalDate.of(2000, 1, 1), "+31612345678"))
            .thenReturn(Result.success(AccountState.Loaded(
                uid = "uid-1", email = "a@b.com", displayName = "Alice",
                dateOfBirth = null, phoneNumber = "+31612345678",
                status = AccountStatus.ACTIVE, role = "user")))

        viewModel.onDisplayNameChanged("Alice")
        viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
        viewModel.onPhoneCountryChanged("NL")
        viewModel.onPhoneNumberChanged("612345678")
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(BootstrapNav.NavigateToMain, viewModel.nav.value)
    }

    @Test fun `submit 422 AGE_INELIGIBLE transitions to NavigateToAgeIneligible`() = runTest(dispatcher) {
        whenever(repository.completeProfile(any(), any(), any()))
            .thenReturn(Result.failure(AgeIneligibleError()))

        viewModel.onDisplayNameChanged("Kid")
        // Deliberately an adult date. This test is about handling the server's 422
        // AGE_INELIGIBLE response, which the mock above supplies -- the server stays the
        // authority on age. An under-13 date here would now be stopped by the client's
        // own validation (see MinimumAgeTest) and the request would never be sent, so the
        // test would silently stop exercising the path it is named for.
        viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
        viewModel.onPhoneCountryChanged("NL")
        viewModel.onPhoneNumberChanged("612345678")
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(BootstrapNav.NavigateToAgeIneligible, viewModel.nav.value)
    }

    @Test fun `submit network error surfaces SAVE_FAILED`() = runTest(dispatcher) {
        whenever(repository.completeProfile(any(), any(), any()))
            .thenReturn(Result.failure(java.io.IOException("offline")))

        viewModel.onDisplayNameChanged("Alice")
        viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
        viewModel.onPhoneCountryChanged("NL")
        viewModel.onPhoneNumberChanged("612345678")
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
        verify(repository, never()).completeProfile(any(), any(), any())
    }

    @Test fun `isFormValid is false on initial empty state`() {
        assertFalse(viewModel.isFormValid)
    }

    @Test fun `isFormValid is false when name is blank but dob is set`() {
        viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
        assertFalse(viewModel.isFormValid)
    }

    @Test fun `isFormValid is false when dob is missing but name is set`() {
        viewModel.onDisplayNameChanged("Alice")
        assertFalse(viewModel.isFormValid)
    }

    @Test fun `isFormValid is false when name exceeds 40 chars`() {
        viewModel.onDisplayNameChanged("A".repeat(41))
        viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
        assertFalse(viewModel.isFormValid)
    }

    @Test fun `isFormValid is true with name, dob, and phone when password not required`() {
        viewModel.onDisplayNameChanged("Alice")
        viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
        viewModel.onPhoneCountryChanged("NL")
        viewModel.onPhoneNumberChanged("612345678")
        assertTrue(viewModel.isFormValid)
    }

    @Test fun `isFormValid is false when passwordRequired but password missing`() {
        viewModel.onDisplayNameChanged("Alice")
        viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
        viewModel.onPhoneCountryChanged("NL")
        viewModel.onPhoneNumberChanged("612345678")
        viewModel.setPasswordRequirement(true)
        assertFalse(viewModel.isFormValid)
    }

    @Test fun `isFormValid is false when password is too short`() {
        viewModel.onDisplayNameChanged("Alice")
        viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
        viewModel.onPhoneCountryChanged("NL")
        viewModel.onPhoneNumberChanged("612345678")
        viewModel.setPasswordRequirement(true)
        viewModel.onPasswordChanged("short")
        viewModel.onPasswordConfirmChanged("short")
        assertFalse(viewModel.isFormValid)
    }

    @Test fun `isFormValid is false when passwords do not match`() {
        viewModel.onDisplayNameChanged("Alice")
        viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
        viewModel.onPhoneCountryChanged("NL")
        viewModel.onPhoneNumberChanged("612345678")
        viewModel.setPasswordRequirement(true)
        viewModel.onPasswordChanged("validpass1")
        viewModel.onPasswordConfirmChanged("validpass2")
        assertFalse(viewModel.isFormValid)
    }

    @Test fun `isFormValid is true with matching 8+ char password when required`() {
        viewModel.onDisplayNameChanged("Alice")
        viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
        viewModel.onPhoneCountryChanged("NL")
        viewModel.onPhoneNumberChanged("612345678")
        viewModel.setPasswordRequirement(true)
        viewModel.onPasswordChanged("validpass1")
        viewModel.onPasswordConfirmChanged("validpass1")
        assertTrue(viewModel.isFormValid)
    }

    @Test fun `isFormValid trims whitespace-only name as blank`() {
        viewModel.onDisplayNameChanged("   ")
        viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
        assertFalse(viewModel.isFormValid)
    }

    @Test fun `submit with missing phone country surfaces INVALID_PHONE_COUNTRY`() = runTest(dispatcher) {
        viewModel.onDisplayNameChanged("Alice")
        viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
        viewModel.onPhoneNumberChanged("612345678")
        viewModel.submit()
        advanceUntilIdle()
        assertEquals(BootstrapError.INVALID_PHONE_COUNTRY, viewModel.ui.value.error)
    }

    /** Owner ruling: the phone is optional. Empty phone → valid, and no phone is sent. */
    @Test fun `empty phone is optional - form valid and request has no phone`() = runTest(dispatcher) {
        whenever(repository.completeProfile(any(), any(), anyOrNull()))
            .thenReturn(Result.success(AccountState.Loaded(
                uid = "uid-1", email = "a@b.com", displayName = "Alice",
                dateOfBirth = null, phoneNumber = null,
                status = AccountStatus.ACTIVE, role = "user")))

        viewModel.onDisplayNameChanged("Alice")
        viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
        assertTrue("no country, no phone", viewModel.isFormValid)
        viewModel.onPhoneCountryChanged("NL")  // the Fragment seeds this from the device locale
        viewModel.onPhoneNumberChanged("  ")
        assertTrue("seeded country, blank phone", viewModel.isFormValid)
        viewModel.submit()
        advanceUntilIdle()

        verify(repository).completeProfile("Alice", LocalDate.of(2000, 1, 1), null)
        assertEquals(BootstrapNav.NavigateToMain, viewModel.nav.value)
    }

    @Test fun `submit with too-short national number surfaces INVALID_PHONE`() = runTest(dispatcher) {
        viewModel.onDisplayNameChanged("Alice")
        viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
        viewModel.onPhoneCountryChanged("NL")
        viewModel.onPhoneNumberChanged("12345")
        viewModel.submit()
        advanceUntilIdle()
        assertEquals(BootstrapError.INVALID_PHONE, viewModel.ui.value.error)
    }

    /**
     * Continue is disabled while the form is invalid, so errors that only appear after a
     * submit are never seen. Once the user has started filling the form, the first thing
     * blocking Continue is shown: on its field if the user has touched that field, otherwise
     * in the line above Continue. A pristine form shows nothing.
     */
    @Test fun `shownError is null on a pristine form, even with a seeded name`() {
        viewModel.seedDisplayName("Alice")
        viewModel.onDisplayNameChanged("Alice")   // the Fragment's setText echo
        viewModel.onPhoneCountryChanged("NL")     // locale seed
        viewModel.setPasswordRequirement(true)
        assertFalse(viewModel.isFormValid)
        assertNull(viewModel.shownError())
    }

    @Test fun `typing a name puts the date-of-birth reason by Continue, not on the untouched date field`() {
        viewModel.onDisplayNameChanged("A")
        assertEquals(ShownError(BootstrapError.INVALID_DOB, onField = false), viewModel.shownError())
    }

    @Test fun `a Google user who picks a date sees the password rule by Continue, not on the password field`() {
        viewModel.seedDisplayName("Alice")
        viewModel.setPasswordRequirement(true)
        viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
        assertEquals(ShownError(BootstrapError.INVALID_PASSWORD, onField = false), viewModel.shownError())

        // Still typing in the password field: no error on it, but Continue says why it is off.
        viewModel.onPasswordChanged("short")
        assertEquals(ShownError(BootstrapError.INVALID_PASSWORD, onField = false), viewModel.shownError())
        viewModel.onFieldLeft(BootstrapField.PASSWORD)
        assertEquals(ShownError(BootstrapError.INVALID_PASSWORD, onField = true), viewModel.shownError())
    }

    @Test fun `the password rule stays off the password field on every keystroke until it is left`() {
        viewModel.onDisplayNameChanged("Alice")
        viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
        viewModel.setPasswordRequirement(true)
        for (typed in listOf("v", "va", "val", "vali")) {
            viewModel.onPasswordChanged(typed)
            assertEquals(ShownError(BootstrapError.INVALID_PASSWORD, onField = false), viewModel.shownError())
        }
    }

    @Test fun `a valid password with an empty confirmation asks to confirm it, by Continue`() {
        viewModel.onDisplayNameChanged("Alice")
        viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
        viewModel.setPasswordRequirement(true)
        viewModel.onPasswordChanged("validpass1")
        assertEquals(ShownError(BootstrapError.CONFIRM_PASSWORD, onField = false), viewModel.shownError())
        assertFalse(viewModel.isFormValid)
        // Leaving the confirmation empty puts the same ask on its field.
        viewModel.onFieldLeft(BootstrapField.CONFIRM)
        assertEquals(ShownError(BootstrapError.CONFIRM_PASSWORD, onField = true), viewModel.shownError())
    }

    @Test fun `password mismatch waits until the confirmation is as long as the password`() {
        viewModel.onDisplayNameChanged("Alice")
        viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
        viewModel.setPasswordRequirement(true)
        viewModel.onPasswordChanged("validpass1")

        viewModel.onPasswordConfirmChanged("valid")          // a prefix, still typing: not a mismatch yet
        assertEquals(ShownError(BootstrapError.CONFIRM_PASSWORD, onField = false), viewModel.shownError())
        viewModel.onPasswordConfirmChanged("validpass2")     // same length, different: by Continue while focused
        assertEquals(ShownError(BootstrapError.PASSWORD_MISMATCH, onField = false), viewModel.shownError())
        viewModel.onFieldLeft(BootstrapField.CONFIRM)
        assertEquals(ShownError(BootstrapError.PASSWORD_MISMATCH, onField = true), viewModel.shownError())
        viewModel.onPasswordConfirmChanged("validpass1")
        assertNull(viewModel.shownError())
        assertTrue(viewModel.isFormValid)
    }

    @Test fun `a confirmation that is not a prefix of the password is a mismatch at once`() {
        viewModel.onDisplayNameChanged("Alice")
        viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
        viewModel.setPasswordRequirement(true)
        viewModel.onPasswordChanged("validpass1")
        viewModel.onPasswordConfirmChanged("vx")             // shorter, but already wrong
        assertEquals(ShownError(BootstrapError.PASSWORD_MISMATCH, onField = false), viewModel.shownError())
        viewModel.onFieldLeft(BootstrapField.CONFIRM)
        assertEquals(ShownError(BootstrapError.PASSWORD_MISMATCH, onField = true), viewModel.shownError())
    }

    @Test fun `a short confirmation is a mismatch once its field is left`() {
        viewModel.onDisplayNameChanged("Alice")
        viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
        viewModel.setPasswordRequirement(true)
        viewModel.onPasswordChanged("validpass1")
        viewModel.onPasswordConfirmChanged("valid")
        viewModel.onFieldLeft(BootstrapField.CONFIRM)
        assertEquals(ShownError(BootstrapError.PASSWORD_MISMATCH, onField = true), viewModel.shownError())
    }

    @Test fun `a typed but invalid phone goes on the phone field only once the field is left`() {
        viewModel.onDisplayNameChanged("Alice")
        viewModel.onDobChanged(LocalDate.of(2000, 1, 1))
        viewModel.onPhoneCountryChanged("NL")
        viewModel.onPhoneNumberChanged("1")
        assertEquals(ShownError(BootstrapError.INVALID_PHONE, onField = false), viewModel.shownError())
        viewModel.onPhoneNumberChanged("12345")
        assertEquals(ShownError(BootstrapError.INVALID_PHONE, onField = false), viewModel.shownError())
        viewModel.onFieldLeft(BootstrapField.PHONE)
        assertEquals(ShownError(BootstrapError.INVALID_PHONE, onField = true), viewModel.shownError())
        viewModel.onPhoneNumberChanged("")
        assertNull(viewModel.shownError())
    }

    @Test fun `shownError prefers a submit or server error`() {
        viewModel.onDisplayNameChanged("Alice")
        viewModel.surfaceError(BootstrapError.SAVE_FAILED)
        assertEquals(ShownError(BootstrapError.SAVE_FAILED, onField = true), viewModel.shownError())
    }

    /**
     * The server's under-13 rejection is permanent: it revokes tokens, disables the
     * Firebase account and tombstones it, with no recovery. So a mistyped year must never
     * reach it. This pins that the request is not even sent.
     */
    @Test fun `submit with an under-age dob surfaces UNDER_AGE and never calls the server`() =
        runTest(dispatcher) {
            viewModel.onDisplayNameChanged("Alice")
            viewModel.onDobChanged(LocalDate.now().minusYears(12))
            viewModel.onPhoneCountryChanged("NL")
            viewModel.onPhoneNumberChanged("612345678")
            viewModel.submit()
            advanceUntilIdle()

            assertEquals(BootstrapError.UNDER_AGE, viewModel.ui.value.error)
            assertEquals(BootstrapNav.Idle, viewModel.nav.value)
            verify(repository, never()).completeProfile(any(), any(), any())
        }
}
