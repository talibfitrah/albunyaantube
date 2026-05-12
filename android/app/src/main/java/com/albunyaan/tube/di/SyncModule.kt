package com.albunyaan.tube.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Plan D — Hilt providers for sync. SyncManager has @Inject constructor so
 * no @Provides needed; this module exists for future fakes and for grouping.
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncModule
