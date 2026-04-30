package com.albunyaan.tube.player

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MpdTtlWatcher(
    private val videoId: String,
    private val registry: SyntheticDashMpdRegistry,
    private val onRefreshNeeded: () -> Unit,
    var clock: () -> Long = { SystemClock.elapsedRealtime() }
) {
    companion object {
        private const val TAG = "MpdTtlWatcher"
        private const val TTL_REFRESH_FRACTION = 0.90
    }

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            val entry = registry.getEntry(videoId) ?: run {
                Log.d(TAG, "No entry for $videoId — TTL watcher inactive")
                return@launch
            }
            val refreshAtMs = entry.registeredAtMs + (SyntheticDashMpdRegistry.MPD_TTL_MS * TTL_REFRESH_FRACTION).toLong()
            val delayMs = (refreshAtMs - clock()).coerceAtLeast(0L)
            Log.d(TAG, "TTL watcher for $videoId: refresh in ${delayMs}ms (refreshAt=$refreshAtMs, registeredAt=${entry.registeredAtMs})")
            if (delayMs > 0L) delay(delayMs)
            Log.d(TAG, "TTL 90% reached for $videoId — triggering refresh")
            onRefreshNeeded()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
