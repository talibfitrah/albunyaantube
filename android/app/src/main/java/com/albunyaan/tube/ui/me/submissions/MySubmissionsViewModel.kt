package com.albunyaan.tube.ui.me.submissions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.approvals.MySubmissionsRepository
import com.albunyaan.tube.data.approvals.dto.PendingApprovalDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface MySubmissionsUiState {
    data object Loading : MySubmissionsUiState
    data class Loaded(val items: List<PendingApprovalDto>) : MySubmissionsUiState
    data object Empty : MySubmissionsUiState
    data class Error(val message: String) : MySubmissionsUiState
}

sealed interface MySubmissionsActionEvent {
    data object DeleteSuccess : MySubmissionsActionEvent
    data object DeleteAlreadyReviewed : MySubmissionsActionEvent
    data object DeleteFailed : MySubmissionsActionEvent
}

@HiltViewModel
class MySubmissionsViewModel @Inject constructor(
    private val repo: MySubmissionsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<MySubmissionsUiState>(MySubmissionsUiState.Loading)
    val state: StateFlow<MySubmissionsUiState> = _state

    // One-shot UI events (snackbar trigger). Use a Channel rather than StateFlow so
    // two consecutive DeleteFailed events fire two snackbars — StateFlow would
    // de-duplicate equal `data object` emissions and the second snackbar would be lost.
    private val _actionEvents = Channel<MySubmissionsActionEvent>(Channel.BUFFERED)
    val actionEvents: Flow<MySubmissionsActionEvent> = _actionEvents.receiveAsFlow()

    // Single-flight refresh: a delete + a pull-refresh both call refresh(), and the
    // older fetch finishing last would clobber the newer state (re-introducing a row
    // we just removed). Cancel the prior job before relaunching.
    private var refreshJob: Job? = null

    init { refresh() }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.value = MySubmissionsUiState.Loading
            repo.fetchMySubmissions().fold(
                onSuccess = { items ->
                    _state.value = if (items.isEmpty()) MySubmissionsUiState.Empty
                                   else MySubmissionsUiState.Loaded(items)
                },
                onFailure = { e -> _state.value = MySubmissionsUiState.Error(e.message ?: "Failed to load") },
            )
        }
    }

    fun deleteSubmission(id: String, type: String) {
        viewModelScope.launch {
            repo.deleteSubmission(type = type, id = id).fold(
                onSuccess = {
                    _actionEvents.trySend(MySubmissionsActionEvent.DeleteSuccess)
                    refresh()
                },
                onFailure = { e ->
                    val event = if (e is com.albunyaan.tube.data.approvals.AlreadyReviewedError) {
                        MySubmissionsActionEvent.DeleteAlreadyReviewed
                    } else {
                        MySubmissionsActionEvent.DeleteFailed
                    }
                    _actionEvents.trySend(event)
                    // An "already reviewed" race means our local list is stale.
                    if (event == MySubmissionsActionEvent.DeleteAlreadyReviewed) refresh()
                },
            )
        }
    }
}
