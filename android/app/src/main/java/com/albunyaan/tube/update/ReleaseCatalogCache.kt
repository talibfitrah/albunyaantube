package com.albunyaan.tube.update

import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.albunyaan.tube.BuildConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
 * Holds one Snapshot for [TTL_MS] from the moment it was loaded. Concurrent
 * loads coalesce via [loadMutex] so two parallel splash+settings entries do
 * not double-fetch. Subsequent callers within the TTL read the AtomicReference
 * without taking the lock.
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
    private data class Snapshot(
        val capturedAtMs: Long,
        val releases: List<UpdateInfo>,
        val summaries: ReleaseSummaries
    )

    private val snapshot = AtomicReference<Snapshot?>(null)
    private val loadMutex = Mutex()

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
        val snap = current() ?: return emptyList()
        return snap.releases.take(limit)
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
        val snap = current() ?: return null
        val installed = currentVersionForTest ?: BuildConfig.VERSION_NAME
        return snap.releases.firstOrNull {
            UpdateChecker.isNewerVersion(it.versionName, installed)
        }
    }

    /** Exposes summaries to callers (the picker). */
    suspend fun summaries(): ReleaseSummaries =
        current()?.summaries ?: ReleaseSummaries(emptyMap())

    private suspend fun current(): Snapshot? {
        // Short-circuit on Play Store installs BEFORE the parallel network calls
        // fire. UpdateChecker.listReleases would return Result.success(emptyList())
        // anyway, but ReleaseSummaryFetcher.load would still hit raw.githubusercontent
        // — wasting one network call per cache miss on Play Store cold starts
        // (cubic R3 P3). The cached `isPlayStoreInstall` lazy avoids per-lookup
        // PackageManager binder traffic (cubic R6 P2).
        if (isPlayStoreInstall) return null
        snapshot.get()?.takeIf { clock() - it.capturedAtMs < TTL_MS }?.let { return it }
        return loadMutex.withLock {
            // Double-check: another coroutine may have just refreshed.
            snapshot.get()?.takeIf { clock() - it.capturedAtMs < TTL_MS }?.let { return@withLock it }
            // Contract preserved from cubic round-3: a transient cold-start network
            // failure must NOT sticky a "no update" result for the full TTL. Only a
            // successful fetch (even one yielding an empty list) gets cached.
            // Result.failure → return null so the next call retries fresh.
            //
            // Releases + summaries fetched in parallel — both are independent
            // network calls and serialising them roughly doubled cold-fill time
            // (cubic R1 C4), which directly pressured the splash gate's 2 s budget.
            val (releasesResult, summariesResult) = coroutineScope {
                val rDef = async { checker.listReleases(limit = INTERNAL_REFRESH_LIMIT) }
                val sDef = async { summaries.load() }
                rDef.await() to sDef.await()
            }
            val releases = releasesResult.getOrNull() ?: return@withLock null
            // Summary fetch failure must NOT sticky a 5-min "no localized
            // strings" state (cubic R1 C2). A genuine 2xx with empty body is
            // Success(emptyMap()); a transient HTTP/IO failure is Failure → null
            // here, which forces the cache to retry the WHOLE snapshot on the
            // next call. Wasteful (re-fetches releases too) but correct.
            val sums = summariesResult.getOrNull() ?: return@withLock null
            val fresh = Snapshot(clock(), releases, sums)
            snapshot.set(fresh)
            fresh
        }
    }

    companion object {
        const val TTL_MS: Long = 5 * 60_000L
        /** Refresh-time fetch quota. Larger than the picker's per-call `limit` so the
         *  cache covers both the picker (5) and any future short-list consumer
         *  without re-fetching. Matches the per-page * 6 envelope used by
         *  [UpdateChecker.listReleases]. */
        private const val INTERNAL_REFRESH_LIMIT = 5
    }
}
