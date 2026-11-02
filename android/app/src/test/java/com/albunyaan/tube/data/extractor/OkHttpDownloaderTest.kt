package com.albunyaan.tube.data.extractor

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Request

/** A [BoundedGet] ends at its call timeout and refuses a body past its cap; other requests are as before. */
class OkHttpDownloaderTest {

    private val server = MockWebServer().apply { start() }
    private val downloader = OkHttpDownloader(OkHttpClient())

    @After fun tearDown() = server.shutdown()

    private fun bounded(timeoutMs: Long = 5_000, maxBytes: Long = 1_000) =
        BoundedGet(server.url("/live").toString(), mapOf("User-Agent" to listOf("t")), timeoutMs, maxBytes)

    @Test fun `a body within the cap is read`() {
        server.enqueue(MockResponse().setBody("x".repeat(1_000)))
        assertEquals(1_000, downloader.execute(bounded()).responseBody().length)
        assertEquals("t", server.takeRequest().getHeader("User-Agent"))
    }

    @Test fun `a body past the cap is an IOException`() {
        server.enqueue(MockResponse().setBody("x".repeat(1_001)))
        assertThrows(IOException::class.java) { downloader.execute(bounded()) }
    }

    /** The whole call, body included: a trickling page ends at the timeout, not at OkHttp's read timeout. */
    @Test fun `a slow body ends at the call timeout`() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(100))).throttleBody(10, 1, TimeUnit.SECONDS))
        val start = System.nanoTime()
        assertThrows(IOException::class.java) { downloader.execute(bounded(timeoutMs = 500)) }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3_000)
    }

    @Test fun `an ordinary request has no cap`() {
        server.enqueue(MockResponse().setBody("x".repeat(5_000)))
        val request = Request.newBuilder().get(server.url("/p").toString()).build()
        assertEquals(5_000, downloader.execute(request).responseBody().length)
    }
}
