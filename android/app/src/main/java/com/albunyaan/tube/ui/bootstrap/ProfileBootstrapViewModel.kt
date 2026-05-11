package com.albunyaan.tube.ui.bootstrap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.auth.AgeIneligibleError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import java.time.LocalDate
import javax.inject.Inject

enum class BootstrapError { INVALID_NAME, INVALID_DOB, SAVE_FAILED }

sealed interface BootstrapNav {
    data object Idle : BootstrapNav
    data object NavigateToMain : BootstrapNav
    data object NavigateToAgeIneligible : BootstrapNav
}

@HiltViewModel
class ProfileBootstrapViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
) : ViewModel() {

    data class UiState(
        val displayName: String = "",
        val dateOfBirth: LocalDate? = null,
        val isLoading: Boolean = false,
        val error: BootstrapError? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _nav = MutableStateFlow<BootstrapNav>(BootstrapNav.Idle)
    val nav: StateFlow<BootstrapNav> = _nav.asStateFlow()

    fun seedDisplayName(initial: String) {
        if (_ui.value.displayName.isEmpty()) _ui.update { it.copy(displayName = initial) }
    }

    fun onDisplayNameChanged(v: String) {
        _ui.update { it.copy(displayName = v, error = null) }
    }

    fun onDobChanged(d: LocalDate) {
        _ui.update { it.copy(dateOfBirth = d, error = null) }
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
        val name = s.displayName.trim()
        if (name.isBlank() || name.length > 40) {
            _ui.update { it.copy(error = BootstrapError.INVALID_NAME) }
            return
        }
        val dob = s.dateOfBirth
        if (dob == null) {
            _ui.update { it.copy(error = BootstrapError.INVALID_DOB) }
            return
        }
        _ui.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val result = accountRepository.completeProfile(name, dob)
            result.fold(
                onSuccess = {
                    _ui.update { it.copy(isLoading = false) }
                    _nav.value = BootstrapNav.NavigateToMain
                },
                onFailure = { e ->
                    _ui.update { it.copy(isLoading = false) }
                    if (e is AgeIneligibleError) {
                        _nav.value = BootstrapNav.NavigateToAgeIneligible
                    } else {
                        _ui.update { it.copy(error = BootstrapError.SAVE_FAILED) }
                    }
                }
            )
        }
    }
}
