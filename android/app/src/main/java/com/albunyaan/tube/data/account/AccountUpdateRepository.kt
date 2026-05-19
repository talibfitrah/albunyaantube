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
    data class ValidationFailed(val message: String) : ProfileUpdateResult()
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
            resp.code() == 400 -> ProfileUpdateResult.ValidationFailed(
                errMessage(resp) ?: "Invalid input"
            )
            resp.code() == 401 -> ProfileUpdateResult.Unknown(401)
            else -> ProfileUpdateResult.Unknown(resp.code())
        }
    } catch (e: IOException) {
        ProfileUpdateResult.NetworkError
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
            val message = map?.get("message")?.toString() ?: "Validation failed"
            ProfileUpdateResult.ValidationFailed(message)
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
