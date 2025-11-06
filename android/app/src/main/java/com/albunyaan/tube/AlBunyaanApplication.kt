package com.albunyaan.tube

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log

class AlBunyaanApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize ServiceLocator early for dependency injection
        // This is lightweight since ServiceLocator uses lazy initialization
        ServiceLocator.init(this)

        Log.d(TAG, "Application initialized")
    }

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
