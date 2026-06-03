package com.albunyaan.tube.data.importflow

import com.albunyaan.tube.data.importflow.dto.ImportItemDto
import com.albunyaan.tube.data.importflow.dto.ImportResolveRequestDto
import com.albunyaan.tube.data.importflow.dto.ImportResolveResponseDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * B8: Verifies the ImportApi Retrofit interface + ImportDtos round-trip through
 * MockWebServer — request serialisation and response deserialisation.
 */
class ImportApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ImportApi

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(ImportApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── request serialisation ─────────────────────────────────────────────────

    @Test
    fun `resolve sends POST to correct path with items array`() = runTest {
        server.enqueue(MockResponse().setBody(MIXED_RESPONSE_JSON))

        val request = ImportResolveRequestDto(
            items = listOf(
                ImportItemDto(
                    type = "CHANNEL",
                    youtubeId = "UCq-Fj5jknLsUf-MWSy4_brA",
                    title = "Tech With Tim",
                    thumbnailUrl = "https://yt3.ggpht.com/thumb.jpg",
                    channelId = null,
                ),
                ImportItemDto(
                    type = "VIDEO",
                    youtubeId = "dQw4w9WgXcQ",
                    title = "Never Gonna Give You Up",
                    thumbnailUrl = null,
                    channelId = "UCuAXFkgsw1L7xaCfnd5JJOw",
                ),
            )
        )

        api.resolve(request)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/account/import/resolve", recorded.path)

        val body = recorded.body.readUtf8()
        // items array present with both entries
        assert(body.contains("\"items\"")) { "body missing 'items': $body" }
        assert(body.contains("\"UCq-Fj5jknLsUf-MWSy4_brA\"")) { "body missing channel youtubeId: $body" }
        assert(body.contains("\"dQw4w9WgXcQ\"")) { "body missing video youtubeId: $body" }
        assert(body.contains("\"CHANNEL\"")) { "body missing type CHANNEL: $body" }
        assert(body.contains("\"VIDEO\"")) { "body missing type VIDEO: $body" }
        assert(body.contains("\"Tech With Tim\"")) { "body missing title: $body" }
    }

    @Test
    fun `resolve request serialises all ImportItemDto fields correctly`() = runTest {
        server.enqueue(MockResponse().setBody(MIXED_RESPONSE_JSON))

        val item = ImportItemDto(
            type = "PLAYLIST",
            youtubeId = "PLrAXtmErZgOdP_8GztsuKi9nrraNbKKp4",
            title = "Python Tutorials",
            thumbnailUrl = "https://yt3.ggpht.com/playlist.jpg",
            channelId = "UCq-Fj5jknLsUf-MWSy4_brA",
        )
        api.resolve(ImportResolveRequestDto(items = listOf(item)))

        val body = server.takeRequest().body.readUtf8()
        assert(body.contains("\"PLAYLIST\"")) { "missing type: $body" }
        assert(body.contains("\"PLrAXtmErZgOdP_8GztsuKi9nrraNbKKp4\"")) { "missing youtubeId: $body" }
        assert(body.contains("\"Python Tutorials\"")) { "missing title: $body" }
        assert(body.contains("\"https://yt3.ggpht.com/playlist.jpg\"")) { "missing thumbnailUrl: $body" }
        assert(body.contains("\"UCq-Fj5jknLsUf-MWSy4_brA\"")) { "missing channelId: $body" }
    }

    // ── response deserialisation ──────────────────────────────────────────────

    @Test
    fun `resolve parses mixed dispositions correctly`() = runTest {
        server.enqueue(MockResponse().setBody(MIXED_RESPONSE_JSON))

        val response: ImportResolveResponseDto = api.resolve(
            ImportResolveRequestDto(items = emptyList())
        )

        assertEquals(3, response.results.size)
    }

    @Test
    fun `resolve APPROVED result carries content object`() = runTest {
        server.enqueue(MockResponse().setBody(MIXED_RESPONSE_JSON))

        val response: ImportResolveResponseDto = api.resolve(
            ImportResolveRequestDto(items = emptyList())
        )

        val approved = response.results.first { it.disposition == "APPROVED" }
        assertEquals("UCq-Fj5jknLsUf-MWSy4_brA", approved.youtubeId)
        assertEquals("CHANNEL", approved.type)
        assertNotNull("APPROVED result must carry content", approved.content)
        // Verify a field on the ContentItemDto
        assertEquals("Tech With Tim", approved.content!!.name)
    }

    @Test
    fun `resolve PENDING result has null content`() = runTest {
        server.enqueue(MockResponse().setBody(MIXED_RESPONSE_JSON))

        val response: ImportResolveResponseDto = api.resolve(
            ImportResolveRequestDto(items = emptyList())
        )

        val pending = response.results.first { it.disposition == "PENDING" }
        assertEquals("PLrAXtmErZgOdP_8GztsuKi9nrraNbKKp4", pending.youtubeId)
        assertEquals("PLAYLIST", pending.type)
        assertNull("PENDING result must have null content", pending.content)
    }

    @Test
    fun `resolve REJECTED result has null content`() = runTest {
        server.enqueue(MockResponse().setBody(MIXED_RESPONSE_JSON))

        val response: ImportResolveResponseDto = api.resolve(
            ImportResolveRequestDto(items = emptyList())
        )

        val rejected = response.results.first { it.disposition == "REJECTED" }
        assertEquals("dQw4w9WgXcQ", rejected.youtubeId)
        assertEquals("VIDEO", rejected.type)
        assertNull("REJECTED result must have null content", rejected.content)
    }

    @Test
    fun `resolve APPROVED content has correct ContentItemDto type enum`() = runTest {
        server.enqueue(MockResponse().setBody(MIXED_RESPONSE_JSON))

        val response: ImportResolveResponseDto = api.resolve(
            ImportResolveRequestDto(items = emptyList())
        )

        val approved = response.results.first { it.disposition == "APPROVED" }
        // ContentItemDto.Type enum must deserialise correctly
        assertEquals(
            com.albunyaan.tube.data.model.api.models.ContentItemDto.Type.CHANNEL,
            approved.content!!.type,
        )
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    companion object {
        /**
         * Three results: APPROVED (with content), PENDING (null content), REJECTED (null content).
         * Content shape mirrors ContentItemDto as served by /api/v1 content endpoints.
         */
        private val MIXED_RESPONSE_JSON = """
        {
          "results": [
            {
              "youtubeId": "UCq-Fj5jknLsUf-MWSy4_brA",
              "type": "CHANNEL",
              "disposition": "APPROVED",
              "content": {
                "id": "firestore-doc-001",
                "type": "CHANNEL",
                "name": "Tech With Tim",
                "thumbnailUrl": "https://yt3.ggpht.com/thumb.jpg",
                "subscribers": 1200000,
                "videoCount": 350
              }
            },
            {
              "youtubeId": "PLrAXtmErZgOdP_8GztsuKi9nrraNbKKp4",
              "type": "PLAYLIST",
              "disposition": "PENDING",
              "content": null
            },
            {
              "youtubeId": "dQw4w9WgXcQ",
              "type": "VIDEO",
              "disposition": "REJECTED",
              "content": null
            }
          ]
        }
        """.trimIndent()
    }
}
