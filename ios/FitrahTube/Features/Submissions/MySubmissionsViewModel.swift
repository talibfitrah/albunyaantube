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

    /// Android asks for 100 in one page and never paginates (`MySubmissionsRepository.kt:26`); the
    /// backend caps `limit` at 100 and defaults to 20. 50 with real pagination behind it costs less
    /// on the first paint and, unlike Android, cannot silently truncate a prolific submitter's list
    /// at the hundredth row.
    static let pageSize = 50

    private let client: ApprovalsClient

    private(set) var state: MySubmissionsUiState = .loading
    /// Latched by a failed `loadMore`, read by `PaginationGuard`'s guard 3 — without it a page that
    /// fits the viewport re-fires the same failing request until the attempt cap bites.
    private(set) var paginationError = false

    private var cursor: String?
    /// The supersession guard, this codebase's `loadGeneration` idiom (wave-3 D1) rather than
    /// Android's cancellable `refreshJob`: a delete's refresh and a pull-to-refresh both call
    /// `refresh()`, and the OLDER answer landing last would re-introduce a row that was just
    /// removed. Every round takes a number and refuses to write unless it is still the current one.
    private var generation = 0

    var hasMore: Bool { cursor != nil }

    init(client: ApprovalsClient) { self.client = client }

    /// A full re-read from the top. `.loading` first, exactly as Android does — the rows on screen
    /// are about to be replaced wholesale and a list that keeps stale rows through a refresh cannot
    /// show a deletion landing.
    func refresh() async {
        generation += 1
        let mine = generation
        state = .loading
        cursor = nil
        paginationError = false
        do {
            let page = try await client.mySubmissions(status: nil, cursor: nil, limit: Self.pageSize)
            guard mine == generation else { return }
            cursor = page.nextCursor
            state = page.items.isEmpty ? .empty : .loaded(page.items)
        } catch {
            guard mine == generation else { return }
            state = .error
        }
    }

    /// The next page, appended. Answers whether a fetch was actually STARTED, which is what the
    /// screen's autofill commits its `PaginationGuard` attempt on.
    @discardableResult
    func loadMore() async -> Bool {
        guard let cursor, !paginationError, case .loaded(let current) = state else { return false }
        generation += 1
        let mine = generation
        do {
            let page = try await client.mySubmissions(status: nil, cursor: cursor, limit: Self.pageSize)
            guard mine == generation else { return true }
            self.cursor = page.nextCursor
            state = .loaded(current + page.items)
        } catch {
            guard mine == generation else { return true }
            // The rows already on screen SURVIVE a failed page — there is nothing wrong with them,
            // and replacing them with a full-page error would punish the user for the tail.
            paginationError = true
        }
        return true
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
