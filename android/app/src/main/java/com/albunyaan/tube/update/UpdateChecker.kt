package com.albunyaan.tube.update

import android.util.Log
import com.albunyaan.tube.BuildConfig
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Represents a newer release of the app published on GitHub. Constructed by [UpdateChecker]
 * only when the remote version is strictly greater than the locally-built version.
 */
data class UpdateInfo(
    val versionName: String,
    val releaseName: String,
    val releaseNotes: String,
    val apkUrl: String,
    val apkSizeBytes: Long
)

@JsonClass(generateAdapter = true)
internal data class GithubReleaseDto(
    val tag_name: String,
    val name: String?,
    val body: String?,
    val prerelease: Boolean,
    val assets: List<GithubAssetDto>
)

@JsonClass(generateAdapter = true)
internal data class GithubAssetDto(
    val name: String,
    val browser_download_url: String,
    val size: Long,
    val content_type: String
)

/**
 * Polls the GitHub releases REST endpoint for the repo's latest release, compares the
 * tag against [BuildConfig.VERSION_NAME], and returns an [UpdateInfo] only if a strictly
 * newer version is published AND it ships an `.apk` asset. No side effects: callers
 * decide whether to prompt the user.
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(GithubReleaseDto::class.java)

    suspend fun checkForUpdate(): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(RELEASES_URL)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "GitHub releases API returned HTTP ${response.code}")
                    return@use null
                }
                val body = response.body?.string() ?: return@use null
                val release = adapter.fromJson(body) ?: return@use null
                val remoteVersion = release.tag_name.removePrefix("v").removePrefix("V")
                val currentVersion = BuildConfig.VERSION_NAME
                if (!isNewerVersion(remoteVersion, currentVersion)) {
                    Log.d(TAG, "No update (remote=$remoteVersion, current=$currentVersion)")
                    return@use null
                }
                val apkAsset = release.assets.firstOrNull {
                    it.name.endsWith(".apk", ignoreCase = true)
                }
                if (apkAsset == null) {
                    Log.w(TAG, "Update $remoteVersion has no APK asset")
                    return@use null
                }
                UpdateInfo(
                    versionName = remoteVersion,
                    releaseName = release.name ?: release.tag_name,
                    releaseNotes = release.body.orEmpty(),
                    apkUrl = apkAsset.browser_download_url,
                    apkSizeBytes = apkAsset.size
                )
            }
        }
    }

    companion object {
        private const val TAG = "UpdateChecker"
        private const val RELEASES_URL =
            "https://api.github.com/repos/talibfitrah/albunyaantube/releases/latest"

        /**
         * True iff [remote] is strictly greater than [current] in semver-2.0.0-ish order.
         *
         * Rules (abridged from https://semver.org):
         *  - Core versions (MAJOR.MINOR.PATCH) compare numerically, left to right.
         *  - A version WITH a pre-release identifier has LOWER precedence than the same
         *    core version WITHOUT one (so `1.0.0` > `1.0.0-rc.1`).
         *  - Pre-release identifiers compare identifier-by-identifier:
         *      * Numeric-only identifiers compare numerically.
         *      * Alphanumeric identifiers compare lexically (ASCII sort).
         *      * A numeric identifier always has lower precedence than an alphanumeric one.
         *      * A larger number of identifiers wins when all preceding ones are equal.
         *
         * The comparator deliberately tolerates malformed inputs (missing segments parse
         * as 0, empty prerelease treated as absent) — we never want a version check to
         * hard-fail and block rollouts.
         */
        internal fun isNewerVersion(remote: String, current: String): Boolean {
            val (rCore, rPre) = splitVersion(remote)
            val (cCore, cPre) = splitVersion(current)
            val coreCompare = compareNumericParts(rCore, cCore)
            if (coreCompare != 0) return coreCompare > 0
            // Same core. Presence of a prerelease makes the version LOWER.
            if (rPre == null && cPre == null) return false
            if (rPre == null) return true          // remote is release, current is prerelease
            if (cPre == null) return false         // remote is prerelease, current is release
            return comparePrereleaseIdentifiers(rPre, cPre) > 0
        }

        /**
         * Splits a version string into its numeric core and optional pre-release identifiers.
         * Keeps identifiers as strings — ordering logic in [comparePrereleaseIdentifiers]
         * decides per-identifier whether to treat them as numeric or alphanumeric.
         */
        private fun splitVersion(v: String): Pair<List<Int>, List<String>?> {
            val dash = v.indexOf('-')
            val core = if (dash < 0) v else v.substring(0, dash)
            val pre = if (dash < 0) null else v.substring(dash + 1).takeIf { it.isNotEmpty() }
            val coreNums = core.split(".").map { it.toIntOrNull() ?: 0 }
            val preIds = pre?.split('.')?.filter { it.isNotEmpty() }
            return coreNums to preIds
        }

        /**
         * Compares two pre-release identifier lists per semver 2.0.0 section 11.
         * Returns positive if `a > b`, negative if `a < b`, zero if equal.
         */
        private fun comparePrereleaseIdentifiers(a: List<String>, b: List<String>): Int {
            val shared = minOf(a.size, b.size)
            for (i in 0 until shared) {
                val ai = a[i]
                val bi = b[i]
                val aNum = ai.toIntOrNull()
                val bNum = bi.toIntOrNull()
                val cmp = when {
                    aNum != null && bNum != null -> aNum.compareTo(bNum)
                    aNum != null -> -1 // numeric identifier has LOWER precedence than alphanumeric
                    bNum != null -> 1
                    else -> ai.compareTo(bi) // both alphanumeric: ASCII lex order
                }
                if (cmp != 0) return cmp
            }
            // All shared identifiers equal: the longer list wins.
            return a.size.compareTo(b.size)
        }

        private fun compareNumericParts(a: List<Int>, b: List<Int>): Int {
            val max = maxOf(a.size, b.size)
            for (i in 0 until max) {
                val ai = a.getOrElse(i) { 0 }
                val bi = b.getOrElse(i) { 0 }
                if (ai != bi) return ai.compareTo(bi)
            }
            return 0
        }
    }
}
