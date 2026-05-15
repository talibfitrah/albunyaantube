package com.albunyaan.tube.auth

import com.albunyaan.tube.data.account.AccountMeResponseDto
import com.albunyaan.tube.data.account.AccountService
import com.albunyaan.tube.data.account.CompleteProfileRequestDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.HttpException

import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class AccountRepositoryImpl(
    private val service: AccountService,
    /** Linear backoff between retry attempts. 1s in prod; overridable for tests. */
    private val backoffMs: Long = 1_000L,
) : AccountRepository {

    private val _state = MutableStateFlow<AccountState>(AccountState.NotSignedIn)
    override val accountState: StateFlow<AccountState> = _state.asStateFlow()

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

    private fun AccountMeResponseDto.toLoaded() = AccountState.Loaded(
        uid = uid,
        email = email,
        displayName = displayName,
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
        val errorBody = e.response()?.errorBody() ?: return false
        val source = errorBody.source()
        source.request(MAX_ERROR_BODY_BYTES)
        val peeked = source.buffer.snapshot(
            minOf(source.buffer.size, MAX_ERROR_BODY_BYTES).toInt()
        ).utf8()
        val pattern = Regex("\"code\"\\s*:\\s*\"" + Regex.escape(code) + "\"")
        return pattern.containsMatchIn(peeked)
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val MAX_ERROR_BODY_BYTES = 4_096L
    }
}
