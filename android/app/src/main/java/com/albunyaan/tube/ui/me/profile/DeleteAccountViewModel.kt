package com.albunyaan.tube.ui.me.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.auth.AccountStatusEmitter
import com.albunyaan.tube.auth.AccountStatusEvent
import com.albunyaan.tube.auth.AuthRepository
import com.albunyaan.tube.data.account.AccountService
import com.albunyaan.tube.data.account.LocalAccountDataWiper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/**
 * ANDROID-ACCT-DEL-01 — in-app account deletion (Google Play policy 13327111).
 *
 * On success the flow reuses the terminal path the backend already drives for
 * an admin-side deletion: [AccountStatusEvent.Deleted] makes
 * `AccountRepositoryImpl` clear the profile and `MainActivity` show the
 * "Account deleted" dialog and route back to sign-in. No new dialog, no new
 * nav action.
 *
 * There is no success state: the terminal dialog owns the screen from that
 * point on.
 */
@HiltViewModel
class DeleteAccountViewModel @Inject constructor(
    private val service: AccountService,
    private val wiper: LocalAccountDataWiper,
    private val authRepository: AuthRepository,
    private val statusEmitter: AccountStatusEmitter,
) : ViewModel() {

    private val _state = MutableStateFlow<DeleteAccountState>(DeleteAccountState.Idle)
    val state: StateFlow<DeleteAccountState> = _state.asStateFlow()

    fun delete() {
        if (_state.value == DeleteAccountState.Deleting) return
        _state.value = DeleteAccountState.Deleting

        viewModelScope.launch {
            val response = try {
                service.deleteAccount()
            } catch (e: IOException) {
                _state.value = DeleteAccountState.FailedNetwork
                return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = DeleteAccountState.FailedUnknown
                return@launch
            }

            if (!response.isSuccessful) {
                // Nothing local is touched on any failure path. Wiping here
                // would destroy a live user's library for an account the
                // server still holds.
                _state.value = if (response.code() == HTTP_CONFLICT) {
                    DeleteAccountState.FailedLastAdmin
                } else {
                    DeleteAccountState.FailedUnknown
                }
                return@launch
            }

            // Server-side erasure has already succeeded and is irreversible, so
            // a local cleanup failure must not strand the user signed in to an
            // account that no longer exists.
            //
            // Cancellation is NOT a cleanup failure. `runCatching` captures
            // CancellationException into Result.failure, so a scope torn down
            // mid-wipe (the user backs out — `by viewModels()` cancels) fell
            // through to signOut + emit as if the wipe had finished. Rethrow it,
            // matching `update/CallExtensions.kt:44-50` (`runCatchingCoroutine`,
            // which lives in the sideload source set and so is not reachable
            // from here).
            try {
                wiper.wipe()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "local wipe after account deletion failed", e)
            }
            authRepository.signOut()
            statusEmitter.emit(AccountStatusEvent.Deleted)
        }
    }

    /** Clears a surfaced error so the row becomes tappable again. */
    fun errorShown() {
        if (_state.value != DeleteAccountState.Deleting) {
            _state.value = DeleteAccountState.Idle
        }
    }

    private companion object {
        const val HTTP_CONFLICT = 409
        const val TAG = "DeleteAccountViewModel"
    }
}

sealed interface DeleteAccountState {
    data object Idle : DeleteAccountState
    data object Deleting : DeleteAccountState

    /** 409 — the caller is the last remaining active admin. */
    data object FailedLastAdmin : DeleteAccountState
    data object FailedNetwork : DeleteAccountState
    data object FailedUnknown : DeleteAccountState
}
