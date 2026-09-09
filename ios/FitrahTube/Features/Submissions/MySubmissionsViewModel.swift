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
    /// `nextCursor = null` (`:563`). So `limit` is the whole reach of this list — asking for 50 shows
    /// the 50 most recent rows with no affordance for the rest (fix round 1 / I1; the comment here
    /// used to claim the opposite). The backend caps `limit` at 100, so 100 is also the ceiling.
    /// The cursor path below stays for the day this screen gains a status filter, which is what
    /// actually unlocks the server's paginated single-status branches.
    static let pageSize = 100

    private let client: ApprovalsClient

    private(set) var state: MySubmissionsUiState = .loading
    /// Latched by a failed `loadMore`, read by `PaginationGuard`'s guard 3 — without it a page that
    /// fits the viewport re-fires the same failing request until the attempt cap bites.
    private(set) var paginationError = false
    /// One page in flight at a time, and the screen's footer spinner. Owned HERE rather than as the
    /// screen's `@State` (`ContentListView`'s shape) because fix round 1 / I2 gives this list a
    /// SECOND trigger: a whole frame of `.onAppear`s fires across the last five rows, and the guard
    /// that makes that cost one page has to sit where both triggers meet.
    private(set) var isLoadingMore = false

    private var cursor: String?
    /// The supersession guard, this codebase's `loadGeneration` idiom (wave-3 D1) rather than
    /// Android's cancellable `refreshJob`: a delete's refresh and a pull-to-refresh both call
    /// `refresh()`, and the OLDER answer landing last would re-introduce a row that was just
    /// removed. Every round takes a number and refuses to write unless it is still the current one.
    private var generation = 0

    var hasMore: Bool { cursor != nil }

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
    @discardableResult
    func refresh() async -> String? {
        generation += 1
        let mine = generation
        if case .loaded = state {} else { state = .loading }
        cursor = nil
        paginationError = false
        do {
            let page = try await client.mySubmissions(status: nil, cursor: nil, limit: Self.pageSize)
            guard mine == generation else { return nil }
            cursor = page.nextCursor
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

    /// The next page, appended. Answers whether a fetch was actually STARTED, which is what the
    /// screen's autofill commits its `PaginationGuard` attempt on.
    @discardableResult
    func loadMore() async -> Bool {
        guard let cursor, !paginationError, !isLoadingMore, case .loaded(let current) = state else { return false }
        generation += 1
        let mine = generation
        isLoadingMore = true
        defer { isLoadingMore = false }
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

    /// The PHONE's trigger (fix round 1 / I2). `PaginationGuard`'s guard 1 refuses to autofill on a
    /// compact width, so the screen's autofill alone never runs on the device most users hold —
    /// CLAUDE.md asks for the scroll listener AS WELL, which is `ContentListView`'s `>=` threshold
    /// (`ContentListView.swift:271-281`) and the reason it is `>=` rather than `==`: after a failed
    /// page the count is unchanged, so an `==` threshold cell has already appeared and scrolling on
    /// through the tail does nothing.
    ///
    /// The last five rows all fire in one frame; `loadMore`'s `isLoadingMore` guard is what makes
    /// that ONE page rather than five.
    func rowAppeared(at index: Int) async {
        guard case .loaded(let rows) = state, index >= max(0, rows.count - 5) else { return }
        await loadMore()
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
