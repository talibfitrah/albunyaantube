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
        val summaries = fetcher.load().getOrThrow()

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
        val summaries = fetcher.load().getOrThrow()

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
        val summaries = fetcher.load().getOrThrow()

        assertTrue(summaries.summaryFor("1.0.0-beta.14", "en") == null)
        server.shutdown()
    }

    // cubic R1 C2: 404 / 5xx now surface as Result.failure so the cache does
    // not sticky an empty-summary snapshot for the full TTL on transient
    // network failure. Pre-cubic-R1 this returned Success(emptyMap).
    @Test
    fun `404 returns Result_failure so cache does not sticky empty for full TTL`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()

        val fetcher = ReleaseSummaryFetcher(OkHttpClient())
        fetcher.metaUrlForTest = server.url("/meta").toString()
        val result = fetcher.load()

        assertTrue("HTTP 404 must produce Result.failure", result.isFailure)
        server.shutdown()
    }

    // Malformed JSON throws inside Moshi → runCatching converts to Result.failure
    // (same behaviour as cubic R1 C2 for HTTP failures).
    @Test
    fun `malformed JSON returns Result_failure`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("not json at all"))
        server.start()

        val fetcher = ReleaseSummaryFetcher(OkHttpClient())
        fetcher.metaUrlForTest = server.url("/meta").toString()
        val result = fetcher.load()

        assertTrue("Malformed JSON must produce Result.failure", result.isFailure)
        server.shutdown()
    }

    // Stage-6 testing T4: body-cap off-by-one boundary tests.
    // The cap is enforced via source.request((MAX+1).toLong()) → reject if true.
    // 65536 bytes (exactly MAX) parses; 65537 bytes (MAX+1) rejects.

    @Test
    fun `body of exactly MAX_META_BODY_BYTES bytes parses successfully`() = runTest {
        val server = MockWebServer()
        // JSON wrapper around the padded string: `{"1.0.0":{"en":"…"}}` = 20 chars
        // of wrapper. Pad the string field so total body length == MAX (65536).
        val target = ReleaseSummaryFetcher.MAX_META_BODY_BYTES
        val wrapper = """{"1.0.0":{"en":""}}"""
        val padLen = target - wrapper.length
        val padding = "x".repeat(padLen)
        val body = """{"1.0.0":{"en":"$padding"}}"""
        require(body.length == target) { "expected exactly $target bytes, got ${body.length}" }
        server.enqueue(MockResponse().setBody(body))
        server.start()

        val fetcher = ReleaseSummaryFetcher(OkHttpClient())
        fetcher.metaUrlForTest = server.url("/meta").toString()
        val summaries = fetcher.load().getOrThrow()

        // Body parsed successfully and the per-string cap (160 chars) truncated
        // the padding — but the entry IS present (not null).
        val rendered = summaries.summaryFor("1.0.0", "en")
        assertTrue("expected non-null entry at exactly MAX bytes", rendered != null)
        assertTrue("expected truncated to ≤160 chars, got ${rendered?.length}",
            (rendered?.length ?: 0) <= ReleaseSummaryFetcher.MAX_SUMMARY_CHARS)
        server.shutdown()
    }

    @Test
    fun `body of MAX_META_BODY_BYTES plus one byte is rejected`() = runTest {
        val server = MockWebServer()
        val target = ReleaseSummaryFetcher.MAX_META_BODY_BYTES + 1
        val wrapper = """{"1.0.0":{"en":""}}"""
        val padding = "x".repeat(target - wrapper.length)
        val body = """{"1.0.0":{"en":"$padding"}}"""
        require(body.length == target) { "expected exactly ${target} bytes, got ${body.length}" }
        server.enqueue(MockResponse().setBody(body))
        server.start()

        val fetcher = ReleaseSummaryFetcher(OkHttpClient())
        fetcher.metaUrlForTest = server.url("/meta").toString()
        val summaries = fetcher.load().getOrThrow()

        assertTrue(
            "expected empty summaries for body > MAX",
            summaries.summaryFor("1.0.0", "en") == null
        )
        server.shutdown()
    }

    // cso S2-1: a tampered or runaway releases-meta.json (e.g. 50MB JSON) must
    // short-circuit before Moshi attempts to deserialise the whole body into a
    // Map. The cap is enforced at 64 KiB; anything above degrades to empty map.
    @Test
    fun `body over 64KB cap is rejected and returns empty`() = runTest {
        val server = MockWebServer()
        // A 200 KB body of valid JSON that would otherwise parse fine.
        val padding = "x".repeat(200_000)
        server.enqueue(MockResponse().setBody("""{"1.0.0-beta.14":{"en":"$padding"}}"""))
        server.start()

        val fetcher = ReleaseSummaryFetcher(OkHttpClient())
        fetcher.metaUrlForTest = server.url("/meta").toString()
        val summaries = fetcher.load().getOrThrow()

        assertTrue(
            "Body over cap must yield empty summaries",
            summaries.summaryFor("1.0.0-beta.14", "en") == null
        )
        server.shutdown()
    }

    // cso S2-1: a 4-clause phishing-style summary (≥160 chars) must be truncated
    // to MAX_SUMMARY_CHARS so the picker subtitle stays bounded and the in-memory
    // allocation per entry is capped.
    @Test
    fun `per-string summary is capped at 160 chars`() = runTest {
        val server = MockWebServer()
        val longSummary = "URGENT: install update from https://evil.example. ".repeat(10)
        server.enqueue(MockResponse().setBody("""{"1.0.0-beta.14":{"en":"$longSummary"}}"""))
        server.start()

        val fetcher = ReleaseSummaryFetcher(OkHttpClient())
        fetcher.metaUrlForTest = server.url("/meta").toString()
        val summaries = fetcher.load().getOrThrow()

        val rendered = summaries.summaryFor("1.0.0-beta.14", "en")
        assertTrue("expected truncated, got length=${rendered?.length}", (rendered?.length ?: 0) <= ReleaseSummaryFetcher.MAX_SUMMARY_CHARS)
        server.shutdown()
    }

    // S1 I2: enforce the META_URL switchback gate.
    //
    // If we ever ship a stable release (VERSION_NAME has no '-' suffix) while
    // META_URL still references the `/develop/` branch, the picker silently 404s
    // for every install. This test fails the build if that happens.
    //
    // VERSION_NAME for tests resolves to whatever build-config Robolectric loads
    // — when this assertion fires in a CI build for a stable tag, the developer
    // must flip META_URL to `/main/` per CLAUDE.md's release checklist.
    @Test
    fun `stable build must not read meta from develop branch`() {
        val versionName = com.albunyaan.tube.BuildConfig.VERSION_NAME
        if (!versionName.contains("-")) {
            assertTrue(
                "Stable release $versionName must not read releases-meta.json from /develop/. " +
                    "Flip ReleaseSummaryFetcher.META_URL to point at /main/ per the release checklist.",
                !ReleaseSummaryFetcher.META_URL.contains("/develop/")
            )
        }
    }
}
