package com.albunyaan.tube.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.auth.AuthErrorCode
import com.albunyaan.tube.auth.AuthRepository
import com.albunyaan.tube.auth.toAuthErrorCode
import com.google.firebase.auth.AuthCredential
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Plan B (ANDROID-AUTH-01) T4: drives the [SignInFragment].
 *
 * State machine:
 *   - SIGN_IN mode → submit calls signInWithEmail
 *   - SIGN_UP mode → submit calls signUpWithEmail
 *   - Google / Microsoft credentials are built in the Fragment (needs an
 *     Activity reference) and handed back via [onCredential]; both converge
 *     on [AuthRepository.signInWithCredential].
 *   - forgot-password: tries [AuthRepository.sendPasswordResetEmail] using
 *     whatever email is currently in the form. Blank email surfaces an
 *     INVALID_EMAIL error instead of hitting the network.
 */
@HiltViewModel
class SignInViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    enum class Mode { SIGN_IN, SIGN_UP }

    data class UiState(
        val mode: Mode = Mode.SIGN_IN,
        val email: String = "",
        val password: String = "",
        val isLoading: Boolean = false,
        val error: AuthErrorCode? = null,
        val passwordResetSent: Boolean = false,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /**
     * Tracks the in-flight credential sign-in [Job], so a second concurrent
     * [onCredential] call can cancel the prior one instead of racing. Without
     * this, two parallel resolutions of [AuthRepository.signInWithCredential]
     * could each write [UiState] and the order would be non-deterministic.
     */
    private var credentialJob: Job? = null

    fun onEmailChanged(value: String) {
        _ui.update { it.copy(email = value, error = null, passwordResetSent = false) }
    }

    fun onPasswordChanged(value: String) {
        _ui.update { it.copy(password = value, error = null) }
    }

    fun toggleMode() {
        _ui.update {
            it.copy(
                mode = if (it.mode == Mode.SIGN_IN) Mode.SIGN_UP else Mode.SIGN_IN,
                error = null,
                passwordResetSent = false,
            )
        }
    }

    fun submit() {
        val snapshot = _ui.value
        if (snapshot.isLoading) return  // de-dupe rapid double-taps
        _ui.update { it.copy(isLoading = true, error = null, passwordResetSent = false) }
        viewModelScope.launch {
            val result = when (snapshot.mode) {
                Mode.SIGN_IN -> authRepository.signInWithEmail(snapshot.email, snapshot.password)
                Mode.SIGN_UP -> authRepository.signUpWithEmail(snapshot.email, snapshot.password)
            }
            _ui.update {
                it.copy(
                    isLoading = false,
                    error = result.exceptionOrNull()?.toAuthErrorCode(),
                )
            }
        }
    }

    fun onCredential(credential: AuthCredential, fallbackError: AuthErrorCode) {
        // No simple isLoading re-entrancy guard here: the call site is the
        // system's ActivityResultLauncher (Google) — not user-tap re-entrant —
        // and launchGoogleSignIn already set isLoading=true before opening the
        // chooser, so an `if (isLoading) return` here would silently drop the
        // returned credential. Instead, cancel any prior in-flight credential
        // coroutine so the latest credential wins deterministically — protects
        // against double-delivery of the ActivityResult and against future
        // callers (e.g., a re-enabled Microsoft path) that haven't pre-set
        // isLoading.
        credentialJob?.cancel()
        _ui.update { it.copy(isLoading = true, error = null) }
        credentialJob = viewModelScope.launch {
            val result = authRepository.signInWithCredential(credential)
            _ui.update {
                it.copy(
                    isLoading = false,
                    error = if (result.isFailure) {
                        val mapped = result.exceptionOrNull()?.toAuthErrorCode()
                        if (mapped == null || mapped == AuthErrorCode.UNKNOWN) fallbackError else mapped
                    } else null,
                )
            }
        }
    }

    /** Used by Microsoft sign-in path which calls FirebaseAuth directly (no AuthCredential). */
    fun surfaceError(code: AuthErrorCode) {
        _ui.update { it.copy(isLoading = false, error = code) }
    }

    fun clearError() {
        _ui.update { it.copy(error = null) }
    }

    fun setLoading(loading: Boolean) {
        _ui.update { it.copy(isLoading = loading) }
    }

    fun forgotPassword() {
        val email = _ui.value.email
        if (email.isBlank()) {
            _ui.update { it.copy(error = AuthErrorCode.INVALID_EMAIL) }
            return
        }
        viewModelScope.launch {
            val result = authRepository.sendPasswordResetEmail(email)
            _ui.update {
                if (result.isSuccess) it.copy(passwordResetSent = true, error = null)
                else it.copy(error = AuthErrorCode.PASSWORD_RESET_FAILED)
            }
        }
    }
}
