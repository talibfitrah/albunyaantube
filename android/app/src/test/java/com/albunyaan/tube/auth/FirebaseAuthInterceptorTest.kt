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
        // MockWebServer binds to localhost — point apiHost at it so the host-
        // scope check passes and the existing signing-path tests still exercise
        // the Bearer-attach logic. The new third-party-host test below spins up
        // a SECOND server with a different (mocked) URL to assert pass-through.
        client = OkHttpClient.Builder()
            .addInterceptor(FirebaseAuthInterceptor(firebaseAuth).apply { apiHost = server.hostName })
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

    /**
     * Regression: the shared OkHttpClient runs this interceptor on EVERY
     * request, including third-party hosts (GitHub releases API, raw.github
     * usercontent.com for releases-meta.json). Pre-fix the Firebase JWT
     * leaked there and GitHub returned 401, leaving the Available Updates
     * screen empty. The host-scope check must skip non-API hosts entirely
     * — no token fetch, no Authorization header — so signed-in users still
     * load releases from GitHub.
     */
    @Test fun `request to non-API host is passed through unsigned even when signed in`() {
        val fresh = tokenResult("ey-fresh")
        whenever(firebaseAuth.currentUser).thenReturn(user)
        whenever(user.getIdToken(false)).thenReturn(Tasks.forResult(fresh))
        // apiHost points at a host the request URL won't match (server.hostName
        // is localhost; api.example.com is not). Same client/server fixture —
        // just retargeting the scope so the localhost request is "off-domain".
        val scopedClient = OkHttpClient.Builder()
            .addInterceptor(FirebaseAuthInterceptor(firebaseAuth).apply { apiHost = "api.example.com" })
            .build()
        server.enqueue(MockResponse().setResponseCode(200))

        scopedClient.newCall(Request.Builder().url(server.url("/x")).build()).execute().close()

        assertNull(server.takeRequest().getHeader("Authorization"))
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

    /**
     * Cubic round 1 P1 regression test: cross-account leak guard.
     *
     * Scenario: request is dispatched while user A is signed in (outer
     * capture). Backend returns 401 (token stale). Between dispatch and the
     * 401 retry, user A signs out and user B signs in on the same device.
     * The 401 retry path must NOT replay the request with user B's token —
     * doing so would silently rebind user A's original request to user B's
     * identity, leaking cross-account state.
     *
     * Pre-fix: `cached != token` would see user B's token as different from
     * user A's failed token, use it, and leak. Post-fix: the
     * `auth.currentUser?.uid != user.uid` guard inside the mutex detects the
     * uid change and returns null; the original 401 surfaces to the caller
     * and the UI re-prompts.
     */
    @Test fun `cross-account drift during 401 retry surfaces original 401 instead of leaking`() {
        val userA = mock<FirebaseUser>()
        val userB = mock<FirebaseUser>()
        whenever(userA.uid).thenReturn("uid-A")
        whenever(userB.uid).thenReturn("uid-B")
        // The interceptor calls auth.currentUser twice:
        //   1. outer capture for the initial request
        //   2. inside the mutex on the 401 retry (added by P1 fix)
        whenever(firebaseAuth.currentUser)
            .thenReturn(userA)  // initial dispatch sees user A
            .thenReturn(userB)  // 401 retry sees user B (sign-out + sign-in happened)
        val tokenA = tokenResult("token-A")  // hoist per setUp() comment — UnfinishedStubbingException if inlined
        whenever(userA.getIdToken(false)).thenReturn(Tasks.forResult(tokenA))
        // userB.getIdToken must NEVER be invoked — guard returns null first.
        // Two responses queued: first 401 triggers refresh; cross-account
        // guard aborts refresh (returns null); per Cubic R7 P1 the interceptor
        // re-executes the signed request to surface a clean final 401 instead
        // of replaying unsigned and faking success on public endpoints.
        server.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Bearer"))
        server.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Bearer"))

        val response = client.newCall(Request.Builder().url(server.url("/x")).build()).execute()
        response.close()

        assertEquals(401, response.code)             // final 401 surfaces — NO rebinding to user B
        assertEquals(2, server.requestCount)         // initial + re-execution (no force-refresh to user B's token)
        // BOTH requests carry user A's stale token, never user B's token.
        // Replaying with user A's stale token is harmless (gets 401 again);
        // replaying with user B's token (pre-fix behavior) would silently
        // bind user A's request to user B's identity — the actual leak.
        assertEquals("Bearer token-A", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer token-A", server.takeRequest().getHeader("Authorization"))
    }
}
