package com.albunyaan.tube.data.account

import retrofit2.http.Body
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
}
