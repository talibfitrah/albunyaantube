import Foundation

/// `ImportUiState.kt:12-84`, minus the one arm iOS cannot enter.
///
/// **`needsConsent` is deliberately absent.** Android needs it because `YouTubeAuthManager` hands
/// back a `PendingIntent` the fragment has to launch, which makes "the consent screen is up" a
/// state the ViewModel is between two calls of — and a configuration change that re-observed the
/// state could launch a SECOND prompt (`ImportViewModel.kt:76-86`, the `onConsentLaunched` sticky).
/// On iOS the consent sheet is raised INSIDE `YouTubeAuthorizer.authorize()`
/// (`GIDGoogleUser.addScopes(_:presenting:)`), so there is no hand-back, nothing for the screen to
/// launch, and no state between the two halves: the whole grant is one await, and `.authorizing`
/// covers it. A case nothing can ever write is what R7-P3 deleted three `Route` cases for. The
/// PROPERTY that sticky state protected is kept and pinned — the single-run guard in
/// `ImportViewModel` refuses a second `start()` while one is in flight, so a second consent sheet
/// cannot be raised over the first.
///
/// **Empty candidates are an `.error`, never an empty `.review`** (`:22-26`): `retryable` is true
/// only when a type actually FAILED, because a retry can recover that type — a user who simply has
/// nothing importable is not offered one.
nonisolated enum ImportUiState: Equatable {
    case idle
    case authorizing
    case fetching
    case review(candidates: [ImportCandidate], selected: Set<String>, partialFailures: Set<CandidateType>)
    case importing(ImportPhase, processed: Int, total: Int)
    case done(ImportSummary)
    case error(messageKey: String, retryable: Bool)
}

extension ImportUiState {
    /// `Review.selectedCandidates()` (`:57-58`), in the order they were fetched: the pipeline
    /// chunks in list order, so a stable order is what makes a partial run resumable in the same
    /// order on retry.
    var selectedCandidates: [ImportCandidate] {
        guard case .review(let candidates, let selected, _) = self else { return [] }
        return candidates.filter { selected.contains($0.youtubeId) }
    }
}

extension ImportSummary {
    /// Task 28 re-review's ruling, as one predicate: a run the daily cap, a cancel or a dead
    /// connection cut short wrote FEWER rows than it set out to. Read off the summary alone —
    /// never off the transient DONE progress emission, which a screen that keeps the summary has
    /// already dropped by the time it renders (review I1).
    var isPartial: Bool { processed < total }
}
