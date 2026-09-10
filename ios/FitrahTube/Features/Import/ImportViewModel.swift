import Foundation
import Observation

/// `ImportViewModel.kt`, over Task 28's three collaborators: the authorizer seam, the three
/// paginators and the write pipeline.
///
/// Idle → Authorizing → Fetching → Review → Importing → Done, with `.error` reachable from the
/// first three. The only state the USER drives is Review; everything else is a step reporting on
/// itself.
///
/// **One run at a time, owned here.** `ImportPipeline` is not re-entrant (Task 28 report) and
/// `YouTubeAuthorizer.authorize()` raises Google's consent sheet itself, so a second tap must be a
/// no-op rather than a second sheet or a second set of writes — Android's F6 double-tap guard
/// (`:120-123`) and its `onConsentLaunched` sticky state (`:76-86`) collapse into this one flag on
/// iOS, where the whole grant is a single await.
@MainActor @Observable final class ImportViewModel {

    private let authorizer: any YouTubeAuthorizer
    private let source: YouTubeImportSource
    private let pipeline: ImportPipeline

    private(set) var state: ImportUiState = .idle
    /// F9's second half: once the token is forgotten the row is replaced by the confirmation, and
    /// the screen has nothing left to ask Google for until the user starts again.
    private(set) var didRevoke = false

    /// Visible (not `private`) only so tests can `await model.job?.value` — the same rationale as
    /// `SuggestContentViewModel.searchTask`.
    private(set) var job: Task<Void, Never>?
    /// The single-run guard itself. A `Task`'s own `isCancelled`/completion is not enough: the
    /// Review → Importing transition happens across a suspension, so two taps in the same frame
    /// both see the old state.
    private var isRunning = false

    init(authorizer: any YouTubeAuthorizer, source: YouTubeImportSource, pipeline: ImportPipeline) {
        self.authorizer = authorizer
        self.source = source
        self.pipeline = pipeline
    }

    // MARK: - The flow

    /// `:59-64`. Authorize, then fetch. A second call while one is in flight does nothing at all —
    /// no second consent sheet, no second fetch.
    func start() {
        guard !isRunning else { return }
        isRunning = true
        didRevoke = false
        state = .authorizing
        job = Task { [self] in
            await authorizeAndFetch()
            isRunning = false
        }
    }

    /// `:161-163`. Identical to `start()`: the flow has no resumable midpoint, because the token is
    /// in memory only and the three paginators are cheap to redo.
    func retry() { start() }

    private func authorizeAndFetch() async {
        do {
            let token = try await authorizer.authorize()
            guard !Task.isCancelled else { return }
            state = .fetching
            let fetched = await source.fetchAll(accessToken: token)
            guard !Task.isCancelled else { return }
            guard !fetched.candidates.isEmpty else {
                // `:194-201`. Retryable ONLY when a type failed — a retry can recover that type,
                // while a user who simply has nothing importable would be offered a button that
                // re-runs the same three empty paginators.
                state = .error(messageKey: "empty_state_no_content",
                               retryable: !fetched.failedTypes.isEmpty)
                return
            }
            // `:209-213`: everything starts selected.
            state = .review(candidates: fetched.candidates,
                            selected: Set(fetched.candidates.map(\.youtubeId)),
                            partialFailures: fetched.failedTypes)
        } catch is CancellationError {
            // F7 (`:154-157`), in the shape a non-throwing `async` method has: a cancelled run
            // writes NOTHING. Turning cooperative cancellation into `.error` would banner a
            // failure at the exact moment the user asked for the work to stop.
        } catch YouTubeAuthorizerError.cancelled {
            // The user dismissed Google's consent sheet. Silent, back to the start — never a
            // banner, and never an error state offering to retry what they just declined.
            state = .idle
        } catch {
            // WHAT, never why: `.unavailable` (the affordance should not have been offered) and
            // `.failed` (the SDK refused) are the same sentence to a user, and neither is a
            // membership fact about their Google account worth spelling out.
            state = .error(messageKey: "auth_error_generic", retryable: true)
        }
    }

