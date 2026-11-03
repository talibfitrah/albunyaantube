package com.albunyaan.tube.data.source

import com.albunyaan.tube.auth.FirebaseAuthInterceptor
import com.albunyaan.tube.data.source.api.ContentApi
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GetTokenResult
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import com.albunyaan.tube.data.source.api.RegistryEntry
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

/** Owner bug 2026-11-03: the registry routes are GETs now, decoded through the real Retrofit + Moshi stack. */
class ContentApiRegistryTest {

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
    fun `getChannel GETs the channel and decodes both exclusion lists`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"youtubeId":"UC1","name":"n","excludedVideoIds":["lesson00001","lesson00002"],"excludedPlaylistIds":["PLgone"]}"""
            )
        )

        val body = api.getChannel("UC1").body()

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/channels/UC1", recorded.requestUrl!!.encodedPath)
        assertEquals(RegistryEntry(listOf("lesson00001", "lesson00002"), listOf("PLgone")), body)
    }

    @Test
    fun `a signed-in user's exclusion GET carries their Firebase Bearer (PERSONAL grants answer only the grantee)`() = runTest {
        // NetworkModule builds ContentApi on the one shared OkHttpClient, whose FirebaseAuthInterceptor signs calls.
        val auth: FirebaseAuth = mock()
        val user: FirebaseUser = mock()
        val token: GetTokenResult = mock()
        whenever(token.token).thenReturn("ey-grantee")
        whenever(auth.currentUser).thenReturn(user)
        whenever(user.getIdToken(false)).thenReturn(Tasks.forResult(token))
        val signedApi = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().addInterceptor(FirebaseAuthInterceptor(auth).apply { apiHost = server.hostName }).build())
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()))
            .build()
            .create(ContentApi::class.java)
        server.enqueue(MockResponse().setBody("""{"excludedVideoIds":[],"excludedPlaylistIds":[]}"""))
        server.enqueue(MockResponse().setBody("""{"excludedVideoIds":[]}"""))

        signedApi.getChannel("UCpersonal")
        signedApi.getPlaylist("PLpersonal")

        assertEquals("Bearer ey-grantee", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer ey-grantee", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `getPlaylist GETs the playlist, which has no playlist list`() = runTest {
        server.enqueue(MockResponse().setBody("""{"youtubeId":"PL1","title":"t","excludedVideoIds":["lesson00009"]}"""))

        val body = api.getPlaylist("PL1").body()

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/playlists/PL1", recorded.requestUrl!!.encodedPath)
        assertEquals(RegistryEntry(excludedVideoIds = listOf("lesson00009")), body)
    }
}
