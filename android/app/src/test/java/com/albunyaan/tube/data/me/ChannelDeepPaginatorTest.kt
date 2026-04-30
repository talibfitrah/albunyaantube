package com.albunyaan.tube.data.me

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * ANDROID-PERSONAL-03 / T1: tests the [ChannelDeepPaginator] in isolation
 * by injecting a fake [ChannelDeepPaginator.PageProvider]. The fake skips
 * NewPipe entirely so we can drive (a) initial-page success, (b) subsequent
 * page progression, (c) end-of-channel detection, and (d) error mapping
 * without going near the network or NewPipe.init().
 */
class ChannelDeepPaginatorTest {

    @Test
    fun `initial page returns items and next page token`() = runTest {
        val fake = FakePageProvider(
            pages = listOf(
                ChannelDeepPaginator.PageProvider.Raw(
                    items = listOf(
                        infoItem("https://www.youtube.com/watch?v=AAAAAAAAAAA", "title-1"),
                        infoItem("https://www.youtube.com/watch?v=BBBBBBBBBBB", "title-2"),
                    ),
                    nextPage = Page("https://www.youtube.com/continuation/2"),
                ),
            ),
        )
        val paginator = ChannelDeepPaginator(newPipeInit = null, pageProvider = fake)

        val result = paginator.fetchNextPage("https://yt/UC1", nextPageToken = null)

        assertTrue("expected Page, got $result", result is ChannelDeepPaginator.DeepPageResult.Page)
        val page = result as ChannelDeepPaginator.DeepPageResult.Page
        assertEquals(listOf("AAAAAAAAAAA", "BBBBBBBBBBB"), page.items.map { it.videoId })
        assertNotNull(page.nextPage)
        assertEquals("https://www.youtube.com/continuation/2", page.nextPage!!.url)
        // Fake observed null page on first call (no token).
        assertEquals(listOf<Page?>(null), fake.calls.map { it.second })
    }

    @Test
    fun `subsequent call advances the page`() = runTest {
        val first = Page("https://www.youtube.com/continuation/2")
        val fake = FakePageProvider(
            pages = listOf(
                ChannelDeepPaginator.PageProvider.Raw(
                    items = listOf(
                        infoItem("https://www.youtube.com/watch?v=AAAAAAAAAAA", "1"),
                    ),
                    nextPage = first,
                ),
                ChannelDeepPaginator.PageProvider.Raw(
                    items = listOf(
                        infoItem("https://www.youtube.com/watch?v=BBBBBBBBBBB", "2"),
                    ),
                    nextPage = null,
                ),
            ),
        )
        val paginator = ChannelDeepPaginator(newPipeInit = null, pageProvider = fake)

        val r1 = paginator.fetchNextPage("https://yt/UC1", null)
        val token = (r1 as ChannelDeepPaginator.DeepPageResult.Page).nextPage
        assertNotNull(token)

        val r2 = paginator.fetchNextPage("https://yt/UC1", token)
        assertTrue("expected Page, got $r2", r2 is ChannelDeepPaginator.DeepPageResult.Page)
        val page2 = r2 as ChannelDeepPaginator.DeepPageResult.Page
        assertEquals(listOf("BBBBBBBBBBB"), page2.items.map { it.videoId })
        assertNull("last page must have null nextPage", page2.nextPage)
        // Fake saw the right Page object on the second call.
        val secondCallPage = fake.calls[1].second
        assertNotNull(secondCallPage)
        assertEquals(first.url, secondCallPage!!.url)
    }

    @Test
    fun `empty page with no next returns EndOfChannel`() = runTest {
        val fake = FakePageProvider(
            pages = listOf(
                ChannelDeepPaginator.PageProvider.Raw(items = emptyList(), nextPage = null),
            ),
        )
        val paginator = ChannelDeepPaginator(newPipeInit = null, pageProvider = fake)

        val result = paginator.fetchNextPage("https://yt/UC1", null)
        assertTrue("expected EndOfChannel, got $result", result is ChannelDeepPaginator.DeepPageResult.EndOfChannel)
    }

