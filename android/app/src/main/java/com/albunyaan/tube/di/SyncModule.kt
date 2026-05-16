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
import kotlinx.coroutines.SupervisorJob
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
     * invokes SyncManager.unbind() when a SignedOut event arrives. Marker
     * type (Unit) — the bean exists for its constructor's side effect of
     * starting the collector. Singleton scope so the collector starts
     * exactly once at app graph creation.
     */
    @Provides
    @Singleton
    fun provideSignOutCollector(
        authRepository: AuthRepository,
        syncManager: SyncManager,
    ): SignOutSyncCollector {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            authRepository.accountStatusEvents
                .filter { it is AccountStatusEvent.SignedOut }
                .collect { syncManager.unbind() }
        }
        return SignOutSyncCollector(scope)
    }
}

/**
 * Marker holder so Hilt instantiates the collector. Holding the scope lets
 * future tests / lifecycle wiring cancel the observer if needed.
 */
class SignOutSyncCollector(@Suppress("unused") private val scope: CoroutineScope)
