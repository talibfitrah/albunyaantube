package com.albunyaan.tube.auth

import com.google.firebase.auth.FirebaseAuth
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import javax.inject.Provider
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Plan B (ANDROID-AUTH-01) T3: covers the Moshi-parsed 403 envelope handling.
 */
class AccountStatusInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var emitter: AccountStatusEmitter
    private lateinit var accountRepository: AccountRepository
    private lateinit var accountRepositoryProvider: Provider<AccountRepository>
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        firebaseAuth = mock()
        emitter = mock()
        accountRepository = mock()
        accountRepositoryProvider = mock()
        whenever(accountRepositoryProvider.get()).thenReturn(accountRepository)
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        client = OkHttpClient.Builder()
            .addInterceptor(AccountStatusInterceptor(firebaseAuth, emitter, accountRepositoryProvider, moshi))
            .build()
    }

    @After fun tearDown() { server.shutdown() }

    private fun call() = client.newCall(Request.Builder().url(server.url("/x")).build()).execute()

    @Test fun `200 response is passed through unchanged`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val response = call()
        assertEquals(200, response.code)
        assertEquals("ok", response.body!!.string())

        verify(emitter, never()).emit(org.mockito.kotlin.any())
        verify(firebaseAuth, never()).signOut()
    }

    @Test fun `403 ACCOUNT_BLOCKED emits Blocked and signs out`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody(
            """{"code":"ACCOUNT_BLOCKED","message":"You are blocked"}"""
        ))

        call().close()

        verify(emitter).emit(AccountStatusEvent.Blocked)
        verify(firebaseAuth).signOut()
    }

    @Test fun `403 ACCOUNT_DELETED emits Deleted and signs out`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody(
            """{"code":"ACCOUNT_DELETED","message":"Account gone"}"""
        ))

        call().close()

        verify(emitter).emit(AccountStatusEvent.Deleted)
        verify(firebaseAuth).signOut()
    }

    @Test fun `403 with other code does NOT emit or signOut`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody(
            """{"code":"FORBIDDEN","message":"Some other check"}"""
        ))

        call().close()

        verify(emitter, never()).emit(org.mockito.kotlin.any())
        verify(firebaseAuth, never()).signOut()
    }

    @Test fun `403 with malformed JSON does NOT emit or signOut`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("not-json-at-all"))

        call().close()

        verify(emitter, never()).emit(org.mockito.kotlin.any())
        verify(firebaseAuth, never()).signOut()
    }

    /**
     * Bounded peek: a huge response body (more than 1024 bytes) is read up to
     * MAX_PEEK_BYTES and then attempted-parsed. A truncated JSON should fail
     * parse and be treated as OTHER (no emit, no signOut). What we're testing
     * here is the bound itself — that the interceptor does not OOM or hang.
     */
    @Test fun `403 with body larger than 1024 bytes is bounded`() {
        val huge = "{\"code\":\"ACCOUNT_BLOCKED\",\"message\":\"" + "x".repeat(2000) + "\"}"
        server.enqueue(MockResponse().setResponseCode(403).setBody(huge))

        // Peek of first 1024 bytes will start with {"code":"ACCOUNT_BLOCKED"...
        // and be truncated mid-string. Moshi rejects truncated JSON → no emit.
        // The point of this test is: we get HERE without OOMing on the 2KB body.
        call().close()

        verify(emitter, never()).emit(org.mockito.kotlin.any())
        verify(firebaseAuth, never()).signOut()
    }

    /**
     * Per-task review fix: signOut MUST run before emit so the AuthStateListener
     * has flipped authState to SignedOut by the time the UI receives the dialog
     * event. Reversing the order creates a transient "blocked + still SignedIn"
     * state in the UI.
     */
    @Test fun `signOut is called before emit`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody(
            """{"code":"ACCOUNT_BLOCKED","message":"x"}"""
        ))

        call().close()

        val ordered = inOrder(firebaseAuth, emitter)
        ordered.verify(firebaseAuth).signOut()
        ordered.verify(emitter).emit(AccountStatusEvent.Blocked)
    }
}
