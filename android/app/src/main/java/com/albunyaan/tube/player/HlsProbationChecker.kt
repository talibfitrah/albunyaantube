package com.albunyaan.tube.player

import com.albunyaan.tube.util.HttpConstants
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fires a HEAD request to the HLS manifest URL before committing to HLS playback.
 * A non-2xx/3xx response or timeout means the manifest is unreachable — caller should
 * poison HLS and fall through to the next source type.
 *
 * The network call runs on a dedicated background thread so this is safe to call
 * from any thread including the main thread.
 */
@Singleton
class HlsProbationChecker @Inject constructor() {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hls-probation").apply { isDaemon = true }
    }

    fun probe(manifestUrl: String, timeoutMs: Int = 500): Boolean {
        val future = executor.submit(Callable { doProbe(manifestUrl, timeoutMs) })
        return try {
            future.get((timeoutMs + 200).toLong(), TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            future.cancel(true)
            false
        }
    }

    private fun doProbe(manifestUrl: String, timeoutMs: Int): Boolean {
        return try {
            val connection = URL(manifestUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", HttpConstants.YOUTUBE_IOS_USER_AGENT)
            try {
                connection.responseCode in 200..399
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            false
        }
    }
}
