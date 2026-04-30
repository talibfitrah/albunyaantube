package com.albunyaan.tube.di

import com.albunyaan.tube.data.me.AtomChannelFeedFetcher
import com.albunyaan.tube.data.me.ChannelFeedFetcher
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Hilt bindings for the Me tab feature.
 *
 * Binds the ATOM-backed feed fetcher (default per spec §4.1) and provides
 * the named IO dispatcher used by MeFeedRepository for off-main-thread work.
 *
 * The legacy NewPipeChannelFeedFetcher remains in the codebase as the
 * rollback path per spec §10 — it is no longer bound by default but still
 * compiles against the v3 [ChannelFeedFetcher] interface.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MeModule {

    @Binds
    @Singleton
    abstract fun bindChannelFeedFetcher(impl: AtomChannelFeedFetcher): ChannelFeedFetcher

    companion object {
        @Provides
        @Singleton
        @Named("io")
        fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
    }
}
