package com.albunyaan.tube.data.source

import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.model.api.models.ContentItemDto
import com.albunyaan.tube.data.model.api.models.PageInfo
import com.albunyaan.tube.data.source.api.ContentApi
import com.albunyaan.tube.data.source.api.CursorPage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class RetrofitContentServiceTest {

    private val api: ContentApi = mock()
    private val service = RetrofitContentService(api)

    // --- VIDEO ---

    @Test
    fun `verifyAvailable VIDEO returns true when backend returns 200`() = runTest {
        whenever(api.checkVideoAvailable("ytv-1")).thenReturn(Response.success(Unit))

        assertTrue(service.verifyAvailable(AvailabilityCheckType.VIDEO, "ytv-1"))
    }

    @Test
    fun `verifyAvailable VIDEO returns true when backend returns 404 (not in registry, fail-open for channel videos)`() = runTest {
        // 404 means "video not in standalone registry" — channel-sourced videos are never
        // individually registered. Fail-open so NewPipe can resolve them via the channel path.
        whenever(api.checkVideoAvailable("ytv-1")).thenReturn(
            Response.error(404, "".toResponseBody(null))
        )

        assertTrue(service.verifyAvailable(AvailabilityCheckType.VIDEO, "ytv-1"))
    }

    @Test
    fun `verifyAvailable VIDEO returns false when backend returns 410 (explicitly admin-removed)`() = runTest {
        // 410 Gone means the video was in the registry and explicitly archived/rejected by admin.
        whenever(api.checkVideoAvailable("ytv-1")).thenReturn(
            Response.error(410, "".toResponseBody(null))
        )

        assertFalse(service.verifyAvailable(AvailabilityCheckType.VIDEO, "ytv-1"))
    }

    @Test
    fun `verifyAvailable VIDEO calls only the video endpoint`() = runTest {
        whenever(api.checkVideoAvailable("ytv-1")).thenReturn(Response.success(Unit))

        service.verifyAvailable(AvailabilityCheckType.VIDEO, "ytv-1")

        verify(api).checkVideoAvailable("ytv-1")
    }

    // --- CHANNEL ---

    @Test
    fun `verifyAvailable CHANNEL returns true when backend returns 200`() = runTest {
        whenever(api.checkChannelAvailable("UCabc")).thenReturn(Response.success(Unit))

        assertTrue(service.verifyAvailable(AvailabilityCheckType.CHANNEL, "UCabc"))
    }

    @Test
    fun `verifyAvailable CHANNEL returns true when backend returns 404 (not in registry, fail-open)`() = runTest {
        // 404 means the channel is not in the backend registry (or not yet approved).
        // Fail-open so NewPipe can resolve channels navigated to from playlists.
        whenever(api.checkChannelAvailable("UCabc")).thenReturn(
            Response.error(404, "".toResponseBody(null))
        )

        assertTrue(service.verifyAvailable(AvailabilityCheckType.CHANNEL, "UCabc"))
    }

    @Test
    fun `verifyAvailable CHANNEL returns false when backend returns 410 (explicitly admin-blocked)`() = runTest {
        // 410 Gone means the channel was explicitly archived/blocked by an admin.
        whenever(api.checkChannelAvailable("UCabc")).thenReturn(
            Response.error(410, "".toResponseBody(null))
        )

        assertFalse(service.verifyAvailable(AvailabilityCheckType.CHANNEL, "UCabc"))
    }

    @Test
    fun `verifyAvailable CHANNEL calls only the channel endpoint`() = runTest {
        whenever(api.checkChannelAvailable("UCabc")).thenReturn(Response.success(Unit))

        service.verifyAvailable(AvailabilityCheckType.CHANNEL, "UCabc")

        verify(api).checkChannelAvailable("UCabc")
    }

    // --- PLAYLIST ---

    @Test
    fun `verifyAvailable PLAYLIST returns true when backend returns 200`() = runTest {
        whenever(api.checkPlaylistAvailable("PLxyz")).thenReturn(Response.success(Unit))

        assertTrue(service.verifyAvailable(AvailabilityCheckType.PLAYLIST, "PLxyz"))
    }

    @Test
    fun `verifyAvailable PLAYLIST returns true when backend returns 404 (not in registry, fail-open)`() = runTest {
        // 404 means the playlist is not in the backend registry (e.g. fetched from a
        // channel's Playlists tab via NewPipe but not individually approved).
        // Fail-open so NewPipe can load the playlist directly.
        whenever(api.checkPlaylistAvailable("PLxyz")).thenReturn(
            Response.error(404, "".toResponseBody(null))
        )

        assertTrue(service.verifyAvailable(AvailabilityCheckType.PLAYLIST, "PLxyz"))
    }

    @Test
    fun `verifyAvailable PLAYLIST returns false when backend returns 410 (explicitly admin-blocked)`() = runTest {
        // 410 Gone means the playlist was explicitly archived/blocked by an admin.
        whenever(api.checkPlaylistAvailable("PLxyz")).thenReturn(
            Response.error(410, "".toResponseBody(null))
        )

        assertFalse(service.verifyAvailable(AvailabilityCheckType.PLAYLIST, "PLxyz"))
    }

    @Test
    fun `verifyAvailable PLAYLIST calls only the playlist endpoint`() = runTest {
        whenever(api.checkPlaylistAvailable("PLxyz")).thenReturn(Response.success(Unit))

        service.verifyAvailable(AvailabilityCheckType.PLAYLIST, "PLxyz")

        verify(api).checkPlaylistAvailable("PLxyz")
    }

    // --- ERROR propagation ---

    @Test(expected = retrofit2.HttpException::class)
    fun `verifyAvailable throws HttpException on non-200 non-404 non-410 response`() = runTest {
        whenever(api.checkVideoAvailable("ytv-err")).thenReturn(
            Response.error(500, "server error".toResponseBody(null))
        )

        service.verifyAvailable(AvailabilityCheckType.VIDEO, "ytv-err")
    }

    // --- search: X-Search-Match (spec §4.6, §6) ---

    @Test
    fun `search reads X-Search-Match and treats a missing or unknown value as EXACT`() = runTest {
        whenever(api.search("zakit", null, 50))
            .thenReturn(Response.success(emptyList(), Headers.headersOf("X-Search-Match", "approximate")))
        assertEquals(SearchMatch.APPROXIMATE, service.search("zakit", null, 50).match)

        whenever(api.search("zakir", null, 50)).thenReturn(Response.success(emptyList()))
        assertEquals(SearchMatch.EXACT, service.search("zakir", null, 50).match)

        whenever(api.search("later", null, 50))
            .thenReturn(Response.success(emptyList(), Headers.headersOf("X-Search-Match", "something-new")))
        assertEquals(SearchMatch.EXACT, service.search("later", null, 50).match)
    }

    @Test
    fun `search fails on a 2xx with no body instead of reading it as no results`() = runTest {
        whenever(api.search("quran", null, 50)).thenReturn(Response.success<List<ContentItemDto>>(204, null))
        val outcome = runCatching { service.search("quran", null, 50) }
        assertTrue("a bodyless answer read as $outcome", outcome.isFailure)
    }

    @Test
    fun `search still fails on a non-2xx answer`() = runTest {
        whenever(api.search("quran", null, 50)).thenReturn(Response.error(503, "".toResponseBody(null)))
        assertTrue(runCatching { service.search("quran", null, 50) }.exceptionOrNull() is HttpException)
    }

    // --- One automatic retry while a restarted server builds its search index (503 + Retry-After) ---

    private fun <T> unavailable(retryAfter: String): Response<T> = Response.error(
        "".toResponseBody(null),
        okhttp3.Response.Builder()
            .code(503).message("Service Unavailable").protocol(Protocol.HTTP_1_1)
            .request(Request.Builder().url("http://localhost/api/v1/search").build())
            .header("Retry-After", retryAfter)
            .build()
    )

    @Test
    fun `search waits out Retry-After on a 503 and tries once more`() = runTest {
        whenever(api.search("quran", null, 50)).thenReturn(unavailable("5"), Response.success(listOf()))

        val result = service.search("quran", null, 50)

        assertEquals(emptyList<Any>(), result.items)
        assertEquals("it did not wait the 5 s the server asked for", 5_000L, testScheduler.currentTime)
        verify(api, times(2)).search("quran", null, 50)
    }

    @Test
    fun `a second 503 is surfaced instead of retried again`() = runTest {
        whenever(api.search("quran", null, 50)).thenReturn(unavailable("5"), unavailable("5"), Response.success(listOf()))

        assertTrue(runCatching { service.search("quran", null, 50) }.exceptionOrNull() is HttpException)
        verify(api, times(2)).search("quran", null, 50)
    }

    @Test
    fun `a Retry-After over 10 s is surfaced at once`() = runTest {
        whenever(api.search("quran", null, 50)).thenReturn(unavailable("11"), Response.success(listOf()))

        assertTrue("an 11 s Retry-After was waited out", runCatching { service.search("quran", null, 50) }.exceptionOrNull() is HttpException)
        verify(api, times(1)).search("quran", null, 50)
    }

    @Test
    fun `content waits out Retry-After on a 503 and tries once more`() = runTest {
        val page = CursorPage(emptyList(), PageInfo(hasNext = false))
        whenever(api.fetchContent(eq("VIDEOS"), isNull(), eq(20), isNull(), isNull(), isNull(), isNull(), eq("quran")))
            .thenThrow(HttpException(unavailable<CursorPage>("5")))
            .thenReturn(page)

        service.fetchContent(ContentType.VIDEOS, null, 20, FilterState(), "quran")

        assertEquals(5_000L, testScheduler.currentTime)
        verify(api, times(2)).fetchContent(eq("VIDEOS"), isNull(), eq(20), isNull(), isNull(), isNull(), isNull(), eq("quran"))
    }
}
