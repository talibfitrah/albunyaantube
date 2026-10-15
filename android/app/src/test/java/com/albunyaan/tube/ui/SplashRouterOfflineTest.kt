package com.albunyaan.tube.ui

import com.albunyaan.tube.R
import com.albunyaan.tube.auth.AccountState
import com.albunyaan.tube.auth.AccountStatus
import com.albunyaan.tube.auth.AccountStatusEvent
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.albunyaan.tube.auth.AccountRepositoryImpl
import com.albunyaan.tube.auth.LastKnownAccountStore
import com.albunyaan.tube.data.account.AccountService
import com.albunyaan.tube.data.account.AccountMeResponseDto
import com.albunyaan.tube.auth.AuthRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.stub
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Offline launch: a signed-in user whose cold-start /me fails for a
 * NON-terminal reason routes on the last /me this device saw for that uid,
 * instead of being signed out (owner, 2026-09-24).
 */
class SplashRouterOfflineTest {

    private fun loaded(status: AccountStatus) = AccountState.Loaded(
        uid = "uid-a", email = "a@b.com", displayName = "A", dateOfBirth = null,
        phoneNumber = null, status = status, role = "user",
    )

    /** [signed]: did the request that got [code] carry a Firebase Bearer? */
    private fun http(code: Int, signed: Boolean = false) = HttpException(
        Response.error<Any>(
            "".toResponseBody(null),
            okhttp3.Response.Builder()
                .code(code).message("x").protocol(okhttp3.Protocol.HTTP_1_1)
                .request(
                    okhttp3.Request.Builder().url("http://localhost/api/account/me")
                        .apply { if (signed) header("Authorization", "Bearer t") }
                        .build()
                )
                .build(),
        )
    )

    private fun route(status: AccountStatus?) = SplashRouter.decideSplashRoute(
        onboardingCompleted = true, signedIn = true, accountStatus = status,
        hasPasswordProvider = false, isEmailVerified = true,
    )

    @Test fun `offline with a persisted ACTIVE record routes to main with no sign-out`() {
        val status = SplashRouter.accountForRoute(
            Result.failure(IOException("offline")), lastKnown = { loaded(AccountStatus.ACTIVE) },
        )?.status

        assertEquals(AccountStatus.ACTIVE, status)
        assertEquals(R.id.action_splash_to_main, route(status))
        // Non-null status: the D12 sign-out (signedIn && status == null) does not fire.
    }

    @Test fun `offline with a persisted PENDING_PROFILE record routes to bootstrap`() {
        val status = SplashRouter.accountForRoute(
            Result.failure(SocketTimeoutException()), lastKnown = { loaded(AccountStatus.PENDING_PROFILE) },
        )?.status

        assertEquals(R.id.action_splash_to_bootstrap, route(status))
    }

    @Test fun `offline with a persisted BLOCKED record routes to sign-in with the terminal event`() {
        val status = SplashRouter.accountForRoute(
            Result.failure(IOException("offline")), lastKnown = { loaded(AccountStatus.BLOCKED) },
        )?.status

        assertEquals(R.id.action_splash_to_signIn, route(status))
        assertEquals(AccountStatusEvent.Blocked, SplashRouter.terminalEvent(signedIn = true, accountStatus = status))
    }

    @Test fun `a 5xx is non-terminal and uses the record`() {
        val status = SplashRouter.accountForRoute(
            Result.failure(http(503)), lastKnown = { loaded(AccountStatus.ACTIVE) },
        )?.status

        assertEquals(AccountStatus.ACTIVE, status)
    }

    @Test fun `offline with no record signs out and routes to sign-in`() {
        val status = SplashRouter.accountForRoute(
            Result.failure(IOException("offline")), lastKnown = { null },
        )?.status

        assertNull(status)
        assertEquals(R.id.action_splash_to_signIn, route(status))
    }

