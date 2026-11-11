package com.albunyaan.tube.update

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android's "update required" floor, mirroring iOS (`FitrahTubeApp.isUpdateRequired`,
 * InnerTubeKit `RemoteConfig.requiresUpdate` / `SemVer`).
 *
 * Reads `minAppVersion` from `android-remote-config.json` on `main` (a file of its own:
 * `releases-meta.json` parses as a map of maps, so a string field there would break every
 * sideload summary). The last good value is cached so an offline launch still honours the
 * floor; a failed fetch never clears it. Fails open: with no good value ever seen, nothing
 * is blocked.
 *
 * ponytail: cold start only. iOS also re-checks on foreground; add a resume check if a floor
 * ever has to bite a process that stays alive for days.
 */
@Singleton
class MinVersionGate @Inject constructor(
    private val okHttpClient: OkHttpClient,
    @ApplicationContext context: Context,
) {
    @VisibleForTesting
    internal var urlForTest: String? = null   // production callers MUST NOT set this

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Refreshes the cached floor (best effort), then decides from the cache. Never throws. */
    suspend fun fetchAndDecide(installed: String): Boolean {
        try {
            fetchMinimum()?.let { prefs.edit().putString(KEY_MIN_VERSION, it).apply() }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "min-version fetch failed: ${e.message}")
        }
        return decideFromCache(installed)
    }

    fun decideFromCache(installed: String): Boolean =
        isBelowMinimum(installed, prefs.getString(KEY_MIN_VERSION, null))

    /** The floor from a good 2xx body, or null for anything else (cache stays untouched). */
    private suspend fun fetchMinimum(): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(urlForTest ?: CONFIG_URL).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val source = response.body?.source() ?: return@use null
            // request(cap+1) is true iff the body is longer than the cap.
            if (source.request(MAX_BODY_BYTES + 1L)) return@use null
            parseMinAppVersion(source.readUtf8())
        }
    }

    companion object {
        private const val TAG = "MinVersionGate"
        private const val PREFS_NAME = "min_version_gate"
        private const val KEY_MIN_VERSION = "min_app_version"
        private const val MAX_BODY_BYTES = 4 * 1024
        private const val CONFIG_URL =
            "https://raw.githubusercontent.com/talibfitrah/albunyaantube/main/android-remote-config.json"
    }
}

/**
 * True if [installed] is older than [minimum], comparing numeric segments like iOS `SemVer`
 * ("1.0.10" > "1.0.9"; a missing segment is 0; each segment is its leading digits, or 0).
 * Null or blank on either side → false (fail open).
 */
internal fun isBelowMinimum(installed: String?, minimum: String?): Boolean {
    if (installed.isNullOrBlank() || minimum.isNullOrBlank()) return false
    val have = versionSegments(installed)
    val need = versionSegments(minimum)
    for (i in 0 until maxOf(have.size, need.size)) {
        val h = have.getOrElse(i) { 0L }
        val n = need.getOrElse(i) { 0L }
        if (h != n) return h < n
    }
    return false
}

private fun versionSegments(version: String): List<Long> =
    version.trim().split('.').map { it.takeWhile(Char::isDigit).toLongOrNull() ?: 0L }

private val configAdapter = Moshi.Builder().build().adapter<Map<String, Any?>>(
    Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
)

/** `minAppVersion` from the config body; null on any parse error, non-string or blank value. */
internal fun parseMinAppVersion(body: String): String? = try {
    (configAdapter.fromJson(body)?.get("minAppVersion") as? String)?.takeIf { it.isNotBlank() }
} catch (e: Exception) {
    null
}
