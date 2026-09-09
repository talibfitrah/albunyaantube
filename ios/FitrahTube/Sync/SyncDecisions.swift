import Foundation

/// What `bind(uid:)` does when an account arrives (`SyncManager.kt:78-96`).
nonisolated enum BindAction: Equatable, Sendable {
    /// No binding row -- this device's anonymous rows are tagged to the uid and merged additively.
    case merge
    /// Same account, its one-time merge already finished: a plain delta pull, then a dirty drain.
    case pullThenPush
    /// A different account. The PREVIOUS uid is carried because the atomic transaction tags this
    /// device's `userId == ""` rows to it *before* wiping them -- tagging them to the NEW uid is
    /// exactly how Android transferred one user's local data to another (R-final5 / R-final6).
    case switchAccount(previousUid: String)
}

/// The §16 merge matrix: every interesting sync decision as a pure function, so the actor that
/// eventually runs them (Task 23) has no branch of its own worth testing. No I/O, no clock, no
/// store -- `nonisolated` on purpose.
nonisolated enum SyncDecisions {

    // MARK: - bind

    static func bind(binding: (userId: String, initialMergeDone: Bool)?, uid: String) -> BindAction {
        guard let binding else { return .merge }
        guard binding.userId == uid else { return .switchAccount(previousUid: binding.userId) }
        // Same account with an unfinished merge: a prior merge crashed mid-way, so re-enter it
        // rather than start pulling over half-merged rows.
        return binding.initialMergeDone ? .pullThenPush : .merge
    }

    // MARK: - Pull, per row (`SyncManager.kt:208-267`)

    enum RowAction: Equatable, Sendable {
        case applyTombstone, applyRow, skipDirty, skipStaleTombstone
    }

    /// A tombstone applies under a monotonicity guard -- Room's predicate is `updated_at < :ts`, so
    /// an older tombstone can never resurrect a newer row, and an absent local row has nothing to
    /// tombstone (the UPDATE is a no-op there); both are `.skipStaleTombstone`. `dirty` does NOT
    /// protect a row from a newer tombstone: `applyTombstone` clears the flag itself.
    ///
    /// Otherwise the server row is SKIPPED whenever the local row is dirty. `dirty == true` alone
    /// is the conflict signal -- a `localUpdatedAt > serverUpdatedAt` clause would be vacuous,
    /// because local writes never bump `updatedAt` (it is server-stamped on push success).
    static func rowAction(serverDeleted: Bool, serverUpdatedAt: Int,
                          localExists: Bool, localDirty: Bool, localUpdatedAt: Int) -> RowAction {
        if serverDeleted {
            return localExists && localUpdatedAt < serverUpdatedAt ? .applyTombstone : .skipStaleTombstone
        }
        return localExists && localDirty ? .skipDirty : .applyRow
    }

    // MARK: - The stalled-cursor guard (`SyncManager.kt:326-363`)

    enum PageDecision: Equatable, Sendable {
        /// Keep pulling.
        case advance
        /// A cursor was minted but nothing moved: the next request returns the same rows. Observed
        /// in production when a stored `updatedAt` carried sub-millisecond precision the millisecond
        /// cursor could not express, so the server's `startAfter()` never passed the row -- the loop
        /// ran unthrottled at ~3 req/s, starved the shared HTTP client and pinned the app on the
        /// splash screen. Stop, and say so: a stalled cursor means this account silently stops
        /// receiving server changes.
        case stalled
        /// Every type returned a null cursor. Normal, and NOT the same condition as `.stalled`.
        case exhausted
    }

    static func page(mintedCursor: Bool,
                     cursorsBefore: [String: Int], cursorsAfter: [String: Int],
                     idsBefore: [String: String?], idsAfter: [String: String?]) -> PageDecision {
        guard mintedCursor else { return .exhausted }
        // A type that advanced only its `lastDocId` still advanced: the next request starts after
        // that document, which is progress inside a group of rows sharing one millisecond.
        let advanced = cursorsAfter != cursorsBefore || idsAfter != idsBefore
        return advanced ? .advance : .stalled
    }

    // MARK: - Push classifier (`SyncManager.kt:575-617`)

    enum PushOutcome: Equatable, Sendable {
        case ok, authFailed, permanentFailure, transientFailure
    }

    /// - 2xx WITH a body -> `.ok`.
    /// - 2xx with NO body -> `.transientFailure` (R-final7 P0). Returning `.ok` meant the caller's
    ///   `clearDirty` never ran, so the row stayed dirty and re-pushed forever.
    /// - 404 -> `.ok`: an idempotent DELETE, nothing left to do.
    /// - 401 / 403 -> `.authFailed`, which breaks the drain.
    /// - 400 / 409 / 422 -> `.permanentFailure`: the payload is bad and the same bytes will fail
    ///   the same way, so the caller clears dirty with a local warning rather than let one
    ///   malformed row block pulls forever.
    /// - everything else (5xx, 429, transport) -> `.transientFailure`.
    static func push(status: Int, hasBody: Bool) -> PushOutcome {
        switch status {
        case 200...299: return hasBody ? .ok : .transientFailure
        case 404: return .ok
        case 401, 403: return .authFailed
        case 400, 409, 422: return .permanentFailure
        default: return .transientFailure
        }
    }

    // MARK: - Pull classifier (Task 22 review / I1; added by Task 23)

    enum PullOutcome: Equatable, Sendable {
        /// The account is gone or blocked. Stop the run and let go of the retry chain; Part A owns
        /// what happens to the session (`AuthorizedTransport` turns the 403 envelope into an
        /// `AccountStatusEvent`, `AccountSession` routes it).
        case terminal
        /// A transport error, a decode failure, a 5xx or a 429 — the bounded ladder, then give up
        /// for THIS run and keep the cursor exactly where it was.
        case transient
        /// The server rejected the REQUEST. Retrying the same bytes cannot help.
        case permanent
    }

    /// The pull half of the table `push` already holds. Without it a generic retry turns a revoked
    /// account's 401 into an unbounded loop against a server that will never say yes — the same
    /// class of bug as the unthrottled stalled-cursor spin, and just as invisible.
    ///
    /// `status` is nil for anything that is not an HTTP verdict: a transport error, or a response
    /// the decoder refused (`SyncPage.items` is required, so one page omitting it fails the whole
    /// three-type decode). Both are transient — the run stops, the cursors stay, nothing is wiped.
    static func pull(status: Int?) -> PullOutcome {
        switch status {
        case 401, 403: return .terminal
        case 400: return .permanent
        default: return .transient
        }
    }
}
