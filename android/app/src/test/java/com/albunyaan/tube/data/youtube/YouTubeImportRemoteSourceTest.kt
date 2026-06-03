package com.albunyaan.tube.data.youtube

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * B7: Verifies YouTubeImportRemoteSource pagination and per-type failure
 * isolation behaviour using MockWebServer.
 */
class YouTubeImportRemoteSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: YouTubeImportRemoteSource

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(YouTubeImportApi::class.java)
        source = YouTubeImportRemoteSource(api)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── multi-page subscriptions collected in full ─────────────────────────

    @Test
    fun `fetchAll collects subscriptions across two pages`() = runTest {
        // Page 1: two channels, has nextPageToken
        server.enqueue(MockResponse().setBody(SUBS_PAGE_1_JSON))
        // Page 2: one channel, no nextPageToken
        server.enqueue(MockResponse().setBody(SUBS_PAGE_2_JSON))
        // Playlists: empty last page
        server.enqueue(MockResponse().setBody(EMPTY_PLAYLISTS_JSON))
        // Liked videos: empty last page
        server.enqueue(MockResponse().setBody(EMPTY_VIDEOS_JSON))

        val result = source.fetchAll("access-token-123")

        assertTrue("failedTypes must be empty on full success", result.failedTypes.isEmpty())

        val channels = result.candidates.filter { it.type == CandidateType.CHANNEL }
        assertEquals("expected 3 channels across 2 pages", 3, channels.size)

        assertEquals("UCq-Fj5jknLsUf-MWSy4_brA", channels[0].youtubeId)
        assertEquals("Tech With Tim", channels[0].title)

        assertEquals("UCBcRF18a7Qf58cCRy5xuWwQ", channels[1].youtubeId)
        assertEquals("3Blue1Brown", channels[1].title)

        assertEquals("UCsXVk37bltHxD1rDPwtNM8Q", channels[2].youtubeId)
        assertEquals("Kurzgesagt", channels[2].title)
    }

    @Test
    fun `fetchAll sends Bearer token correctly on subscription pages`() = runTest {
        server.enqueue(MockResponse().setBody(SUBS_PAGE_2_JSON))   // subs last page
        server.enqueue(MockResponse().setBody(EMPTY_PLAYLISTS_JSON))
        server.enqueue(MockResponse().setBody(EMPTY_VIDEOS_JSON))

        source.fetchAll("my-oauth-token")

        val subsRequest = server.takeRequest()
        assertEquals("Bearer my-oauth-token", subsRequest.getHeader("Authorization"))
    }

    // ── per-type failure isolation ─────────────────────────────────────────

    @Test
    fun `fetchAll returns channels and playlists when likedVideos returns 403`() = runTest {
        // Subscriptions: one channel
        server.enqueue(MockResponse().setBody(SUBS_PAGE_2_JSON))
        // Playlists: one playlist
        server.enqueue(MockResponse().setBody(ONE_PLAYLIST_JSON))
        // Liked videos: 403 (scope not granted by user)
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":403,"message":"The caller does not have permission"}}"""))

        val result = source.fetchAll("access-token")

        // VIDEO must be in failedTypes
        assertTrue(
            "failedTypes must contain VIDEO on 403",
            result.failedTypes.contains(CandidateType.VIDEO)
        )

        // CHANNEL and PLAYLIST must NOT be in failedTypes
        assertTrue("CHANNEL must not be in failedTypes", !result.failedTypes.contains(CandidateType.CHANNEL))
        assertTrue("PLAYLIST must not be in failedTypes", !result.failedTypes.contains(CandidateType.PLAYLIST))

        val channels = result.candidates.filter { it.type == CandidateType.CHANNEL }
        val playlists = result.candidates.filter { it.type == CandidateType.PLAYLIST }

        assertEquals("expected 1 channel", 1, channels.size)
        assertEquals("UCsXVk37bltHxD1rDPwtNM8Q", channels[0].youtubeId)

        assertEquals("expected 1 playlist", 1, playlists.size)
        assertEquals("PLrAXtmErZgOdP_8GztsuKi9nrraNbKKp4", playlists[0].youtubeId)
    }

    @Test
    fun `fetchAll still returns results from other types when subscriptions throw`() = runTest {
        // Subscriptions: 500 server error
        server.enqueue(MockResponse().setResponseCode(500))
        // Playlists: one playlist
        server.enqueue(MockResponse().setBody(ONE_PLAYLIST_JSON))
        // Liked videos: one video
        server.enqueue(MockResponse().setBody(ONE_VIDEO_JSON))

        val result = source.fetchAll("access-token")

        assertTrue("CHANNEL must be in failedTypes", result.failedTypes.contains(CandidateType.CHANNEL))
        assertTrue("PLAYLIST must not be in failedTypes", !result.failedTypes.contains(CandidateType.PLAYLIST))
        assertTrue("VIDEO must not be in failedTypes", !result.failedTypes.contains(CandidateType.VIDEO))

        assertEquals(1, result.candidates.filter { it.type == CandidateType.PLAYLIST }.size)
        assertEquals(1, result.candidates.filter { it.type == CandidateType.VIDEO }.size)
    }

    @Test
    fun `fetchAll maps playlist type and youtubeId correctly`() = runTest {
        server.enqueue(MockResponse().setBody(SUBS_PAGE_2_JSON))
        server.enqueue(MockResponse().setBody(ONE_PLAYLIST_JSON))
        server.enqueue(MockResponse().setBody(EMPTY_VIDEOS_JSON))

        val result = source.fetchAll("tok")

        val playlists = result.candidates.filter { it.type == CandidateType.PLAYLIST }
        assertEquals(1, playlists.size)
        assertEquals(CandidateType.PLAYLIST, playlists[0].type)
        assertEquals("PLrAXtmErZgOdP_8GztsuKi9nrraNbKKp4", playlists[0].youtubeId)
        assertEquals("Python Tutorials", playlists[0].title)
        assertNullOrNotBlank(playlists[0].thumbnailUrl)
    }

    @Test
    fun `fetchAll maps video type channelId and youtubeId correctly`() = runTest {
        server.enqueue(MockResponse().setBody(SUBS_PAGE_2_JSON))
        server.enqueue(MockResponse().setBody(EMPTY_PLAYLISTS_JSON))
        server.enqueue(MockResponse().setBody(ONE_VIDEO_JSON))

        val result = source.fetchAll("tok")

        val videos = result.candidates.filter { it.type == CandidateType.VIDEO }
        assertEquals(1, videos.size)
        val v = videos[0]
        assertEquals(CandidateType.VIDEO, v.type)
        assertEquals("dQw4w9WgXcQ", v.youtubeId)
        assertEquals("Never Gonna Give You Up", v.title)
        assertEquals("UCuAXFkgsw1L7xaCfnd5JJOw", v.channelId)
    }

    @Test
    fun `fetchAll channel candidates have null channelId`() = runTest {
        server.enqueue(MockResponse().setBody(SUBS_PAGE_2_JSON))
        server.enqueue(MockResponse().setBody(EMPTY_PLAYLISTS_JSON))
        server.enqueue(MockResponse().setBody(EMPTY_VIDEOS_JSON))

        val result = source.fetchAll("tok")

        val channels = result.candidates.filter { it.type == CandidateType.CHANNEL }
        assertTrue("channel candidates should have null channelId", channels.all { it.channelId == null })
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun assertNullOrNotBlank(value: String?) {
        if (value != null) {
            assertTrue("thumbnailUrl must not be blank if present", value.isNotBlank())
        }
    }

    // ── canned JSON bodies ────────────────────────────────────────────────────

    companion object {
        private val SUBS_PAGE_1_JSON = """
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

        private val SUBS_PAGE_2_JSON = """
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

        private val EMPTY_PLAYLISTS_JSON = """{ "items": [] }"""

        private val EMPTY_VIDEOS_JSON = """{ "items": [] }"""

        private val ONE_PLAYLIST_JSON = """
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
            }
          ]
        }
        """.trimIndent()

        private val ONE_VIDEO_JSON = """
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
            }
          ]
        }
        """.trimIndent()
    }
}
