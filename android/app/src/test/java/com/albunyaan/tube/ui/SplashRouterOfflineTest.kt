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
import kotlinx.coroutines.test.runTest
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

        val account = SplashRouter.resolveAccount(repo, "uid-a")

        assertEquals(loaded(AccountStatus.ACTIVE), account)
        assertEquals(loaded(AccountStatus.ACTIVE), repo.accountState.value)
    }

    @Test fun `resolveAccount on a signed 401 never reads the record`() = runTest {
        val store = mock<LastKnownAccountStore>()
        val repo = repoWith(store) { throw http(401, signed = true) }

        assertNull(SplashRouter.resolveAccount(repo, "uid-a"))
        verify(store, never()).read(any())
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
