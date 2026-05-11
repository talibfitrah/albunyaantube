package com.albunyaan.tube.auth

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GetTokenResult
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Plan B (ANDROID-AUTH-01) T3: covers token attachment + the one-shot 401 retry.
 */
class FirebaseAuthInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var user: FirebaseUser
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        firebaseAuth = mock()
        user = mock()
        client = OkHttpClient.Builder()
            .addInterceptor(FirebaseAuthInterceptor(firebaseAuth))
            .build()
    }

    @After fun tearDown() { server.shutdown() }

    /**
     * Build a stubbed GetTokenResult standalone — do NOT call this inline inside
     * another `whenever(...).thenReturn(...)` argument list, otherwise Mockito
     * strict-mode flags it as UnfinishedStubbingException.
     */
    private fun tokenResult(token: String?): GetTokenResult {
        val result = mock<GetTokenResult>()
        whenever(result.token).thenReturn(token)
        return result
    }

    @Test fun `signed-out user sends no Authorization header`() {
        whenever(firebaseAuth.currentUser).thenReturn(null)
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(Request.Builder().url(server.url("/x")).build()).execute().close()

        val req = server.takeRequest()
        assertNull(req.getHeader("Authorization"))
    }

    @Test fun `signed-in user attaches Bearer token`() {
        val fresh = tokenResult("ey-fresh")
        whenever(firebaseAuth.currentUser).thenReturn(user)
        whenever(user.getIdToken(false)).thenReturn(Tasks.forResult(fresh))
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(Request.Builder().url(server.url("/x")).build()).execute().close()

        assertEquals("Bearer ey-fresh", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `null token bypasses header attach`() {
        val empty = tokenResult(null)
        whenever(firebaseAuth.currentUser).thenReturn(user)
        whenever(user.getIdToken(false)).thenReturn(Tasks.forResult(empty))
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(Request.Builder().url(server.url("/x")).build()).execute().close()

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `401 with WWW-Authenticate Bearer triggers force-refresh retry`() {
        val stale = tokenResult("stale")
        val fresh = tokenResult("fresh")
        whenever(firebaseAuth.currentUser).thenReturn(user)
        whenever(user.getIdToken(false)).thenReturn(Tasks.forResult(stale))
        whenever(user.getIdToken(true)).thenReturn(Tasks.forResult(fresh))
        server.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Bearer realm=\"firebase\""))
        server.enqueue(MockResponse().setResponseCode(200))

        val response = client.newCall(Request.Builder().url(server.url("/x")).build()).execute()
        response.close()

        assertEquals(200, response.code)
        assertEquals("Bearer stale", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization"))
    }

    /** Backend 401 from Firestore/timeout (no Bearer challenge) must NOT retry — would loop. */
    @Test fun `401 without WWW-Authenticate header is not retried`() {
        val token = tokenResult("any")
        whenever(firebaseAuth.currentUser).thenReturn(user)
        whenever(user.getIdToken(false)).thenReturn(Tasks.forResult(token))
        server.enqueue(MockResponse().setResponseCode(401))

        val response = client.newCall(Request.Builder().url(server.url("/x")).build()).execute()
        response.close()

        assertEquals(401, response.code)
        assertEquals(1, server.requestCount)  // no retry
    }

    @Test fun `two consecutive 401s return final 401 without infinite loop`() {
        val stale = tokenResult("stale")
        val alsoStale = tokenResult("also-stale")
        whenever(firebaseAuth.currentUser).thenReturn(user)
        whenever(user.getIdToken(false)).thenReturn(Tasks.forResult(stale))
        whenever(user.getIdToken(true)).thenReturn(Tasks.forResult(alsoStale))
        server.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Bearer"))
        server.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Bearer"))

        val response = client.newCall(Request.Builder().url(server.url("/x")).build()).execute()
        response.close()

        assertEquals(401, response.code)
        assertEquals(2, server.requestCount)  // exactly one retry, then give up
    }

    /**
     * Per-task review fix: getIdToken throwing must not leak the raw Firebase
     * exception through the OkHttp chain. The interceptor sends the request
     * unsigned and lets the backend respond 401 cleanly.
     */
    @Test fun `getIdToken throwing falls back to unsigned request`() {
        whenever(firebaseAuth.currentUser).thenReturn(user)
        whenever(user.getIdToken(false))
            .thenReturn(Tasks.forException(java.io.IOException("network blip mid-refresh")))
        server.enqueue(MockResponse().setResponseCode(200))

        val response = client.newCall(Request.Builder().url(server.url("/x")).build()).execute()
        response.close()

        assertEquals(200, response.code)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }
}
