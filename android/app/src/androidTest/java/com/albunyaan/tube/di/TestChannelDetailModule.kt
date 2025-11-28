package com.albunyaan.tube.di

import com.albunyaan.tube.data.channel.ChannelDetailRepository
import com.albunyaan.tube.data.channel.FakeChannelDetailRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Test module that provides a fake ChannelDetailRepository for UI tests.
 *
 * This module uses @TestInstallIn to replace the production ChannelDetailRepository
 * binding from ChannelDetailRepositoryModule in all tests using @HiltAndroidTest.
 *
 * Usage:
 * - The singleton FakeChannelDetailRepository instance can be accessed via `TestChannelDetailModule.fakeRepository`
 * - Configure the fake before launching fragments:
 *   ```
 *   TestChannelDetailModule.fakeRepository.headerToReturn = ...
 *   ```
 *
 * Note: Tests should call `fakeRepository.reset()` in @Before or @After to avoid test pollution.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [ChannelDetailRepositoryModule::class]
)
object TestChannelDetailModule {

    /**
     * Shared fake repository instance accessible from tests.
     */
    val fakeRepository = FakeChannelDetailRepository()

    @Provides
    @Singleton
    fun provideChannelDetailRepository(): ChannelDetailRepository {
        return fakeRepository
    }
}
