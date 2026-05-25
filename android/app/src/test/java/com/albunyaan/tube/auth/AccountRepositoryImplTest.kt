package com.albunyaan.tube.auth

import com.albunyaan.tube.data.account.AccountMeResponseDto
import com.albunyaan.tube.data.account.AccountService
import com.albunyaan.tube.data.account.CompleteProfileRequestDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
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

    private fun dto(status: String, displayName: String = "Alice", dateOfBirth: String? = null) =
        AccountMeResponseDto(
            uid = "uid-1", email = "a@b.com", displayName = displayName,
            dateOfBirth = dateOfBirth, phoneNumber = null, status = status, role = "user", profileCompletedAt = null,
        )
}
