package com.albunyaan.tube.data.account

import com.albunyaan.tube.data.account.dto.UpdateProfileRequestDto
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed class ProfileUpdateResult {
    data class Success(val response: AccountMeResponseDto) : ProfileUpdateResult()
    data class RateLimited(val retryAfterSec: Long) : ProfileUpdateResult()
    object AgeIneligible : ProfileUpdateResult()
    /**
     * Plan G cubic R2 P1: carries the rejected field so the UI can route
     * the error to the right input row. Backend
     * ProfileValidationException maps the field via the
     * `"<field>: <reason>"` message envelope. If the backend returns a
     * generic 400 with no field prefix, [field] defaults to "displayName"
     * for backward-compat with the v1 UI behavior.
     */
    data class ValidationFailed(val field: String, val message: String) : ProfileUpdateResult()
    object NetworkError : ProfileUpdateResult()
    data class Unknown(val code: Int) : ProfileUpdateResult()
}

@Singleton
class AccountUpdateRepository @Inject constructor(
    private val api: AccountUpdateApi,
) {
    // Lightweight Moshi instance for parsing error bodies. The full app-scoped
    // Moshi (with codegen adapters) is wired to Retrofit; this one only needs
    // to parse simple Map-like error envelopes so reflect adapter is enough.
    private val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    suspend fun updateProfile(body: UpdateProfileRequestDto): ProfileUpdateResult = try {
        val resp = api.updateProfile(body)
        when {
            resp.isSuccessful && resp.body() != null -> ProfileUpdateResult.Success(resp.body()!!)
            resp.code() == 429 -> parseRateLimited(resp)
            resp.code() == 422 -> parseAgeIneligibleOrValidation(resp)
            resp.code() == 400 -> {
                val msg = errMessage(resp) ?: "Invalid input"
                val (field, reason) = splitFieldMessage(msg)
                ProfileUpdateResult.ValidationFailed(field, reason)
            }
            resp.code() == 401 -> ProfileUpdateResult.Unknown(401)
            else -> ProfileUpdateResult.Unknown(resp.code())
        }
    } catch (e: IOException) {
        ProfileUpdateResult.NetworkError
    } catch (e: kotlinx.coroutines.CancellationException) {
        // Plan G cubic R3 P2: never swallow cancellation — let the
        // coroutine's structured-concurrency contract propagate so the
        // ViewModel/Fragment lifecycle correctly tears down the launch.
        throw e
    } catch (e: Exception) {
        // Plan G cubic R1 P1: catch JsonDataException, JsonEncodingException
        // and any other Retrofit/Moshi failure that fires during success-body
        // deserialization. Without this clause those propagate out of the
        // suspend function, get lost to the default uncaught-exception
        // handler, and leave the UI stuck on the "saving" spinner.
        ProfileUpdateResult.Unknown(0)
    }

    private fun parseRateLimited(resp: retrofit2.Response<*>): ProfileUpdateResult.RateLimited {
        // Prefer the JSON body's retryAfterSeconds, fall back to Retry-After header.
        val bodyStr = resp.errorBody()?.string().orEmpty()
        val fromBody = (parseErrorMap(bodyStr)?.get("retryAfterSeconds") as? Double)?.toLong()
        val fromHeader = resp.headers()["Retry-After"]?.toLongOrNull()
        return ProfileUpdateResult.RateLimited(fromBody ?: fromHeader ?: 60L)
    }

    private fun parseAgeIneligibleOrValidation(resp: retrofit2.Response<*>): ProfileUpdateResult {
        // Read the body exactly once — the stream is single-use.
        val bodyStr = resp.errorBody()?.string().orEmpty()
        val map = parseErrorMap(bodyStr)
        val code = map?.get("code")?.toString()
        return if (code == "AGE_INELIGIBLE") {
            ProfileUpdateResult.AgeIneligible
        } else {
            val rawMessage = map?.get("message")?.toString() ?: "Validation failed"
            // Plan G cubic R2 P1: backend ProfileValidationException's HTTP
            // envelope is `"message": "<field>: <reason>"`. Split into
            // (field, reason) so the UI renders DOB errors on the DOB row,
            // not under display name. Falls back to ("displayName", raw)
            // if the prefix isn't present (older backend, generic 400).
            val (field, reason) = splitFieldMessage(rawMessage)
            ProfileUpdateResult.ValidationFailed(field, reason)
        }
    }

    /**
     * Parse "<field>: <reason>" produced by backend
     * ProfileValidationException. Returns ("displayName", whole-message)
     * when no field prefix is detectable so the UI degrades gracefully.
     */
    private fun splitFieldMessage(raw: String): Pair<String, String> {
        val sep = raw.indexOf(": ")
        if (sep <= 0) return "displayName" to raw
        val field = raw.substring(0, sep).trim()
        // Only honor known field names — guards against unrelated colons
        // (e.g. "Error: HTTP 500") being parsed as a field name.
        return when (field) {
            "displayName", "dateOfBirth" -> field to raw.substring(sep + 2).trim()
            else -> "displayName" to raw
        }
    }

    private fun errMessage(resp: retrofit2.Response<*>): String? {
        val bodyStr = resp.errorBody()?.string() ?: return null
        return parseErrorMap(bodyStr)?.get("message")?.toString()
    }

    private fun parseErrorMap(bodyStr: String): Map<String, Any?>? = runCatching {
        @Suppress("UNCHECKED_CAST")
        val adapter = moshi.adapter(Map::class.java) as com.squareup.moshi.JsonAdapter<Map<String, Any?>>
        adapter.fromJson(bodyStr)
    }.getOrNull()
}
