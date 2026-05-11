package com.albunyaan.tube.ui.bootstrap

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.auth.AuthRepository
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

sealed interface AgeIneligibleNav {
    data object Idle : AgeIneligibleNav
    data object NavigateToSignIn : AgeIneligibleNav
}

@HiltViewModel
class AgeIneligibleViewModel @Inject constructor(
    private val firebaseAuth: FirebaseAuth,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private companion object { const val TAG = "AgeIneligibleVM" }

    private val _nav = MutableStateFlow<AgeIneligibleNav>(AgeIneligibleNav.Idle)
    val nav: StateFlow<AgeIneligibleNav> = _nav.asStateFlow()

    fun acknowledge() {
        viewModelScope.launch {
            // Try to delete the Firebase Auth user. Backend already revoked
            // their refresh tokens in §5.2 — if delete() fails (network),
            // they still can't sign back in once their ID token expires.
            try {
                firebaseAuth.currentUser?.delete()?.await()
            } catch (e: Throwable) {
                Log.w(TAG, "FirebaseAuth.delete() failed in AgeIneligible flow, proceeding", e)
            }
            authRepository.signOut()
            _nav.value = AgeIneligibleNav.NavigateToSignIn
        }
    }

    fun consumeNav() { _nav.value = AgeIneligibleNav.Idle }
}
