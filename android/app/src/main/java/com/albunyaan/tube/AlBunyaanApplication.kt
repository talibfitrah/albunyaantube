package com.albunyaan.tube

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.albunyaan.tube.app.AppLifecycleTracker
import com.albunyaan.tube.data.extractor.NewPipeExtractorClient
import com.albunyaan.tube.data.me.work.RefreshScheduler
import com.albunyaan.tube.download.DownloadScheduler
import com.google.firebase.FirebaseApp
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

    @Inject
    lateinit var extractorClient: NewPipeExtractorClient

    @Inject
    lateinit var lifecycleTracker: AppLifecycleTracker

    @Inject
    lateinit var refreshScheduler: RefreshScheduler

    /**
     * Plan B (ANDROID-AUTH-01) T3: initialize FirebaseApp BEFORE Hilt's
     * eager-injection chain runs in onCreate(). On real devices, the
     * google-services plugin's manifest <provider> auto-initializes Firebase
     * before any Application code; in Robolectric / JVM unit tests the
     * provider does not run, so OkHttpClient → FirebaseAuthInterceptor →
     * FirebaseAuth.getInstance() throws "Default FirebaseApp not initialized".
     *
     * attachBaseContext runs before onCreate (and before Hilt_*.onCreate()
     * calls hiltInternalInject), so this is the earliest hook we have.
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (FirebaseApp.getApps(this).isEmpty()) {
            FirebaseApp.initializeApp(this)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application initialized with Hilt DI")

        // Register process-level foreground tracker (ANDROID-PERSONAL-02 T8)
        lifecycleTracker.register()

        // ANDROID-PERSONAL-02 / T9: arm hourly Me-feed refresh worker.
        // KEEP semantics — safe to call on every cold start.
        refreshScheduler.enqueuePeriodic()
        Log.d(TAG, "Me-feed periodic refresh scheduled")

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
        // 60 == ComponentCallbacks2.TRIM_MEMORY_MODERATE (constant deprecated in API 34)
        if (level >= 60) {
            com.albunyaan.tube.player.MultiQualityMediaSourceFactory.releaseCache()
            Log.d(TAG, "Cache released due to memory pressure (level: $level)")
        }

        // Clear stream URL cache only when process kill is imminent (level 80+).
        // NOT at level 60: clearing during moderate background pressure breaks URL refresh
        // fallback if user returns to the app mid-playback.
        // 80 == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        if (level >= 80) {
            val cleared = extractorClient.clearStreamCache()
            Log.d(TAG, "Stream cache cleared ($cleared entries) due to critical memory pressure (level: $level)")
        }
    }

    companion object {
        private const val TAG = "AlBunyaanApp"
    }
}
