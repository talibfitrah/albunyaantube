package com.albunyaan.tube.ui.bootstrap

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.auth.AgeIneligibleError
import com.albunyaan.tube.util.PhoneFormat
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

import java.time.LocalDate
import javax.inject.Inject

enum class BootstrapError {
    INVALID_NAME,
    INVALID_DOB,
    INVALID_PHONE_COUNTRY,
    INVALID_PHONE,
    INVALID_PASSWORD,
    PASSWORD_MISMATCH,
    PASSWORD_SET_FAILED,
    SAVE_FAILED,
}

sealed interface BootstrapNav {
    data object Idle : BootstrapNav
    data object NavigateToMain : BootstrapNav
    data object NavigateToAgeIneligible : BootstrapNav
}

@HiltViewModel
class ProfileBootstrapViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val firebaseAuth: FirebaseAuth,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    data class UiState(
        val displayName: String = "",
        val dateOfBirth: LocalDate? = null,
        val phoneCountry: String? = null,    // ISO-3166-1 alpha-2 e.g. "NL"
        val phoneNumber: String = "",         // national-portion as typed
        val password: String = "",
        val passwordConfirm: String = "",
        /**
         * True when the signed-in Firebase user has only a Google provider
         * attached (no password). Setting a password during profile
         * completion attaches the password provider so the same email can
         * later sign in to the admin dashboard via email/password too —
         * the Android side of bidirectional auth (the web side handles
         * password→Google via account linking on LoginView).
         */
        val passwordRequired: Boolean = false,
        /**
         * True once accountRepository.completeProfile() has returned
         * success in this session. If the subsequent updatePassword call
         * fails, the user can retry without re-sending the profile data
         * (which the backend may reject as duplicate).
         */
        val profileSaved: Boolean = false,
        val isLoading: Boolean = false,
        val error: BootstrapError? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _nav = MutableStateFlow<BootstrapNav>(BootstrapNav.Idle)
    val nav: StateFlow<BootstrapNav> = _nav.asStateFlow()

    /**
     * Returns the first validation error in field-order, or null when the
     * state is valid. Shared source of truth between [isFormValid] (button
     * enable state) and [submit] (error-message dispatch) so the two cannot
     * drift out of sync.
     *
     * Requires [appContext] for libphonenumber, so lives on the ViewModel
     * rather than inside [UiState].
     */
    fun firstValidationError(s: UiState = _ui.value): BootstrapError? {
        val name = s.displayName.trim()
        if (name.isBlank() || name.length > 40) return BootstrapError.INVALID_NAME
        if (s.dateOfBirth == null)              return BootstrapError.INVALID_DOB
        if (s.phoneCountry.isNullOrBlank())     return BootstrapError.INVALID_PHONE_COUNTRY
        PhoneFormat.formatE164(appContext, s.phoneCountry, s.phoneNumber)
            ?: return BootstrapError.INVALID_PHONE
        if (s.passwordRequired) {
            if (s.password.length < MIN_PASSWORD_LENGTH) return BootstrapError.INVALID_PASSWORD
            if (s.password != s.passwordConfirm)         return BootstrapError.PASSWORD_MISMATCH
        }
        return null
    }

    /** Drives submit button enable state. True iff the form passes [firstValidationError]. */
    val isFormValid: Boolean get() = firstValidationError() == null

    fun seedDisplayName(initial: String) {
        if (_ui.value.displayName.isEmpty()) _ui.update { it.copy(displayName = initial) }
    }

    /**
     * Called by the Fragment after inspecting firebaseAuth.currentUser's
     * providerData. Drives the visibility of the password fields in the
     * UI — Google-only accounts get the prompt, email/password accounts
     * already have a password and shouldn't be asked again.
     */
    fun setPasswordRequirement(required: Boolean) {
        _ui.update { it.copy(passwordRequired = required) }
    }

    fun onDisplayNameChanged(v: String) {
        _ui.update { it.copy(displayName = v, error = null) }
    }

    fun onDobChanged(d: LocalDate) {
        _ui.update { it.copy(dateOfBirth = d, error = null) }
    }

    fun onPhoneCountryChanged(region: String) {
        _ui.update { it.copy(phoneCountry = region, error = null) }
    }

    fun onPhoneNumberChanged(v: String) {
        _ui.update { it.copy(phoneNumber = v, error = null) }
    }

    fun onPasswordChanged(v: String) {
        _ui.update { it.copy(password = v, error = null) }
    }

    fun onPasswordConfirmChanged(v: String) {
        _ui.update { it.copy(passwordConfirm = v, error = null) }
    }

    fun setLoading(loading: Boolean) {
        _ui.update { it.copy(isLoading = loading) }
    }

    fun surfaceError(e: BootstrapError) {
        _ui.update { it.copy(isLoading = false, error = e) }
    }

    fun consumeNav() { _nav.value = BootstrapNav.Idle }

    fun submit() {
        val s = _ui.value
        if (s.isLoading) return  // de-dupe rapid double-taps
        val validationError = firstValidationError(s)
        if (validationError != null) {
            _ui.update { it.copy(error = validationError) }
            return
        }
        val name = s.displayName.trim()
        val dob = s.dateOfBirth!!  // firstValidationError() guarantees non-null
        val phoneE164 = PhoneFormat.formatE164(appContext, s.phoneCountry!!, s.phoneNumber)!!
        _ui.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            // Skip completeProfile if a prior submit already saved it and
            // we're only retrying the password step (which can fail
            // independently — see PASSWORD_SET_FAILED branch below).
            if (!s.profileSaved) {
                val profileResult = accountRepository.completeProfile(name, dob, phoneE164)
                profileResult.fold(
                    onSuccess = { _ui.update { it.copy(profileSaved = true) } },
                    onFailure = { e ->
                        _ui.update { it.copy(isLoading = false) }
                        if (e is AgeIneligibleError) {
                            _nav.value = BootstrapNav.NavigateToAgeIneligible
                        } else {
                            _ui.update { it.copy(error = BootstrapError.SAVE_FAILED) }
                        }
                        return@launch
                    }
                )
            }

            if (_ui.value.passwordRequired) {
                val user = firebaseAuth.currentUser
                if (user == null) {
                    // Session expired between profile save and password
                    // set. Profile is already committed backend-side; the
                    // user has to sign in again to attach a password.
                    _ui.update { it.copy(isLoading = false, error = BootstrapError.PASSWORD_SET_FAILED) }
                    return@launch
                }
                try {
                    user.updatePassword(_ui.value.password).await()
                } catch (e: Exception) {
                    // Profile is saved, password attach failed. Stay on
                    // screen so the user can retry password without
                    // re-submitting the profile (profileSaved guards
                    // against duplicate completeProfile calls).
                    _ui.update { it.copy(isLoading = false, error = BootstrapError.PASSWORD_SET_FAILED) }
                    return@launch
                }
            }

            _ui.update { it.copy(isLoading = false) }
            _nav.value = BootstrapNav.NavigateToMain
        }
    }

    companion object {
        const val MIN_PASSWORD_LENGTH = 8
    }
}
