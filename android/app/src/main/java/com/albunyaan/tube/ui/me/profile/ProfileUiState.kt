package com.albunyaan.tube.ui.me.profile

data class ProfileFields(
    val displayName: String,
    val dateOfBirth: String?,     // ISO "YYYY-MM-DD" or null
    val emailReadOnly: String,
    val phoneNumber: String?,
    val hasPasswordProvider: Boolean,
)

sealed class ProfileUiState {
    object Loading : ProfileUiState()
    data class Editing(
        val original: ProfileFields,
        val draft: ProfileFields,
        val saving: Boolean = false,
        val error: ProfileError? = null,
    ) : ProfileUiState() {
        val isDirty: Boolean get() = original != draft
    }
    object SignedOut : ProfileUiState()
}

sealed class ProfileError {
    object Network : ProfileError()
    data class RateLimited(val retryAfterSec: Long) : ProfileError()
    object AgeIneligible : ProfileError()
    data class Validation(val field: String, val message: String) : ProfileError()
    object Unknown : ProfileError()
}
