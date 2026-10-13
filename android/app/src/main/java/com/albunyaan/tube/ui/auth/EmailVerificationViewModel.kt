package com.albunyaan.tube.ui.auth

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.auth.AuthRepository
import com.albunyaan.tube.data.account.AccountService
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.IOException
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
    private val accountService: AccountService,
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
        // autoSend=false: the splash's cold-start route (app_nav_graph.xml), which an earlier process mailed.
        if (saved.get<Long>("lastSentAtMs") == null && saved.get<Boolean>("autoSend") != false) {
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
                    // reload() refreshes the user record, not the cached ID token: re-mint it so its
                    // email_verified claim is true. Only POST /api/account/profile re-checks the record
                    // (and 403s if that lookup fails); every other route trusts the claim as minted.
                    user.getIdToken(true).await()
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
                val resp = accountService.sendVerificationEmail()
                if (!resp.isSuccessful) {
                    user.sendEmailVerification().await()
                }
                val now = System.currentTimeMillis()
                saved["lastSentAtMs"] = now
                _ui.update { it.copy(isResending = false, lastSentAtMs = now) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IOException) {
                _ui.update { it.copy(isResending = false, error = EmailVerifyError.NETWORK) }
            } catch (e: com.google.firebase.FirebaseTooManyRequestsException) {
                _ui.update { it.copy(isResending = false, error = EmailVerifyError.RATE_LIMITED) }
            } catch (e: Exception) {
                _ui.update { it.copy(isResending = false, error = EmailVerifyError.NETWORK) }
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
