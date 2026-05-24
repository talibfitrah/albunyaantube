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
    private val summaries: ReleaseSummaryFetcher
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

    /** Returns up to [limit] releases (newest first), refreshing if stale. */
    suspend fun list(limit: Int = 5): List<UpdateInfo> {
        val snap = current() ?: return emptyList()
        return snap.releases.take(limit)
    }

    /** Returns the first cached release strictly newer than the running build, or null. */
    suspend fun latest(): UpdateInfo? {
        val snap = current() ?: return null
        return snap.releases.firstOrNull {
            UpdateChecker.isNewerVersion(it.versionName, BuildConfig.VERSION_NAME)
        }
    }

    /** Exposes summaries to callers (the picker). */
    suspend fun summaries(): ReleaseSummaries =
        current()?.summaries ?: ReleaseSummaries(emptyMap())

    private suspend fun current(): Snapshot? {
        snapshot.get()?.takeIf { clock() - it.capturedAtMs < TTL_MS }?.let { return it }
        return loadMutex.withLock {
            // Double-check: another coroutine may have just refreshed.
            snapshot.get()?.takeIf { clock() - it.capturedAtMs < TTL_MS }?.let { return@withLock it }
            // Cubic round-3 contract (preserved across the cache refactor): a transient
            // cold-start network failure must NOT sticky a "no update" result for the
            // full TTL. Only a successful fetch (even one yielding an empty list) gets
            // cached. Result.failure → we return null so the next call retries fresh.
            val releases = checker.listReleases(limit = 5).getOrNull() ?: return@withLock null
            val sums = summaries.load()
            val fresh = Snapshot(clock(), releases, sums)
            snapshot.set(fresh)
            fresh
        }
    }

    companion object {
        const val TTL_MS: Long = 5 * 60_000L
    }
}
