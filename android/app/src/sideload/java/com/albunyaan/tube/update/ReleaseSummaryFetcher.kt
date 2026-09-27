package com.albunyaan.tube.update

import android.util.Log
import androidx.annotation.VisibleForTesting
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
 * Fetches releases-meta.json from the repo's develop branch and exposes localized
 * one-line summaries per release tag. Authoring lives in the repo so the release
 * process is a single git operation; the app reads via raw.githubusercontent.com
 * without depending on a backend.
 *
 * Branch choice: pinned to develop because the project's branching policy keeps
 * main empty until a stable release lands. Once stable releases start cutting
 * into main, switch [META_URL] back to main.
 *
 * Failure modes (404, 5xx, timeout, parse error) all degrade to an empty map.
 * Missing locale falls back to "en"; missing version returns null. The picker
 * renders rows with no summary when the lookup misses.
 *
 * Pre-tag entries: meta entries authored before the matching GitHub release is
 * tagged are harmless. [UpdateChecker.listReleases] only returns tagged releases,
 * so a summary keyed on an untagged version is never joined into a row — it sits
 * as orphan map data until the tag catches up.
 */
@Singleton
class ReleaseSummaryFetcher @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    @VisibleForTesting
    internal var metaUrlForTest: String? = null   // production callers MUST NOT set this

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val mapType = Types.newParameterizedType(
        Map::class.java, String::class.java,
        Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
    )
    private val adapter = moshi.adapter<Map<String, Map<String, String>>>(mapType)

    /**
     * Fetches per-version localized summaries.
     *
     * Failure semantics (cubic R1 C2): HTTP non-2xx and IOException return
     * `Result.failure` so [ReleaseCatalogCache] does not cache an empty-summary
     * snapshot for the full TTL when the network failed transiently. A genuine
     * 2xx with an empty body or `{}` payload still returns
     * `Result.success(ReleaseSummaries(emptyMap()))` — distinguishable only by
     * `Result.isSuccess`. Oversize bodies (> [MAX_META_BODY_BYTES]) also surface
     * as `Result.success(emptyMap())` because that is structural defence, not a
     * transient condition (re-fetching would yield the same oversize response).
     */
    suspend fun load(): Result<ReleaseSummaries> = withContext(Dispatchers.IO) {
        runCatchingCoroutine {
            val request = Request.Builder().url(metaUrlForTest ?: META_URL).build()
            val call = okHttpClient.newCall(request).cancelWhenCoroutineCancels()
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "releases-meta.json returned HTTP ${response.code}")
                    throw java.io.IOException(
                        "releases-meta.json HTTP ${response.code}"
                    )
                }
                // Cap the body size to defend against a tampered or malformed
                // releases-meta.json that would otherwise OOM the parse (cso S2-1).
                // 64 KiB is generous: the file is ~3 strings × ~80 chars × N versions,
                // so even 200 versions × 3 locales × 120 chars ~= 72 KB. The current
                // file is under 4 KB; the cap leaves several years of release headroom.
                // `request(cap+1)` returns true iff the body has more bytes than the cap
                // (Okio populates the buffer up to the requested count); rejecting at
                // that point keeps the buffered allocation at cap+1 bytes worst case.
                val source = response.body?.source() ?: return@use ReleaseSummaries(emptyMap())
                if (source.request((MAX_META_BODY_BYTES + 1).toLong())) {
                    Log.w(TAG, "releases-meta.json exceeded ${MAX_META_BODY_BYTES} byte cap; refusing")
                    return@use ReleaseSummaries(emptyMap())
                }
                val body = source.readUtf8()
                val raw = adapter.fromJson(body) ?: emptyMap()
                // Per-string cap (cso S2-1) — a phishing-style line ("URGENT install
                // from https://evil") would render to ~160 chars in our row UI, so
                // truncate at 160. The picker subtitle uses maxLines=2 anyway; this
                // bounds the worst-case in-memory allocation per entry.
                val capped = raw.mapValues { (_, perLocale) ->
                    perLocale.mapValues { (_, value) -> value.take(MAX_SUMMARY_CHARS) }
                }
                ReleaseSummaries(capped)
            }
        }.onFailure { Log.w(TAG, "releases-meta.json fetch failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "ReleaseSummaryFetcher"
        /** Max bytes accepted from a releases-meta.json response body. Above this
         *  cap the parse short-circuits to an empty map (cso S2-1). */
        internal const val MAX_META_BODY_BYTES = 64 * 1024
        /** Max characters retained per locale summary string. Defends against a
         *  tampered releases-meta entry from inflating in-memory + UI width
         *  beyond what the picker subtitle (maxLines=2) can show (cso S2-1). */
        internal const val MAX_SUMMARY_CHARS = 160

        // Pinned to `develop` because the project branching policy keeps `main`
        // empty of in-flight work until a stable release lands. releases-meta.json
        // is authored on develop alongside the versionCode bump; reading from
        // main would 404 for every beta cut. Switch back to main once stable
        // releases start landing there (see CLAUDE.md release checklist).
        //
        // TODO(ANDROID-VERSIONS-01): flip META_URL back to /main/releases-meta.json
        // on the first stable release that merges to main.
        //
        // Enforcement: ReleaseSummaryFetcherTest.`stable build must not read meta
        // from develop branch` asserts that a stable VERSION_NAME (no '-' suffix)
        // is never paired with a `/develop/` META_URL — the build fails if a
        // stable release ships with the develop pin still in place (S1 I2).
        @VisibleForTesting
        internal const val META_URL =
            "https://raw.githubusercontent.com/talibfitrah/albunyaantube/develop/releases-meta.json"
    }
}

/**
 * Immutable view over the per-version, per-locale summary map. Resolution rules:
 *  - exact (version, locale) match → that string
 *  - exact version match but missing locale → fall back to "en"
 *  - missing version → null (caller renders empty subtitle)
 */
data class ReleaseSummaries(
    private val byVersion: Map<String, Map<String, String>>
) {
    fun summaryFor(versionName: String, locale: String): String? {
        val perLocale = byVersion[versionName] ?: return null
        return perLocale[locale] ?: perLocale["en"]
    }
}
