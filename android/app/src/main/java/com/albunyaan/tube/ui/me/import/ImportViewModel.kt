package com.albunyaan.tube.ui.me.import

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.importflow.ImportProgress
import com.albunyaan.tube.data.importflow.YouTubeImportRepository
import com.albunyaan.tube.data.youtube.AuthResult
import com.albunyaan.tube.data.youtube.CandidateType
import com.albunyaan.tube.data.youtube.ImportCandidate
import com.albunyaan.tube.data.youtube.YouTubeAuthManager
import com.albunyaan.tube.data.youtube.YouTubeImportRemoteSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * B10: ViewModel for the YouTube-import flow.
 *
 * Drives [ImportUiState] through the full import lifecycle:
 *   Idle → Authorizing → [NeedsConsent →] Fetching → Review → Importing → Done
 * Any step can fall to Error.
 *
 * @HiltViewModel / @Inject are intentionally absent here and are added in B15.
 * Reason: adding them now causes KSP/Dagger to emit ImportViewModel_HiltModules
 * which references YouTubeImportApi — a type with no Hilt binding until B15.
 * That makes kspDebugKotlin crash with a DaggerSuperficialValidation NPE on the
 * generated class, breaking the entire app Hilt graph. B15 adds @HiltViewModel,
 * @Inject, and all required @Binds/@Provides in one atomic step.
 *
 * Empty-candidates policy: when fetchAll() returns zero candidates we emit
 * Error("No items found", retryable=false) rather than an empty Review list.
 * If there were also failedTypes the error is retryable=true (a retry may
 * recover the failed type). This keeps the fragment's happy path clean — it
 * only renders Review when there is actually something to show.
 */
class ImportViewModel(
    private val authManager: YouTubeAuthManager,
    private val remoteSource: YouTubeImportRemoteSource,
    private val importRepository: YouTubeImportRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

    /** Collector job for [importRepository.progress]; cancelled after import completes. */
    private var progressJob: Job? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Begin the import flow. Transitions to [ImportUiState.Authorizing], then
     * proceeds based on the [AuthResult].
     */
    fun start() {
        viewModelScope.launch {
            _uiState.value = ImportUiState.Authorizing
            handleAuthResult(authManager.authorize())
        }
    }

    /**
     * Called by the Fragment after the user completes (or dismisses) the Google
     * consent screen. [data] is the Intent returned from the consent activity.
     */
    fun onConsentResult(data: Intent?) {
        viewModelScope.launch {
            _uiState.value = ImportUiState.Authorizing
            handleAuthResult(authManager.authorizeFromConsentResult(data))
        }
    }

    /**
     * Toggle selection for a single candidate identified by [youtubeId].
     * No-op if the current state is not [ImportUiState.Review].
     */
    fun toggleSelection(youtubeId: String) {
        val current = _uiState.value as? ImportUiState.Review ?: return
        val newSelected = if (youtubeId in current.selected) {
            current.selected - youtubeId
        } else {
            current.selected + youtubeId
        }
        _uiState.value = current.copy(selected = newSelected)
    }

    /**
     * Select or deselect all candidates of a given [type].
     * No-op if the current state is not [ImportUiState.Review].
     */
    fun setGroupSelected(type: CandidateType, selected: Boolean) {
        val current = _uiState.value as? ImportUiState.Review ?: return
        val typeIds = current.candidates.filter { it.type == type }.map { it.youtubeId }.toSet()
        val newSelected = if (selected) {
            current.selected + typeIds
        } else {
            current.selected - typeIds
        }
        _uiState.value = current.copy(selected = newSelected)
    }

    /**
     * Begin importing the selected candidates.
     * No-op if the current state is not [ImportUiState.Review].
     */
    fun confirmImport() {
        val current = _uiState.value as? ImportUiState.Review ?: return
        val selectedCandidates = current.selectedCandidates()

        viewModelScope.launch {
            // Collect progress updates into the state while the import runs.
            progressJob?.cancel()
            progressJob = launch {
                importRepository.progress.collect { progress ->
                    // Only update if we're still in Importing (don't overwrite Done/Error).
                    if (_uiState.value is ImportUiState.Importing) {
                        _uiState.value = ImportUiState.Importing(progress)
                    }
                }
            }

            // Emit initial Importing state with the current progress snapshot.
            _uiState.value = ImportUiState.Importing(importRepository.progress.value)

            try {
                val summary = importRepository.import(selectedCandidates)
                progressJob?.cancel()
                progressJob = null
                _uiState.value = ImportUiState.Done(summary)
            } catch (e: Exception) {
                progressJob?.cancel()
                progressJob = null
                _uiState.value = ImportUiState.Error(
                    message = e.message ?: "Import failed",
                    retryable = true,
                )
            }
        }
    }

    /**
     * Restart the flow from the beginning (re-authorize and re-fetch).
     * Can be called from any state.
     */
    fun retry() {
        start()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Handles an [AuthResult] after either [authorize] or [authorizeFromConsentResult].
     * On success transitions to Fetching and invokes the remote source.
     */
    private suspend fun handleAuthResult(result: AuthResult) {
        when (result) {
            is AuthResult.Granted      -> fetchCandidates(result.accessToken)
            is AuthResult.NeedsConsent -> _uiState.value = ImportUiState.NeedsConsent(result.pendingIntent)
            is AuthResult.Denied       -> _uiState.value = ImportUiState.Error("Permission denied", retryable = true)
            is AuthResult.Failed       -> _uiState.value = ImportUiState.Error(
                message = result.error.message ?: "Authorization failed",
                retryable = true,
            )
        }
    }

    /**
     * Fetch candidates from the YouTube Data API using the given [accessToken].
     * On success transitions to [ImportUiState.Review] (or Error if no candidates).
     */
    private suspend fun fetchCandidates(accessToken: String) {
        _uiState.value = ImportUiState.Fetching
        try {
            val fetchResult = remoteSource.fetchAll(accessToken)
            if (fetchResult.candidates.isEmpty()) {
                // Empty result — emit Error rather than an empty Review list.
                // retryable=true when there were partial failures (some types may
                // succeed on a second attempt); retryable=false when everything was
                // fetched successfully but the user simply has nothing importable.
                _uiState.value = ImportUiState.Error(
                    message = "No items found",
                    retryable = fetchResult.failedTypes.isNotEmpty(),
                )
            } else {
                val allIds = fetchResult.candidates.map { it.youtubeId }.toSet()
                _uiState.value = ImportUiState.Review(
                    candidates = fetchResult.candidates,
                    selected = allIds,
                    partialFailureTypes = fetchResult.failedTypes,
                )
            }
        } catch (e: Exception) {
            _uiState.value = ImportUiState.Error(
                message = e.message ?: "Failed to fetch YouTube data",
                retryable = true,
            )
        }
    }
}
