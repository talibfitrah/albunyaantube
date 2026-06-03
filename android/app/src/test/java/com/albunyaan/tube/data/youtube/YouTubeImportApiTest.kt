package com.albunyaan.tube.data.youtube

import com.albunyaan.tube.data.youtube.dto.LikedVideosResponse
import com.albunyaan.tube.data.youtube.dto.PlaylistListResponse
import com.albunyaan.tube.data.youtube.dto.SubscriptionListResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * B6: Verifies DTO parsing of YouTube Data API v3 responses through the
 * Retrofit interface backed by MockWebServer. Tests realistic snippet shapes
 * including nextPageToken, channelId, title, and thumbnail URLs.
 */
class YouTubeImportApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: YouTubeImportApi

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
            .create(YouTubeImportApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── subscriptions ─────────────────────────────────────────────────────────

    @Test
    fun `subscriptions parses channelId title and thumbnail from snippet`() = runTest {
        server.enqueue(MockResponse().setBody(SUBSCRIPTIONS_PAGE_1_JSON))

        val response: SubscriptionListResponse = api.subscriptions(bearer = "Bearer test-token")

        assertEquals(2, response.items.size)

        val first = response.items[0]
        assertEquals("UCq-Fj5jknLsUf-MWSy4_brA", first.snippet.resourceId.channelId)
        assertEquals("Tech With Tim", first.snippet.title)
        assertEquals("https://yt3.ggpht.com/channel1_medium.jpg", first.snippet.thumbnails.medium?.url
            ?: first.snippet.thumbnails.default?.url)

        val second = response.items[1]
        assertEquals("UCBcRF18a7Qf58cCRy5xuWwQ", second.snippet.resourceId.channelId)
        assertEquals("3Blue1Brown", second.snippet.title)
    }

    @Test
    fun `subscriptions parses nextPageToken when present`() = runTest {
        server.enqueue(MockResponse().setBody(SUBSCRIPTIONS_PAGE_1_JSON))

        val response: SubscriptionListResponse = api.subscriptions(bearer = "Bearer test-token")

        assertEquals("CAUQAA", response.nextPageToken)
    }

    @Test
    fun `subscriptions nextPageToken is null on last page`() = runTest {
        server.enqueue(MockResponse().setBody(SUBSCRIPTIONS_LAST_PAGE_JSON))

        val response: SubscriptionListResponse = api.subscriptions(bearer = "Bearer test-token")

        assertNull(response.nextPageToken)
        assertEquals(1, response.items.size)
    }

    @Test
    fun `subscriptions request sends correct query params and Authorization header`() = runTest {
        server.enqueue(MockResponse().setBody(SUBSCRIPTIONS_LAST_PAGE_JSON))

        api.subscriptions(bearer = "Bearer my-access-token", pageToken = "NEXT_TOKEN")

        val request = server.takeRequest()
        assertEquals("Bearer my-access-token", request.getHeader("Authorization"))
        val path = request.path ?: ""
        assert(path.contains("part=snippet")) { "expected part=snippet in $path" }
        assert(path.contains("mine=true")) { "expected mine=true in $path" }
        assert(path.contains("pageToken=NEXT_TOKEN")) { "expected pageToken=NEXT_TOKEN in $path" }
    }

    // ── playlists ─────────────────────────────────────────────────────────────

    @Test
    fun `playlists parses id title and thumbnail from snippet`() = runTest {
        server.enqueue(MockResponse().setBody(PLAYLISTS_JSON))

        val response: PlaylistListResponse = api.playlists(bearer = "Bearer test-token")

        assertEquals(2, response.items.size)

        val first = response.items[0]
        assertEquals("PLrAXtmErZgOdP_8GztsuKi9nrraNbKKp4", first.id)
        assertEquals("Python Tutorials", first.snippet.title)
        assertEquals("https://yt3.ggpht.com/playlist1_medium.jpg",
            first.snippet.thumbnails.medium?.url ?: first.snippet.thumbnails.default?.url)

        val second = response.items[1]
        assertEquals("PLZHQObOWTQDPD3MizzM2xVFitgF8hE_ab", second.id)
        assertEquals("3B1B Essentials", second.snippet.title)
    }

    @Test
    fun `playlists parses nextPageToken`() = runTest {
        server.enqueue(MockResponse().setBody(PLAYLISTS_JSON))

        val response: PlaylistListResponse = api.playlists(bearer = "Bearer test-token")

        assertEquals("CBQQAA", response.nextPageToken)
    }

    // ── liked videos ──────────────────────────────────────────────────────────

    @Test
    fun `likedVideos parses id title channelId and thumbnail`() = runTest {
        server.enqueue(MockResponse().setBody(LIKED_VIDEOS_JSON))

        val response: LikedVideosResponse = api.likedVideos(bearer = "Bearer test-token")

        assertEquals(2, response.items.size)

        val first = response.items[0]
        assertEquals("dQw4w9WgXcQ", first.id)
        assertEquals("Never Gonna Give You Up", first.snippet.title)
        assertEquals("UCuAXFkgsw1L7xaCfnd5JJOw", first.snippet.channelId)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg",
            first.snippet.thumbnails.medium?.url ?: first.snippet.thumbnails.default?.url)

        val second = response.items[1]
        assertEquals("oHg5SJYRHA0", second.id)
        assertEquals("RickRoll'D", second.snippet.title)
        assertEquals("UCWwWOFUFNBXPxNEaVhBTXaA", second.snippet.channelId)
    }

    @Test
    fun `likedVideos nextPageToken is null on last page`() = runTest {
        server.enqueue(MockResponse().setBody(LIKED_VIDEOS_NO_NEXT_PAGE_JSON))

        val response: LikedVideosResponse = api.likedVideos(bearer = "Bearer test-token")

        assertNull(response.nextPageToken)
    }

    @Test
    fun `likedVideos request sends myRating=like`() = runTest {
        server.enqueue(MockResponse().setBody(LIKED_VIDEOS_NO_NEXT_PAGE_JSON))

        api.likedVideos(bearer = "Bearer tok")

        val path = server.takeRequest().path ?: ""
        assert(path.contains("myRating=like")) { "expected myRating=like in $path" }
    }

    // ── thumbnail fallback ────────────────────────────────────────────────────

    @Test
    fun `thumbnail fallback uses default when medium is absent`() = runTest {
        server.enqueue(MockResponse().setBody(SUBSCRIPTIONS_NO_MEDIUM_THUMBNAIL_JSON))

        val response: SubscriptionListResponse = api.subscriptions(bearer = "Bearer tok")

        val item = response.items[0]
        // medium is absent; the default thumbnail should still be accessible
        assertNull(item.snippet.thumbnails.medium)
        assertEquals("https://yt3.ggpht.com/default_only.jpg", item.snippet.thumbnails.default?.url)
    }

    // ── canned JSON bodies ────────────────────────────────────────────────────

    companion object {
        private val SUBSCRIPTIONS_PAGE_1_JSON = """
        {
          "items": [
            {
              "snippet": {
                "title": "Tech With Tim",
                "resourceId": { "channelId": "UCq-Fj5jknLsUf-MWSy4_brA" },
                "thumbnails": {
                  "default": { "url": "https://yt3.ggpht.com/channel1_default.jpg" },
                  "medium":  { "url": "https://yt3.ggpht.com/channel1_medium.jpg" }
                }
              }
            },
            {
              "snippet": {
                "title": "3Blue1Brown",
                "resourceId": { "channelId": "UCBcRF18a7Qf58cCRy5xuWwQ" },
                "thumbnails": {
                  "default": { "url": "https://yt3.ggpht.com/channel2_default.jpg" },
                  "medium":  { "url": "https://yt3.ggpht.com/channel2_medium.jpg" }
                }
              }
            }
          ],
          "nextPageToken": "CAUQAA"
        }
        """.trimIndent()

        private val SUBSCRIPTIONS_LAST_PAGE_JSON = """
        {
          "items": [
            {
              "snippet": {
                "title": "Kurzgesagt",
                "resourceId": { "channelId": "UCsXVk37bltHxD1rDPwtNM8Q" },
                "thumbnails": {
                  "default": { "url": "https://yt3.ggpht.com/channel3_default.jpg" },
                  "medium":  { "url": "https://yt3.ggpht.com/channel3_medium.jpg" }
                }
              }
            }
          ]
        }
        """.trimIndent()

        private val SUBSCRIPTIONS_NO_MEDIUM_THUMBNAIL_JSON = """
        {
          "items": [
            {
              "snippet": {
                "title": "No Medium Channel",
                "resourceId": { "channelId": "UC_no_medium_001" },
                "thumbnails": {
                  "default": { "url": "https://yt3.ggpht.com/default_only.jpg" }
                }
              }
            }
          ]
        }
        """.trimIndent()

        private val PLAYLISTS_JSON = """
        {
          "items": [
            {
              "id": "PLrAXtmErZgOdP_8GztsuKi9nrraNbKKp4",
              "snippet": {
                "title": "Python Tutorials",
                "thumbnails": {
                  "default": { "url": "https://yt3.ggpht.com/playlist1_default.jpg" },
                  "medium":  { "url": "https://yt3.ggpht.com/playlist1_medium.jpg" }
                }
              }
            },
            {
              "id": "PLZHQObOWTQDPD3MizzM2xVFitgF8hE_ab",
              "snippet": {
                "title": "3B1B Essentials",
                "thumbnails": {
                  "default": { "url": "https://yt3.ggpht.com/playlist2_default.jpg" },
                  "medium":  { "url": "https://yt3.ggpht.com/playlist2_medium.jpg" }
                }
              }
            }
          ],
          "nextPageToken": "CBQQAA"
        }
        """.trimIndent()

        private val LIKED_VIDEOS_JSON = """
        {
          "items": [
            {
              "id": "dQw4w9WgXcQ",
              "snippet": {
                "title": "Never Gonna Give You Up",
                "channelId": "UCuAXFkgsw1L7xaCfnd5JJOw",
                "thumbnails": {
                  "default": { "url": "https://i.ytimg.com/vi/dQw4w9WgXcQ/default.jpg" },
                  "medium":  { "url": "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg" }
                }
              }
            },
            {
              "id": "oHg5SJYRHA0",
              "snippet": {
                "title": "RickRoll'D",
                "channelId": "UCWwWOFUFNBXPxNEaVhBTXaA",
                "thumbnails": {
                  "default": { "url": "https://i.ytimg.com/vi/oHg5SJYRHA0/default.jpg" },
                  "medium":  { "url": "https://i.ytimg.com/vi/oHg5SJYRHA0/mqdefault.jpg" }
                }
              }
            }
          ],
          "nextPageToken": "CDsQAA"
        }
        """.trimIndent()

        private val LIKED_VIDEOS_NO_NEXT_PAGE_JSON = """
        {
          "items": [
            {
              "id": "abc123XYZ",
              "snippet": {
                "title": "Last Liked Video",
                "channelId": "UCLastChannel0001",
                "thumbnails": {
                  "default": { "url": "https://i.ytimg.com/vi/abc123XYZ/default.jpg" }
                }
              }
            }
          ]
        }
        """.trimIndent()
    }
}
