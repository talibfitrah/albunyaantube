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

    override suspend fun fetchMe(): Result<AccountState.Loaded> {
        _state.value = AccountState.Loading
        var lastError: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val dto = service.getMe()
                val loaded = dto.toLoaded()
                _state.value = loaded
                return Result.success(loaded)
            } catch (e: IOException) {
                lastError = e
                if (attempt < MAX_ATTEMPTS - 1) delay(backoffMs)
            } catch (e: HttpException) {
                // 4xx/5xx — don't retry, bubble up.
                _state.value = AccountState.Failed(e)
                return Result.failure(e)
            }
        }
        val cause = lastError ?: IOException("unknown fetch failure")
        _state.value = AccountState.Failed(cause)
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
     * Parses the HttpException error body for a `code` field. Uses naive
     * substring match: the canonical Moshi parse would round-trip ResponseBody,
     * which Retrofit has already consumed by the time we catch HttpException.
     * Cheap and safe: the only producer is our own AccountController.
     */
    private fun bodyHasCode(e: HttpException, code: String): Boolean {
        val body = e.response()?.errorBody()?.string() ?: return false
        return body.contains("\"code\"") && body.contains("\"$code\"")
    }

    companion object { private const val MAX_ATTEMPTS = 3 }
}
