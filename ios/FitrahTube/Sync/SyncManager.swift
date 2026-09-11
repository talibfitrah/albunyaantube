import Foundation
import SwiftData

/// Task 24's view of the manager: the four calls the five trigger sites make, and nothing else.
///
/// A protocol only because the wiring has to be assertable. Every trigger site is a one-liner whose
/// whole content is *which* call it makes, with which uid, and under what guard — and pinning that
/// against the real actor would mean building a `ModelContainer`, a transport and a whole drain to
/// observe one method name. `SyncManager` is the only production conformer.
nonisolated protocol SyncTriggering: Sendable {
    func bind(uid: String) async
    func unbind() async
    func pushDirty(uid: String) async
    func syncNow(uid: String) async
}

extension SyncManager: SyncTriggering {}

/// Plan D's `SyncManager.kt`, in Swift: the thin actor over `SyncDecisions`. Every branch worth
/// testing is a pure function in that file; what lives HERE is the exclusion, the I/O order and the
/// transactions.
///
/// **The exclusion is not the actor.** An `actor` serialises message delivery, not critical
/// sections: every `await` inside an isolated method is a reentrancy point, so a second `syncNow()`
/// runs its pull while the first is parked in the network. Android's version had a real
/// `Mutex` — and even that started as two of them (`pullMutex`/`pushMutex`), which let a pull read
/// the server `updatedAt` while a push wrote concurrently and persist a stale cursor tail
/// (`SyncManager.kt:44-56`). ONE exclusion covers bind, pull AND push, and `unbind` takes it too so
/// an account switch cannot slip writes tagged with the wrong uid into the new user's tables.
actor SyncManager {

    // MARK: - Dependencies

    private let client: any SyncTransporting
    private let modelContainer: ModelContainer
    private var backoff: SyncBackoff
    /// Injected so the gate stays hermetic — the retry ladder never touches a real clock in tests.
    private let sleep: @Sendable (Duration) async -> Void

    // MARK: - The exclusion

    private var inFlight = false
    private var waiters: [CheckedContinuation<Void, Never>] = []
    private var pendingRetry: Task<Void, Never>?
    private var retriesLeft = SyncManager.maxPushRetries

    /// **The identity fence** (Part B gate, stage 3 I-4 / stage 5 C1 / stage 4 S2). The exclusion
    /// serialises WORK; it does not serialise identity. `AuthorizedTransport` mints the bearer per
    /// request from whoever Firebase says is current, so a drain for A that is still walking its
    /// rows when B signs in signs A's remaining PUTs with B's token — and the container's coalesced
    /// follow-up (`AppContainer.pushDirtySoon`) is a second scheduler `unbindLocked()` never sees.
    /// Both are closed by the same two facts, written OUTSIDE the lock so an in-flight run can read
    /// them mid-drain: `boundUid` is the ONE uid any operation may run for, and `epoch` moves on
    /// every bind/unbind so a run captures the number it started under and stops the moment it
    /// changes — before the next request, before the next page write, before the next `clearDirty`.
    private var boundUid: String?
    private var epoch = 0

    /// Stage 5 I3: the stores keep their own long-lived `ModelContext` and re-read only on their
    /// own writes, so a pull that restored a whole library on a fresh device rendered NOTHING until
    /// the next toggle or relaunch. Called on the main actor after every committed sync write.
    private let onWrite: (@MainActor @Sendable () -> Void)?

    /// Stage 4 S6 / Codex 10: `.advance` accepts any cursor pair that differs from the last, so a
    /// server that keeps minting a moving cursor (or cycles between two) is neither exhausted nor
    /// stalled, and the loop holds the ONE exclusion for as long as it runs. Same guard as
    /// `YouTubeImportSource.maxPages`; injectable so a test can hit it in three pages.
    private let maxPullPages: Int

    /// The push retry ladder is BOUNDED. `SyncClient.put` maps BOTH "no body" and "undecodable
    /// body" to a `.transientFailure`, and an undecodable body is not transient at all — it fails
    /// identically forever. Android chains one retry per transient drain with no cap, so that shape
    /// would re-push the same row until the process dies. After the ladder the rows keep their dirt,
    /// the run reports it, and the next trigger starts a fresh ladder.
    private static let maxPushRetries = 3
    /// The pull's own bounded ladder (`SyncManager.kt:170-200` — three attempts, 200 ms / 400 ms).
    private static let maxPullAttempts = 3

    /// What a support engineer needs and cannot get from a packet capture: the stalled cursor pair,
    /// the cursor id dropped before the request, the undecodable push echo. Capped, because this is
    /// diagnostic breadcrumbs, not a log file.
    private(set) var incidents: [String] = []
    private static let maxIncidents = 50

    #if DEBUG
    /// CF-A-16's injection point. The account switch is the one all-or-nothing write in this file
    /// and nothing on the SwiftData path can be made to fail on demand, so the rollback assertion
    /// needs a throw it can place exactly — between the `SyncState` clear and the `AccountBinding`
    /// insert, which is the hard case (`#Unique<AccountBinding>([\.userId])` upserts at SAVE time,
    /// not at insert time). DEBUG-only, like `ScriptedTransport`; the Release stage compiles it out.
    ///
    /// `@TaskLocal`, not a plain static: Swift Testing runs suites in parallel, and a global would
    /// be seen by whichever OTHER test happened to be switching accounts at the time (observed —
    /// the happy-path switch failed with the rollback test's injected error). A task-local is
    /// scoped to the task tree that set it and propagates through the `MainActor.run` hop.
    @TaskLocal static var injectedSwitchFailure: (@Sendable () throws -> Void)?

    /// Review M3's seam, and the twin of the above for the same reason: nothing on the SwiftData
    /// path can be made to fail on demand, so "a save that throws is REPORTED, not swallowed"
    /// needs an injected throw. Read by `SyncStore.save`, which is every write outside the two
    /// transactions that throw on their own.
    @TaskLocal static var injectedSaveFailure: (@Sendable () throws -> Void)?
    #endif

    init(client: any SyncTransporting, modelContainer: ModelContainer,
         backoff: SyncBackoff, sleep: @escaping @Sendable (Duration) async -> Void,
         onWrite: (@MainActor @Sendable () -> Void)? = nil, maxPullPages: Int = 200) {
        self.client = client
        self.modelContainer = modelContainer
        self.backoff = backoff
        self.sleep = sleep
        self.onWrite = onWrite
        self.maxPullPages = maxPullPages
    }

    // MARK: - Public triggers, each under the ONE exclusion

    /// The identity is taken BEFORE the lock (see `boundUid`): a bind queued behind another
    /// account's drain is what stops that drain at its next row.
    func bind(uid: String) async {
        boundUid = uid
        epoch += 1
        await acquire(); defer { release() }
        await bindLocked(uid)
    }

    func unbind() async {
        boundUid = nil
        epoch += 1
        await acquire(); defer { release() }
        unbindLocked()
    }

    func pushDirty(uid: String) async {
        await acquire(); defer { release() }
        await pushDirtyLocked(uid)
    }

    /// Only tests call this directly; production pulls through `bind` and `syncNow`. Kept as the
    /// seam that lets the pull loop be pinned without a merge in front of it.
    func pullAll(uid: String) async {
        await acquire(); defer { release() }
        _ = await pullAllLocked(uid)
    }

    /// The foreground trigger: pull, then push — the push only if the pull did not end on a
    /// terminal verdict or an identity change (stage 5 I5).
    func syncNow(uid: String) async {
        await acquire(); defer { release() }
        guard await pullAllLocked(uid) else { return }
        await pushDirtyLocked(uid)
    }

    #if DEBUG
    /// Test seam, the `@TaskLocal`s' sibling: a suite that pins the pull loop or a single drain
    /// must not have to script a whole merge first to satisfy the identity fence.
    func assumeBound(uid: String) { boundUid = uid; epoch += 1 }
    #endif

    // MARK: - The exclusion itself

    /// DIRECT HANDOFF, not "clear the flag and let them race": `release()` resumes the head of the
    /// queue with `inFlight` still set, so a caller arriving between the resume and the resumed
    /// task actually running cannot barge past the queue, and a wake-up cannot be lost to the gap.
    private func acquire() async {
        guard inFlight else { inFlight = true; return }
        await withCheckedContinuation { waiters.append($0) }
    }

    /// Called from `defer` on EVERY exit path — including a caller cancelled mid-`await`, which is
    /// how a hand-rolled exclusion usually wedges: one cancellation strands the flag and every
    /// later sync silently never runs.
    private func release() {
        if waiters.isEmpty { inFlight = false } else { waiters.removeFirst().resume() }
    }

    private func unbindLocked() {
        // FIRST, and under the exclusion: a retry queued while the user was still signed in
        // otherwise fires after sign-out and pushes the previous account's dirty rows under
        // whatever bearer is current (`SyncManager.kt:614-628`, cubic R7 P2 / R8 P2).
        pendingRetry?.cancel()
        pendingRetry = nil
        backoff.reset()
        retriesLeft = Self.maxPushRetries
    }

    // MARK: - bind

    private func bindLocked(_ uid: String) async {
        // Stage 3 M-1: the same guard `pushDirtyLocked` has — a bind handed the exclusion under a
        // cancelled task must not run its SwiftData half and then skip the pull and the push.
        guard !Task.isCancelled, uid == boundUid else { return }
        let container = modelContainer
        switch SyncDecisions.bind(binding: await MainActor.run { SyncStore.binding(container) }, uid: uid) {
        case .merge:
            note(await MainActor.run { SyncStore.beginBinding(container, uid: uid) })
            await mergeLocked(uid)
        case .pullThenPush:
            // Stage 5 M1: guest rows written between two sessions of the SAME account are claimed
            // here too. `tagAnonRows` is idempotent and the merge's additive semantics already
            // accept it; without this line a favorite made while signed out was invisible while
            // signed in, reappeared on the next sign-out, and was never pushed.
            note(await MainActor.run { SyncStore.tagAnonRows(container, to: uid) })
            await notifyWrite()
            guard await pullAllLocked(uid) else { return }
            await pushDirtyLocked(uid)
        case .switchAccount(let previousUid):
            do {
                try await MainActor.run { try SyncStore.switchAccount(container, from: previousUid, to: uid) }
            } catch {
                // Nothing was applied — the merge must NOT run on top of a half-applied switch, or
                // it re-tags whatever survived to the new uid, which is the data transfer the
                // transaction exists to prevent.
                note("account switch rolled back, nothing applied: \(error)")
                return
            }
            await notifyWrite()
            await mergeLocked(uid)
        }
    }

    /// `SyncManager.kt:136-147`, in this order. Tagging after the pull loses an anon row to a
    /// server row of the same id; marking the merge done before the drain means a crash mid-push
    /// never re-enters the merge. A pull that ended on a terminal verdict (or under a changed
    /// identity) ends the merge too: pushing under a bearer the server just refused and then
    /// stamping `initialMergeDone` for a terminal account is the "halves do not talk" seam of
    /// stage 5 I5.
    private func mergeLocked(_ uid: String) async {
        let container = modelContainer
        note(await MainActor.run { SyncStore.tagAnonRows(container, to: uid) })
        await notifyWrite()
        guard await pullAllLocked(uid) else { return }
        await pushDirtyLocked(uid)
        note(await MainActor.run { SyncStore.markMergeDone(container, uid: uid) })
    }

    private func notifyWrite() async {
        guard let onWrite else { return }
        await MainActor.run { onWrite() }
    }

    // MARK: - Pull

    /// `false` when the run stopped for a reason the caller must respect: a terminal verdict, a
    /// cancellation, or the identity moving underneath it. `true` covers every other exit —
    /// exhausted, stalled, gave up, rejected — after which a push is still the right next step.
    private func pullAllLocked(_ uid: String) async -> Bool {
        guard !Task.isCancelled, uid == boundUid else { return false }
        let run = epoch
        let container = modelContainer
        var loaded = await MainActor.run { SyncStore.cursors(container, uid: uid) }
        for dropped in loaded.dropped {
            // `SyncClient` drops an id the server would 400 on, silently. Silently is how a pull
            // that has quietly lost its same-millisecond tiebreaker looks exactly like a healthy
            // one, so it is said out loud beside the stall line and by the same helper.
            note("dropped invalid lastDocId for \(dropped.type): \(dropped.id)")
        }
        var cursors = loaded.cursors
        var ids = loaded.ids
        var pages = 0

        while !Task.isCancelled {
            guard pages < maxPullPages else {
                note("pull stopped at the \(maxPullPages)-page cap, cursor kept")
                return true
            }
            switch await pullPage(uid: uid, cursors: cursors, ids: ids) {
            case .page(let body):
                // The fence, checked AFTER the network await and BEFORE the write: the page was
                // requested under this identity, and it is written only if that is still true.
                guard epoch == run, !Task.isCancelled else {
                    note("pull stopped: identity changed while page \(pages + 1) was in flight")
                    return false
                }
                pages += 1
                let cursorsBefore = cursors, idsBefore = ids
                let advanced: SyncStore.Advance
                do {
                    advanced = try await MainActor.run {
                        try SyncStore.applyPage(container, uid: uid, body: body)
                    }
                } catch {
                    note("page write failed, cursor left where it was: \(error)")
                    return true
                }
                for skipped in advanced.skipped { note("skipped a server row with an invalid id: \(skipped)") }
                await notifyWrite()
                cursors.merge(advanced.cursors) { _, new in new }
                ids.merge(advanced.ids) { _, new in new }

                let minted = body.subscriptions.nextCursor != nil || body.playlists.nextCursor != nil
                    || body.favorites.nextCursor != nil
                switch SyncDecisions.page(mintedCursor: minted, cursorsBefore: cursorsBefore,
                                          cursorsAfter: cursors, idsBefore: idsBefore, idsAfter: ids) {
                case .advance:
                    continue
                case .exhausted:
                    return true
                case .stalled:
                    noteStall(cursorsBefore: cursorsBefore, cursorsAfter: cursors,
                              idsBefore: idsBefore, idsAfter: ids)
                    return true
                }
            case .stopped:
                return true
            case .terminal:
                return false
            }
        }
        return false
    }

    private enum PullStep { case page(SyncResponse), stopped, terminal }

    /// One page, with the three-armed failure classifier in front of the ladder. `.stopped` when
    /// the run must end but a push may follow; `.terminal` when nothing may.
    private func pullPage(uid: String, cursors: [String: Int],
                          ids: [String: String?]) async -> PullStep {
        var attempt = 1
        while true {
            do {
                return .page(try await client.pull(cursors: cursors, ids: ids))
            } catch {
                let status = Self.pullStatus(of: error)
                switch SyncDecisions.pull(status: status) {
                case .terminal:
                    // The account is gone or blocked. Part A owns the routing (`AuthorizedTransport`
                    // turns the 403 envelope into an `AccountStatusEvent` and `AccountSession` acts
                    // on it); this side just stops and lets go of the retry chain.
                    note("pull terminal (status \(status.map(String.init) ?? "none")); stopping and unbinding")
                    unbindLocked()
                    return .terminal
                case .permanent:
                    note("pull rejected the request (status \(status.map(String.init) ?? "none")); no retry")
                    // Review I1. The stored tiebreaker is the only part of this request the client
                    // can be wrong about on its own — the pre-request validator is a hand
                    // transcription of the server's, and a 400 is precisely the case where the two
                    // have drifted. Keeping it wedges the pull FOREVER: same id, same 400, same
                    // line, no self-heal. The status covers the whole three-type request, so it
                    // cannot say which id was bad and all three go; the cost is a re-fetch from the
                    // stored millisecond, which `applyPage` upserts.
                    let cleared = await MainActor.run { SyncStore.dropCursorIds(modelContainer, uid: uid) }
                    note(cleared.failure)
                    for dropped in cleared.dropped {
                        note("dropped server-rejected lastDocId for \(dropped.type): \(dropped.id)")
                    }
                    return .stopped
                case .transient:
                    guard attempt < Self.maxPullAttempts else {
                        note("pull gave up after \(attempt) attempts, cursor kept: \(error)")
                        return .stopped
                    }
                    await sleep(.milliseconds(200 * attempt))
                    attempt += 1
                }
            }
        }
    }

    /// nil for anything that is not an HTTP verdict — a transport error, or a decode failure
    /// (`SyncPage.items` is a required key, so a page that omits it fails the WHOLE response
    /// decode). Both are transient by the classifier, and neither ever wipes anything.
    private static func pullStatus(of error: any Error) -> Int? {
        guard case .pullStatus(let status)? = error as? SyncClientError else { return nil }
        return status
    }

    // MARK: - Push

    private func pushDirtyLocked(_ uid: String) async {
        // Review M1. `acquire()` is a `withCheckedContinuation` and is not cancellation-aware, so a
        // caller cancelled while QUEUED on the exclusion stays parked and is then handed the
        // critical section — the retry cancelled by `unbindLocked()` from inside an in-flight pull
        // is exactly that, and it would drain the previous account's rows under whatever bearer is
        // current next. The retry's own guard runs before `acquire()` and cannot see this.
        guard !Task.isCancelled, uid == boundUid else { return }
        let run = epoch
        let container = modelContainer
        var transient = false
        var authFailed = false

        for type in SyncEntityType.allCases {
            let (rows, failure) = await MainActor.run { SyncStore.dirtyRows(container, uid: uid, type: type) }
            note(failure)
            for row in rows {
                // The fence, before every request: a bind or unbind that arrived while the previous
                // row was on the wire moves `epoch`, and the rest of this drain belongs to nobody.
                guard epoch == run else {
                    note("push stopped: identity changed mid-drain")
                    return
                }
                let outcome = await push(row, type: type, uid: uid, run: run)
                switch outcome {
                case .ok:
                    break
                case .authFailed:
                    authFailed = true
                case .permanentFailure:
                    // The same bytes will fail the same way, so the row is cleared with a warning
                    // rather than left to block every later pull forever.
                    note("push permanently rejected \(type.rawValue)/\(row.id); dropping its dirty flag")
                    note(await MainActor.run {
                        SyncStore.clearDirty(container, uid: uid, type: type, id: row.id,
                                             serverUpdatedAt: nil, pushedRemoved: row.isRemoved)
                    })
                case .transientFailure:
                    transient = true
                }
                if authFailed { break }
            }
            if authFailed { break }
        }

        // Stage 5 M2: a refused bearer is not a transient the ladder can wait out — it would
        // re-drain under the same refused token up to three times.
        scheduleRetry(uid: uid, needed: transient && !authFailed)
    }

    private func push(_ row: SyncStore.PendingPush, type: SyncEntityType,
                      uid: String, run: Int) async -> SyncDecisions.PushOutcome {
        let container = modelContainer
        do {
            switch row.payload {
            case .encodeFailed:
                note("push body could not be encoded for \(type.rawValue)/\(row.id)")
                return .permanentFailure
            case .tombstone:
                let status = try await client.delete(type, id: row.id)
                // `hasBody: true` deliberately: this endpoint answers with the row DTO, but
                // `SyncTransporting.delete` hands back only the status (Task 22, ruling F1) because
                // a tombstone needs nothing from the body. Passing `false` would classify every
                // successful DELETE as transient and re-push it forever.
                let outcome = SyncDecisions.push(status: status, hasBody: true)
                // The fence again, after the await: the echo of a request signed under the previous
                // identity must not clear a flag on rows the new identity now owns.
                guard epoch == run else { return .authFailed }
                if outcome == .ok {
                    note(await MainActor.run {
                        SyncStore.clearDirty(container, uid: uid, type: type, id: row.id,
                                             serverUpdatedAt: nil, pushedRemoved: row.isRemoved)
                    })
                }
                return outcome
            case .body(let body):
                let (status, echo) = try await client.put(type, id: row.id, body: body)
                if (200...299).contains(status) && echo == nil {
                    note("push \(type.rawValue)/\(row.id) answered \(status) with no decodable body")
                }
                let outcome = SyncDecisions.push(status: status, hasBody: echo != nil)
                guard epoch == run else { return .authFailed }
                if outcome == .ok, let echo {
                    note(await MainActor.run {
                        // SYNC-ECHO-01: `deleted: true` on a PUT means the server's projection
                        // knows a parent was archived, so the row is tombstoned locally rather
                        // than merely cleared. Through the tombstone writer, never `SyncCodec`.
                        echo.deleted
                            ? SyncStore.tombstone(container, uid: uid, type: type, id: row.id, at: echo.updatedAt)
                            : SyncStore.clearDirty(container, uid: uid, type: type, id: row.id,
                                                   serverUpdatedAt: echo.updatedAt, pushedRemoved: row.isRemoved)
                    })
                }
                return outcome
            }
        } catch {
            return .transientFailure
        }
    }

    private func scheduleRetry(uid: String, needed: Bool) {
        guard needed else {
            // Stage 3 M-2: a drain that succeeded owes nothing to a retry a previous one armed.
            pendingRetry?.cancel()
            pendingRetry = nil
            backoff.reset()
            retriesLeft = Self.maxPushRetries
            return
        }
        guard retriesLeft > 0 else {
            note("push retry ladder exhausted; rows stay dirty for the next trigger")
            backoff.reset()
            retriesLeft = Self.maxPushRetries
            return
        }
        retriesLeft -= 1
        let wait = backoff.next()
        pendingRetry?.cancel()
        let sleep = self.sleep
        pendingRetry = Task { [weak self] in
            await sleep(wait)
            guard !Task.isCancelled else { return }
            await self?.pushDirty(uid: uid)
        }
    }

    // MARK: - Incidents

    /// The `SyncStore` half cannot reach the ring: it runs on the main actor and every entry point
    /// is behind a `MainActor.run` hop, so a failed save comes back as a line to note or nil.
    private func note(_ failure: String?) { if let failure { note(failure) } }

    private func note(_ line: String) {
        // Stage 4 S4: lines carry server document ids and SwiftData error descriptions (which can
        // embed row values); the ring is the assertable surface, the console is Debug-only like
        // every other diagnostic in the app target.
        #if DEBUG
        print("SyncManager: \(line)")
        #endif
        incidents.append(line)
        if incidents.count > Self.maxIncidents { incidents.removeFirst() }
    }

    /// `SyncDecisions.page` compares whole dictionaries, so it can say THAT the cursor stalled and
    /// not which type did. The incident this guard exists for — a stored `updatedAt` carrying
    /// sub-millisecond precision the millisecond cursor cannot express, so the server's
    /// `startAfter()` never passes the row — is undiagnosable without the pair.
    private func noteStall(cursorsBefore: [String: Int], cursorsAfter: [String: Int],
                           idsBefore: [String: String?], idsAfter: [String: String?]) {
        let detail = SyncEntityType.allCases.map { type -> String in
            let key = type.rawValue
            let before = "\(cursorsBefore[key] ?? 0)/\((idsBefore[key] ?? nil) ?? "-")"
            let after = "\(cursorsAfter[key] ?? 0)/\((idsAfter[key] ?? nil) ?? "-")"
            return "\(key) \(before) -> \(after)"
        }.joined(separator: ", ")
        note("pull stalled, cursor did not advance: \(detail)")
    }
}

