package com.albunyaan.tube.data.source

import com.albunyaan.tube.data.extractor.MetadataHydrator
import com.albunyaan.tube.data.source.api.ContentApi
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import retrofit2.Response

class RetrofitContentServiceTest {

    private val api: ContentApi = mock()
    private val metadataHydrator: MetadataHydrator = mock()
    private val service = RetrofitContentService(api, metadataHydrator)

    // --- VIDEO ---

    @Test
    fun `verifyAvailable VIDEO returns true when backend returns 200`() = runTest {
        whenever(api.checkVideoAvailable("ytv-1")).thenReturn(Response.success(Unit))

        assertTrue(service.verifyAvailable(AvailabilityCheckType.VIDEO, "ytv-1"))
    }

    @Test
    fun `verifyAvailable VIDEO returns false when backend returns 404`() = runTest {
        whenever(api.checkVideoAvailable("ytv-1")).thenReturn(
            Response.error(404, "".toResponseBody(null))
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
    fun `verifyAvailable CHANNEL returns false when backend returns 404`() = runTest {
        whenever(api.checkChannelAvailable("UCabc")).thenReturn(
            Response.error(404, "".toResponseBody(null))
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
    fun `verifyAvailable PLAYLIST returns false when backend returns 404`() = runTest {
        whenever(api.checkPlaylistAvailable("PLxyz")).thenReturn(
            Response.error(404, "".toResponseBody(null))
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
    fun `verifyAvailable throws HttpException on non-200 non-404 response`() = runTest {
        whenever(api.checkVideoAvailable("ytv-err")).thenReturn(
            Response.error(500, "server error".toResponseBody(null))
        )

        service.verifyAvailable(AvailabilityCheckType.VIDEO, "ytv-err")
    }
}
