package com.albunyaan.tube.data.account

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Plan C T5: Retrofit definition for the new /api/account/ endpoints.
 * Auth header is injected by Plan B's FirebaseAuthInterceptor.
 */
interface AccountService {

    @POST("api/account/profile")
    suspend fun completeProfile(@Body body: CompleteProfileRequestDto): AccountMeResponseDto

    @GET("api/account/me")
    suspend fun getMe(): AccountMeResponseDto

    @POST("api/account/send-verification-email")
    suspend fun sendVerificationEmail(): retrofit2.Response<Unit>

    /**
     * ANDROID-ACCT-DEL-01 — self-serve permanent deletion (Google Play policy
     * 13327111). Backend answers 204 on both the first call and an idempotent
     * retry, and 409 when the caller is the last remaining active admin.
     *
     * Returns the raw [retrofit2.Response] rather than Unit so the 409 arrives
     * as a status code the caller can branch on instead of an HttpException.
     */
    @DELETE("api/account/me")
    suspend fun deleteAccount(): retrofit2.Response<Unit>
}
