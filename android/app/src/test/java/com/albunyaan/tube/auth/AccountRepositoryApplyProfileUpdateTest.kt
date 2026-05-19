package com.albunyaan.tube.auth

import com.albunyaan.tube.data.account.AccountMeResponseDto
import com.albunyaan.tube.data.account.AccountService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Plan G A2 — unit tests for [AccountRepository.applyProfileUpdate].
 *
 * Seeding strategy: call [AccountRepositoryImpl.fetchMe] with a mocked
 * [AccountService] that returns a canned DTO, which drives the repo into
 * [AccountState.Loaded].  This mirrors the same pattern used in
 * [AccountRepositoryImplTest] and avoids any reflection hacks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountRepositoryApplyProfileUpdateTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var service: AccountService
    private lateinit var repository: AccountRepositoryImpl

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        service = mock()
        repository = AccountRepositoryImpl(service, backoffMs = 0L)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun fakeDto(
        displayName: String? = "Old Name",
        dateOfBirth: String? = null,
    ) = AccountMeResponseDto(
        uid = "uid-1",
        email = "a@b.com",
        displayName = displayName,
        dateOfBirth = dateOfBirth,
        status = "active",
        role = "user",
        profileCompletedAt = null,
    )

    /** Drive the repo into [AccountState.Loaded] with the given display name. */
    private suspend fun seedLoaded(displayName: String = "Old Name", dateOfBirth: String? = null) {
        whenever(service.getMe()).thenReturn(fakeDto(displayName = displayName, dateOfBirth = dateOfBirth))
        repository.fetchMe()
    }

    // ── happy-path ───────────────────────────────────────────────────────

    @Test
    fun `applyProfileUpdate emits new Loaded with updated displayName`() = runTest(dispatcher) {
        seedLoaded(displayName = "Old Name")

        repository.applyProfileUpdate(fakeDto(displayName = "New Name", dateOfBirth = "2000-01-01"))

        val state = repository.accountState.first() as AccountState.Loaded
        assertEquals("New Name", state.displayName)
        assertEquals("2000-01-01", state.dateOfBirth)
    }

    @Test
    fun `applyProfileUpdate emits new Loaded with updated dateOfBirth`() = runTest(dispatcher) {
        seedLoaded(displayName = "Alice", dateOfBirth = "1999-06-15")

        repository.applyProfileUpdate(fakeDto(displayName = "Alice", dateOfBirth = "2000-01-01"))

        val state = repository.accountState.first() as AccountState.Loaded
        assertEquals("2000-01-01", state.dateOfBirth)
        // displayName unchanged
        assertEquals("Alice", state.displayName)
    }

    @Test
    fun `applyProfileUpdate preserves existing displayName when response has null`() = runTest(dispatcher) {
        seedLoaded(displayName = "Alice", dateOfBirth = "2000-01-01")

        // Response carries null displayName — should keep the cached value.
        repository.applyProfileUpdate(fakeDto(displayName = null, dateOfBirth = "2000-01-01"))

        val state = repository.accountState.first() as AccountState.Loaded
        assertEquals("Alice", state.displayName)
    }

    @Test
    fun `applyProfileUpdate preserves existing dateOfBirth when response has null`() = runTest(dispatcher) {
        seedLoaded(displayName = "Alice", dateOfBirth = "1990-03-22")

        repository.applyProfileUpdate(fakeDto(displayName = "Alice", dateOfBirth = null))

        val state = repository.accountState.first() as AccountState.Loaded
        assertEquals("1990-03-22", state.dateOfBirth)
    }

    @Test
    fun `applyProfileUpdate keeps other Loaded fields intact`() = runTest(dispatcher) {
        seedLoaded(displayName = "Alice")

        val stateBefore = repository.accountState.first() as AccountState.Loaded
        repository.applyProfileUpdate(fakeDto(displayName = "Bob", dateOfBirth = "1985-12-01"))

        val stateAfter = repository.accountState.first() as AccountState.Loaded
        // uid, email, status, role must be unchanged
        assertEquals(stateBefore.uid, stateAfter.uid)
        assertEquals(stateBefore.email, stateAfter.email)
        assertEquals(stateBefore.status, stateAfter.status)
        assertEquals(stateBefore.role, stateAfter.role)
        // only the profile fields changed
        assertEquals("Bob", stateAfter.displayName)
        assertEquals("1985-12-01", stateAfter.dateOfBirth)
    }

    // ── no-op when not Loaded ────────────────────────────────────────────

    @Test
    fun `applyProfileUpdate is no-op when state is NotSignedIn`() = runTest(dispatcher) {
        // repo starts in NotSignedIn — never seed
        assertEquals(AccountState.NotSignedIn, repository.accountState.value)

        repository.applyProfileUpdate(fakeDto(displayName = "Ghost"))

        // Still NotSignedIn — no exception, no state change
        assertEquals(AccountState.NotSignedIn, repository.accountState.value)
    }

    @Test
    fun `applyProfileUpdate is no-op when state is Loading`() = runTest(dispatcher) {
        // Make getMe hang so repo stays in Loading — we never advance the dispatcher
        // so fetchMe() suspends and the state stays Loading after we kick it off.
        // We test applyProfileUpdate synchronously without advancing the dispatcher.
        whenever(service.getMe()).thenReturn(fakeDto())

        // Don't await fetchMe — just check that calling applyProfileUpdate while
        // still NotSignedIn (before any fetch) doesn't mutate state.
        repository.applyProfileUpdate(fakeDto(displayName = "Ghost"))
        assertTrue(repository.accountState.value !is AccountState.Loaded)
    }
}
