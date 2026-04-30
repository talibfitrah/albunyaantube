package com.albunyaan.tube.app

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-level foreground state tracker.
 *
 * Used by Me-tab refresh telemetry (T12) to tag worker ticks as foreground vs
 * background. The single periodic refresh worker (T9) does not toggle scheduling
 * based on foreground state, so this class is observation-only.
 */
@Singleton
class AppLifecycleTracker @Inject constructor() : DefaultLifecycleObserver {
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground

    /** Call once from [com.albunyaan.tube.AlBunyaanApplication.onCreate]. */
    fun register() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) { _isForeground.value = true }
    override fun onStop(owner: LifecycleOwner) { _isForeground.value = false }
}
