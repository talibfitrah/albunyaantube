package com.albunyaan.tube.update

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

class ReleaseCatalogCacheTest {

    private val release14 = UpdateInfo("1.0.0-beta.14", "beta-14", "https://x/14.apk", 1)
    private val release13 = UpdateInfo("1.0.0-beta.13", "beta-13", "https://x/13.apk", 1)

    /** All existing tests assume sideload (not Play Store) so the short-circuit
     *  at the top of [ReleaseCatalogCache.current] does not fire. */
    private fun sideloadInstall(): InstallSource = mock { on { isPlayStore() } doReturn false }

    @Test
    fun `first call hits network second call within TTL serves from cache`() = runTest {
        var now = 1_000L
        val checker = mock<UpdateChecker> {
            onBlocking { listReleases(any()) } doReturn Result.success(listOf(release14, release13))
        }
        val summaries = mock<ReleaseSummaryFetcher> {
            onBlocking { load() } doReturn Result.success(ReleaseSummaries(emptyMap()))
        }
        val cache = ReleaseCatalogCache(checker, summaries, sideloadInstall()).apply { clock = { now } }

        cache.list(limit = 5)
        cache.list(limit = 5)

        verify(checker, times(1)).listReleases(any())
    }

    @Test
    fun `call after TTL expiry refetches`() = runTest {
        var now = 0L
        val checker = mock<UpdateChecker> {
            onBlocking { listReleases(any()) } doReturn Result.success(listOf(release14))
        }
        val summaries = mock<ReleaseSummaryFetcher> {
            onBlocking { load() } doReturn Result.success(ReleaseSummaries(emptyMap()))
        }
        val cache = ReleaseCatalogCache(checker, summaries, sideloadInstall()).apply { clock = { now } }

        cache.list(limit = 5)
        now += ReleaseCatalogCache.TTL_MS + 1
        cache.list(limit = 5)

        verify(checker, times(2)).listReleases(any())
    }

    @Test
    fun `latest returns first newer-than-installed entry from cached snapshot`() = runTest {
        val veryNew = UpdateInfo("99.0.0", "future", "https://x/99.apk", 1)
        val checker = mock<UpdateChecker> {
            onBlocking { listReleases(any()) } doReturn Result.success(listOf(veryNew))
        }
        val summaries = mock<ReleaseSummaryFetcher> {
            onBlocking { load() } doReturn Result.success(ReleaseSummaries(emptyMap()))
        }
        val cache = ReleaseCatalogCache(checker, summaries, sideloadInstall()).apply { clock = { 0L } }

        val latest = cache.latest()
        assertEquals("99.0.0", latest?.versionName)
    }

    @Test
    fun `list and latest share the same snapshot - no double fetch`() = runTest {
        val veryNew = UpdateInfo("99.0.0", "future", "https://x/99.apk", 1)
        val checker = mock<UpdateChecker> {
            onBlocking { listReleases(any()) } doReturn Result.success(listOf(veryNew))
        }
        val summaries = mock<ReleaseSummaryFetcher> {
            onBlocking { load() } doReturn Result.success(ReleaseSummaries(emptyMap()))
        }
        val cache = ReleaseCatalogCache(checker, summaries, sideloadInstall()).apply { clock = { 0L } }

        cache.list(limit = 5)
        cache.latest()

        verify(checker, times(1)).listReleases(any())
        // Neither call touches the notes host — notes are cached separately and are
        // never on the path that decides whether an update exists.
        verify(summaries, org.mockito.kotlin.never()).load()
    }

    @Test
    fun `Result_failure from listReleases does not cache - next call retries`() = runTest {
        val checker = mock<UpdateChecker> {
            // First call fails (simulating an IOException), second call succeeds.
            onBlocking { listReleases(any()) }
                .doReturn(Result.failure(java.io.IOException("offline")))
                .doReturn(Result.success(listOf(release14)))
        }
        val summaries = mock<ReleaseSummaryFetcher> {
            onBlocking { load() } doReturn Result.success(ReleaseSummaries(emptyMap()))
        }
        val cache = ReleaseCatalogCache(checker, summaries, sideloadInstall()).apply { clock = { 0L } }

        val firstResult = cache.list(limit = 5)
        val secondResult = cache.list(limit = 5)

        // First attempt returned empty (failure), second returned the cached success.
        assertEquals(emptyList<UpdateInfo>(), firstResult)
        assertEquals(listOf(release14), secondResult)

        // Critical: failure must not have cached. Both attempts hit the network.
        verify(checker, times(2)).listReleases(any())
        // The notes fetcher is not touched at all by list(): notes are cached
        // separately, so a releases refresh no longer drags a second host along.
        verify(summaries, org.mockito.kotlin.never()).load()
    }