// MARK: - The store half

/// Everything that touches SwiftData, on the main actor — `@Model` rows and `SyncCodec` are
/// MainActor-isolated under the target's default isolation, and a `ModelContext` is not `Sendable`,
/// so the actor cannot hold one. Each entry point opens its own context and finishes its
/// transaction inside one hop; what crosses back to the actor is `Sendable` by construction.
@MainActor
enum SyncStore {

    /// A dirty row reduced to what the push needs, so no `@Model` object is held across a
    /// suspension (SwiftData's contexts are not built for that, and it would not be `Sendable`).
    nonisolated struct PendingPush: Sendable {
        enum Payload: Sendable { case tombstone, body(Data), encodeFailed }
        let id: String
        let payload: Payload
        /// The `isRemoved` this push carries (stage 3 I-2). The only local edit these rows take
        /// is a toggle, which always flips this bit — so a row whose bit differs when the echo
        /// lands was edited DURING the round trip, and clearing its dirt would lose that edit.
        let isRemoved: Bool
    }

    /// The types that minted a new cursor this page, and only those.
    nonisolated struct Advance: Sendable {
        var cursors: [String: Int] = [:]
        var ids: [String: String?] = [:]
        /// Stage 4 S5: server rows whose id fails the same validator every LOCAL writer applies,
        /// reported rather than written.
        var skipped: [String] = []
    }

