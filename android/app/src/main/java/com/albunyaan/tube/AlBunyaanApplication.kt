package com.albunyaan.tube

import android.app.Application
import android.util.Log

class AlBunyaanApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Initialize ServiceLocator early for dependency injection
        // This is lightweight since ServiceLocator uses lazy initialization
        ServiceLocator.init(this)
        
        Log.d(TAG, "Application initialized")
    }
    
    companion object {
        private const val TAG = "AlBunyaanApp"
    }
}
