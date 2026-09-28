package com.albunyaan.tube.auth

import android.util.Log
import com.albunyaan.tube.data.account.AccountMeResponseDto
import com.albunyaan.tube.data.account.AccountService
import com.albunyaan.tube.data.account.CompleteProfileRequestDto
import com.albunyaan.tube.data.account.LocalAccountDataWiper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import retrofit2.HttpException

import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class AccountRepositoryImpl(
    private val service: AccountService,
    /** Linear backoff between retry attempts. 1s in prod; overridable for tests. */
    private val backoffMs: Long = 1_000L,
    /**
     * AUTH-INTERCEPT-DECOUPLE-01 — optional AuthRepository observer that, when
     * supplied, clears the AccountState on terminal AccountStatusEvent
     * (currently {@link AccountStatusEvent.Blocked} and
     * {@link AccountStatusEvent.Deleted}). Replaces the
     * Provider<AccountRepository> hack that AccountStatusInterceptor
     * previously used to call signOut() imperatively. Null for the
     * lightweight test-default constructor. If a future PR extends the
     * sealed AccountStatusEvent type, update the `when` block below
     * accordingly — Kotlin will not flag it as non-exhaustive because the
     * `when` is a statement, not an expression.
     */
    authStatusEvents: kotlinx.coroutines.flow.SharedFlow<AccountStatusEvent>? = null,
    observerScope: kotlinx.coroutines.CoroutineScope? = null,
    /**
     * Erases everything this install holds for the signed-in user. Non-null
     * when wired through Hilt; null for the lightweight test-default
     * constructor, same shape as [authStatusEvents] above.
     *
     * Runs on [AccountStatusEvent.Deleted] ONLY. Deletion is the one terminal
     * event that is irreversible server-side, so it is the only one where
     * keeping local data is wrong: a block is reversible and an ordinary
     * sign-out deliberately keeps the library for the same person signing back
     * in.
     */
    private val wiper: LocalAccountDataWiper? = null,
    /**
     * The last successful /me, persisted so an offline cold start routes on it
     * (see [restoreLastKnown]). Null for the lightweight test-default constructor.
     */
    private val lastKnown: LastKnownAccountStore? = null,
    /**
     * Firebase session. Gates [lastKnown] writes on the signed-in uid, clears
     * the record on any Firebase sign-out (including one Firebase starts itself,
     * e.g. a disabled user), and lets a terminal revalidation sign out for real.
     * Null for the lightweight test-default constructor.
     */
    private val auth: AuthRepository? = null,
    /**
     * `firebaseAuth.currentUser?.uid`, read live. The only synchronous truth:
     * AccountStatusInterceptor's firebaseAuth.signOut() clears currentUser at
     * once, while [auth]'s authState waits for a listener posted to the main
     * looper. Gates both reading and writing [lastKnown]. Null (lightweight
     * test constructor) = no gate.
     */
    private val currentFirebaseUid: (() -> String?)? = null,
) : AccountRepository {

    private val _state = MutableStateFlow<AccountState>(AccountState.NotSignedIn)
    override val accountState: StateFlow<AccountState> = _state.asStateFlow()

    /** True while the Loaded state came from [lastKnown], not from the server. */
    @Volatile private var restoredOffline = false

    /** One /me revalidation in flight at most, however many triggers fire. */
    private val revalidating = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Guards state-publish + record-write against signOut's state-reset +
     * record-clear, so a clear can never be followed by a stale write.
     */
    private val lock = Any()

    init {
        // AUTH-INTERCEPT-DECOUPLE-01 — when wired through Hilt the
        // authStatusEvents flow and observerScope are non-null; we subscribe
        // for the lifetime of this singleton and clear the local profile on
        // any terminal event the interceptor emits. Pre-fix the interceptor
        // had to inject a Provider<AccountRepository> and call signOut()
        // imperatively (Hilt cycle break); now the dependency direction is
        // reversed and the cycle is gone.
        if (authStatusEvents != null && observerScope != null) {
            observerScope.launch {
                authStatusEvents.collect { event ->
                    // Cubic R-final2 P2 — exhaustive WHEN-as-EXPRESSION so a
                    // future variant added to AccountStatusEvent forces a
                    // compile error here instead of silently no-opping.
                    val unused: Unit = when (event) {
                        AccountStatusEvent.Blocked -> signOut()
                        // The account is gone server-side and cannot come back,
                        // so this device must not keep the library, downloads
                        // or device id for the next person to sign in here.
                        // DeleteAccountViewModel wipes on its own success path,
                        // but it deliberately does NOT wipe when the request
                        // fails — and a failed request can still mean a deleted
                        // account (the tombstone commits before the purge). The
                        // retry then 403s ACCOUNT_DELETED and lands here. Same
                        // route an admin-side deletion takes, which nothing
                        // wiped for before. Double-wiping is harmless: every
                        // step is idempotent.
                        AccountStatusEvent.Deleted -> {
                            signOut()
                            wipeLocalData()
                        }
                        // Clear local state on user-initiated sign-out so any
                        // concurrent coroutine racing the back-stack teardown
                        // sees NotSignedIn rather than the old user's Loaded
                        // state. signOut() here is AccountRepositoryImpl's own
                        // method (_state = NotSignedIn) — no Hilt cycle.
                        AccountStatusEvent.SignedOut -> signOut()
                    }
                }
            }
        }
    }

    init {
        // Any Firebase sign-out — ours, or one Firebase starts itself when the
        // user is disabled — must not leave the last /me (PII) on disk.
        if (auth != null && observerScope != null) {
            observerScope.launch {
                auth.authState.collect { if (it is AuthState.SignedOut) signOut() }
            }
        }
    }

    override suspend fun fetchMe(): Result<AccountState.Loaded> = fetchMe(MAX_ATTEMPTS)

    /**
     * Cubic R7 P1 — splash-aware retry budget.
     *
     * Pre-fix every fetchMe() ran the full MAX_ATTEMPTS=3 retry budget,
     * blocking the splash screen up to ~2–3 s on a flaky network with no
     * progress signal. SplashFragment now calls fetchMe(maxAttempts=1) so the
     * splash route decision happens fast; if the single attempt fails, the
     * downstream screen (sign-in or main shell, per SplashRouter) handles
     * retry with the full budget.
     */
    override suspend fun fetchMe(maxAttempts: Int): Result<AccountState.Loaded> {
        val budget = maxAttempts.coerceAtLeast(1)
        _state.value = AccountState.Loading
        var lastError: Throwable? = null
        repeat(budget) { attempt ->
            try {
                val dto = service.getMe()
                val loaded = dto.toLoaded()
                confirm(loaded)
                return Result.success(loaded)
            } catch (e: IOException) {
                lastError = e
                if (attempt < budget - 1) delay(backoffMs)
            } catch (e: HttpException) {
                // 4xx/5xx — don't retry, bubble up.
                // Cubic R7 P1 — discard the HttpException body/Response and
                // store only the code + message. The original exception is
                // returned via Result.failure for the caller (eg. SplashFragment)
                // but is no longer pinned in StateFlow.
                _state.value = AccountState.Failed(
                    httpCode = e.code(),
                    message = e.message(),
                    cause = null,
                )
                return Result.failure(e)
            }
        }
        val cause = lastError ?: IOException("unknown fetch failure")
        // Cubic R7 P1 — IOException path retains the cause (no Response/Body
        // attached, lightweight), so debugging gets the original stack.
        _state.value = AccountState.Failed(
            httpCode = null,
            message = cause.message,
            cause = cause,
        )
        return Result.failure(cause)
    }

    override suspend fun completeProfile(
        displayName: String,
        dateOfBirth: LocalDate,
        phoneNumber: String?,
    ): Result<AccountState.Loaded> {
        val request = CompleteProfileRequestDto(
            displayName = displayName,
            dateOfBirth = dateOfBirth.format(DateTimeFormatter.ISO_LOCAL_DATE),
            phoneNumber = phoneNumber,
        )
        return try {
            val dto = service.completeProfile(request)
            val loaded = dto.toLoaded()
            confirm(loaded)
            Result.success(loaded)
        } catch (e: HttpException) {
            if (e.code() == 422 && bodyHasCode(e, "AGE_INELIGIBLE")) {
                Result.failure(AgeIneligibleError())
            } else {
                Result.failure(e)
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    override fun signOut() {
        synchronized(lock) {
            restoredOffline = false
            _state.value = AccountState.NotSignedIn
            lastKnown?.clear()
        }
    }

    /** A server-confirmed account: publish it and persist it for offline launch. */
    private fun confirm(loaded: AccountState.Loaded) {
        synchronized(lock) {
            restoredOffline = false
            _state.value = loaded
            persist(loaded)
        }
    }

    /**
     * Caller holds [lock]. Writes only while Firebase is signed in as that uid,
     * so a /me that lands after sign-out cannot bring the record back.
     */
    private fun persist(loaded: AccountState.Loaded) {
        if (firebaseSignedInAs(loaded.uid)) lastKnown?.write(loaded)
    }

    private fun firebaseSignedInAs(uid: String): Boolean =
        currentFirebaseUid == null || currentFirebaseUid.invoke() == uid

    /**
     * Best-effort local erase. Nothing may escape: an exception here would end
     * the `collect {}` in [init] and leave every later terminal event unhandled
     * for the rest of the process. CancellationException is rethrown rather
     * than swallowed — same trap `update/CallExtensions.kt:44-50`
     * (`runCatchingCoroutine`) documents; that helper lives in the sideload
     * source set, so it is not reachable from here.
     */
    private suspend fun wipeLocalData() {
        val target = wiper ?: return
        try {
            target.wipe()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "local wipe after account deletion failed", e)
        }
    }

    override fun restoreLastKnown(uid: String): AccountState.Loaded? = synchronized(lock) {
        // A 403 lifecycle envelope signs Firebase out synchronously before the
        // failed /me reaches here — never resurrect that account from disk.
        if (!firebaseSignedInAs(uid)) return null
        lastKnown?.read(uid)?.also {
            restoredOffline = true
            _state.value = it
        }
    }

    /**
     * One /me for a session restored offline; no-op otherwise, and at most one
     * in flight. Triggered on reconnect and on foreground.
     *
     * The server's verdict wins: a signed 401 signs out and deletes the record,
     * the same line [com.albunyaan.tube.ui.SplashRouter.accountForRoute] draws at
     * launch ([isTerminalAccountFailure]). An account blocked or deleted while
     * offline is signed out by the Firebase SDK when its token mint fails, or by
     * the 403 envelope. Anything else, or a malformed body, keeps the account.
     */
    override suspend fun revalidateRestored() {
        if (!restoredOffline || !revalidating.compareAndSet(false, true)) return
        try {
            val loaded = try {
                service.getMe().toLoaded()
            } catch (e: HttpException) {
                if (e.isTerminalAccountFailure()) {
                    signOut()
                    auth?.signOut()
                }
                return
            } catch (e: IOException) {
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Anything else — malformed body, Retrofit's KotlinNullPointerException
                // on an empty 200 — is non-terminal. This runs on appScope with no
                // handler, so an escape would crash the process.
                Log.w(TAG, "revalidate /me failed; keeping the restored account", e)
                return
            }
            synchronized(lock) {
                // Only replace the account we restored — a sign-out that landed
                // while the request was in flight must not be undone.
                val result = _state.updateAndGet { current ->
                    if (current is AccountState.Loaded && current.uid == loaded.uid) loaded else current
                }
                if (result === loaded) {
                    restoredOffline = false
                    persist(loaded)
                }
            }
        } finally {
            revalidating.set(false)
        }
    }

    override fun applyProfileUpdate(response: AccountMeResponseDto) {
        // Atomic CAS via MutableStateFlow.update so a concurrent signOut
        // from an off-main observerScope can't be clobbered by a
        // post-read overwrite. Not-Loaded states (NotSignedIn / Loading /
        // Failed / etc.) and another account's late response pass through unchanged.
        synchronized(lock) {
            val updated = _state.updateAndGet { current ->
                if (current is AccountState.Loaded && current.uid == response.uid) {
                    current.copy(
                        displayName = response.displayName ?: current.displayName,
                        dateOfBirth = response.dateOfBirth ?: current.dateOfBirth,
                        // The response is the whole account: null = no phone (e.g. removed on request).
                        phoneNumber = response.phoneNumber,
                    )
                } else {
                    current
                }
            }
            (updated as? AccountState.Loaded)?.let(::persist)
        }
    }

    private fun AccountMeResponseDto.toLoaded() = AccountState.Loaded(
        uid = uid,
        email = email,
        displayName = displayName,
        dateOfBirth = dateOfBirth,
        phoneNumber = phoneNumber,
        status = AccountStatus.fromWire(status),
        role = (role ?: "user").lowercase(),
    )

    /**
     * Parses the HttpException error body for a `code` field. Uses a tighter
     * substring match — the previous shape `contains("\"code\"") && contains("\"$code\"")`
     * matched on `validationField: "AGE_INELIGIBLE_input"` and other places
     * the code text appears in any field value, misrouting the user (cubic R5
     * P2). Looks for the exact JSON key/value pair `"code":"$code"` (with
     * optional whitespace) instead.
     */
    private fun bodyHasCode(e: HttpException, code: String): Boolean {
        // Cubic R7 P2 — bound the error-body read. A misbehaving server
        // returning a multi-MB error body would OOM the app on
        // errorBody().string(). The code envelope is two short fields;
        // 4 KiB is comfortably larger than any legitimate payload.
        // Cubic R8 P2 — wrap in `.use { … }` so the underlying OkHttp
        // ResponseBody (and its connection slot) is released on return.
        // Pre-R8 the body was opened, peeked, then left dangling for GC;
        // under retry storms the connection pool starved waiting for
        // finalisation.
        val errorBody = e.response()?.errorBody() ?: return false
        return errorBody.use { body ->
            val source = body.source()
            source.request(MAX_ERROR_BODY_BYTES)
            val peeked = source.buffer.snapshot(
                minOf(source.buffer.size, MAX_ERROR_BODY_BYTES).toInt()
            ).utf8()
            val pattern = Regex("\"code\"\\s*:\\s*\"" + Regex.escape(code) + "\"")
            pattern.containsMatchIn(peeked)
        }
    }

    companion object {
        private const val TAG = "AccountRepository"
        private const val MAX_ATTEMPTS = 3
        private const val MAX_ERROR_BODY_BYTES = 4_096L
    }
}
