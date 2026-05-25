package com.albunyaan.tube.ui.me.profile.edit

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.data.account.AccountUpdateRepository
import com.albunyaan.tube.data.account.ProfileUpdateResult
import com.albunyaan.tube.data.account.dto.UpdateProfileRequestDto
import com.albunyaan.tube.util.PhoneFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class EditPhoneError { INVALID_COUNTRY, INVALID_PHONE, NETWORK, RATE_LIMITED, UNKNOWN }

@HiltViewModel
class EditPhoneViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val updateRepository: AccountUpdateRepository,
    private val accountRepository: AccountRepository,
) : ViewModel() {

    data class UiState(
        val country: String? = null,
        val number: String = "",
        val saving: Boolean = false,
        val error: EditPhoneError? = null,
    )

    sealed interface Nav { data object Idle : Nav; data object Done : Nav }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private val _nav = MutableStateFlow<Nav>(Nav.Idle)
    val nav: StateFlow<Nav> = _nav.asStateFlow()

    fun seed(country: String?, number: String?) {
        _ui.update { it.copy(country = country, number = number.orEmpty()) }
    }

    fun onCountryChanged(c: String) = _ui.update { it.copy(country = c, error = null) }
    fun onNumberChanged(n: String)  = _ui.update { it.copy(number = n, error = null) }

    fun submit() {
        val s = _ui.value
        if (s.saving) return
        if (s.country.isNullOrBlank()) {
            _ui.update { it.copy(error = EditPhoneError.INVALID_COUNTRY) }
            return
        }
        val e164 = PhoneFormat.formatE164(appContext, s.country, s.number)
        if (e164 == null) {
            _ui.update { it.copy(error = EditPhoneError.INVALID_PHONE) }
            return
        }
        _ui.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            when (val r = updateRepository.updateProfile(UpdateProfileRequestDto(phoneNumber = e164))) {
                is ProfileUpdateResult.Success -> {
                    accountRepository.applyProfileUpdate(r.response)
                    _nav.value = Nav.Done
                }
                is ProfileUpdateResult.RateLimited ->
                    _ui.update { it.copy(saving = false, error = EditPhoneError.RATE_LIMITED) }
                ProfileUpdateResult.NetworkError ->
                    _ui.update { it.copy(saving = false, error = EditPhoneError.NETWORK) }
                is ProfileUpdateResult.ValidationFailed ->
                    _ui.update { it.copy(saving = false, error = EditPhoneError.INVALID_PHONE) }
                ProfileUpdateResult.AgeIneligible,
                is ProfileUpdateResult.Unknown ->
                    _ui.update { it.copy(saving = false, error = EditPhoneError.UNKNOWN) }
            }
        }
    }
}
