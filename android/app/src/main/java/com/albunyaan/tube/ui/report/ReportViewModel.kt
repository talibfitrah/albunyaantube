package com.albunyaan.tube.ui.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.report.ReportContentSubType
import com.albunyaan.tube.data.report.ReportReason
import com.albunyaan.tube.data.report.ReportRepository
import com.albunyaan.tube.data.report.ReportTargetType
import com.albunyaan.tube.data.report.RetrofitReportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReportViewModel @Inject constructor(
    private val reportRepository: ReportRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<ReportUiState>(ReportUiState.Idle)
    val uiState: StateFlow<ReportUiState> = _uiState.asStateFlow()

    fun submitReport(
        targetType: ReportTargetType,
        targetId: String,
        reasons: List<ReportReason>,
        otherDescription: String?,
        parentType: ReportTargetType? = null,
        parentId: String? = null,
        contentSubType: ReportContentSubType? = null,
    ) {
        if (reasons.isEmpty()) {
            _uiState.value = ReportUiState.Error("Please select at least one reason.")
            return
        }
        viewModelScope.launch {
            _uiState.value = ReportUiState.Loading
            val result = reportRepository.submitReport(
                targetType, targetId, reasons, otherDescription,
                parentType, parentId, contentSubType,
            )
            _uiState.value = result.fold(
                onSuccess = { ReportUiState.Success },
                onFailure = { e ->
                    when (e) {
                        is RetrofitReportRepository.RateLimitException -> ReportUiState.RateLimited
                        else -> ReportUiState.Error(e.message ?: "Failed to submit report.")
                    }
                }
            )
        }
    }

    fun resetState() {
        _uiState.value = ReportUiState.Idle
    }
}

sealed interface ReportUiState {
    object Idle : ReportUiState
    object Loading : ReportUiState
    object Success : ReportUiState
    object RateLimited : ReportUiState
    data class Error(val message: String) : ReportUiState
}
