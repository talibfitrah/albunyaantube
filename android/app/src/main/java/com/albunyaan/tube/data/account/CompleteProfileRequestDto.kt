package com.albunyaan.tube.data.account

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CompleteProfileRequestDto(
    val displayName: String,
    /** Wire format: "YYYY-MM-DD". */
    val dateOfBirth: String,
)
