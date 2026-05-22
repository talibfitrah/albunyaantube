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
