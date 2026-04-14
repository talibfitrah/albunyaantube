package com.albunyaan.tube.di

import com.albunyaan.tube.data.me.ChannelFeedFetcher
import com.albunyaan.tube.data.me.NewPipeChannelFeedFetcher
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
 * Binds the NewPipe-backed feed fetcher and provides the named IO dispatcher
 * used by MeFeedRepository for off-main-thread work.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MeModule {

    @Binds
    @Singleton
    abstract fun bindChannelFeedFetcher(impl: NewPipeChannelFeedFetcher): ChannelFeedFetcher

    companion object {
        @Provides
        @Singleton
        @Named("io")
        fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
    }
}
