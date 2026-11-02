package com.albunyaan.tube.data.extractor

import com.albunyaan.tube.data.source.api.LiveItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response

/** Spec §9.1, on device: a channel's /live page names its current live in the canonical link. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LiveChannelResolverTest {

    private val channel = "UCOll3M-P7oKs5cSrQ9ytt6g"
    /** The test's own scheduler: an instant fake answers in virtual time. */
    private val TestScope.io get() = StandardTestDispatcher(testScheduler)

    /** The player response's videoDetails, in the 2026-11-02 probe's key order (a keywords array may sit before channelId). */
    private fun details(id: String, ch: String = channel, live: String = "\"isLive\":true") =
        """{"videoDetails":{"videoId":"$id","title":"t","lengthSeconds":"0",$live,"keywords":["a","b"],"channelId":"$ch","shortDescription":"d"}}"""

    // Hand-built from the tags of the 2026-11-02 pages (the real ones are ~1.3 MB).
    private val livePage = """<!DOCTYPE html><html><head><title>ZAD TV - YouTube</title>
        <meta name="title" content="البث المباشر لقناة زاد الفضائية | ZAD TV Channel Live">
        <link rel="canonical" href="https://www.youtube.com/watch?v=D4Vhq0AJAUs"></head>
        <body><script>var ytInitialPlayerResponse = ${details("D4Vhq0AJAUs")};</script></body></html>"""
    private val ogPage = """<html><head><meta property="og:title" content="Tafsir &amp; Q&#39;A &quot;live&quot;">
        <meta name="title" content="other"><link rel="canonical" href="https://www.youtube.com/watch?v=eC4LfEVxvKg"></head><body>${details("eC4LfEVxvKg")}</body></html>"""
    private val untitledPage = """<html><head><link rel="canonical" href="https://www.youtube.com/watch?v=D4Vhq0AJAUs"></head><body>${details("D4Vhq0AJAUs")}</body></html>"""
    /** A scheduled stream's waiting room: a watch canonical, but the player response is upcoming, not live. */
    private val upcomingPage = """<html><head><link rel="canonical" href="https://www.youtube.com/watch?v=eC4LfEVxvKg"></head>
        <body>${details("eC4LfEVxvKg", live = "\"isUpcoming\":true")}</body></html>"""
    /** Another channel's live (a featured or redirected stream): not this channel's. */
    private val otherChannelPage = """<html><head><link rel="canonical" href="https://www.youtube.com/watch?v=D4Vhq0AJAUs"></head>
        <body>${details("D4Vhq0AJAUs", ch = "UCROKYPep-UuODNwyipe6JMw")}</body></html>"""
    /** "isLive":true elsewhere on the page (a live shelf), but the player response is not live. */
    private val strayIsLivePage = """<html><head><link rel="canonical" href="https://www.youtube.com/watch?v=D4Vhq0AJAUs"></head>
        <body>${details("D4Vhq0AJAUs", live = "\"isLiveContent\":false")}{"isLive":true}</body></html>"""
    private val notLivePage = """<html><head><meta property="og:title" content="ZAD TV">
        <link rel="canonical" href="https://www.youtube.com/channel/$channel"></head></html>"""
    private val consentPage = """<html><head><title>Before you continue to YouTube</title></head>
        <body><form action="https://consent.youtube.com/save"></form></body></html>"""
    private val otherCanonicalPage = """<html><head><link rel="canonical" href="https://consent.youtube.com/m?continue=x"></head></html>"""

    private class Page(private val code: Int, private val body: String) : Downloader() {
        val requests = mutableListOf<Request>()
        override fun execute(request: Request): Response {
            requests += request
            return Response(code, "", emptyMap(), body, request.url())
        }
    }

    @Test fun `a live page names the live and its title`() {
        assertEquals(listOf(LiveItem("D4Vhq0AJAUs", "البث المباشر لقناة زاد الفضائية | ZAD TV Channel Live")),
            LiveChannelResolver.parse(channel, livePage))
    }

    @Test fun `og title comes first and is unescaped`() {
        assertEquals(listOf(LiveItem("eC4LfEVxvKg", "Tafsir & Q'A \"live\"")), LiveChannelResolver.parse(channel, ogPage))
    }

    @Test fun `no title is the backend's empty title`() {
        assertEquals(listOf(LiveItem("D4Vhq0AJAUs", "")), LiveChannelResolver.parse(channel, untitledPage))
    }

    @Test fun `the channel's own canonical is not live`() {
        assertEquals(emptyList<LiveItem>(), LiveChannelResolver.parse(channel, notLivePage))
    }

    @Test fun `an upcoming waiting room is no answer`() {
        assertNull(LiveChannelResolver.parse(channel, upcomingPage))
    }

    @Test fun `another channel's live is no answer`() {
        assertNull(LiveChannelResolver.parse(channel, otherChannelPage))
    }

    @Test fun `isLive outside the player's videoDetails is no answer`() {
        assertNull(LiveChannelResolver.parse(channel, strayIsLivePage))
    }

    @Test fun `videoDetails for another id is no answer`() {
        assertNull(LiveChannelResolver.parse(channel, livePage.replace("\"videoId\":\"D4Vhq0AJAUs\"", "\"videoId\":\"eC4LfEVxvKg\"")))
    }

    /** The segment ends at shortDescription within 64 KB of its start; anything else is no answer. */
    @Test fun `videoDetails without an end in 64 KB is no answer`() {
        assertNull(LiveChannelResolver.parse(channel, livePage.replace("\"shortDescription\"", "\"other\"")))
        val far = livePage.replace("\"keywords\":[\"a\",\"b\"]", "\"keywords\":[\"${"k".repeat(64 * 1024)}\"]")
        assertNull(LiveChannelResolver.parse(channel, far))
    }

    @Test fun `a consent page, another canonical or garbage is no answer`() {
        assertNull(LiveChannelResolver.parse(channel, consentPage))
        assertNull(LiveChannelResolver.parse(channel, otherCanonicalPage))
        assertNull(LiveChannelResolver.parse(channel, "\u0000garbage"))
    }

    @Test fun `the live page is fetched with a desktop browser's headers`() = runTest {
        val page = Page(200, livePage)
        assertEquals("D4Vhq0AJAUs", LiveChannelResolver(page, io).lives(channel)?.single()?.id)
        val request = page.requests.single()
        assertEquals("https://www.youtube.com/channel/$channel/live", request.url())
        assertTrue(request.headers()["User-Agent"]!!.single().contains("Firefox"))
        assertEquals(listOf("en"), request.headers()["Accept-Language"])
        assertTrue(request.headers()["Cookie"]!!.single().startsWith("SOCS="))
    }

    /** A recovery the user waits on: PLAYER, so the background cooldown never gates it (spec D1). */
    @Test fun `the page is fetched at player priority`() = runTest {
        val seen = mutableListOf<Priority>()
        val page = object : Downloader() {
            override fun execute(request: Request): Response {
                seen += NewPipePriorityContext.currentOrDefault()
                return Response(200, "", emptyMap(), livePage, request.url())
            }
        }
        assertEquals("D4Vhq0AJAUs", LiveChannelResolver(page, io).lives(channel)?.single()?.id)
        assertEquals(listOf(Priority.PLAYER), seen)
    }

    /** The download itself ends at the 5 s budget and stops past 4 MB (OkHttpDownloader enforces a [BoundedGet]). */
    @Test fun `the page is a bounded get`() = runTest {
        val page = Page(200, livePage)
        LiveChannelResolver(page, io).lives(channel)
        val request = page.requests.single() as BoundedGet
        assertEquals("GET", request.httpMethod())
        assertEquals(5_000L, request.callTimeoutMs)
        assertEquals(4L * 1024 * 1024, request.maxBytes)
    }

    @Test fun `a non-2xx or a network error is no answer`() = runTest {
        assertNull(LiveChannelResolver(Page(429, livePage), io).lives(channel))
        val offline = object : Downloader() {
            override fun execute(request: Request): Response = throw java.io.IOException("offline")
        }
        assertNull(LiveChannelResolver(offline, io).lives(channel))
    }

    @Test fun `a malformed channel id is never fetched`() = runTest {
        val page = Page(200, livePage)
        assertNull(LiveChannelResolver(page, io).lives("UC../../x"))
        assertTrue(page.requests.isEmpty())
    }

    /** The backend fallback must still fit the recovery's budget: a page that hangs is no answer after 5 s. */
    @Test fun `a hanging page is no answer after 5 s`() = runTest {
        val gate = java.util.concurrent.CountDownLatch(1)
        val hanging = object : Downloader() {
            override fun execute(request: Request): Response { gate.await(); throw java.io.IOException("released") }
        }
        try {
            val start = currentTime
            assertNull(LiveChannelResolver(hanging, Dispatchers.IO).lives(channel))
            assertEquals(5_000L, currentTime - start)
        } finally {
            gate.countDown()
        }
    }
}