    @Test fun `a signed 401 ignores the record`() {
        var read = false
        val status = SplashRouter.accountForRoute(
            Result.failure(http(401, signed = true)), lastKnown = { read = true; loaded(AccountStatus.ACTIVE) },
        )?.status

        assertFalse("record must not be read on a terminal failure", read)
        assertNull(status)
    }

    @Test fun `an unsigned 401 after a network mint failure keeps the record`() {
        // FirebaseAuthInterceptor sends the request unsigned when the token mint
        // fails (flaky network / timeout); the 401 says nothing about the account.
        val status = SplashRouter.accountForRoute(
            Result.failure(http(401, signed = false)), lastKnown = { loaded(AccountStatus.ACTIVE) },
        )?.status

        assertEquals(AccountStatus.ACTIVE, status)
    }

    @Test fun `a bare 403 and a 404 keep the record`() {
        // Bare = no lifecycle envelope (proxy / WAF). The envelope acts through
        // AccountStatusInterceptor, which signs out before this ever runs.
        for (code in listOf(403, 404)) {
            val status = SplashRouter.accountForRoute(
                Result.failure(http(code, signed = true)), lastKnown = { loaded(AccountStatus.ACTIVE) },
            )?.status
            assertEquals("code $code", AccountStatus.ACTIVE, status)
        }
    }

    @Test fun `a successful fetch wins over the record`() {
        var read = false
        val status = SplashRouter.accountForRoute(
            Result.success(loaded(AccountStatus.PENDING_PROFILE)),
            lastKnown = { read = true; loaded(AccountStatus.ACTIVE) },
        )?.status

        assertFalse(read)
        assertEquals(AccountStatus.PENDING_PROFILE, status)
    }

    // ── The call SplashFragment actually makes ─────────────────────────────

    private fun repoWith(store: LastKnownAccountStore, answer: () -> AccountMeResponseDto) =
        AccountRepositoryImpl(
            mock<AccountService>().also { svc -> svc.stub { onBlocking { getMe() } doAnswer { answer() } } },
            backoffMs = 0L,
            lastKnown = store,
        )

    @Test fun `resolveAccount offline publishes the persisted record for that uid`() = runTest {
        val store = mock<LastKnownAccountStore>()
        whenever(store.read("uid-a")).thenReturn(loaded(AccountStatus.ACTIVE))
        val repo = repoWith(store) { throw IOException("offline") }

        val account = SplashRouter.resolveAccount(repo, "uid-a", backgroundScope)

        assertEquals(loaded(AccountStatus.ACTIVE), account)
        assertEquals(loaded(AccountStatus.ACTIVE), repo.accountState.value)
    }

    @Test fun `resolveAccount with no record on a signed 401 routes to sign-in`() = runTest {
        val store = mock<LastKnownAccountStore>()
        val repo = repoWith(store) { throw http(401, signed = true) }

        assertNull(SplashRouter.resolveAccount(repo, "uid-a", backgroundScope))
        verify(store, never()).write(any())
    }

    // ── Launch must never wait on sync or long on /me (1.0.3, R4) ─────────

    /** Firebase still signed in as the launch uid. */
    private val signedIn: () -> String? = { "uid-a" }

    private fun me(status: String) = AccountMeResponseDto(
        uid = "uid-a", email = "a@b.com", displayName = "A", dateOfBirth = null,
        phoneNumber = null, status = status, role = "user", profileCompletedAt = null,
    )

    @Test fun `the launch status does not wait for sync`() = runTest {
        // A dead backend held the splash for minutes: bind's pull retries ran as a
        // child of the coroutine routing awaited (SplashFragment.kt:147-150 pre-fix).
        val repo = repoWith(mock()) { me("active") }
        var boundUid: String? = null

        val status = withTimeout(1_000) {
            SplashRouter.launchAccountStatus(repo, "uid-a", backgroundScope, signedIn) { uid ->
                boundUid = uid
                awaitCancellation()
            }
        }
        runCurrent()

        assertEquals(AccountStatus.ACTIVE, status)
        assertEquals("sync still starts, off the routing path", "uid-a", boundUid)
    }

