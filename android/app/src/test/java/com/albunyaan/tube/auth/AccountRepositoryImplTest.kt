package com.albunyaan.tube.auth

import com.albunyaan.tube.data.account.AccountMeResponseDto
import com.albunyaan.tube.data.account.AccountService
import com.albunyaan.tube.data.account.CompleteProfileRequestDto
import com.albunyaan.tube.data.account.LocalAccountDataWiper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import org.mockito.Mockito.doAnswer
import retrofit2.HttpException
import retrofit2.Response

import java.io.IOException
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class AccountRepositoryImplTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var service: AccountService
    private lateinit var repository: AccountRepositoryImpl

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        service = mock()
        repository = AccountRepositoryImpl(service, backoffMs = 0L)  // no real delay in tests
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `initial state is NotSignedIn`() {
        assertEquals(AccountState.NotSignedIn, repository.accountState.value)
    }

    @Test fun `fetchMe success updates accountState to Loaded`() = runTest(dispatcher) {
        whenever(service.getMe()).thenReturn(dto(status = "active"))
        val result = repository.fetchMe()

        assertTrue(result.isSuccess)
        val state = repository.accountState.first() as AccountState.Loaded
        assertEquals("uid-1", state.uid)
        assertEquals(AccountStatus.ACTIVE, state.status)
    }

    @Test fun `fetchMe retries 3 times on network error then fails`() = runTest(dispatcher) {
        // doAnswer avoids Mockito's checked-exception guard on suspend functions.
        doAnswer { throw IOException("offline") }.whenever(service).getMe()
        val result = repository.fetchMe()

        assertTrue(result.isFailure)
        verify(service, times(3)).getMe()
        val state = repository.accountState.first() as AccountState.Failed
        // Cubic R7 P1 — Failed state now carries an optional cause (IOException
        // retry exhaustion preserves the original, HttpException paths
        // discard it to avoid pinning OkHttp Response/ResponseBody).
        assertTrue(state.cause is IOException)
        assertEquals(null, state.httpCode)
    }

    @Test fun `fetchMe succeeds on second attempt after one failure`() = runTest(dispatcher) {
        // First call throws, second call returns successfully.
        doAnswer { throw IOException("flaky") }
            .doAnswer { dto(status = "pending_profile") }
            .whenever(service).getMe()

        val result = repository.fetchMe()
        assertTrue(result.isSuccess)
        val state = repository.accountState.first() as AccountState.Loaded
        assertEquals(AccountStatus.PENDING_PROFILE, state.status)
    }

    @Test fun `completeProfile success updates accountState`() = runTest(dispatcher) {
        whenever(service.completeProfile(any())).thenReturn(dto(status = "active"))

        val result = repository.completeProfile("Alice", LocalDate.of(2000, 1, 1), "+31612345678")

        assertTrue(result.isSuccess)
        val state = repository.accountState.first() as AccountState.Loaded
        assertEquals(AccountStatus.ACTIVE, state.status)
        verify(service).completeProfile(CompleteProfileRequestDto("Alice", "2000-01-01", "+31612345678"))
    }

    @Test fun `completeProfile maps 422 AGE_INELIGIBLE to AgeIneligibleError`() = runTest(dispatcher) {
        val errJson = """{"code":"AGE_INELIGIBLE","message":"too young"}"""
        val errBody = errJson.toResponseBody("application/json".toMediaTypeOrNull())
        whenever(service.completeProfile(any()))
            .thenThrow(HttpException(Response.error<Any>(422, errBody)))

        val result = repository.completeProfile("Kid", LocalDate.of(2020, 1, 1), "+31612345678")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AgeIneligibleError)
    }

    @Test fun `signOut resets accountState`() = runTest(dispatcher) {
        whenever(service.getMe()).thenReturn(dto(status = "active"))
        repository.fetchMe()

        repository.signOut()
        assertEquals(AccountState.NotSignedIn, repository.accountState.value)
    }

    // ── Terminal-event wiping ──────────────────────────────────────────────
    //
    // Deletion succeeding server-side while the device keeps everything is the
    // hole LocalAccountDataWiper exists to close, and DeleteAccountViewModel is
    // not enough on its own: it skips the wipe on any HTTP failure (correctly —
    // a live account's library must survive a failed call), and it never runs at
    // all for an admin-side deletion. Both of those reach the app the same way:
    // a 403 ACCOUNT_DELETED envelope → AccountStatusInterceptor →
    // AccountStatusEvent.Deleted. Wiping HERE closes both.

    private fun repositoryObserving(
        events: MutableSharedFlow<AccountStatusEvent>,
        wiper: LocalAccountDataWiper,
        scope: kotlinx.coroutines.CoroutineScope,
    ) = AccountRepositoryImpl(
        service,
        backoffMs = 0L,
        authStatusEvents = events,
        observerScope = scope,
        wiper = wiper,
    )

    @Test fun `Deleted event wipes this device's local data`() = runTest(dispatcher) {
        val events = MutableSharedFlow<AccountStatusEvent>()
        val wiper = mock<LocalAccountDataWiper>()
        val repo = repositoryObserving(events, wiper, backgroundScope)
        runCurrent()

        events.emit(AccountStatusEvent.Deleted)
        advanceUntilIdle()

        verifyBlocking(wiper) { wipe() }
        assertEquals(AccountState.NotSignedIn, repo.accountState.value)
    }

    @Test fun `Blocked event signs out but never wipes`() = runTest(dispatcher) {
        val events = MutableSharedFlow<AccountStatusEvent>()
        val wiper = mock<LocalAccountDataWiper>()
        val repo = repositoryObserving(events, wiper, backgroundScope)
        runCurrent()

        events.emit(AccountStatusEvent.Blocked)
        advanceUntilIdle()

        // A block is reversible — destroying the library would be a data-loss bug.
        verifyBlocking(wiper, never()) { wipe() }
        assertEquals(AccountState.NotSignedIn, repo.accountState.value)
    }

    @Test fun `SignedOut event signs out but never wipes`() = runTest(dispatcher) {
        val events = MutableSharedFlow<AccountStatusEvent>()
        val wiper = mock<LocalAccountDataWiper>()
        repositoryObserving(events, wiper, backgroundScope)
        runCurrent()

        events.emit(AccountStatusEvent.SignedOut)
        advanceUntilIdle()

        // Sign-out deliberately keeps local data — the same person signs back in.
        verifyBlocking(wiper, never()) { wipe() }
    }

    @Test fun `a failing wipe does not kill the collector`() = runTest(dispatcher) {
        val events = MutableSharedFlow<AccountStatusEvent>()
        val wiper = mock<LocalAccountDataWiper>()
        wiper.stub { onBlocking { wipe() } doAnswer { throw IOException("disk full") } }
        repositoryObserving(events, wiper, backgroundScope)
        runCurrent()

        events.emit(AccountStatusEvent.Deleted)
        advanceUntilIdle()
        events.emit(AccountStatusEvent.Deleted)
        advanceUntilIdle()

        // An escaping exception would end collect{} and leave every later
        // terminal event unhandled for the process lifetime.
        verifyBlocking(wiper, times(2)) { wipe() }
    }

    private fun dto(status: String, displayName: String = "Alice", dateOfBirth: String? = null) =
        AccountMeResponseDto(
            uid = "uid-1", email = "a@b.com", displayName = displayName,
            dateOfBirth = dateOfBirth, phoneNumber = null, status = status, role = "user", profileCompletedAt = null,
        )
}
