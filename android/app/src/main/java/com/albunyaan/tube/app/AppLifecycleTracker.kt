package com.albunyaan.tube.app

import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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
class AppLifecycleTracker @VisibleForTesting internal constructor(
    private val now: () -> Long,
) : DefaultLifecycleObserver {
    @Inject constructor() : this(SystemClock::elapsedRealtime)

    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground

    private val _returnsAfterIdle = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Owner bug 2026-11-03: the app came back after [IDLE_MS] or more in the background. Home and the content lists
     * reload on it, so what the dashboard changed meanwhile (an exclusion) shows without a pull-to-refresh.
     */
    val returnsAfterIdle: SharedFlow<Unit> = _returnsAfterIdle
    private var stoppedAt: Long? = null

    /** Call once from [com.albunyaan.tube.AlBunyaanApplication.onCreate]. */
    fun register() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        _isForeground.value = true
        val at = stoppedAt ?: return
        if (now() - at >= IDLE_MS) _returnsAfterIdle.tryEmit(Unit)
    }

    override fun onStop(owner: LifecycleOwner) {
        _isForeground.value = false
        stoppedAt = now()
    }

    private companion object {
        const val IDLE_MS = 5 * 60_000L
    }
}
