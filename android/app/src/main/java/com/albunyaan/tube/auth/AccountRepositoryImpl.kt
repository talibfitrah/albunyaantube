package com.albunyaan.tube.auth

import com.albunyaan.tube.data.account.AccountMeResponseDto
import com.albunyaan.tube.data.account.AccountService
import com.albunyaan.tube.data.account.CompleteProfileRequestDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) : AccountRepository {

    private val _state = MutableStateFlow<AccountState>(AccountState.NotSignedIn)
    override val accountState: StateFlow<AccountState> = _state.asStateFlow()

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
                        AccountStatusEvent.Deleted -> signOut()
                        // Cubic R-final5 P1 — sign-out is initiated by the
                        // user (not a 403 envelope), so AccountRepository
                        // doesn't need to react. The SyncManager collector
                        // wired in SyncModule consumes this event for
                        // unbind(). The branch keeps the `when` exhaustive
                        // (compile-error on future sealed variants).
                        AccountStatusEvent.SignedOut -> Unit
                    }
                }
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
                _state.value = loaded
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
    ): Result<AccountState.Loaded> {
        val request = CompleteProfileRequestDto(
            displayName = displayName,
            dateOfBirth = dateOfBirth.format(DateTimeFormatter.ISO_LOCAL_DATE),
        )
        return try {
            val dto = service.completeProfile(request)
            val loaded = dto.toLoaded()
            _state.value = loaded
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
        _state.value = AccountState.NotSignedIn
    }

    override fun applyProfileUpdate(response: AccountMeResponseDto) {
        val current = _state.value
        if (current is AccountState.Loaded) {
            _state.value = current.copy(
                displayName = response.displayName ?: current.displayName,
                dateOfBirth = response.dateOfBirth ?: current.dateOfBirth,
            )
        }
        // Not Loaded (NotSignedIn, Loading, Failed) — sign-out race; ignore silently.
    }

    private fun AccountMeResponseDto.toLoaded() = AccountState.Loaded(
        uid = uid,
        email = email,
        displayName = displayName,
        dateOfBirth = dateOfBirth,
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
        private const val MAX_ATTEMPTS = 3
        private const val MAX_ERROR_BODY_BYTES = 4_096L
    }
}
