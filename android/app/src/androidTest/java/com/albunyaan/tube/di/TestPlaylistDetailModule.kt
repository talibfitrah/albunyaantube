package com.albunyaan.tube.di

import com.albunyaan.tube.data.playlist.FakePlaylistDetailRepository
import com.albunyaan.tube.data.playlist.PlaylistDetailRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Test module that provides a fake PlaylistDetailRepository for UI tests.
 *
 * This module uses @TestInstallIn to replace the production PlaylistDetailRepository
 * binding from PlaylistDetailRepositoryModule in all tests using @HiltAndroidTest.
 *
 * Usage:
 * - The singleton FakePlaylistDetailRepository instance can be accessed via `TestPlaylistDetailModule.fakeRepository`
 * - Configure the fake before launching fragments:
 *   ```
 *   TestPlaylistDetailModule.fakeRepository.headerToReturn = ...
 *   ```
 *
 * Note: Tests should call `fakeRepository.reset()` in @Before or @After to avoid test pollution.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [PlaylistDetailRepositoryModule::class]
)
object TestPlaylistDetailModule {

    /**
     * Shared fake repository instance accessible from tests.
     */
    val fakeRepository = FakePlaylistDetailRepository()

    @Provides
    @Singleton
    fun providePlaylistDetailRepository(): PlaylistDetailRepository {
        return fakeRepository
    }
}
