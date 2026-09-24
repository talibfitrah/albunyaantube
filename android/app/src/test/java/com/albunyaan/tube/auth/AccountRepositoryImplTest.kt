package com.albunyaan.tube.auth

import com.albunyaan.tube.data.account.AccountMeResponseDto
import com.albunyaan.tube.data.account.AccountService
import com.albunyaan.tube.data.account.CompleteProfileRequestDto
import com.albunyaan.tube.data.account.LocalAccountDataWiper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import org.mockito.Mockito.doAnswer
import retrofit2.HttpException
import retrofit2.Response

import java.io.IOException
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class AccountRepositoryImplTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var service: AccountService
    private lateinit var repository: AccountRepositoryImpl

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        service = mock()
        repository = AccountRepositoryImpl(service, backoffMs = 0L)  // no real delay in tests
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `initial state is NotSignedIn`() {
        assertEquals(AccountState.NotSignedIn, repository.accountState.value)
    }

    @Test fun `fetchMe success updates accountState to Loaded`() = runTest(dispatcher) {
        whenever(service.getMe()).thenReturn(dto(status = "active"))
        val result = repository.fetchMe()

        assertTrue(result.isSuccess)
        val state = repository.accountState.first() as AccountState.Loaded
        assertEquals("uid-1", state.uid)
        assertEquals(AccountStatus.ACTIVE, state.status)
    }

    @Test fun `fetchMe retries 3 times on network error then fails`() = runTest(dispatcher) {
        // doAnswer avoids Mockito's checked-exception guard on suspend functions.
        doAnswer { throw IOException("offline") }.whenever(service).getMe()
        val result = repository.fetchMe()

        assertTrue(result.isFailure)
        verify(service, times(3)).getMe()
        val state = repository.accountState.first() as AccountState.Failed
        // Cubic R7 P1 — Failed state now carries an optional cause (IOException
        // retry exhaustion preserves the original, HttpException paths
        // discard it to avoid pinning OkHttp Response/ResponseBody).
        assertTrue(state.cause is IOException)
        assertEquals(null, state.httpCode)
    }

    @Test fun `fetchMe succeeds on second attempt after one failure`() = runTest(dispatcher) {
        // First call throws, second call returns successfully.
        doAnswer { throw IOException("flaky") }
            .doAnswer { dto(status = "pending_profile") }
            .whenever(service).getMe()

        val result = repository.fetchMe()
        assertTrue(result.isSuccess)
        val state = repository.accountState.first() as AccountState.Loaded
        assertEquals(AccountStatus.PENDING_PROFILE, state.status)
    }

    @Test fun `completeProfile success updates accountState`() = runTest(dispatcher) {
        whenever(service.completeProfile(any())).thenReturn(dto(status = "active"))

        val result = repository.completeProfile("Alice", LocalDate.of(2000, 1, 1), "+31612345678")

        assertTrue(result.isSuccess)
        val state = repository.accountState.first() as AccountState.Loaded
        assertEquals(AccountStatus.ACTIVE, state.status)
        verify(service).completeProfile(CompleteProfileRequestDto("Alice", "2000-01-01", "+31612345678"))
    }

    @Test fun `completeProfile maps 422 AGE_INELIGIBLE to AgeIneligibleError`() = runTest(dispatcher) {
        val errJson = """{"code":"AGE_INELIGIBLE","message":"too young"}"""
        val errBody = errJson.toResponseBody("application/json".toMediaTypeOrNull())
        whenever(service.completeProfile(any()))
            .thenThrow(HttpException(Response.error<Any>(422, errBody)))

        val result = repository.completeProfile("Kid", LocalDate.of(2020, 1, 1), "+31612345678")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AgeIneligibleError)
    }

    @Test fun `signOut resets accountState`() = runTest(dispatcher) {
        whenever(service.getMe()).thenReturn(dto(status = "active"))
        repository.fetchMe()

        repository.signOut()
        assertEquals(AccountState.NotSignedIn, repository.accountState.value)
    }

    // ── Terminal-event wiping ──────────────────────────────────────────────
    //
    // Deletion succeeding server-side while the device keeps everything is the
    // hole LocalAccountDataWiper exists to close, and DeleteAccountViewModel is
    // not enough on its own: it skips the wipe on any HTTP failure (correctly —
    // a live account's library must survive a failed call), and it never runs at
    // all for an admin-side deletion. Both of those reach the app the same way:
    // a 403 ACCOUNT_DELETED envelope → AccountStatusInterceptor →
    // AccountStatusEvent.Deleted. Wiping HERE closes both.

    private fun repositoryObserving(
        events: MutableSharedFlow<AccountStatusEvent>,
        wiper: LocalAccountDataWiper,
        scope: kotlinx.coroutines.CoroutineScope,
    ) = AccountRepositoryImpl(
        service,
        backoffMs = 0L,
        authStatusEvents = events,
        observerScope = scope,
        wiper = wiper,
    )

    @Test fun `Deleted event wipes this device's local data`() = runTest(dispatcher) {
        val events = MutableSharedFlow<AccountStatusEvent>()
        val wiper = mock<LocalAccountDataWiper>()
        val repo = repositoryObserving(events, wiper, backgroundScope)
        runCurrent()

        events.emit(AccountStatusEvent.Deleted)
        advanceUntilIdle()

        verifyBlocking(wiper) { wipe() }
        assertEquals(AccountState.NotSignedIn, repo.accountState.value)
    }

    @Test fun `Blocked event signs out but never wipes`() = runTest(dispatcher) {
        val events = MutableSharedFlow<AccountStatusEvent>()
        val wiper = mock<LocalAccountDataWiper>()
        val repo = repositoryObserving(events, wiper, backgroundScope)
        runCurrent()

        events.emit(AccountStatusEvent.Blocked)
        advanceUntilIdle()

        // A block is reversible — destroying the library would be a data-loss bug.
        verifyBlocking(wiper, never()) { wipe() }
        assertEquals(AccountState.NotSignedIn, repo.accountState.value)
    }

    @Test fun `SignedOut event signs out but never wipes`() = runTest(dispatcher) {
        val events = MutableSharedFlow<AccountStatusEvent>()
        val wiper = mock<LocalAccountDataWiper>()
        repositoryObserving(events, wiper, backgroundScope)
        runCurrent()

        events.emit(AccountStatusEvent.SignedOut)
        advanceUntilIdle()

        // Sign-out deliberately keeps local data — the same person signs back in.
        verifyBlocking(wiper, never()) { wipe() }
    }

    @Test fun `a failing wipe does not kill the collector`() = runTest(dispatcher) {
        val events = MutableSharedFlow<AccountStatusEvent>()
        val wiper = mock<LocalAccountDataWiper>()
        wiper.stub { onBlocking { wipe() } doAnswer { throw IOException("disk full") } }
        repositoryObserving(events, wiper, backgroundScope)
        runCurrent()

        events.emit(AccountStatusEvent.Deleted)
        advanceUntilIdle()
        events.emit(AccountStatusEvent.Deleted)
        advanceUntilIdle()

        // An escaping exception would end collect{} and leave every later
        // terminal event unhandled for the process lifetime.
        verifyBlocking(wiper, times(2)) { wipe() }
    }

    // ── Last-known /me (offline launch) ────────────────────────────────────

    private fun repositoryWithStore(
        store: LastKnownAccountStore,
        events: MutableSharedFlow<AccountStatusEvent>? = null,
        scope: kotlinx.coroutines.CoroutineScope? = null,
    ) = AccountRepositoryImpl(
        service,
        backoffMs = 0L,
        authStatusEvents = events,
        observerScope = scope,
        lastKnown = store,
    )

    private val restored = AccountState.Loaded(
        uid = "uid-1", email = "a@b.com", displayName = "Alice", dateOfBirth = null,
        phoneNumber = null, status = AccountStatus.ACTIVE, role = "admin",
    )

    @Test fun `a successful fetchMe overwrites the persisted record`() = runTest(dispatcher) {
        val store = mock<LastKnownAccountStore>()
        whenever(service.getMe()).thenReturn(dto(status = "pending_profile"))

        val loaded = repositoryWithStore(store).fetchMe().getOrThrow()

        verify(store).write(loaded)
    }

    @Test fun `completeProfile persists the new status`() = runTest(dispatcher) {
        val store = mock<LastKnownAccountStore>()
        whenever(service.completeProfile(any())).thenReturn(dto(status = "active"))

        val loaded = repositoryWithStore(store)
            .completeProfile("Alice", LocalDate.of(1990, 1, 1), "+31612345678").getOrThrow()

        verify(store).write(loaded)
    }

    @Test fun `a failed fetchMe leaves the persisted record alone`() = runTest(dispatcher) {
        val store = mock<LastKnownAccountStore>()
        doAnswer { throw IOException("offline") }.whenever(service).getMe()

        repositoryWithStore(store).fetchMe(maxAttempts = 1)

        verify(store, never()).write(any())
        verify(store, never()).clear()
    }

    @Test fun `restoreLastKnown publishes the record as the account state`() {
        val store = mock<LastKnownAccountStore>()
        whenever(store.read("uid-1")).thenReturn(restored)
        val repo = repositoryWithStore(store)

        assertEquals(restored, repo.restoreLastKnown("uid-1"))
        assertEquals(restored, repo.accountState.value)
        assertEquals("uid-1", repo.currentUid())
    }

    @Test fun `restoreLastKnown for another uid leaves state untouched`() {
        val store = mock<LastKnownAccountStore>()  // read() returns null for any uid
        val repo = repositoryWithStore(store)

        assertNull(repo.restoreLastKnown("uid-2"))
        assertEquals(AccountState.NotSignedIn, repo.accountState.value)
    }

    @Test fun `signOut deletes the persisted record`() {
        val store = mock<LastKnownAccountStore>()

        repositoryWithStore(store).signOut()

        verify(store).clear()
    }

    @Test fun `SignedOut event deletes the persisted record`() = runTest(dispatcher) {
        val store = mock<LastKnownAccountStore>()
        val events = MutableSharedFlow<AccountStatusEvent>()
        repositoryWithStore(store, events, backgroundScope)
        runCurrent()

        events.emit(AccountStatusEvent.SignedOut)
        advanceUntilIdle()

        verify(store).clear()
    }

    @Test fun `revalidate re-fetches me once connectivity returns after a restored launch`() = runTest(dispatcher) {
        val store = mock<LastKnownAccountStore>()
        whenever(store.read("uid-1")).thenReturn(restored)
        whenever(service.getMe()).thenReturn(dto(status = "active"))
        val repo = repositoryWithStore(store)
        repo.restoreLastKnown("uid-1")

        repo.revalidateRestored()
        repo.revalidateRestored()  // confirmed now — no second call

        verify(service, times(1)).getMe()
        assertEquals("user", (repo.accountState.value as AccountState.Loaded).role)
        verify(store).write(repo.accountState.value as AccountState.Loaded)
    }

    @Test fun `revalidate does nothing for a session confirmed online`() = runTest(dispatcher) {
        val store = mock<LastKnownAccountStore>()
        whenever(service.getMe()).thenReturn(dto(status = "active"))
        val repo = repositoryWithStore(store)
        repo.fetchMe()

        repo.revalidateRestored()

        verify(service, times(1)).getMe()
    }

    @Test fun `revalidate still offline keeps the restored account`() = runTest(dispatcher) {
        val store = mock<LastKnownAccountStore>()
        whenever(store.read("uid-1")).thenReturn(restored)
        doAnswer { throw IOException("still offline") }.whenever(service).getMe()
        val repo = repositoryWithStore(store)
        repo.restoreLastKnown("uid-1")

        repo.revalidateRestored()

        assertEquals(restored, repo.accountState.value)
    }

    @Test fun `revalidate never resurrects an account signed out mid-flight`() = runTest(dispatcher) {
        val store = mock<LastKnownAccountStore>()
        whenever(store.read("uid-1")).thenReturn(restored)
        val repo = repositoryWithStore(store)
        doAnswer { repo.signOut(); dto(status = "active") }.whenever(service).getMe()
        repo.restoreLastKnown("uid-1")

        repo.revalidateRestored()

        assertEquals(AccountState.NotSignedIn, repo.accountState.value)
        verify(store, never()).write(any())
    }

    // ── Patch round: terminal revalidation, single-flight, resurrection ────

    private val signedInA = MutableStateFlow<AuthState>(AuthState.SignedIn(mock(), "uid-1"))

    private fun authFake(state: MutableStateFlow<AuthState> = signedInA): AuthRepository {
        val auth = mock<AuthRepository>()
        whenever(auth.authState).thenReturn(state)
        return auth
    }

    private fun restoredRepo(
        store: LastKnownAccountStore,
        auth: AuthRepository = authFake(),
        scope: kotlinx.coroutines.CoroutineScope? = null,
    ): AccountRepositoryImpl {
        whenever(store.read("uid-1")).thenReturn(restored)
        return AccountRepositoryImpl(service, backoffMs = 0L, observerScope = scope, lastKnown = store, auth = auth)
            .also { it.restoreLastKnown("uid-1") }
    }

    /** [signed]: did the request that got [code] carry a Firebase Bearer? */
    private fun httpError(code: Int, signed: Boolean = false) = HttpException(
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

    @Test fun `revalidate signed 401 signs the user out and deletes the record`() = runTest(dispatcher) {
        val store = mock<LastKnownAccountStore>()
        val auth = authFake()
        val repo = restoredRepo(store, auth)
        doAnswer { throw httpError(401, signed = true) }.whenever(service).getMe()

        repo.revalidateRestored()

        verifyBlocking(auth) { signOut() }
        verify(store).clear()
        assertEquals(AccountState.NotSignedIn, repo.accountState.value)
    }

    @Test fun `revalidate unsigned 401 after a network mint failure keeps the record`() = runTest(dispatcher) {
        val store = mock<LastKnownAccountStore>()
        val auth = authFake()
        val repo = restoredRepo(store, auth)
        doAnswer { throw httpError(401, signed = false) }.whenever(service).getMe()

        repo.revalidateRestored()

        verifyBlocking(auth, never()) { signOut() }
        verify(store, never()).clear()
        assertEquals(restored, repo.accountState.value)
    }

    @Test fun `revalidate bare 403 and 404 keep the record`() = runTest(dispatcher) {
        for (code in listOf(403, 404)) {
            val store = mock<LastKnownAccountStore>()
            val auth = authFake()
            val repo = restoredRepo(store, auth)
            doAnswer { throw httpError(code, signed = true) }.whenever(service).getMe()

            repo.revalidateRestored()

            verifyBlocking(auth, never()) { signOut() }
            verify(store, never()).clear()
            assertEquals("code $code", restored, repo.accountState.value)
        }
    }

    /**
     * Invalid user (disabled / deleted / revoked): the token mint fails, the
     * interceptor sends unsigned, and the 401 alone is non-terminal — but the
     * Firebase SDK signs out itself on that mint failure (firebase-auth 24.2.0:
     * FirebaseUser.getIdToken → FirebaseAuth.zza(user, force) → callback
     * com.google.firebase.auth.zzz.zza(Status) calls FirebaseAuth.signOut() for
     * 17011 USER_NOT_FOUND / 17021 USER_TOKEN_EXPIRED / 17005 USER_DISABLED).
     * That SignedOut must end the session here.
     */
    @Test fun `invalid-user mint failure ends signed out`() = runTest(dispatcher) {
        val store = mock<LastKnownAccountStore>()
        val authState = MutableStateFlow<AuthState>(AuthState.SignedIn(mock(), "uid-1"))
        val repo = restoredRepo(store, authFake(authState), backgroundScope)
        runCurrent()
        doAnswer {
            authState.value = AuthState.SignedOut  // the SDK's own sign-out
            throw httpError(401, signed = false)
        }.whenever(service).getMe()

        repo.revalidateRestored()
        runCurrent()

        assertEquals(AccountState.NotSignedIn, repo.accountState.value)
        verify(store, atLeastOnce()).clear()
    }

    @Test fun `revalidate 5xx keeps the restored account`() = runTest(dispatcher) {
        val store = mock<LastKnownAccountStore>()
        val auth = authFake()
        val repo = restoredRepo(store, auth)
        doAnswer { throw httpError(503) }.whenever(service).getMe()

        repo.revalidateRestored()

        verifyBlocking(auth, never()) { signOut() }
        assertEquals(restored, repo.accountState.value)
    }

    @Test fun `revalidate 429 and 408 keep the restored account`() = runTest(dispatcher) {
        for (code in listOf(408, 429)) {
            val store = mock<LastKnownAccountStore>()
            val auth = authFake()
            val repo = restoredRepo(store, auth)
            doAnswer { throw httpError(code) }.whenever(service).getMe()

            repo.revalidateRestored()

            verifyBlocking(auth, never()) { signOut() }
            verify(store, never()).clear()
            assertEquals("code $code", restored, repo.accountState.value)
        }
    }

    @Test fun `revalidate with any other exception keeps the account and does not throw`() = runTest(dispatcher) {
        // e.g. Retrofit's KotlinNullPointerException on a 200 with an empty body.
        // Runs on appScope (no handler): an escape would crash the process.
        val store = mock<LastKnownAccountStore>()
        val repo = restoredRepo(store)
        doAnswer { throw KotlinNullPointerException("Response from getMe was null") }.whenever(service).getMe()

        repo.revalidateRestored()

        assertEquals(restored, repo.accountState.value)
    }

    @Test fun `revalidate rethrows cancellation`() = runTest(dispatcher) {
        val store = mock<LastKnownAccountStore>()
        val repo = restoredRepo(store)
        doAnswer { throw kotlinx.coroutines.CancellationException("scope gone") }.whenever(service).getMe()

        val thrown = runCatching { repo.revalidateRestored() }.exceptionOrNull()

        assertTrue(thrown is kotlinx.coroutines.CancellationException)
    }

    @Test fun `revalidate with a malformed me keeps the account and does not throw`() = runTest(dispatcher) {
        val store = mock<LastKnownAccountStore>()
        val repo = restoredRepo(store)
        doAnswer { throw com.squareup.moshi.JsonDataException("bad") }.whenever(service).getMe()

        repo.revalidateRestored()

        assertEquals(restored, repo.accountState.value)
    }

    @Test fun `revalidate runs at most one me request at a time`() = runTest(dispatcher) {
        val store = mock<LastKnownAccountStore>()
        val repo = restoredRepo(store)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        service.stub { onBlocking { getMe() } doSuspendableAnswer { gate.await(); dto(status = "active") } }

        launch { repo.revalidateRestored() }
        launch { repo.revalidateRestored() }
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        verify(service, times(1)).getMe()
    }

    @Test fun `a me that lands after sign-out never persists the record`() = runTest(dispatcher) {
        val store = mock<LastKnownAccountStore>()
        val authState = MutableStateFlow<AuthState>(AuthState.SignedIn(mock(), "uid-1"))
        val repo = AccountRepositoryImpl(
            service, backoffMs = 0L, lastKnown = store, auth = authFake(authState),
            currentFirebaseUid = { (authState.value as? AuthState.SignedIn)?.uid },
        )
        // The user signs out while /me is in flight.
        doAnswer {
            authState.value = AuthState.SignedOut
            repo.signOut()
            dto(status = "active")
        }.whenever(service).getMe()

        repo.fetchMe()

        verify(store, never()).write(any())
    }

    @Test fun `a Firebase-initiated sign-out deletes the record`() = runTest(dispatcher) {
        val store = mock<LastKnownAccountStore>()
        val authState = MutableStateFlow<AuthState>(AuthState.SignedIn(mock(), "uid-1"))
        restoredRepo(store, authFake(authState), backgroundScope)
        runCurrent()

        authState.value = AuthState.SignedOut  // e.g. account disabled in the Firebase console
        runCurrent()

        verify(store).clear()
    }

    // ── Cubic: restore must not outrun a synchronous Firebase sign-out ──────

    @Test fun `restore after Firebase already signed out publishes nothing`() {
        // 403 envelope at cold start: AccountStatusInterceptor calls
        // firebaseAuth.signOut() synchronously; authState (listener, main looper)
        // still says SignedIn when resolveAccount falls back to the record.
        val store = mock<LastKnownAccountStore>()
        whenever(store.read("uid-1")).thenReturn(restored)
        val repo = AccountRepositoryImpl(
            service, backoffMs = 0L, lastKnown = store, auth = authFake(),
            currentFirebaseUid = { null },
        )

        assertNull(repo.restoreLastKnown("uid-1"))
        assertEquals(AccountState.NotSignedIn, repo.accountState.value)
    }

    @Test fun `restore for a uid Firebase is not signed in as publishes nothing`() {
        val store = mock<LastKnownAccountStore>()
        whenever(store.read("uid-1")).thenReturn(restored)
        val repo = AccountRepositoryImpl(
            service, backoffMs = 0L, lastKnown = store, currentFirebaseUid = { "uid-2" },
        )

        assertNull(repo.restoreLastKnown("uid-1"))
    }

    @Test fun `restore while Firebase is signed in as that uid publishes the record`() {
        val store = mock<LastKnownAccountStore>()
        whenever(store.read("uid-1")).thenReturn(restored)
        val repo = AccountRepositoryImpl(
            service, backoffMs = 0L, lastKnown = store, currentFirebaseUid = { "uid-1" },
        )

        assertEquals(restored, repo.restoreLastKnown("uid-1"))
    }

    private fun dto(status: String, displayName: String = "Alice", dateOfBirth: String? = null) =
        AccountMeResponseDto(
            uid = "uid-1", email = "a@b.com", displayName = displayName,
            dateOfBirth = dateOfBirth, phoneNumber = null, status = status, role = "user", profileCompletedAt = null,
        )
}
