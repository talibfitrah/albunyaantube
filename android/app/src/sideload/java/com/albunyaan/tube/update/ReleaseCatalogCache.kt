package com.albunyaan.tube.update

import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.albunyaan.tube.BuildConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for "what releases exist on GitHub right now". Owned
 * by the update package and consumed by both [UpdatePromptFlow] (latest only)
 * and AvailableVersionsViewModel (full list).
 *
 * The releases list and the localized release notes are cached INDEPENDENTLY, each
 * for [TTL_MS]. That split is deliberate: they come from different hosts and have
 * different criticality. Whether a newer build exists is decided by the releases list
 * alone — the cold-start update prompt never renders notes (see
 * [UpdatePromptFlow.showUpdateDialog], which uses a generic localized body) — so the
 * notes fetch must never sit between the user and an update. Sharing one all-or-nothing
 * snapshot meant a slow or blocked raw.githubusercontent.com suppressed update
 * detection entirely, on a network that could reach api.github.com perfectly well.
 *
 * Concurrent loads of the same field coalesce via its mutex so parallel splash+settings
 * entries do not double-fetch. Callers within the TTL read the AtomicReference without
 * taking the lock.
 *
 * Rationale: GitHub anonymous limit is 60 req/h per IP. Splash gate, settings
 * deep-link, and rotation re-entries MUST share one call within a sane window.
 * 5 minutes matches the typical edit cadence on a release page.
 */
@Singleton
class ReleaseCatalogCache @Inject constructor(
    private val checker: UpdateChecker,
    private val summaries: ReleaseSummaryFetcher,
    private val installSource: InstallSource,
) {
    /**
     * Overrideable time source. Tests set this to a controllable lambda;
     * production callers MUST NOT touch it (matches the [UpdateChecker.apiBaseUrlForTest]
     * pattern used elsewhere in this package).
     */
    @VisibleForTesting
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }
    private data class Cached<T>(val capturedAtMs: Long, val value: T)

    private val releasesCache = AtomicReference<Cached<List<UpdateInfo>>?>(null)
    private val summariesCache = AtomicReference<Cached<ReleaseSummaries>?>(null)
    private val releasesMutex = Mutex()
    private val summariesMutex = Mutex()

    // Process-stable: package install source is fixed for the lifetime of the
    // app. Caching the boolean avoids a synchronous PackageManager binder call
    // on every cache lookup (cubic R6 P2).
    private val isPlayStoreInstall: Boolean by lazy { installSource.isPlayStore() }

    /**
     * Returns up to [limit] releases (newest first), refreshing if stale.
     * [limit] MUST be ≤ [INTERNAL_REFRESH_LIMIT] — otherwise the request silently
     * caps and the caller would get fewer releases than asked for (cubic R2 P2).
     * If a caller needs more than [INTERNAL_REFRESH_LIMIT], bump
     * [INTERNAL_REFRESH_LIMIT] and `per_page` envelope in tandem.
     */
    suspend fun list(limit: Int): List<UpdateInfo> {
        require(limit in 1..INTERNAL_REFRESH_LIMIT) {
            "limit must be in 1..$INTERNAL_REFRESH_LIMIT, got $limit"
        }
        return releases()?.take(limit) ?: emptyList()
    }

    /**
     * Test seam mirroring [UpdateChecker.currentVersionForTest]. Production callers
     * MUST NOT touch this — it exists so [latest] can be exercised against an
     * arbitrary installed version (cubic R1 C6). When unset, the runtime
     * `BuildConfig.VERSION_NAME` is read at call time.
     */
    @VisibleForTesting
    internal var currentVersionForTest: String? = null

    /** Returns the first cached release strictly newer than the running build, or null. */
    suspend fun latest(): UpdateInfo? {
        val installed = currentVersionForTest ?: BuildConfig.VERSION_NAME
        return releases()?.firstOrNull {
            UpdateChecker.isNewerVersion(it.versionName, installed)
        }
    }

    /**
     * Localized release notes for the picker. Cosmetic: a failure yields an empty map
     * rather than propagating, and never affects [latest] or [list].
     */
    suspend fun summaries(): ReleaseSummaries =
        cached(summariesCache, summariesMutex) { summaries.load() } ?: ReleaseSummaries(emptyMap())

    private suspend fun releases(): List<UpdateInfo>? =
        cached(releasesCache, releasesMutex) { checker.listReleases(limit = INTERNAL_REFRESH_LIMIT) }

    /**
     * Double-checked, mutex-coalesced read-through cache for one field.
     *
     * Play Store installs short-circuit BEFORE any network call: listReleases would
     * return success(emptyList()) anyway, but the notes fetch would still hit
     * raw.githubusercontent (cubic R3 P3).
     *
     * A failed fetch is NOT cached, so a transient cold-start failure cannot sticky a
     * "no update" answer for the whole TTL (cubic round-3 contract). A successful fetch
     * is cached even when empty — that is a real answer.
     */
    private suspend fun <T : Any> cached(
        ref: AtomicReference<Cached<T>?>,
        mutex: Mutex,
        fetch: suspend () -> Result<T>,
    ): T? {
        if (isPlayStoreInstall) return null
        ref.get()?.takeIf { clock() - it.capturedAtMs < TTL_MS }?.let { return it.value }
        return mutex.withLock {
            // Double-check: another coroutine may have just refreshed.
            ref.get()?.takeIf { clock() - it.capturedAtMs < TTL_MS }?.let { return@withLock it.value }
            val value = fetch().getOrNull() ?: return@withLock null
            ref.set(Cached(clock(), value))
            value
        }
    }

    companion object {
        const val TTL_MS: Long = 5 * 60_000L
        /** Refresh-time fetch quota. Larger than the picker's per-call `limit` so the
         *  cache covers both the picker (5) and any future short-list consumer
         *  without re-fetching. [UpdateChecker.listReleases] applies its own
         *  per-page envelope on top of this (2x on prerelease builds, 6x on
         *  stable) to absorb the rows its filters drop. */
        private const val INTERNAL_REFRESH_LIMIT = 5

    }
}
