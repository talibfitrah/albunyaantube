package com.albunyaan.tube.ui.me.submissions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.approvals.MySubmissionsRepository
import com.albunyaan.tube.data.approvals.dto.PendingApprovalDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface MySubmissionsUiState {
    data object Loading : MySubmissionsUiState
    data class Loaded(val items: List<PendingApprovalDto>) : MySubmissionsUiState
    data object Empty : MySubmissionsUiState
    data class Error(val message: String) : MySubmissionsUiState
}

@HiltViewModel
class MySubmissionsViewModel @Inject constructor(
    private val repo: MySubmissionsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<MySubmissionsUiState>(MySubmissionsUiState.Loading)
    val state: StateFlow<MySubmissionsUiState> = _state

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
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
}