    // MARK: - The binding

    static func binding(_ container: ModelContainer) -> (userId: String, initialMergeDone: Bool)? {
        let context = ModelContext(container)
        guard let row = try? context.fetch(FetchDescriptor<AccountBinding>()).first else { return nil }
        return (row.userId, row.initialMergeDone)
    }

    /// Fetched and mutated, never re-inserted. `#Unique<AccountBinding>([\.userId])` CLOBBERS the
    /// colliding row at save time rather than rejecting it, so a second `insert` for the same uid
    /// silently resets `initialMergeDone` to false — a finished merge would re-run on every launch
    /// (Task 20 fix round 1 / M2).
    static func beginBinding(_ container: ModelContainer, uid: String) -> String? {
        let context = ModelContext(container)
        if let row = try? context.fetch(FetchDescriptor<AccountBinding>(
            predicate: #Predicate { $0.userId == uid })).first {
            row.initialMergeDone = false
        } else {
            context.insert(AccountBinding(userId: uid))
        }
        return save(context, "begin binding")
    }

    static func markMergeDone(_ container: ModelContainer, uid: String) -> String? {
        let context = ModelContext(container)
        guard let row = try? context.fetch(FetchDescriptor<AccountBinding>(
            predicate: #Predicate { $0.userId == uid })).first else { return nil }
        row.initialMergeDone = true
        return save(context, "mark merge done")
    }

    /// Every `userId == ""` row becomes this account's — Android's `tagAnonRowsToUid`. The anon
    /// sentinel is what every pre-sign-in write used, so this is the whole of the additive merge's
    /// local half.
    static func tagAnonRows(_ container: ModelContainer, to uid: String) -> String? {
        let context = ModelContext(container)
        tagAnonRows(context, to: uid)
        return save(context, "tag anon rows")
    }

    private static func tagAnonRows(_ context: ModelContext, to uid: String) {
        let anon = ""
        for row in (try? context.fetch(FetchDescriptor<SubscribedChannel>(
            predicate: #Predicate { $0.userId == anon }))) ?? [] { row.userId = uid }
        for row in (try? context.fetch(FetchDescriptor<SavedPlaylist>(
            predicate: #Predicate { $0.userId == anon }))) ?? [] { row.userId = uid }
        for row in (try? context.fetch(FetchDescriptor<FavoriteVideo>(
            predicate: #Predicate { $0.userId == anon }))) ?? [] { row.userId = uid }
    }

    // MARK: - The account switch

    /// ONE context, ONE save, `rollback()` on the way out — SwiftData offers no stronger
    /// transaction primitive, so this shape IS the atomicity. Two bugs are encoded in the ORDER
    /// (`SyncManager.kt:92-122`): the anon rows are tagged to the PREVIOUS uid and wiped with it,
    /// because tagging them to the NEW one transferred user A's local library into user B's
    /// account (R-final5); and the tagging is INSIDE the transaction, because doing it outside left
    /// a crash window in which A's rows were attributed to A but not wiped, and the relaunch merged
    /// them into B anyway (R-final6).
    ///
    /// `context.delete(model:where:)` is deliberately NOT used: that is a batch delete request
    /// against the STORE, which `rollback()` cannot undo — it would make exactly the half-applied
    /// switch this exists to prevent.
    static func switchAccount(_ container: ModelContainer, from previous: String, to uid: String) throws {
        let context = ModelContext(container)
        do {
            tagAnonRows(context, to: previous)                                       // 1
            try delete(context, FetchDescriptor<SubscribedChannel>(                  // 2
                predicate: #Predicate { $0.userId == previous }))
            try delete(context, FetchDescriptor<SavedPlaylist>(
                predicate: #Predicate { $0.userId == previous }))
            try delete(context, FetchDescriptor<FavoriteVideo>(
                predicate: #Predicate { $0.userId == previous }))
            try delete(context, FetchDescriptor<SyncState>(                          // 3
                predicate: #Predicate { $0.userId == previous }))
            try delete(context, FetchDescriptor<AccountBinding>())                   // 4
            #if DEBUG
            try SyncManager.injectedSwitchFailure?()
            #endif
            context.insert(AccountBinding(userId: uid))                              // 5
            try context.save()
        } catch {
            context.rollback()
            throw error
        }
    }

    private static func delete<T: PersistentModel>(_ context: ModelContext,
                                                   _ descriptor: FetchDescriptor<T>) throws {
        for row in try context.fetch(descriptor) { context.delete(row) }
    }

    // MARK: - Cursors

    static func cursors(_ container: ModelContainer,
                        uid: String) -> (cursors: [String: Int], ids: [String: String?],
                                         dropped: [(type: String, id: String)]) {
        let context = ModelContext(container)
        var cursors: [String: Int] = [:]
        var ids: [String: String?] = [:]
        var dropped: [(type: String, id: String)] = []
        for type in SyncEntityType.allCases {
            let key = type.rawValue
            guard let state = state(context, uid: uid, entityType: key) else {
                cursors[key] = 0
                ids[key] = String?.none
                continue
            }
            cursors[key] = state.lastCursor
            if let id = state.lastDocId, !SyncClient.isValidCursorId(id) {
                dropped.append((key, id))
                ids[key] = String?.none
            } else {
                ids[key] = state.lastDocId
            }
        }
        return (cursors, ids, dropped)
    }

    /// Review I1: the server rejected the request these tiebreakers were part of, so they go. The
    /// cursor MILLISECOND stays — it is the server's own last answer and was never in question —
    /// so the next run re-fetches only from that boundary, and `applyPage` upserts what comes back.
    /// Returns what it dropped, because "the pull is wedged on id X" is the whole diagnosis.
    static func dropCursorIds(_ container: ModelContainer,
                              uid: String) -> (dropped: [(type: String, id: String)],
                                               failure: String?) {
        let context = ModelContext(container)
        var dropped: [(type: String, id: String)] = []
        for type in SyncEntityType.allCases {
            guard let row = state(context, uid: uid, entityType: type.rawValue),
                  let id = row.lastDocId else { continue }
            row.lastDocId = nil
            dropped.append((type.rawValue, id))
        }
        return (dropped, dropped.isEmpty ? nil : save(context, "drop rejected cursor ids"))
    }

    // MARK: - One page, ONE save

    /// Ruling F3 / SYNC-CURSOR-PERSIST-01: the rows and the cursor that advances past them commit
    /// in ONE `save()`. Two saves leave a window where a crash keeps a cursor that has moved past
    /// rows which were never written — those rows are then never fetched again.
    static func applyPage(_ container: ModelContainer, uid: String, body: SyncResponse) throws -> Advance {
        let context = ModelContext(container)
        var advance = Advance()
        do {
            // Stage 4 S5: the same id rule every LOCAL writer enforces, because a server id is a
            // `#Unique` key, a `Route.channel(id:)` push, a synthesised URL pushed back, and an
            // accessibility identifier. Defence in depth against a buggy or replaced server.
            for dto in body.subscriptions.items {
                guard SwiftDataSubscriptionsStore.isValid(dto.entityId) else {
                    advance.skipped.append("subscriptions/\(dto.entityId.prefix(80))"); continue
                }
                let local = try one(context, uid: uid, channelId: dto.entityId)
                switch action(dto.deleted, dto.updatedAt, local) {
                case .applyRow:
                    let row = local ?? insert(context, SubscribedChannel(
                        channelId: dto.entityId, title: dto.name, avatarUrl: nil, userId: uid))
                    SyncCodec.apply(dto, to: row)
                case .applyTombstone:
                    if let local { tombstone(local, at: dto.updatedAt) }
                case .skipDirty, .skipStaleTombstone:
                    continue
                }
            }
            for dto in body.playlists.items {
                guard SwiftDataSavedPlaylistsStore.isValid(dto.entityId) else {
                    advance.skipped.append("playlists/\(dto.entityId.prefix(80))"); continue
                }
                let local = try one(context, uid: uid, playlistId: dto.entityId)
                switch action(dto.deleted, dto.updatedAt, local) {
                case .applyRow:
                    let row = local ?? insert(context, SavedPlaylist(
                        playlistId: dto.entityId, title: dto.name, thumbnailUrl: nil,
                        itemCount: 0, userId: uid))
                    SyncCodec.apply(dto, to: row)
                case .applyTombstone:
                    if let local { tombstone(local, at: dto.updatedAt) }
                case .skipDirty, .skipStaleTombstone:
                    continue
                }
            }
            for dto in body.favorites.items {
                guard SwiftDataFavoritesStore.isValid(dto.entityId) else {
                    advance.skipped.append("favorites/\(dto.entityId.prefix(80))"); continue
                }
                let local = try one(context, uid: uid, videoId: dto.entityId)
                switch action(dto.deleted, dto.updatedAt, local) {
                case .applyRow:
                    let row = local ?? insert(context, FavoriteVideo(
                        videoId: dto.entityId, title: dto.title, channelName: dto.channelName,
                        thumbnailUrl: nil, durationSeconds: 0, userId: uid))
                    SyncCodec.apply(dto, to: row)
                case .applyTombstone:
                    if let local { tombstone(local, at: dto.updatedAt) }
                case .skipDirty, .skipStaleTombstone:
                    continue
                }
            }

            func advanceCursor(_ type: SyncEntityType, _ cursor: Int?, _ docId: String?) {
                guard let cursor else { return }
                let key = type.rawValue
                let row = state(context, uid: uid, entityType: key)
                    ?? insert(context, SyncState(entityType: key, userId: uid))
                row.lastCursor = cursor
                // Stage 4 S5: validated on the way IN, not only on the way out (`cursors(_:uid:)`),
                // so a bad tiebreaker is never persisted and then dropped-and-noted a run later.
                if let docId, !SyncClient.isValidCursorId(docId) {
                    advance.skipped.append("\(key) cursor id/\(docId.prefix(80))")
                    row.lastDocId = nil
                } else {
                    row.lastDocId = docId
                }
                row.lastSyncAt = Date()
                advance.cursors[key] = cursor
                advance.ids[key] = row.lastDocId
            }
            advanceCursor(.subscriptions, body.subscriptions.nextCursor, body.subscriptions.nextCursorId)
            advanceCursor(.playlists, body.playlists.nextCursor, body.playlists.nextCursorId)
            advanceCursor(.favorites, body.favorites.nextCursor, body.favorites.nextCursorId)

            try context.save()
            return advance
        } catch {
            context.rollback()
            throw error
        }
    }

    // MARK: - Push snapshots

    /// The dirty rows for one type, in the order the drain sends them, each already reduced to its
    /// wire bytes. The URL synthesis happens HERE, before the first byte leaves: the stores never
    /// set `channelUrl`/`playlistUrl` (Android fills it in at subscribe time,
    /// `ChannelDetailFragment.kt:335` / `PlaylistDetailFragment.kt:183`), and an empty one hits the
    /// backend's `@NotBlank` as a 400 -> `.permanentFailure` -> the row's dirt dropped and the edit
    /// silently lost. Persisted with the same save, so it is synthesised once and not on every push.
    static func dirtyRows(_ container: ModelContainer, uid: String,
                          type: SyncEntityType) -> (rows: [PendingPush], failure: String?) {
        let context = ModelContext(container)
        var pending: [PendingPush] = []
        switch type {
        case .subscriptions:
            let rows = (try? context.fetch(FetchDescriptor<SubscribedChannel>(
                predicate: #Predicate { $0.userId == uid && $0.dirty == true },
                sortBy: [SortDescriptor(\.followedAt)]))) ?? []
            for row in rows {
                if row.channelUrl.isEmpty { row.channelUrl = SyncURL.channel(row.channelId) }
                pending.append(PendingPush(id: row.channelId, payload: payload(row.isRemoved) {
                    try SyncCodec.body(for: row)
                }, isRemoved: row.isRemoved))
            }
        case .playlists:
            let rows = (try? context.fetch(FetchDescriptor<SavedPlaylist>(
                predicate: #Predicate { $0.userId == uid && $0.dirty == true },
                sortBy: [SortDescriptor(\.addedAt)]))) ?? []
            for row in rows {
                if row.playlistUrl.isEmpty { row.playlistUrl = SyncURL.playlist(row.playlistId) }
                pending.append(PendingPush(id: row.playlistId, payload: payload(row.isRemoved) {
                    try SyncCodec.body(for: row)
                }, isRemoved: row.isRemoved))
            }
        case .favorites:
            let rows = (try? context.fetch(FetchDescriptor<FavoriteVideo>(
                predicate: #Predicate { $0.userId == uid && $0.dirty == true },
                sortBy: [SortDescriptor(\.addedAt)]))) ?? []
            for row in rows {
                pending.append(PendingPush(id: row.videoId, payload: payload(row.isRemoved) {
                    try SyncCodec.body(for: row)
                }, isRemoved: row.isRemoved))
            }
        }
        return (pending, save(context, "dirty rows"))
    }

    private static func payload(_ isRemoved: Bool,
                                _ body: () throws -> Data) -> PendingPush.Payload {
        guard !isRemoved else { return .tombstone }
        guard let data = try? body() else { return .encodeFailed }
        return .body(data)
    }

    // MARK: - Post-push writes

    /// `dirty` always goes; `updatedAt` advances only under Room's monotonicity guard
    /// (`updated_at < :ts`, `FavoriteVideoDao.kt:161`).
    ///
    /// Two deviations from `SyncManager.kt`, both deliberate. Android's guarded UPDATE leaves the
    /// row DIRTY when the guard fails, which re-pushes it forever; the push has just succeeded, so
    /// the flag must go regardless. And a DELETE gets `serverUpdatedAt: nil` rather than
    /// `System.currentTimeMillis()`: a device clock ahead of the server writes a future timestamp
    /// that makes the guard reject every later server update to that row, permanently (gate wave-2
    /// W12, `FavoritesStore.toggle`).
    static func clearDirty(_ container: ModelContainer, uid: String, type: SyncEntityType,
                           id: String, serverUpdatedAt: Int?, pushedRemoved: Bool? = nil) -> String? {
        write(container, uid: uid, type: type, id: id, "clear dirty") { row in
            // Stage 3 I-2 / Codex 5: the echo is for the bytes that were pushed. A toggle that
            // landed while they were on the wire flipped `isRemoved` and re-dirtied the row; that
            // newer edit keeps its dirt and goes out with the next drain. (Two flips inside one
            // round trip — removed and resurrected — still read as "unchanged" here; CF.)
            if let pushedRemoved, row.isRemoved != pushedRemoved { return }
            row.dirty = false
            if let serverUpdatedAt, row.updatedAt < SyncCodec.date(millis: serverUpdatedAt) {
                row.updatedAt = SyncCodec.date(millis: serverUpdatedAt)
            }
        }
    }

    static func tombstone(_ container: ModelContainer, uid: String, type: SyncEntityType,
                          id: String, at millis: Int) -> String? {
        write(container, uid: uid, type: type, id: id, "tombstone") { tombstone($0, at: millis) }
    }

    /// The ONE tombstone writer. `SyncCodec.apply()` is the wrong door for this: it would take
    /// `isRemoved` from the DTO and overwrite every snapshot column, bypassing the monotonicity
    /// guard the `RowAction` was computed under.
    static func tombstone(_ row: any SyncableRow, at millis: Int) {
        row.isRemoved = true
        row.dirty = false
        row.updatedAt = SyncCodec.date(millis: millis)
    }

    // MARK: - Shared helpers

    private static func action(_ deleted: Bool, _ updatedAt: Int,
                               _ local: (any SyncableRow)?) -> SyncDecisions.RowAction {
        SyncDecisions.rowAction(serverDeleted: deleted, serverUpdatedAt: updatedAt,
                                localExists: local != nil, localDirty: local?.dirty ?? false,
                                localUpdatedAt: local.map { SyncCodec.millis($0.updatedAt) } ?? 0)
    }

    private static func write(_ container: ModelContainer, uid: String, type: SyncEntityType,
                              id: String, _ what: String,
                              _ body: (any SyncableRow) -> Void) -> String? {
        let context = ModelContext(container)
        let row: (any SyncableRow)?
        switch type {
        case .subscriptions: row = try? one(context, uid: uid, channelId: id)
        case .playlists: row = try? one(context, uid: uid, playlistId: id)
        case .favorites: row = try? one(context, uid: uid, videoId: id)
        }
        guard let row else { return nil }
        body(row)
        return save(context, what)
    }

    /// Review M3: the ONE save for every write outside `applyPage` and `switchAccount`, which
    /// throw on their own because they are transactions. A bare `try?` here made a merge that never
    /// persisted, or a `clearDirty` that never committed, indistinguishable from success — and a
    /// `clearDirty` that fails silently re-pushes its row on every trigger, forever. Returns the
    /// incident line for the actor to `note()`, because the ring is on the other side of the hop.
    static func save(_ context: ModelContext, _ what: String) -> String? {
        do {
            #if DEBUG
            try SyncManager.injectedSaveFailure?()
            #endif
            try context.save()
            return nil
        } catch {
            return "save failed (\(what)): \(error)"
        }
    }

    private static func insert<T: PersistentModel>(_ context: ModelContext, _ model: T) -> T {
        context.insert(model)
        return model
    }

    private static func one(_ context: ModelContext, uid: String,
                            channelId: String) throws -> SubscribedChannel? {
        try context.fetch(FetchDescriptor<SubscribedChannel>(
            predicate: #Predicate { $0.channelId == channelId && $0.userId == uid })).first
    }

    private static func one(_ context: ModelContext, uid: String,
                            playlistId: String) throws -> SavedPlaylist? {
        try context.fetch(FetchDescriptor<SavedPlaylist>(
            predicate: #Predicate { $0.playlistId == playlistId && $0.userId == uid })).first
    }

    private static func one(_ context: ModelContext, uid: String,
                            videoId: String) throws -> FavoriteVideo? {
        try context.fetch(FetchDescriptor<FavoriteVideo>(
            predicate: #Predicate { $0.videoId == videoId && $0.userId == uid })).first
    }

    private static func state(_ context: ModelContext, uid: String,
                              entityType: String) -> SyncState? {
        try? context.fetch(FetchDescriptor<SyncState>(
            predicate: #Predicate { $0.entityType == entityType && $0.userId == uid })).first
    }
}

/// The three synced rows share the sync columns; their identity keys do not, so the fetch stays
/// per-type. Declared here rather than on the models because it is the SYNC layer's view of them.
@MainActor protocol SyncableRow: AnyObject {
    var updatedAt: Date { get set }
    var isRemoved: Bool { get set }
    var dirty: Bool { get set }
}

extension SubscribedChannel: SyncableRow {}
extension SavedPlaylist: SyncableRow {}
extension FavoriteVideo: SyncableRow {}

/// The canonical URL for a stored row, synthesised from its id exactly as Android does
/// (`ChannelDetailFragment.kt:335`, `PlaylistDetailFragment.kt:183`). STORED DATA the wire requires,
/// never a navigable affordance — nothing in this app links or redirects to YouTube.
nonisolated enum SyncURL {
    static func channel(_ id: String) -> String { "https://www.youtube.com/channel/\(id)" }
    static func playlist(_ id: String) -> String { "https://www.youtube.com/playlist?list=\(id)" }
}
