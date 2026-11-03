package com.albunyaan.tube.data.source

import com.albunyaan.tube.data.source.api.ContentApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/** `GET /api/v1/live/links/{videoId}` through the real Retrofit stack: a 204 has no body and must not throw. */
class ContentApiLiveLinkTest {

    private val server = MockWebServer().apply { start() }
    private val api = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .client(OkHttpClient())
        .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()))
        .build()
        .create(ContentApi::class.java)
    private val service = RetrofitContentService(api, Exclusions(api))

    @After fun tearDown() = server.shutdown()

    @Test fun `204 is served`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        service.checkLiveLink("Rs7St51oDDc")

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/live/links/Rs7St51oDDc", recorded.requestUrl!!.encodedPath)
    }

    @Test fun `404 and 503 surface as their HTTP codes`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "30"))

        assertEquals(404, (runCatching { service.checkLiveLink("a") }.exceptionOrNull() as HttpException).code())
        assertEquals(503, (runCatching { service.checkLiveLink("a") }.exceptionOrNull() as HttpException).code())
    }
}
