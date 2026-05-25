package com.albunyaan.tube.data.account

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CompleteProfileRequestDto(
    val displayName: String,
    /** Wire format: "YYYY-MM-DD". */
    val dateOfBirth: String,
    /** E.164 international format, e.g. "+31612345678". */
    val phoneNumber: String,
)
