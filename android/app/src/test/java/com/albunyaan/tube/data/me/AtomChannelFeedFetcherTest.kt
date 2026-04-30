package com.albunyaan.tube.data.me

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Network-layer regression tests for [AtomChannelFeedFetcher].
 *
 * Pinned to SDK 31 to match sibling Robolectric tests in this package.
 * Uses MockWebServer so we drive HTTP responses (200/304/429/5xx) without
 * touching YouTube.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class AtomChannelFeedFetcherTest {

    private lateinit var server: MockWebServer
    private lateinit var fetcher: AtomChannelFeedFetcher

    @Before
    fun setup() {
        server = MockWebServer().also { it.start() }
        val client = OkHttpClient.Builder().build()
        fetcher = AtomChannelFeedFetcher(
            client = client,
            parser = AtomFeedParser(),
            baseUrlOverride = server.url("/").toString(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun returns_items_on_200() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "W/\"abc\"")
                .setBody(readResource("/atom/channel-with-uploads.xml")),
        )

        val result = fetcher.fetchLatest("https://www.youtube.com/channel/UCdBK94H6oZT2Q7l0-b0xmMg")
        assertTrue("expected Items, got $result", result is ChannelFeedFetcher.FetchResult.Items)

        val items = result as ChannelFeedFetcher.FetchResult.Items
        assertEquals("W/\"abc\"", items.etag)
        assertTrue("expected non-empty parsed items", items.items.isNotEmpty())

        // The fetcher must call the channel-id endpoint, not the input URL.
        val recorded = server.takeRequest()
        assertTrue(
            "expected feeds/videos.xml URL, got ${recorded.path}",
            recorded.path?.contains("/feeds/videos.xml") == true,
        )
        assertTrue(
            "expected channel_id query param, got ${recorded.path}",
            recorded.path?.contains("channel_id=UCdBK94H6oZT2Q7l0-b0xmMg") == true,
        )
    }

    @Test
    fun returns_NotModified_on_304() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(304)
                .setHeader("ETag", "W/\"abc\""),
        )

        val result = fetcher.fetchLatest(
            channelUrl = "https://www.youtube.com/channel/UCdBK94H6oZT2Q7l0-b0xmMg",
            priorEtag = "W/\"abc\"",
        )
        assertTrue("expected NotModified, got $result", result is ChannelFeedFetcher.FetchResult.NotModified)

        val recorded = server.takeRequest()
        assertEquals(
            "must echo prior ETag in If-None-Match",
            "W/\"abc\"",
            recorded.getHeader("If-None-Match"),
        )
    }

    @Test
    fun throws_on_429() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))

        var observed: Throwable? = null
        try {
            fetcher.fetchLatest("https://www.youtube.com/channel/UCdBK94H6oZT2Q7l0-b0xmMg")
        } catch (t: Throwable) {
            observed = t
        }
        assertTrue(
            "expected IOException for 429, got ${observed?.javaClass?.simpleName}",
            observed is IOException,
        )
    }

    @Test
    fun throws_on_5xx() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))

        var observed: Throwable? = null
        try {
            fetcher.fetchLatest("https://www.youtube.com/channel/UCdBK94H6oZT2Q7l0-b0xmMg")
        } catch (t: Throwable) {
            observed = t
        }
        assertTrue(
            "expected IOException for 503, got ${observed?.javaClass?.simpleName}",
            observed is IOException,
        )
    }

    @Test
    fun throws_on_unparseable_url() = runTest {
        var observed: Throwable? = null
        try {
            fetcher.fetchLatest("https://example.com/not-a-channel-url")
        } catch (t: Throwable) {
            observed = t
        }
        assertTrue(
            "expected IllegalArgumentException, got ${observed?.javaClass?.simpleName}",
            observed is IllegalArgumentException,
        )
    }

    private fun readResource(path: String): String =
        javaClass.getResourceAsStream(path)!!.bufferedReader().readText()
}
