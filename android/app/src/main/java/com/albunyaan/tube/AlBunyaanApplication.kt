package com.albunyaan.tube

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.albunyaan.tube.download.DownloadScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * P3-T1: Hilt Application
 *
 * Main application class annotated with @HiltAndroidApp to enable Hilt DI.
 * Implements Configuration.Provider for WorkManager with HiltWorkerFactory.
 */
@HiltAndroidApp
class AlBunyaanApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var downloadScheduler: DownloadScheduler

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application initialized with Hilt DI")

        // Schedule periodic download expiry cleanup (P4-T3)
        downloadScheduler.scheduleExpiryCleanup()
        Log.d(TAG, "Download expiry cleanup scheduled")
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        // Release ExoPlayer cache when system is under moderate or higher memory pressure
        // This ensures cache cleanup actually runs in production (unlike onTerminate)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            com.albunyaan.tube.player.MultiQualityMediaSourceFactory.releaseCache()
            Log.d(TAG, "Cache released due to memory pressure (level: $level)")
        }
    }

    companion object {
        private const val TAG = "AlBunyaanApp"
    }
}
