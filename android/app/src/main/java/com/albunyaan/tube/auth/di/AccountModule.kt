package com.albunyaan.tube.auth.di

import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.auth.AccountRepositoryImpl
import com.albunyaan.tube.auth.AuthRepository
import com.albunyaan.tube.data.account.AccountService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AccountModule {

    /**
     * AUTH-INTERCEPT-DECOUPLE-01 — wires AccountRepositoryImpl with the
     * AuthRepository status flow so terminal events (Blocked/Deleted/
     * Unauthenticated) trigger an automatic signOut on the AccountState.
     * Pre-fix AccountStatusInterceptor injected Provider<AccountRepository>
     * and called signOut() imperatively, creating a Hilt construction cycle
     * AccountStatusInterceptor → OkHttp → AccountService → AccountRepository
     * → AccountStatusInterceptor that Provider broke only lazily. The bus-
     * based shape removes the cycle entirely.
     */
    @Provides
    @Singleton
    fun provideAccountRepository(
        service: AccountService,
        authRepository: AuthRepository,
    ): AccountRepository {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return AccountRepositoryImpl(
            service = service,
            authStatusEvents = authRepository.accountStatusEvents,
            observerScope = scope,
        )
    }
}
