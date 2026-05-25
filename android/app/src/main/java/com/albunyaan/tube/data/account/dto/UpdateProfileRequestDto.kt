package com.albunyaan.tube.data.account.dto

import com.squareup.moshi.JsonClass

/**
 * Request body for PUT /api/account/profile.
 * All fields nullable: null = no change.
 */
@JsonClass(generateAdapter = true)
data class UpdateProfileRequestDto(
    val displayName: String? = null,
    val dateOfBirth: String? = null,
    val phoneNumber: String? = null,
)
