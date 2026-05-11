package com.albunyaan.tube.auth.di

import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.auth.AccountRepositoryImpl
import com.albunyaan.tube.data.account.AccountService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AccountModule {

    @Provides
    @Singleton
    fun provideAccountRepository(service: AccountService): AccountRepository =
        AccountRepositoryImpl(service)
}
