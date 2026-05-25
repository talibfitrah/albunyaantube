package com.albunyaan.tube.ui.auth

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.auth.AuthRepository
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

enum class EmailVerifyError {
    NOT_YET_VERIFIED,
    RATE_LIMITED,
    NETWORK,
    UNKNOWN,
}

@HiltViewModel
class EmailVerificationViewModel @Inject constructor(
    private val firebaseAuth: FirebaseAuth,
    private val authRepository: AuthRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {

    data class UiState(
        val email: String = "",
        val isChecking: Boolean = false,
        val isResending: Boolean = false,
        val lastSentAtMs: Long? = null,
        val error: EmailVerifyError? = null,
    )

    sealed interface Nav {
        data object Idle : Nav
        data object NavigateToSplash : Nav
        data object NavigateToSignIn : Nav
    }

    private val _ui = MutableStateFlow(
        UiState(
            email = firebaseAuth.currentUser?.email.orEmpty(),
            lastSentAtMs = saved.get<Long>("lastSentAtMs"),
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _nav = MutableStateFlow<Nav>(Nav.Idle)
    val nav: StateFlow<Nav> = _nav.asStateFlow()

    init {
        if (saved.get<Long>("lastSentAtMs") == null) {
            sendVerificationEmail()
        }
    }

    fun consumeNav() {
        _nav.value = Nav.Idle
    }

    fun checkNow() {
        if (_ui.value.isChecking) return
        _ui.update { it.copy(isChecking = true, error = null) }
        viewModelScope.launch {
            val user = firebaseAuth.currentUser
            if (user == null) {
                _ui.update { it.copy(isChecking = false, error = EmailVerifyError.UNKNOWN) }
                return@launch
            }
            try {
                user.reload().await()
                if (user.isEmailVerified) {
                    _ui.update { it.copy(isChecking = false) }
                    _nav.value = Nav.NavigateToSplash
                } else {
                    _ui.update { it.copy(isChecking = false, error = EmailVerifyError.NOT_YET_VERIFIED) }
                }
            } catch (e: com.google.firebase.FirebaseTooManyRequestsException) {
                _ui.update { it.copy(isChecking = false, error = EmailVerifyError.RATE_LIMITED) }
                return@launch
            } catch (e: Exception) {
                _ui.update { it.copy(isChecking = false, error = EmailVerifyError.NETWORK) }
            }
        }
    }

    fun resend() {
        val last = _ui.value.lastSentAtMs
        val now = System.currentTimeMillis()
        if (last != null && now - last < COOLDOWN_MS) {
            _ui.update { it.copy(error = EmailVerifyError.RATE_LIMITED) }
            return
        }
        sendVerificationEmail()
    }

    private fun sendVerificationEmail() {
        if (_ui.value.isResending) return
        _ui.update { it.copy(isResending = true, error = null) }
        viewModelScope.launch {
            val user = firebaseAuth.currentUser
            if (user == null) {
                _ui.update { it.copy(isResending = false, error = EmailVerifyError.UNKNOWN) }
                return@launch
            }
            try {
                user.sendEmailVerification().await()
                val now = System.currentTimeMillis()
                saved["lastSentAtMs"] = now
                _ui.update { it.copy(isResending = false, lastSentAtMs = now) }
            } catch (e: Exception) {
                val mapped = if (e is com.google.firebase.FirebaseTooManyRequestsException)
                    EmailVerifyError.RATE_LIMITED
                else EmailVerifyError.NETWORK
                _ui.update { it.copy(isResending = false, error = mapped) }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.signOut()
            _nav.value = Nav.NavigateToSignIn
        }
    }

    companion object {
        const val COOLDOWN_MS = 60_000L
    }
}
