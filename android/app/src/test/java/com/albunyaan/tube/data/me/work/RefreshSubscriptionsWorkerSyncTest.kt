package com.albunyaan.tube.data.me.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.auth.AccountState
import com.albunyaan.tube.auth.AccountStatus
import com.albunyaan.tube.data.account.AccountMeResponseDto
import com.albunyaan.tube.data.local.ChannelVideoCacheDao
import com.albunyaan.tube.data.me.MeFeedRepository
import com.albunyaan.tube.data.me.MeRefreshTelemetry
import com.albunyaan.tube.data.sync.SyncManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.LocalDate

/**
 * Field bug: an item approved in the admin dashboard kept showing as "pending" on the Me screen.
 *
 * The device only ever learned about an approval from [SyncManager.pullAll], and the sole caller
 * was the process-level foreground callback. Pull-to-refresh and the Me screen's foreground burst
 * both land here, in the refresh worker, which refetched YouTube feeds and nothing else — so while
 * the app stayed open the user's own refresh gesture could not fetch the decision.
 *
 * Pins that the worker pulls account sync, that it skips the pull when nobody is signed in, and
 * that a failing pull never costs the user their feed refresh.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class RefreshSubscriptionsWorkerSyncTest {

    private lateinit var ctx: Context
    private lateinit var repository: MeFeedRepository
    private lateinit var telemetry: MeRefreshTelemetry
    private lateinit var cacheDao: ChannelVideoCacheDao
    private lateinit var syncManager: SyncManager

    @Before
    fun setUp() {
        ShadowLog.stream = System.out
        ctx = ApplicationProvider.getApplicationContext()
        repository = mock()
        telemetry = mock()
        cacheDao = mock()
        syncManager = mock()
    }

    private suspend fun runWorker(uid: String): ListenableWorker.Result {
        val accountRepository = FakeAccountRepository(uid)
        val worker = TestListenableWorkerBuilder<RefreshSubscriptionsWorker>(ctx)
            .setInputData(workDataOf(RefreshSubscriptionsWorker.KEY_FORCE to true))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = RefreshSubscriptionsWorker(
                    appContext,
                    workerParameters,
                    repository,
                    telemetry,
                    cacheDao,
                    syncManager,
                    accountRepository,
                )
            })
            .build()
        return worker.doWork()
    }

    @Test
    fun `pull to refresh pulls account sync for the signed-in user`() = runTest {
        runWorker(uid = "uid-123")

        verify(syncManager).pullAll("uid-123")
    }

    @Test
    fun `pull to refresh also pushes anything waiting to go up`() = runTest {
        // pullAll skips any row with dirty=1 and defers it to the push, so pulling without
        // pushing leaves an offline change unsynced AND its server counterpart permanently
        // skipped, until a foreground transition happens to run both.
        runWorker(uid = "uid-123")

        verify(syncManager).pushDirty("uid-123")
    }

    @Test
    fun `signed-out device does not attempt an account sync pull`() = runTest {
        runWorker(uid = "")

        verify(syncManager, never()).pullAll(any())
        verify(syncManager, never()).pushDirty(any())
    }

    @Test
    fun `a failing sync pull still refreshes the feed`() = runTest {
        syncManager.stub {
            onBlocking { pullAll(any()) } doThrow RuntimeException("network down")
        }

        val result = runWorker(uid = "uid-123")

        verify(repository).refresh(force = true, perTickBudget = RefreshSubscriptionsWorker.PULL_BUDGET)
        assertEquals(ListenableWorker.Result.success(), result)
    }

    private class FakeAccountRepository(private val uid: String) : AccountRepository {
        override val accountState: StateFlow<AccountState> = MutableStateFlow(
            if (uid.isEmpty()) {
                AccountState.NotSignedIn
            } else {
                AccountState.Loaded(
                    uid = uid,
                    email = null,
                    displayName = "Test User",
                    dateOfBirth = null,
                    phoneNumber = null,
                    status = AccountStatus.ACTIVE,
                    role = "USER",
                )
            }
        )

        override suspend fun fetchMe(): Result<AccountState.Loaded> =
            Result.failure(RuntimeException("stub"))

        override suspend fun completeProfile(
            displayName: String,
            dateOfBirth: LocalDate,
            phoneNumber: String,
        ): Result<AccountState.Loaded> = Result.failure(RuntimeException("stub"))

        override fun signOut() {}

        override fun applyProfileUpdate(response: AccountMeResponseDto) {}
    }
}
