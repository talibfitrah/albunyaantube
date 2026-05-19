package com.albunyaan.tube.data.account.dto

import com.squareup.moshi.JsonClass

/**
 * Request body for PUT /api/account/profile.
 * dateOfBirth is sent as "YYYY-MM-DD", matching the wire format used by
 * CompleteProfileRequestDto (consistent with Plan C convention).
 */
@JsonClass(generateAdapter = true)
data class UpdateProfileRequestDto(
    val displayName: String? = null,
    /** Wire format: "YYYY-MM-DD". Null means "do not change". */
    val dateOfBirth: String? = null,
)
