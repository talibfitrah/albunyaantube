package com.albunyaan.tube.di

import com.albunyaan.tube.data.channel.ChannelDetailRepository
import com.albunyaan.tube.data.channel.NewPipeChannelDetailRepository
import com.albunyaan.tube.data.extractor.NewPipeExtractorClient
import com.albunyaan.tube.data.index.IndexRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Separate module for ChannelDetailRepository binding.
 *
 * This module is extracted from DataModule to allow replacement in instrumentation tests
 * without uninstalling the entire DataModule (which contains many other bindings).
 */
@Module
@InstallIn(SingletonComponent::class)
object ChannelDetailRepositoryModule {

    @Provides
    @Singleton
    fun provideChannelDetailRepository(
        extractorClient: NewPipeExtractorClient,
        indexRepository: IndexRepository
    ): ChannelDetailRepository {
        return NewPipeChannelDetailRepository(extractorClient, indexRepository)
    }
}
