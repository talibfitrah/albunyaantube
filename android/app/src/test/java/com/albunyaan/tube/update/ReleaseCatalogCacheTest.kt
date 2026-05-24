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

    private val release14 = UpdateInfo("1.0.0-beta.14", "beta-14", "", "https://x/14.apk", 1)
    private val release13 = UpdateInfo("1.0.0-beta.13", "beta-13", "", "https://x/13.apk", 1)

    @Test
    fun `first call hits network second call within TTL serves from cache`() = runTest {
        var now = 1_000L
        val checker = mock<UpdateChecker> {
            onBlocking { listReleases(any()) } doReturn Result.success(listOf(release14, release13))
        }
        val summaries = mock<ReleaseSummaryFetcher> {
            onBlocking { load() } doReturn ReleaseSummaries(emptyMap())
        }
        val cache = ReleaseCatalogCache(checker, summaries).apply { clock = { now } }

        cache.list(limit = 5)
        cache.list(limit = 5)

        verify(checker, times(1)).listReleases(any())
        verify(summaries, times(1)).load()
    }

    @Test
    fun `call after TTL expiry refetches`() = runTest {
        var now = 0L
        val checker = mock<UpdateChecker> {
            onBlocking { listReleases(any()) } doReturn Result.success(listOf(release14))
        }
        val summaries = mock<ReleaseSummaryFetcher> {
            onBlocking { load() } doReturn ReleaseSummaries(emptyMap())
        }
        val cache = ReleaseCatalogCache(checker, summaries).apply { clock = { now } }

        cache.list()
        now += ReleaseCatalogCache.TTL_MS + 1
        cache.list()

        verify(checker, times(2)).listReleases(any())
    }

    @Test
    fun `latest returns first newer-than-installed entry from cached snapshot`() = runTest {
        val veryNew = UpdateInfo("99.0.0", "future", "", "https://x/99.apk", 1)
        val checker = mock<UpdateChecker> {
            onBlocking { listReleases(any()) } doReturn Result.success(listOf(veryNew))
        }
        val summaries = mock<ReleaseSummaryFetcher> {
            onBlocking { load() } doReturn ReleaseSummaries(emptyMap())
        }
        val cache = ReleaseCatalogCache(checker, summaries).apply { clock = { 0L } }

        val latest = cache.latest()
        assertEquals("99.0.0", latest?.versionName)
    }

    @Test
    fun `list and latest share the same snapshot - no double fetch`() = runTest {
        val veryNew = UpdateInfo("99.0.0", "future", "", "https://x/99.apk", 1)
        val checker = mock<UpdateChecker> {
            onBlocking { listReleases(any()) } doReturn Result.success(listOf(veryNew))
        }
        val summaries = mock<ReleaseSummaryFetcher> {
            onBlocking { load() } doReturn ReleaseSummaries(emptyMap())
        }
        val cache = ReleaseCatalogCache(checker, summaries).apply { clock = { 0L } }

        cache.list()
        cache.latest()

        verify(checker, times(1)).listReleases(any())
        verify(summaries, times(1)).load()
    }
}
