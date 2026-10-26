package com.albunyaan.tube.di

import com.albunyaan.tube.auth.AccountStatusEvent
import com.albunyaan.tube.auth.AuthRepository
import com.albunyaan.tube.data.sync.SyncManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.mockito.Mockito.timeout
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyBlocking

/** Cubic r4 (SyncManager.kt:93): every sign-out unbinds, so a queued retry for the old account is cancelled. */
class SyncModuleTest {

    @Test fun `a blocked or deleted sign-out unbinds sync like a user sign-out`() {
        val events = MutableSharedFlow<AccountStatusEvent>(extraBufferCapacity = 8)
        val auth = mock<AuthRepository> { on { accountStatusEvents } doReturn events }
        val sync = mock<SyncManager>()
        SyncModule.provideSignOutCollector(auth, sync).use {
            runBlocking { withTimeout(5_000) { events.subscriptionCount.first { it > 0 } } }   // the collector is up

            events.tryEmit(AccountStatusEvent.Blocked)                       // AccountStatusInterceptor's sign-outs
            events.tryEmit(AccountStatusEvent.Deleted)

            verifyBlocking(sync, timeout(5_000).times(2)) { unbind() }
        }
    }
}
