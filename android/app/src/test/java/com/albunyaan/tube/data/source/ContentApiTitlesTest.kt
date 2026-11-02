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
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/** Owner issue 1(b): `GET /api/v1/titles` through the real Retrofit + Moshi stack (a mock would hide a Map decode surprise). */
class ContentApiTitlesTest {

    private val server = MockWebServer().apply { start() }
    private val api = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .client(OkHttpClient())
        .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()))
        .build()
        .create(ContentApi::class.java)

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `fetchTitles GETs api v1 titles with the ids comma-joined and decodes the map`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Cache-Control", "public, max-age=3600")
                .setBody("""{"lesson00001":"زبدة النور: الدرس الأول","lesson00002":"Lesson 2"}""")
        )

        val response = api.fetchTitles("lesson00001,lesson00002")

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/titles", recorded.requestUrl!!.encodedPath)
        assertEquals("lesson00001,lesson00002", recorded.requestUrl!!.queryParameter("ids"))
        assertEquals(mapOf("lesson00001" to "زبدة النور: الدرس الأول", "lesson00002" to "Lesson 2"), response.body())
        assertEquals(3600, response.raw().cacheControl.maxAgeSeconds)
    }
}
