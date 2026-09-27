import Foundation
import Observation

/// `MySubmissionsUiState.kt:16-21`, with ONE difference that is the whole point of ruling C13:
/// `.error` is a state this app actually renders. Android's fragment answers its own `Error` arm
/// with `{ /* TODO snackbar in T12 */ }` (`MySubmissionsFragment.kt:70`), so a failed load there is
/// a blank list with no message and no retry.
nonisolated enum MySubmissionsUiState: Equatable {
    case loading, loaded([Submission]), empty, error
}

/// `MySubmissionsViewModel.kt`, over `ApprovalsClient`. Three things it owns: the state machine, the
/// cursor, and the ONE outcome table the two submitter-owned writes share.
@MainActor @Observable final class MySubmissionsViewModel {

    /// Android's 100 (`MySubmissionsRepository.kt:23`), and for the same reason: THIS screen asks
    /// with no `status`, and that is the branch the backend does not paginate. `getMySubmissions`
    /// treats null/empty/`ALL` as `allStatuses` (`ApprovalService.java:496`) and routes to
    /// `getMySubmissionsAllStatuses(submittedBy, type, pageSize)` (`:513`), which takes NO cursor,
    /// merges the four statuses by `submittedAt` desc, truncates to `pageSize` and answers
    /// `nextCursor = null` (`:563`). So `limit` is the whole reach of this list, and the backend
    /// caps it at 100. Part B gate (stage 1 B1): the cursor/`loadMore`/autofill engine that used to
    /// sit behind that null was deleted — working machinery with no wire behind it, the
    /// `AuthMiddleware` precedent — and returns with the first status filter, which is the first
    /// backend branch that pages (CF-B-17).
    static let pageSize = 100

    private let client: ApprovalsClient

    private(set) var state: MySubmissionsUiState = .loading

    /// The supersession guard, this codebase's `loadGeneration` idiom (wave-3 D1) rather than
    /// Android's cancellable `refreshJob`: a delete's refresh and a pull-to-refresh both call
    /// `refresh()`, and the OLDER answer landing last would re-introduce a row that was just
    /// removed. Every round takes a number and refuses to write unless it is still the current one.
    private var generation = 0

    init(client: ApprovalsClient) { self.client = client }

    /// A full re-read from the top.
    ///
    /// Fix round 1 / M6: rows already on screen STAY on screen while their own re-read runs — the
    /// `AccountSession.fetch` precedent (`:323`, "the account ALREADY on screen stays on screen
    /// while its own refresh runs"). Android paints its skeleton here, but on iOS every caller
    /// already says a refresh is happening: `.refreshable` spins its own control, and the two
    /// post-write refreshes come with a banner. Dropping a loaded list to `SkeletonListView` on top
    /// of that reads as a reload, and it is the same double indicator Part A removed. A `.loading`
    /// with nothing behind it — first load, or a re-read after the error/empty arm — still paints
    /// the skeleton, because there the skeleton IS the only indicator.
    /// Re-review nit 1: a TRANSIENT failure with rows on screen keeps the rows and RETURNS the
    /// message to banner — `AccountSession.fetch`'s `.network where … state.me != nil` precedent
    /// (`:369`), "a cached account beats an offline banner". A pull-to-refresh in a lift has no
    /// business replacing a list the user is reading with a Retry card. Discriminated, not blanket:
    /// only `.network` takes that arm. Anything the SERVER decided — a revoked moderator's 403, a
    /// 500, a malformed page — still goes to `.error`, because those say the rows on screen may be
    /// exactly what is wrong. With nothing loaded, `.network` fails to `.error` as before: there the
    /// error card is the only thing on screen.
    ///
    @discardableResult
    func refresh() async -> String? {
        generation += 1
        let mine = generation
        if case .loaded = state {} else { state = .loading }
        do {
            let page = try await client.mySubmissions(limit: Self.pageSize)
            guard mine == generation else { return nil }
            state = page.items.isEmpty ? .empty : .loaded(page.items)
        } catch {
            guard mine == generation else { return nil }
            if error == .network, case .loaded = state {
                return String(localized: "auth_error_network")
            }
            state = .error
        }
        return nil
    }

    // MARK: - The two submitter-owned writes

    /// Returns the message the caller banners (`my_submissions_delete_success` /
    /// `my_submissions_already_reviewed` / `my_submissions_action_failed`).
    func delete(_ submission: Submission) async -> String {
        await perform(success: "my_submissions_delete_success") {
            try await client.deleteSubmission(type: submission.type, id: submission.id)
        }
    }

    func updateNote(_ submission: Submission, note: String) async -> String {
        await perform(success: "my_submissions_edit_success") {
            try await client.updateNote(type: submission.type, id: submission.id, note: note)
        }
    }

    /// The ONE outcome table both writes share — they hit the same two endpoints on the same row
    /// with the same three answers, and `MySubmissionsViewModel.kt` + `EditSubmissionBottomSheet.kt`
    /// carry two copies of it on Android.
    ///
    /// A message is RETURNED rather than published, so the view posts it: two consecutive identical
    /// failures then post two banners. Android needed a `Channel` for exactly that reason
    /// (`:38-41` — a `StateFlow` de-duplicates equal emissions and the second snackbar is lost), and
    /// an `@Observable` property read through `.onChange` would have the same hole.
    ///
    /// Both the success AND the 409 re-read. The 409 means the row was adjudicated while the sheet
    /// was open, so the local list is stale by definition — and the row is NOT removed
    /// optimistically on either path: only what the server answers next decides what is on screen.
    /// `throws`, not `throws(AccountError)`: a closure literal does not infer a typed thrown error
    /// from the parameter position, and the alternative is every caller wrapping its own
    /// `do`/`catch` to hand back a `Result` — which is the duplication this exists to remove. The
    /// client keeps its typed throws; the catch-all here is also the right home for the one other
    /// thing that can arrive, a `CancellationError`, which is a failed write to the user.
    private func perform(success key: String.LocalizationValue,
                         _ write: () async throws -> Void) async -> String {
        do {
            try await write()
        } catch AccountError.conflict {
            await refresh()
            return String(localized: "my_submissions_already_reviewed")
        } catch {
            return String(localized: "my_submissions_action_failed")
        }
        await refresh()
        return String(localized: key)
    }
}
