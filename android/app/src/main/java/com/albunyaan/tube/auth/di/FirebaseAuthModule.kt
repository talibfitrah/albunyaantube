package com.albunyaan.tube.auth.di

import com.albunyaan.tube.auth.AccountStatusEmitter
import com.albunyaan.tube.auth.AuthRepository
import com.albunyaan.tube.auth.AuthRepositoryImpl
import com.google.firebase.auth.FirebaseAuth
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Plan B (ANDROID-AUTH-01) T2: Hilt module for the auth subsystem.
 *
 * - Provides the singleton [FirebaseAuth] used by interceptors (T3),
 *   ViewModels (T4), and the repository.
 * - Binds [AuthRepository] -> [AuthRepositoryImpl].
 *
 * No Activity-scoped bindings here — the Microsoft OAuth flow that needs an
 * Activity reference is invoked from the ViewModel layer (T4), not from any
 * SingletonComponent provider.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class FirebaseAuthModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    /**
     * Same singleton instance — [AuthRepositoryImpl] implements both interfaces.
     * Splitting the binding keeps UI-bound code on [AuthRepository] (no
     * `emit(...)`) while [AccountStatusInterceptor] gets [AccountStatusEmitter].
     */
    @Binds
    @Singleton
    abstract fun bindAccountStatusEmitter(impl: AuthRepositoryImpl): AccountStatusEmitter

    companion object {
        @Provides
        @Singleton
        fun firebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()
    }
}
