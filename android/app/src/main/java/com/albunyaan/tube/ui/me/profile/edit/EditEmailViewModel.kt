package com.albunyaan.tube.ui.me.profile.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.util.isEmailShape
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

enum class EditEmailError {
    INVALID_EMAIL, WRONG_PASSWORD, EMAIL_IN_USE, NETWORK, UNKNOWN,
}

@HiltViewModel
class EditEmailViewModel @Inject constructor(
    private val firebaseAuth: FirebaseAuth,
) : ViewModel() {

    data class UiState(
        val currentPassword: String = "",
        val newEmail: String = "",
        val saving: Boolean = false,
        val error: EditEmailError? = null,
    )

    sealed interface Nav { data object Idle : Nav; data object Done : Nav }
    fun consumeNav() { _nav.value = Nav.Idle }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private val _nav = MutableStateFlow<Nav>(Nav.Idle)
    val nav: StateFlow<Nav> = _nav.asStateFlow()

    fun onCurrentPasswordChanged(v: String) = _ui.update { it.copy(currentPassword = v, error = null) }
    fun onNewEmailChanged(v: String)        = _ui.update { it.copy(newEmail = v, error = null) }

    fun submit() {
        val s = _ui.value
        if (s.saving) return
        if (!isEmailShape(s.newEmail)) {
            _ui.update { it.copy(error = EditEmailError.INVALID_EMAIL) }
            return
        }
        val user = firebaseAuth.currentUser
        val currentEmail = user?.email
        if (user == null || currentEmail.isNullOrBlank()) {
            _ui.update { it.copy(error = EditEmailError.UNKNOWN) }
            return
        }
        _ui.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                user.reauthenticate(EmailAuthProvider.getCredential(currentEmail, s.currentPassword)).await()
            } catch (e: FirebaseAuthInvalidCredentialsException) {
                _ui.update { it.copy(saving = false, error = EditEmailError.WRONG_PASSWORD) }
                return@launch
            } catch (e: Exception) {
                _ui.update { it.copy(saving = false, error = EditEmailError.NETWORK) }
                return@launch
            }
            try {
                user.verifyBeforeUpdateEmail(s.newEmail).await()
                _ui.update { it.copy(saving = false) }
                _nav.value = Nav.Done
            } catch (e: FirebaseAuthUserCollisionException) {
                _ui.update { it.copy(saving = false, error = EditEmailError.EMAIL_IN_USE) }
            } catch (e: FirebaseAuthInvalidCredentialsException) {
                _ui.update { it.copy(saving = false, error = EditEmailError.INVALID_EMAIL) }
            } catch (e: Exception) {
                _ui.update { it.copy(saving = false, error = EditEmailError.NETWORK) }
            }
        }
    }
}
