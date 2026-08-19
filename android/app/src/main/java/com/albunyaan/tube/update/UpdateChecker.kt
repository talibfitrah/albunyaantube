package com.albunyaan.tube.update

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.albunyaan.tube.BuildConfig
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
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
    val apkUrl: String,
    val apkSizeBytes: Long,
    val publishedAt: java.time.Instant? = null,   // null when GitHub omits it
)

/**
 * Shared DTO for both the single-release endpoint (`GET /releases/latest`) and the
 * release-list endpoint (`GET /releases`). Originally split into two DTOs so they
 * could evolve independently; consolidated 2026-05-25 after Task 7 (commit fe2335a2)
 * threaded `published_at` through both — the split justification no longer holds and
 * the duplication was bloat (initial bloat-audit, finding #2).
 */
@JsonClass(generateAdapter = true)
internal data class GithubReleaseDto(
    val tag_name: String,
    val name: String?,
    val prerelease: Boolean,
    val assets: List<GithubAssetDto> = emptyList(),
    val published_at: String? = null,
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
    private val okHttpClient: OkHttpClient,
    private val installSource: InstallSource
) {
    @VisibleForTesting
    internal var apiBaseUrlForTest: String? = null   // production callers MUST NOT set this

    @VisibleForTesting
    internal var currentVersionForTest: String? = null  // production callers MUST NOT set this

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listAdapter = moshi.adapter<List<GithubReleaseDto>>(
        Types.newParameterizedType(List::class.java, GithubReleaseDto::class.java)
    )

    suspend fun checkForUpdate(): Result<UpdateInfo?> {
        if (installSource.isPlayStore()) {
            Log.d(TAG, "Installed from Play Store — skipping GitHub update check")
            return Result.success(null)
        }
        // Use the releases LIST, not GitHub's /releases/latest. /releases/latest returns
        // ONLY the latest non-prerelease, but every FitrahTube beta is a prerelease — so it
        // returned an ancient beta.18 and the auto-check / "Check for updates" never offered
        // any newer beta to anyone (the core "auto-update not working"). listReleases pulls
        // the actual newest releases (prerelease-eligible for beta builds, each with an APK);
        // pick the newest one strictly newer than the installed build.
        val currentVersion = currentVersionForTest ?: BuildConfig.VERSION_NAME
        return listReleases(limit = LATEST_SCAN_LIMIT).map { releases ->
            releases.firstOrNull { isNewerVersion(it.versionName, currentVersion) }.also { info ->
                if (info == null) {
                    Log.d(TAG, "No update newer than $currentVersion among latest releases")
                } else {
                    Log.d(TAG, "Update available: ${info.versionName} (current $currentVersion)")
                }
            }
        }
    }

    /**
     * Returns up to [limit] most-recent releases (newest first) that ship an APK asset.
     * Filters out Play Store installs, releases without an APK, and (when the running
     * build is stable) pre-releases — matching the rules in [checkForUpdate].
     *
     * Failure semantics:
     *  - HTTP non-2xx (rate-limit, 5xx, transient outage) → `Result.failure(IOException)`
     *    so [ReleaseCatalogCache] does NOT sticky an empty-list result for the full
     *    TTL. Empty 2xx response (genuine "no releases") still returns
     *    `Result.success(emptyList())` — distinguishable from transient failure
     *    only by Result.isSuccess (codex C-3 / cubic R7 P0).
     *  - Thrown exceptions inside the network or parse path (IOException, SocketTimeoutException,
     *    Moshi JsonDataException on a malformed but non-null body) propagate as `Result.failure`.
     *  - Play Store installs short-circuit to `Result.success(emptyList())` before the network call.
     *
     * Pagination: `per_page` is bumped to `limit * 2` on prerelease builds and
     * `limit * 6` on stable ones (only stable builds can have rows dropped by the
     * prerelease filter) (capped at GitHub's 100/page
     * maximum). The `.take(limit)` after filtering ensures the caller still gets at
     * most [limit] post-filter entries. Without overfetching, a feed where the top
     * N entries are prereleases-on-stable-build or no-APK would silently produce
     * fewer (or zero) results (codex C-2).
     */
    suspend fun listReleases(
        limit: Int
    ): Result<List<UpdateInfo>> = withContext(Dispatchers.IO) {
        require(limit in 1..GITHUB_MAX_PER_PAGE) {
            "limit must be in 1..$GITHUB_MAX_PER_PAGE, got $limit"
        }
        if (installSource.isPlayStore()) return@withContext Result.success(emptyList())
        runCatchingCoroutine {
            val base = apiBaseUrlForTest ?: "https://api.github.com/"
            // Compute as Long to defuse Int overflow on `limit * 6` for any future
            // caller passing a near-MAX_VALUE limit; coerce back into the
            // 1..GITHUB_MAX_PER_PAGE window so `per_page` is always a valid
            // GitHub API value (codex stage-6 MEDIUM).
            val currentVersion = currentVersionForTest ?: BuildConfig.VERSION_NAME
            val currentIsPrerelease = currentVersion.contains('-')
            // The envelope absorbs the two filters below. On a prerelease build the
            // prerelease filter drops nothing (every beta IS a prerelease), leaving
            // only the rare APK-less release — so 6x was pure waste, and not free: the
            // payload grows with every release and had reached ~150 KB to use ~25 KB,
            // parsed on-device during cold start, squeezing the splash probe's budget.
            val overfetch = if (currentIsPrerelease) 2L else 6L
            val pageSize = (limit.toLong() * overfetch).coerceIn(1L, GITHUB_MAX_PER_PAGE.toLong()).toInt()
            val url = "${base.trimEnd('/')}/repos/$GITHUB_REPO/releases?per_page=$pageSize"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()
            val call = okHttpClient.newCall(request).cancelWhenCoroutineCancels()
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    // Throw inside runCatching so ReleaseCatalogCache's read-through cache reads
                    // Result.failure and does not cache an empty snapshot (codex C-3).
                    throw java.io.IOException(
                        "GitHub releases API returned HTTP ${response.code}"
                    )
                }
                val body = response.body?.string() ?: return@use emptyList<UpdateInfo>()
                val list = listAdapter.fromJson(body) ?: return@use emptyList<UpdateInfo>()
                list.asSequence()
                    .filterNot { it.prerelease && !currentIsPrerelease }
                    .mapNotNull { release ->
                        val apk = release.assets.firstOrNull {
                            it.name.endsWith(".apk", ignoreCase = true)
                        } ?: return@mapNotNull null
                        UpdateInfo(
                            versionName = release.tag_name.removePrefix("v").removePrefix("V").trim(),
                            releaseName = release.name ?: release.tag_name,
                            apkUrl = apk.browser_download_url,
                            apkSizeBytes = apk.size,
                            publishedAt = release.published_at?.let {
                                runCatching { java.time.Instant.parse(it) }.getOrNull()
                            },
                        )
                    }
                    .take(limit)
                    .toList()
            }
        }
    }

    companion object {
        private const val TAG = "UpdateChecker"
        /** Single source of truth for the GitHub repo coordinate. Shared with
         *  [UpdatePromptFlow] for the "View full changelog" deep-link URL
         *  (S1 M5 — was duplicated as a private const at both ends). */
        const val GITHUB_REPO = "talibfitrah/albunyaantube"
        /** How many of the newest releases [checkForUpdate] scans for the newest one
         *  strictly newer than the installed build. Small: the newest eligible release is
         *  near the top; a handful absorbs prerelease-filtering and no-APK skips. */
        private const val LATEST_SCAN_LIMIT = 5
        /** GitHub's documented maximum `per_page` for paginated REST endpoints
         *  (https://docs.github.com/en/rest/releases/releases#list-releases). */
        private const val GITHUB_MAX_PER_PAGE = 100

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
         *
         * Per semver 2.0.0 §10, build metadata (anything after a literal `+`) MUST be ignored
         * for precedence. We strip it from both the core and the prerelease sections before
         * parsing, so `1.0.0+build.1` == `1.0.0` and `1.0.0-beta.1+sha.abc` == `1.0.0-beta.1`.
         */
        private fun splitVersion(v: String): Pair<List<Int>, List<String>?> {
            val withoutBuild = v.substringBefore('+')
            val dash = withoutBuild.indexOf('-')
            val core = if (dash < 0) withoutBuild else withoutBuild.substring(0, dash)
            val pre = if (dash < 0) null else withoutBuild.substring(dash + 1).takeIf { it.isNotEmpty() }
            val coreNums = core.split(".").map { it.toIntOrNull() ?: 0 }
            // A prerelease string that parses to no non-empty identifiers (e.g. "1.0.0-..")
            // is treated as absent so downstream precedence logic doesn't see a phantom
            // empty list vs null (CodeRabbit #4). Preserves the documented invariant.
            val preIds = pre?.split('.')?.filter { it.isNotEmpty() }?.ifEmpty { null }
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
