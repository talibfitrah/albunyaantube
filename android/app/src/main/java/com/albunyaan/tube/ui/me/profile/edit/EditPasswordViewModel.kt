package com.albunyaan.tube.ui.me.profile.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

enum class EditPasswordError {
    WEAK_PASSWORD, PASSWORD_MISMATCH, WRONG_PASSWORD, NETWORK, UNKNOWN,
}

@HiltViewModel
class EditPasswordViewModel @Inject constructor(
    private val firebaseAuth: FirebaseAuth,
) : ViewModel() {

    data class UiState(
        val current: String = "",
        val newPassword: String = "",
        val confirm: String = "",
        val saving: Boolean = false,
        val error: EditPasswordError? = null,
    )

    sealed interface Nav { data object Idle : Nav; data object Done : Nav }
    fun consumeNav() { _nav.value = Nav.Idle }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private val _nav = MutableStateFlow<Nav>(Nav.Idle)
    val nav: StateFlow<Nav> = _nav.asStateFlow()

    fun onCurrentChanged(v: String) = _ui.update { it.copy(current = v, error = null) }
    fun onNewChanged(v: String)     = _ui.update { it.copy(newPassword = v, error = null) }
    fun onConfirmChanged(v: String) = _ui.update { it.copy(confirm = v, error = null) }

    fun submit() {
        val s = _ui.value
        if (s.saving) return
        if (s.newPassword.length < MIN_PASSWORD_LENGTH) {
            _ui.update { it.copy(error = EditPasswordError.WEAK_PASSWORD) }
            return
        }
        if (s.newPassword != s.confirm) {
            _ui.update { it.copy(error = EditPasswordError.PASSWORD_MISMATCH) }
            return
        }
        val user = firebaseAuth.currentUser
        val email = user?.email
        if (user == null || email.isNullOrBlank()) {
            _ui.update { it.copy(error = EditPasswordError.UNKNOWN) }
            return
        }
        _ui.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                user.reauthenticate(EmailAuthProvider.getCredential(email, s.current)).await()
            } catch (e: FirebaseAuthInvalidCredentialsException) {
                _ui.update { it.copy(saving = false, error = EditPasswordError.WRONG_PASSWORD) }
                return@launch
            } catch (e: Exception) {
                _ui.update { it.copy(saving = false, error = EditPasswordError.NETWORK) }
                return@launch
            }
            try {
                user.updatePassword(s.newPassword).await()
                _ui.update { it.copy(saving = false) }
                _nav.value = Nav.Done
            } catch (e: FirebaseAuthWeakPasswordException) {
                _ui.update { it.copy(saving = false, error = EditPasswordError.WEAK_PASSWORD) }
            } catch (e: Exception) {
                _ui.update { it.copy(saving = false, error = EditPasswordError.NETWORK) }
            }
        }
    }

    companion object { const val MIN_PASSWORD_LENGTH = 8 }
}
