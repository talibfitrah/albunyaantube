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

    suspend fun load(): ReleaseSummaries = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(metaUrlForTest ?: META_URL).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "releases-meta.json returned HTTP ${response.code}")
                    return@use ReleaseSummaries(emptyMap())
                }
                val body = response.body?.string() ?: return@use ReleaseSummaries(emptyMap())
                ReleaseSummaries(adapter.fromJson(body) ?: emptyMap())
            }
        }.getOrElse {
            Log.w(TAG, "releases-meta.json fetch failed: ${it.message}")
            ReleaseSummaries(emptyMap())
        }
    }

    private companion object {
        const val TAG = "ReleaseSummaryFetcher"
        // Pinned to `develop` because the project branching policy keeps `main`
        // empty of in-flight work until a stable release lands. releases-meta.json
        // is authored on develop alongside the versionCode bump; reading from
        // main would 404 for every beta cut. Switch back to main once stable
        // releases start landing there (see CLAUDE.md release checklist).
        //
        // TODO(ANDROID-VERSIONS-01): flip META_URL back to /main/releases-meta.json
        // on the first stable release that merges to main. The literal string
        // "/develop/releases-meta.json" below is grep-able so CI / a release
        // checklist step can detect it and prompt the switchback (cubic R1 P3).
        const val META_URL =
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
