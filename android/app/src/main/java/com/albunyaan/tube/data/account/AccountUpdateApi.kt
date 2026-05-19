package com.albunyaan.tube.data.account

import com.albunyaan.tube.data.account.dto.UpdateProfileRequestDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.PUT

/**
 * Retrofit interface for PUT /api/account/profile.
 * Returns Response<> (not raw DTO) so the repository can inspect
 * HTTP status codes and error body / Retry-After header.
 * Auth header is injected by FirebaseAuthInterceptor.
 */
interface AccountUpdateApi {

    @PUT("api/account/profile")
    suspend fun updateProfile(@Body body: UpdateProfileRequestDto): Response<AccountMeResponseDto>
}
