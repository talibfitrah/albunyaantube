package com.albunyaan.tube.update

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * ANDROID-FLAVOR-01 — sideload binding: the real updater.
 *
 * The `play` source set has a file of the same name binding `NoUpdateGateway` instead.
 * Exactly one of the two is ever on the compile path.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateGatewayModule {

    @Binds
    @Singleton
    abstract fun bindUpdateGateway(impl: UpdatePromptFlow): UpdateGateway
}
