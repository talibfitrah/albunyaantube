package com.albunyaan.tube.ui.me.importflow

import android.app.PendingIntent
import com.albunyaan.tube.data.importflow.ImportProgress
import com.albunyaan.tube.data.importflow.ImportSummary
import com.albunyaan.tube.data.youtube.CandidateType
import com.albunyaan.tube.data.youtube.ImportCandidate

/**
 * B10: UI state for the YouTube-import flow.
 *
 * Transitions:
 *   Idle
 *    → Authorizing          (start() called)
 *    → NeedsConsent         (OAuth consent screen required)
 *    → Fetching             (token obtained; fetching YouTube lists)
 *    → Review               (candidates ready; user selects items)
 *    → Importing            (confirmImport() called)
 *    → Done                 (import finished)
 *    → Error                (any failure; retryable controls whether retry() is offered)
 *
 * Empty-candidates policy: when fetchAll() returns zero candidates (regardless of
 * failedTypes), we emit Error("No items found", retryable=false) rather than an
 * empty Review list. This keeps the fragment's happy path clean — it only renders
 * Review when there is actually something to show. If there were also failedTypes,
 * the caller can still retry (retryable=true).
 */
sealed interface ImportUiState {

    /** Initial state before the user has triggered anything. */
    data object Idle : ImportUiState

    /** OAuth authorization is in progress. */
    data object Authorizing : ImportUiState

    /**
     * The Google consent screen must be shown.
     *
     * The fragment should launch [pendingIntent] via an ActivityResult contract,
     * then pass the result back via [ImportViewModel.onConsentResult].
     */
    data class NeedsConsent(val pendingIntent: PendingIntent) : ImportUiState

    /** Candidates are being fetched from the YouTube Data API. */
    data object Fetching : ImportUiState

    /**
     * Candidates are ready for user review.
     *
     * @param candidates         All fetched candidates (may be multi-typed).
     * @param selected           Set of [ImportCandidate.youtubeId] values that are
     *                           currently selected. Starts as all youtubeIds.
     * @param partialFailureTypes Any [CandidateType] whose fetch failed; shown as
     *                           a warning in the UI but does not block import.
     */
    data class Review(
        val candidates: List<ImportCandidate>,
        val selected: Set<String>,
        val partialFailureTypes: Set<CandidateType>,
    ) : ImportUiState {

        /** Convenience: candidates whose youtubeId is in [selected]. */
        fun selectedCandidates(): List<ImportCandidate> =
            candidates.filter { it.youtubeId in selected }
    }

    /**
     * Import is in progress.
     *
     * [progress] is kept in sync with [YouTubeImportRepository.progress] so the
     * fragment can show a determinate progress indicator.
     */
    data class Importing(val progress: ImportProgress) : ImportUiState

    /** Import completed successfully. */
    data class Done(val summary: ImportSummary) : ImportUiState

    /**
     * An error occurred.
     *
     * @param message   Human-readable description (may be shown in the UI).
     * @param retryable Whether [ImportViewModel.retry] is a sensible next action.
     */
    data class Error(val message: String, val retryable: Boolean) : ImportUiState
}