    /**
     * The whole point of the split: notes are cosmetic, so a notes host that is
     * blocked, throttled or simply slow must not cost the user the update. Sharing one
     * all-or-nothing snapshot meant a failed raw.githubusercontent.com fetch suppressed
     * update detection entirely on a network that could reach api.github.com fine.
     */
    @Test
    fun `a failing notes host does not affect update detection`() = runTest {
        val veryNew = UpdateInfo("99.0.0", "future", "https://x/99.apk", 1)
        val checker = mock<UpdateChecker> {
            onBlocking { listReleases(any()) } doReturn Result.success(listOf(veryNew))
        }
        val summaries = mock<ReleaseSummaryFetcher> {
            onBlocking { load() } doReturn Result.failure(java.io.IOException("blocked"))
        }
        val cache = ReleaseCatalogCache(checker, summaries, sideloadInstall()).apply { clock = { 0L } }

        assertEquals("99.0.0", cache.latest()?.versionName)
        assertEquals(listOf(veryNew), cache.list(limit = 5))
        assertEquals(ReleaseSummaries(emptyMap()), cache.summaries())
    }

    /** A notes failure is never cached, so the notes come back as soon as the host does. */
    @Test
    fun `notes failure is not cached - next call retries`() = runTest {
        val checker = mock<UpdateChecker> {
            onBlocking { listReleases(any()) } doReturn Result.success(listOf(release14))
        }
        val good = ReleaseSummaries(mapOf("1.0.0-beta.14" to mapOf("en" to "notes")))
        val summaries = mock<ReleaseSummaryFetcher> {
            onBlocking { load() }
                .doReturn(Result.failure(java.io.IOException("blocked")))
                .doReturn(Result.success(good))
        }
        val cache = ReleaseCatalogCache(checker, summaries, sideloadInstall()).apply { clock = { 0L } }

        assertEquals(ReleaseSummaries(emptyMap()), cache.summaries())
        // Clock never advances, so a cached failure would have served this call too.
        assertEquals(good, cache.summaries())
    }

    // cubic R3 P3: Play Store install short-circuits BEFORE any network call —
    // both listReleases (which would short-circuit to success(emptyList) anyway)
    // and summaries.load (which would have wasted a raw.githubusercontent fetch).
    @Test
    fun `Play Store install short-circuits before fetching releases or summaries`() = runTest {
        val checker = mock<UpdateChecker>()
        val summaries = mock<ReleaseSummaryFetcher>()
        val installSource = mock<InstallSource> { on { isPlayStore() } doReturn true }
        val cache = ReleaseCatalogCache(checker, summaries, installSource).apply { clock = { 0L } }

        assertEquals(emptyList<UpdateInfo>(), cache.list(limit = 5))
        verify(checker, org.mockito.kotlin.never()).listReleases(any())
        verify(summaries, org.mockito.kotlin.never()).load()
    }

    @Test
    fun `entry exactly at TTL boundary is treated as expired`() = runTest {
        var now = 0L
        val checker = mock<UpdateChecker> {
            onBlocking { listReleases(any()) } doReturn Result.success(listOf(release14))
        }
        val summaries = mock<ReleaseSummaryFetcher> {
            onBlocking { load() } doReturn Result.success(ReleaseSummaries(emptyMap()))
        }
        val cache = ReleaseCatalogCache(checker, summaries, sideloadInstall()).apply { clock = { now } }

        cache.list(limit = 5)
        now += ReleaseCatalogCache.TTL_MS   // exactly at boundary
        cache.list(limit = 5)

        verify(checker, times(2)).listReleases(any())
    }
}
