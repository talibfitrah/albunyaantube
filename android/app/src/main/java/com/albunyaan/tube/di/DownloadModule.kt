package com.albunyaan.tube.di

import android.content.Context
import androidx.work.WorkManager
import com.albunyaan.tube.analytics.ExtractorMetricsReporter
import com.albunyaan.tube.download.DefaultDownloadRepository
import com.albunyaan.tube.download.DownloadExpiryPolicy
import com.albunyaan.tube.download.DownloadRepository
import com.albunyaan.tube.download.DownloadScheduler
import com.albunyaan.tube.download.DownloadStorage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import java.time.Clock
import javax.inject.Named
import javax.inject.Singleton

/**
 * P3-T1: Download DI Module
 *
 * Provides download-related dependencies: WorkManager, DownloadRepository, DownloadStorage
 */
@Module
@InstallIn(SingletonComponent::class)
object DownloadModule {

    @Provides
    @Singleton
    fun provideClock(): Clock {
        return Clock.systemUTC()
    }

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager {
        return WorkManager.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideDownloadScheduler(workManager: WorkManager): DownloadScheduler {
        return DownloadScheduler(workManager)
    }

    @Provides
    @Singleton
    fun provideDownloadStorage(@ApplicationContext context: Context): DownloadStorage {
        return DownloadStorage(context)
    }

    @Provides
    @Singleton
    fun provideDownloadRepository(
        workManager: WorkManager,
        scheduler: DownloadScheduler,
        storage: DownloadStorage,
        metrics: ExtractorMetricsReporter,
        expiryPolicy: DownloadExpiryPolicy,
        @Named("applicationScope") scope: CoroutineScope
    ): DownloadRepository {
        return DefaultDownloadRepository(workManager, scheduler, storage, metrics, expiryPolicy, scope)
    }
}
