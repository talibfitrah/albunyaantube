package com.albunyaan.tube.data.account

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AccountMeResponseDto(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val status: String,
    val role: String?,
    val profileCompletedAt: String?,
)
