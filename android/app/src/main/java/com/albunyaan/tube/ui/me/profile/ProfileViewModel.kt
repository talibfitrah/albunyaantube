package com.albunyaan.tube.ui.me.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.auth.AccountState
import com.albunyaan.tube.data.account.AccountUpdateRepository
import com.albunyaan.tube.data.account.ProfileUpdateResult
import com.albunyaan.tube.data.account.dto.UpdateProfileRequestDto
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val updateRepository: AccountUpdateRepository,
    private val firebaseAuth: FirebaseAuth,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        // Eager read for the common case (AccountState already Loaded
        // when the fragment opens). The collect below handles the slow-/me
        // case where the user navigates here before /me settles — only the
        // first Loaded promotes Loading → Editing; later state changes
        // don't clobber draft edits in progress.
        loadFromAccount()
        viewModelScope.launch {
            accountRepository.accountState.collect { state ->
                if (state is AccountState.Loaded && _uiState.value is ProfileUiState.Loading) {
                    loadFromAccount()
                }
            }
        }
    }

    private fun loadFromAccount() {
        val state = accountRepository.accountState.value
        if (state is AccountState.Loaded) {
            val hasPwd = firebaseAuth.currentUser?.providerData
                ?.any { it.providerId == EmailAuthProvider.PROVIDER_ID }
                ?: false
            val fields = ProfileFields(
                displayName = state.displayName.orEmpty(),
                dateOfBirth = state.dateOfBirth,
                emailReadOnly = state.email.orEmpty(),
                phoneNumber = state.phoneNumber,
                hasPasswordProvider = hasPwd,
            )
            _uiState.value = ProfileUiState.Editing(original = fields, draft = fields)
        }
    }

    fun onDisplayNameChange(value: String) = mutateDraft { it.copy(displayName = value) }
    fun onDateOfBirthChange(value: String?) = mutateDraft { it.copy(dateOfBirth = value) }

    private fun mutateDraft(transform: (ProfileFields) -> ProfileFields) {
        val s = _uiState.value as? ProfileUiState.Editing ?: return
        _uiState.value = s.copy(draft = transform(s.draft), error = null)
    }

    fun save() {
        val state = _uiState.value as? ProfileUiState.Editing ?: return
        if (!state.isDirty || state.saving) return
        _uiState.value = state.copy(saving = true, error = null)

        viewModelScope.launch {
            val req = buildRequest(state.original, state.draft)
            when (val r = updateRepository.updateProfile(req)) {
                is ProfileUpdateResult.Success -> {
                    accountRepository.applyProfileUpdate(r.response)
                    val newFields = state.draft
                    _uiState.value = ProfileUiState.Editing(original = newFields, draft = newFields)
                }
                is ProfileUpdateResult.RateLimited ->
                    _uiState.value = state.copy(saving = false, error = ProfileError.RateLimited(r.retryAfterSec))
                ProfileUpdateResult.AgeIneligible -> {
                    // Stop at the dialog-trigger state; the fragment calls
                    // confirmAgeIneligibleSignOut() on OK. Emitting
                    // SignedOut here would be conflated past Editing.
                    _uiState.value = state.copy(saving = false, error = ProfileError.AgeIneligible)
                }
                is ProfileUpdateResult.ValidationFailed ->
                    _uiState.value = state.copy(
                        saving = false,
                        error = ProfileError.Validation(r.field, r.message),
                    )
                ProfileUpdateResult.NetworkError ->
                    _uiState.value = state.copy(saving = false, error = ProfileError.Network)
                is ProfileUpdateResult.Unknown ->
                    _uiState.value = state.copy(saving = false, error = ProfileError.Unknown)
            }
        }
    }

    /** Called from the AgeIneligible dialog's OK button; staged to avoid
     *  StateFlow conflation skipping past the dialog-trigger state. */
    fun confirmAgeIneligibleSignOut() {
        accountRepository.signOut()
        _uiState.value = ProfileUiState.SignedOut
    }

    private fun buildRequest(original: ProfileFields, draft: ProfileFields): UpdateProfileRequestDto {
        val name = if (draft.displayName != original.displayName) draft.displayName else null
        val dob = if (draft.dateOfBirth != original.dateOfBirth) draft.dateOfBirth else null
        return UpdateProfileRequestDto(displayName = name, dateOfBirth = dob)
    }
}
