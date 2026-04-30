package com.albunyaan.tube.player

import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fires a HEAD request to the HLS manifest URL before committing to HLS playback.
 * A non-2xx response or timeout means the manifest is unreachable — caller should
 * poison HLS and fall through to the next source type.
 */
@Singleton
class HlsProbationChecker @Inject constructor() {

    fun probe(manifestUrl: String, timeoutMs: Int = 500): Boolean {
        return try {
            val connection = URL(manifestUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            connection.connect()
            val code = connection.responseCode
            connection.disconnect()
            code in 200..299
        } catch (e: Exception) {
            false
        }
    }
}
