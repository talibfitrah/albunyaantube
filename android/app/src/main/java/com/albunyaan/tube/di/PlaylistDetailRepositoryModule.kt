package com.albunyaan.tube.di

import com.albunyaan.tube.data.extractor.NewPipeExtractorClient
import com.albunyaan.tube.data.playlist.NewPipePlaylistDetailRepository
import com.albunyaan.tube.data.playlist.PlaylistDetailRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Separate module for PlaylistDetailRepository binding.
 *
 * This module is extracted to allow replacement in instrumentation tests
 * without uninstalling the entire DataModule (which contains many other bindings).
 */
@Module
@InstallIn(SingletonComponent::class)
object PlaylistDetailRepositoryModule {

    @Provides
    @Singleton
    fun providePlaylistDetailRepository(
        extractorClient: NewPipeExtractorClient
    ): PlaylistDetailRepository {
        return NewPipePlaylistDetailRepository(extractorClient)
    }
}