    // MARK: - Review

    /// `:90-98`. A no-op outside `.review`.
    func toggle(_ youtubeId: String) {
        guard case .review(let candidates, var selected, let partialFailures) = state else { return }
        if selected.contains(youtubeId) { selected.remove(youtubeId) } else { selected.insert(youtubeId) }
        state = .review(candidates: candidates, selected: selected, partialFailures: partialFailures)
    }

    /// `:104-114`. Select or clear a whole group. A no-op outside `.review`.
    func setGroupSelected(_ type: CandidateType, _ selected: Bool) {
        guard case .review(let candidates, var current, let partialFailures) = state else { return }
        let ids = Set(candidates.filter { $0.type == type }.map(\.youtubeId))
        if selected { current.formUnion(ids) } else { current.subtract(ids) }
        state = .review(candidates: candidates, selected: current, partialFailures: partialFailures)
    }

    // MARK: - The Sharī'ah caution gate (`ImportFromYouTubeFragment.kt:199-217`)

    /// Whether the caution dialog is up. **It lives here, not on the screen.** Acceptance says the
    /// gate cannot be bypassed, and a `@State` flag in the view is bypassed by any later edit that
    /// wires the Import button straight to `confirmImport()`. Here, the only path from Review to
    /// Importing that the screen has is `importTapped()` → `acceptCaution()`, and
    /// `ImportFromYouTubeScreen` names `confirmImport` nowhere at all (pinned).
    private(set) var isCautionPresented = false

    /// The Import button. It starts NOTHING — it raises the gate.
    func importTapped() {
        guard case .review = state, !isRunning else { return }
        isCautionPresented = true
    }

    /// Dismissed, cancelled, swiped away: the selection is untouched and no import ran
    /// (`:213-215` — the negative button passes `null`, i.e. nothing but a dismiss).
    func dismissCaution() { isCautionPresented = false }

    /// `import_caution_continue`. The ONE path into `confirmImport()` the screen has.
    func acceptCaution() {
        guard isCautionPresented else { return }
        isCautionPresented = false
        confirmImport()
    }

    /// `:120-160`. Called by the caution gate's Continue, never by the Import button itself — the
    /// gate is what stands between the two (`ImportFromYouTubeFragment.kt:199-217`).
    func confirmImport() {
        guard case .review = state, !isRunning else { return }
        let chosen = state.selectedCandidates
        isRunning = true
        // `:141-144`: a FRESH zero, not the pipeline's last emission. Seeding from the previous
        // run's value would flash that run's DONE frame for one frame of a re-import.
        state = .importing(.resolving, processed: 0, total: 0)
        job = Task { [self] in
            let summary = await pipeline.run(chosen) { phase, processed, total in
                // `:132-136`: only while still importing. A revoke or a cancel that lands between
                // two chunks must not be painted over by the chunk that was already in flight.
                guard case .importing = state else { return }
                state = .importing(phase, processed: processed, total: total)
            }
            isRunning = false
            guard !Task.isCancelled else { return }
            state = .done(summary)
        }
    }

    // MARK: - Revoke (ruling F9)

    /// Forget the token this device holds, stop anything still using it, and put the screen back
    /// where it started. NEVER `GIDSignIn.disconnect()`: that revokes every scope the user ever
    /// granted — sign-in included — and signs them out of the app. Revoking the GRANT is the
    /// user's own to do, on Google's account-permissions page, which is what the confirmation
    /// links to.
    ///
    /// One rule, whatever is in flight: a fetch still holding that bearer must stop, and an import
    /// (which addresses the FitrahTube backend, not Google) stops with it rather than finishing
    /// under a screen that says access is gone. The chunks already written persist and dedupe on
    /// the next run — the same state a 429 leaves.
    func revoke() {
        job?.cancel()
        job = nil
        isRunning = false
        authorizer.forget()
        state = .idle
        didRevoke = true
    }
}
