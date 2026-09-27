package com.albunyaan.tube.ui.me.profile.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.account.AccountService
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
import java.io.IOException
import javax.inject.Inject

enum class EditEmailError {
    INVALID_EMAIL, WRONG_PASSWORD, EMAIL_IN_USE, NETWORK, UNKNOWN,
}

@HiltViewModel
class EditEmailViewModel @Inject constructor(
    private val firebaseAuth: FirebaseAuth,
    private val accountService: AccountService,
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
                // Re-mint so the bearer carries the fresh auth_time: the backend refuses a sign-in
                // older than 5 minutes (REQUIRES_RECENT_LOGIN) and the cached token predates this one.
                user.getIdToken(true).await()
            } catch (e: FirebaseAuthInvalidCredentialsException) {
                _ui.update { it.copy(saving = false, error = EditEmailError.WRONG_PASSWORD) }
                return@launch
            } catch (e: Exception) {
                _ui.update { it.copy(saving = false, error = EditEmailError.NETWORK) }
                return@launch
            }
            try {
                // Backend first: Firebase's own mailer does not deliver for this project. Firebase
                // only when the backend has no mailer (503) or was unreachable.
                val resp = try {
                    accountService.sendChangeEmailVerification(mapOf("newEmail" to s.newEmail))
                } catch (e: IOException) {
                    null
                }
                val error = when {
                    resp == null || resp.code() == 503 -> {
                        user.verifyBeforeUpdateEmail(s.newEmail).await()
                        null
                    }
                    resp.isSuccessful -> null
                    resp.code() == 409 -> EditEmailError.EMAIL_IN_USE
                    // REQUIRES_RECENT_LOGIN: the password field is this sheet's re-auth prompt.
                    resp.code() == 401 -> EditEmailError.WRONG_PASSWORD
                    resp.code() == 400 -> EditEmailError.INVALID_EMAIL
                    else -> EditEmailError.NETWORK
                }
                _ui.update { it.copy(saving = false, error = error) }
                if (error == null) _nav.value = Nav.Done
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
