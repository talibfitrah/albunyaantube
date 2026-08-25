package com.albunyaan.tube.ui.me.profile

import com.albunyaan.tube.auth.AccountStatusEmitter
import com.albunyaan.tube.auth.AccountStatusEvent
import com.albunyaan.tube.auth.AuthRepository
import com.albunyaan.tube.data.account.AccountService
import com.albunyaan.tube.data.account.LocalAccountDataWiper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import retrofit2.Response
import java.io.IOException

/**
 * ANDROID-ACCT-DEL-01 — the destructive half of the Play-mandated in-app
 * deletion flow.
 *
 * The load-bearing property is the negative one: a local wipe on a call that
 * did NOT succeed would destroy a live user's library, downloads and
 * subscriptions for an account the server still holds. Every non-2xx path
 * therefore asserts `never()` on the wiper.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteAccountViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var service: AccountService
    private lateinit var wiper: LocalAccountDataWiper
    private lateinit var authRepository: AuthRepository
    private lateinit var emitter: RecordingEmitter

    /** Real recorder rather than a mock: the emitted event IS the observable output. */
    private class RecordingEmitter : AccountStatusEmitter {
        val events = mutableListOf<AccountStatusEvent>()
        override fun emit(event: AccountStatusEvent) {
            events += event
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        service = mock()
        wiper = mock()
        authRepository = mock()
        emitter = RecordingEmitter()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun newVm() = DeleteAccountViewModel(service, wiper, authRepository, emitter)

    private fun errorBody() = "{}".toResponseBody("application/json".toMediaType())

    @Test
    fun `204 wipes local data and emits Deleted`() = runTest(dispatcher) {
        service.stub { onBlocking { deleteAccount() } doReturn Response.success(204, Unit) }

        val vm = newVm()
        vm.delete()
        advanceUntilIdle()

        verify(wiper).wipe()
        assertEquals(listOf(AccountStatusEvent.Deleted), emitter.events)
    }

    @Test
    fun `409 last admin surfaces an error and never wipes`() = runTest(dispatcher) {
        service.stub { onBlocking { deleteAccount() } doReturn Response.error(409, errorBody()) }

        val vm = newVm()
        vm.delete()
        advanceUntilIdle()

        assertEquals(DeleteAccountState.FailedLastAdmin, vm.state.value)
        verify(wiper, never()).wipe()
        verifyNoInteractions(authRepository)
        assertTrue(emitter.events.isEmpty())
    }

    @Test
    fun `network failure surfaces an error and never wipes`() = runTest(dispatcher) {
        // doThrow() rejects a checked exception on a suspend function — Mockito
        // reads the JVM signature, which declares none. doAnswer sidesteps it.
        service.stub { onBlocking { deleteAccount() } doAnswer { throw IOException("offline") } }

        val vm = newVm()
        vm.delete()
        advanceUntilIdle()

        assertEquals(DeleteAccountState.FailedNetwork, vm.state.value)
        verify(wiper, never()).wipe()
        verifyNoInteractions(authRepository)
        assertTrue(emitter.events.isEmpty())
    }

    @Test
    fun `unexpected server error surfaces an error and never wipes`() = runTest(dispatcher) {
        service.stub { onBlocking { deleteAccount() } doReturn Response.error(500, errorBody()) }

        val vm = newVm()
        vm.delete()
        advanceUntilIdle()

        assertEquals(DeleteAccountState.FailedUnknown, vm.state.value)
        verify(wiper, never()).wipe()
        assertTrue(emitter.events.isEmpty())
    }

    @Test
    fun `second tap while deleting does not fire a second request`() = runTest(dispatcher) {
        service.stub { onBlocking { deleteAccount() } doReturn Response.success(204, Unit) }

        val vm = newVm()
        vm.delete()
        vm.delete()
        advanceUntilIdle()

        verify(service, org.mockito.kotlin.times(1)).deleteAccount()
    }
}