    @Test fun `a hanging me with a saved record routes on it within 3 s and a later signed 401 signs out`() = runTest {
        val store = mock<LastKnownAccountStore>()
        whenever(store.read("uid-a")).thenReturn(loaded(AccountStatus.ACTIVE))
        val auth = mock<AuthRepository>()
        val pendingMe = CompletableDeferred<AccountMeResponseDto>()
        val repo = AccountRepositoryImpl(
            mock<AccountService>().also { svc -> svc.stub { onBlocking { getMe() } doSuspendableAnswer { pendingMe.await() } } },
            backoffMs = 0L,
            lastKnown = store,
            auth = auth,
        )

        val start = currentTime
        val status = withTimeout(10_000) {
            SplashRouter.launchAccountStatus(repo, "uid-a", backgroundScope, signedIn) {}
        }

        assertEquals(AccountStatus.ACTIVE, status)
        assertTrue("waited ${currentTime - start} ms", currentTime - start <= SplashRouter.LAUNCH_ME_BUDGET_MS)

        // The /me kept running after routing; the server's verdict lands now.
        pendingMe.completeExceptionally(http(401, signed = true))
        runCurrent()

        assertEquals(AccountState.NotSignedIn, repo.accountState.value)
        verify(store).clear()
        verify(auth).signOut()
    }

    @Test fun `with a saved record a me that answers within the budget wins`() = runTest {
        val store = mock<LastKnownAccountStore>()
        whenever(store.read("uid-a")).thenReturn(loaded(AccountStatus.ACTIVE))
        val repo = repoWith(store) { me("pending_profile") }

        val status = SplashRouter.launchAccountStatus(repo, "uid-a", backgroundScope, signedIn) {}

        assertEquals(AccountStatus.PENDING_PROFILE, status)
        verify(store).write(loaded(AccountStatus.PENDING_PROFILE))
    }

    @Test fun `with a saved record a signed 401 within the budget signs out`() = runTest {
        val store = mock<LastKnownAccountStore>()
        whenever(store.read("uid-a")).thenReturn(loaded(AccountStatus.ACTIVE))
        val auth = mock<AuthRepository>()
        val repo = AccountRepositoryImpl(
            mock<AccountService>().also { svc -> svc.stub { onBlocking { getMe() } doAnswer { throw http(401, signed = true) } } },
            backoffMs = 0L,
            lastKnown = store,
            auth = auth,
        )

        assertNull(SplashRouter.launchAccountStatus(repo, "uid-a", backgroundScope, signedIn) {})
        verify(store).clear()
        verify(auth).signOut()
    }

    @Test fun `sync never binds a uid Firebase has already signed out`() = runTest {
        // A 403 lifecycle envelope signs Firebase out in AccountStatusInterceptor before the
        // failed /me returns; the Deleted/Blocked observer clears accountState only later.
        val store = mock<LastKnownAccountStore>()
        whenever(store.read("uid-a")).thenReturn(loaded(AccountStatus.ACTIVE))
        var firebaseUid: String? = "uid-a"
        val repo = repoWith(store) { firebaseUid = null; throw http(403, signed = true) }
        var bound = false

        SplashRouter.launchAccountStatus(repo, "uid-a", backgroundScope, { firebaseUid }) { bound = true }
        runCurrent()

        assertFalse("bind must not start for a signed-out uid", bound)
    }

    @Test fun `429 and 408 are non-terminal and use the record, like 5xx`() {
        for (code in listOf(408, 429)) {
            val status = SplashRouter.accountForRoute(
                Result.failure(http(code)), lastKnown = { loaded(AccountStatus.ACTIVE) },
            )?.status
            assertEquals("code $code", AccountStatus.ACTIVE, status)
        }
    }
}
