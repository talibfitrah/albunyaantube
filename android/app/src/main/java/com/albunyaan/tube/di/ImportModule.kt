package com.albunyaan.tube.di

import com.albunyaan.tube.data.youtube.GoogleYouTubeAuthManager
import com.albunyaan.tube.data.youtube.YouTubeAuthManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * B15: Hilt bindings for the YouTube-import feature.
 *
 * Binds the production [GoogleYouTubeAuthManager] (which wraps the Google
 * Identity Services Authorization API) to the [YouTubeAuthManager] interface
 * so repository and ViewModel code remain unit-testable via fakes.
 *
 * API providers (ImportApi, YouTubeImportApi) live in NetworkModule because
 * they are Retrofit instances and follow the same @Provides pattern as all
 * other API interfaces in that module.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ImportModule {

    @Binds
    @Singleton
    abstract fun bindYouTubeAuthManager(impl: GoogleYouTubeAuthManager): YouTubeAuthManager
}
