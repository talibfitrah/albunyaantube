package com.albunyaan.tube.update

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseSummaryFetcherTest {

    @Test
    fun `parses well-formed JSON and exposes per-locale summary`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""
            {
              "1.0.0-beta.14": {
                "en": "English summary.",
                "ar": "الملخص العربي.",
                "nl": "Nederlandse samenvatting."
              }
            }
        """.trimIndent()))
        server.start()

        val fetcher = ReleaseSummaryFetcher(OkHttpClient())
        fetcher.metaUrlForTest = server.url("/meta").toString()
        val summaries = fetcher.load()

        assertEquals("English summary.", summaries.summaryFor("1.0.0-beta.14", "en"))
        assertEquals("الملخص العربي.", summaries.summaryFor("1.0.0-beta.14", "ar"))
        server.shutdown()
    }

    @Test
    fun `missing locale falls back to en`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""
            {"1.0.0-beta.14": {"en": "Only English."}}
        """.trimIndent()))
        server.start()

        val fetcher = ReleaseSummaryFetcher(OkHttpClient())
        fetcher.metaUrlForTest = server.url("/meta").toString()
        val summaries = fetcher.load()

        assertEquals("Only English.", summaries.summaryFor("1.0.0-beta.14", "nl"))
        server.shutdown()
    }

    @Test
    fun `missing version returns null`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"1.0.0-beta.99": {"en": "x"}}"""))
        server.start()

        val fetcher = ReleaseSummaryFetcher(OkHttpClient())
        fetcher.metaUrlForTest = server.url("/meta").toString()
        val summaries = fetcher.load()

        assertTrue(summaries.summaryFor("1.0.0-beta.14", "en") == null)
        server.shutdown()
    }

    @Test
    fun `404 returns empty map without throwing`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()

        val fetcher = ReleaseSummaryFetcher(OkHttpClient())
        fetcher.metaUrlForTest = server.url("/meta").toString()
        val summaries = fetcher.load()

        assertTrue(summaries.summaryFor("1.0.0-beta.14", "en") == null)
        server.shutdown()
    }

    @Test
    fun `malformed JSON returns empty map`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("not json at all"))
        server.start()

        val fetcher = ReleaseSummaryFetcher(OkHttpClient())
        fetcher.metaUrlForTest = server.url("/meta").toString()
        val summaries = fetcher.load()

        assertTrue(summaries.summaryFor("1.0.0-beta.14", "en") == null)
        server.shutdown()
    }
}
