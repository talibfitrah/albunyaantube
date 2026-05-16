package com.albunyaan.tube.di

import com.albunyaan.tube.auth.AccountStatusEvent
import com.albunyaan.tube.auth.AuthRepository
import com.albunyaan.tube.data.sync.SyncManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Singleton

/**
 * Plan D — Hilt providers for sync. SyncManager has @Inject constructor so
 * no @Provides needed for the manager itself.
 *
 * Cubic R-final5 P1 — wires a SignedOut-event collector that releases
 * per-account sync state on user-initiated sign-out. Mirrors the bus shape
 * used by AccountModule for Blocked/Deleted, avoiding a Hilt construction
 * cycle (AuthRepository → SyncManager → AuthRepository) that direct
 * injection would create.
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    /**
     * Side-effect bean: collects AuthRepository.accountStatusEvents and
     * invokes SyncManager.unbind() when a SignedOut event arrives. Singleton
     * scope so the collector starts exactly once at app graph creation; the
     * scope lives for the app's lifetime alongside the SingletonComponent.
     *
     * <p>Cubic R-final7 P2 — return a closeable holder so tests can cancel
     * the scope without leaking observers across Robolectric runs.
     */
    @Provides
    @Singleton
    fun provideSignOutCollector(
        authRepository: AuthRepository,
        syncManager: SyncManager,
    ): SignOutSyncCollector {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        scope.launch {
            authRepository.accountStatusEvents
                .filter { it is AccountStatusEvent.SignedOut }
                .collect { syncManager.unbind() }
        }
        return SignOutSyncCollector(scope, job)
    }
}

/**
 * Holder for the sign-out collector's scope. Exposes [close] so tests can
 * cancel the observer cleanly between runs. Production keeps the scope
 * alive for the singleton's lifetime.
 */
class SignOutSyncCollector(
    private val scope: CoroutineScope,
    private val job: Job,
) : AutoCloseable {
    override fun close() {
        job.cancel()
        scope.cancel()
    }
}