    @Test
    fun `network failure returns Error with reason`() = runTest {
        val fake = FakePageProvider(throwOnCall = java.io.IOException("network down"))
        val paginator = ChannelDeepPaginator(newPipeInit = null, pageProvider = fake)

        val result = paginator.fetchNextPage("https://yt/UC1", null)
        assertTrue("expected Error, got $result", result is ChannelDeepPaginator.DeepPageResult.Error)
        assertEquals("network down", (result as ChannelDeepPaginator.DeepPageResult.Error).reason)
    }

    @Test
    fun `items with unparseable urls are filtered out`() = runTest {
        val fake = FakePageProvider(
            pages = listOf(
                ChannelDeepPaginator.PageProvider.Raw(
                    items = listOf(
                        infoItem("https://www.youtube.com/watch?v=AAAAAAAAAAA", "1"),
                        // No 11-char id available — must be dropped.
                        infoItem("https://example.com/not-a-video", "junk"),
                        infoItem("https://www.youtube.com/watch?v=CCCCCCCCCCC", "3"),
                    ),
                    nextPage = null,
                ),
            ),
        )
        val paginator = ChannelDeepPaginator(newPipeInit = null, pageProvider = fake)

        val result = paginator.fetchNextPage("https://yt/UC1", null)
        val page = result as ChannelDeepPaginator.DeepPageResult.Page
        assertEquals(listOf("AAAAAAAAAAA", "CCCCCCCCCCC"), page.items.map { it.videoId })
    }

    @Test
    fun `serialized page round-trips via toPage and back`() {
        val original = ChannelDeepPaginator.SerializedPage(
            url = "https://www.youtube.com/continuation/abc",
            id = null,
            ids = null,
            cookies = mapOf("CONSENT" to "YES+1"),
            body = "{\"continuation\":\"opaque-token\"}".toByteArray(),
        )
        val newPipePage = original.toPage()
        val roundTripped = ChannelDeepPaginator.SerializedPage.fromPage(newPipePage)
        assertEquals(original, roundTripped)
    }

    @Test
    fun `serialized page with no cookies maps to null on round trip`() {
        val original = ChannelDeepPaginator.SerializedPage(
            url = "https://www.youtube.com/continuation/abc",
            id = null,
            ids = null,
            cookies = null,
            body = null,
        )
        val newPipePage = original.toPage()
        val roundTripped = ChannelDeepPaginator.SerializedPage.fromPage(newPipePage)
        assertEquals(null, roundTripped.cookies)
        assertEquals(null, roundTripped.body)
    }

    /**
     * Construct a [StreamInfoItem] suitable for tests. NewPipe sets URL +
     * name via constructor; thumbnails default to an empty list.
     */
    private fun infoItem(url: String, name: String): StreamInfoItem {
        return StreamInfoItem(
            ServiceList.YouTube.serviceId,
            url,
            name,
            StreamType.VIDEO_STREAM,
        )
    }

    /**
     * Fake [ChannelDeepPaginator.PageProvider] that returns a queue of
     * canned pages and records the (channelUrl, page) of every call.
     */
    private class FakePageProvider(
        private val pages: List<ChannelDeepPaginator.PageProvider.Raw> = emptyList(),
        private val throwOnCall: Throwable? = null,
    ) : ChannelDeepPaginator.PageProvider {
        val calls = mutableListOf<Pair<String, Page?>>()
        private var index = 0

        override suspend fun fetch(
            channelUrl: String,
            page: Page?,
        ): ChannelDeepPaginator.PageProvider.Raw {
            calls += channelUrl to page
            throwOnCall?.let { throw it }
            val raw = pages.getOrNull(index)
                ?: ChannelDeepPaginator.PageProvider.Raw(emptyList(), null)
            index += 1
            return raw
        }
    }
}
