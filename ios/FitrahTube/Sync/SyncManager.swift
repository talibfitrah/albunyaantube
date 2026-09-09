import Foundation
import SwiftData

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
    #endif

    init(client: any SyncTransporting, modelContainer: ModelContainer,
         backoff: SyncBackoff, sleep: @escaping @Sendable (Duration) async -> Void) {
        self.client = client
        self.modelContainer = modelContainer
        self.backoff = backoff
        self.sleep = sleep
    }

    // MARK: - Public triggers, each under the ONE exclusion

    func bind(uid: String) async {
        await acquire(); defer { release() }
        await bindLocked(uid)
    }

    func unbind() async {
        await acquire(); defer { release() }
        unbindLocked()
    }

    func pushDirty(uid: String) async {
        await acquire(); defer { release() }
        await pushDirtyLocked(uid)
    }

    func pullAll(uid: String) async {
        await acquire(); defer { release() }
        await pullAllLocked(uid)
    }

    /// The foreground trigger: pull, then push.
    func syncNow(uid: String) async {
        await acquire(); defer { release() }
        await pullAllLocked(uid)
        await pushDirtyLocked(uid)
    }

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
        let container = modelContainer
        switch SyncDecisions.bind(binding: await MainActor.run { SyncStore.binding(container) }, uid: uid) {
        case .merge:
            await MainActor.run { SyncStore.beginBinding(container, uid: uid) }
            await mergeLocked(uid)
        case .pullThenPush:
            await pullAllLocked(uid)
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
            await mergeLocked(uid)
        }
    }

    /// `SyncManager.kt:136-147`, in this order. Tagging after the pull loses an anon row to a
    /// server row of the same id; marking the merge done before the drain means a crash mid-push
    /// never re-enters the merge.
    private func mergeLocked(_ uid: String) async {
        let container = modelContainer
        await MainActor.run { SyncStore.tagAnonRows(container, to: uid) }
        await pullAllLocked(uid)
        await pushDirtyLocked(uid)
        await MainActor.run { SyncStore.markMergeDone(container, uid: uid) }
    }

    // MARK: - Pull

    private func pullAllLocked(_ uid: String) async {
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

        while !Task.isCancelled {
            guard let body = await pullPage(cursors: cursors, ids: ids) else { return }
            let cursorsBefore = cursors, idsBefore = ids
            let advanced: SyncStore.Advance
            do {
                advanced = try await MainActor.run {
                    try SyncStore.applyPage(container, uid: uid, body: body)
                }
            } catch {
                note("page write failed, cursor left where it was: \(error)")
                return
            }
            cursors.merge(advanced.cursors) { _, new in new }
            ids.merge(advanced.ids) { _, new in new }

            let minted = body.subscriptions.nextCursor != nil || body.playlists.nextCursor != nil
                || body.favorites.nextCursor != nil
            switch SyncDecisions.page(mintedCursor: minted, cursorsBefore: cursorsBefore,
                                      cursorsAfter: cursors, idsBefore: idsBefore, idsAfter: ids) {
            case .advance:
                continue
            case .exhausted:
                return
            case .stalled:
                noteStall(cursorsBefore: cursorsBefore, cursorsAfter: cursors,
                          idsBefore: idsBefore, idsAfter: ids)
                return
            }
        }
    }

    /// One page, with the three-armed failure classifier in front of the ladder. Returns nil when
    /// the run must stop.
    private func pullPage(cursors: [String: Int], ids: [String: String?]) async -> SyncResponse? {
        var attempt = 1
        while true {
            do {
                return try await client.pull(cursors: cursors, ids: ids)
            } catch {
                let status = Self.pullStatus(of: error)
                switch SyncDecisions.pull(status: status) {
                case .terminal:
                    // The account is gone or blocked. Part A owns the routing (`AuthorizedTransport`
                    // turns the 403 envelope into an `AccountStatusEvent` and `AccountSession` acts
                    // on it); this side just stops and lets go of the retry chain.
                    note("pull terminal (status \(status.map(String.init) ?? "none")); stopping and unbinding")
                    unbindLocked()
                    return nil
                case .permanent:
                    note("pull rejected the request (status \(status.map(String.init) ?? "none")); no retry")
                    return nil
                case .transient:
                    guard attempt < Self.maxPullAttempts else {
                        note("pull gave up after \(attempt) attempts, cursor kept: \(error)")
                        return nil
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
        let container = modelContainer
        var transient = false

        for type in SyncEntityType.allCases {
            let rows = await MainActor.run { SyncStore.dirtyRows(container, uid: uid, type: type) }
            var authFailed = false
            for row in rows {
                let outcome = await push(row, type: type, uid: uid)
                switch outcome {
                case .ok:
                    break
                case .authFailed:
                    authFailed = true
                case .permanentFailure:
                    // The same bytes will fail the same way, so the row is cleared with a warning
                    // rather than left to block every later pull forever.
                    note("push permanently rejected \(type.rawValue)/\(row.id); dropping its dirty flag")
                    await MainActor.run {
                        SyncStore.clearDirty(container, uid: uid, type: type, id: row.id, serverUpdatedAt: nil)
                    }
                case .transientFailure:
                    transient = true
                }
                if authFailed { break }
            }
            if authFailed { break }
        }

        scheduleRetry(uid: uid, needed: transient)
    }

    private func push(_ row: SyncStore.PendingPush, type: SyncEntityType,
                      uid: String) async -> SyncDecisions.PushOutcome {
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
                if outcome == .ok {
                    await MainActor.run {
                        SyncStore.clearDirty(container, uid: uid, type: type, id: row.id, serverUpdatedAt: nil)
                    }
                }
                return outcome
            case .body(let body):
                let (status, echo) = try await client.put(type, id: row.id, body: body)
                if (200...299).contains(status) && echo == nil {
                    note("push \(type.rawValue)/\(row.id) answered \(status) with no decodable body")
                }
                let outcome = SyncDecisions.push(status: status, hasBody: echo != nil)
                if outcome == .ok, let echo {
                    await MainActor.run {
                        // SYNC-ECHO-01: `deleted: true` on a PUT means the server's projection
                        // knows a parent was archived, so the row is tombstoned locally rather
                        // than merely cleared. Through the tombstone writer, never `SyncCodec`.
                        if echo.deleted {
                            SyncStore.tombstone(container, uid: uid, type: type, id: row.id, at: echo.updatedAt)
                        } else {
                            SyncStore.clearDirty(container, uid: uid, type: type, id: row.id,
                                                 serverUpdatedAt: echo.updatedAt)
                        }
                    }
                }
                return outcome
            }
        } catch {
            return .transientFailure
        }
    }

    private func scheduleRetry(uid: String, needed: Bool) {
        guard needed else {
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

    private func note(_ line: String) {
        print("SyncManager: \(line)")
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
    }

    /// The types that minted a new cursor this page, and only those.
    nonisolated struct Advance: Sendable {
        var cursors: [String: Int] = [:]
        var ids: [String: String?] = [:]
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
    static func beginBinding(_ container: ModelContainer, uid: String) {
        let context = ModelContext(container)
        if let row = try? context.fetch(FetchDescriptor<AccountBinding>(
            predicate: #Predicate { $0.userId == uid })).first {
            row.initialMergeDone = false
        } else {
            context.insert(AccountBinding(userId: uid))
        }
        try? context.save()
    }

    static func markMergeDone(_ container: ModelContainer, uid: String) {
        let context = ModelContext(container)
        guard let row = try? context.fetch(FetchDescriptor<AccountBinding>(
            predicate: #Predicate { $0.userId == uid })).first else { return }
        row.initialMergeDone = true
        try? context.save()
    }

    /// Every `userId == ""` row becomes this account's — Android's `tagAnonRowsToUid`. The anon
    /// sentinel is what every pre-sign-in write used, so this is the whole of the additive merge's
    /// local half.
    static func tagAnonRows(_ container: ModelContainer, to uid: String) {
        let context = ModelContext(container)
        tagAnonRows(context, to: uid)
        try? context.save()
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

    // MARK: - One page, ONE save

    /// Ruling F3 / SYNC-CURSOR-PERSIST-01: the rows and the cursor that advances past them commit
    /// in ONE `save()`. Two saves leave a window where a crash keeps a cursor that has moved past
    /// rows which were never written — those rows are then never fetched again.
    static func applyPage(_ container: ModelContainer, uid: String, body: SyncResponse) throws -> Advance {
        let context = ModelContext(container)
        do {
            for dto in body.subscriptions.items {
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

            var advance = Advance()
            func advanceCursor(_ type: SyncEntityType, _ cursor: Int?, _ docId: String?) {
                guard let cursor else { return }
                let key = type.rawValue
                let row = state(context, uid: uid, entityType: key)
                    ?? insert(context, SyncState(entityType: key, userId: uid))
                row.lastCursor = cursor
                row.lastDocId = docId
                row.lastSyncAt = Date()
                advance.cursors[key] = cursor
                advance.ids[key] = docId
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
                          type: SyncEntityType) -> [PendingPush] {
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
                }))
            }
        case .playlists:
            let rows = (try? context.fetch(FetchDescriptor<SavedPlaylist>(
                predicate: #Predicate { $0.userId == uid && $0.dirty == true },
                sortBy: [SortDescriptor(\.addedAt)]))) ?? []
            for row in rows {
                if row.playlistUrl.isEmpty { row.playlistUrl = SyncURL.playlist(row.playlistId) }
                pending.append(PendingPush(id: row.playlistId, payload: payload(row.isRemoved) {
                    try SyncCodec.body(for: row)
                }))
            }
        case .favorites:
            let rows = (try? context.fetch(FetchDescriptor<FavoriteVideo>(
                predicate: #Predicate { $0.userId == uid && $0.dirty == true },
                sortBy: [SortDescriptor(\.addedAt)]))) ?? []
            for row in rows {
                pending.append(PendingPush(id: row.videoId, payload: payload(row.isRemoved) {
                    try SyncCodec.body(for: row)
                }))
            }
        }
        try? context.save()
        return pending
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
                           id: String, serverUpdatedAt: Int?) {
        write(container, uid: uid, type: type, id: id) { row in
            row.dirty = false
            if let serverUpdatedAt, row.updatedAt < SyncCodec.date(millis: serverUpdatedAt) {
                row.updatedAt = SyncCodec.date(millis: serverUpdatedAt)
            }
        }
    }

    static func tombstone(_ container: ModelContainer, uid: String, type: SyncEntityType,
                          id: String, at millis: Int) {
        write(container, uid: uid, type: type, id: id) { tombstone($0, at: millis) }
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
                              id: String, _ body: (any SyncableRow) -> Void) {
        let context = ModelContext(container)
        let row: (any SyncableRow)?
        switch type {
        case .subscriptions: row = try? one(context, uid: uid, channelId: id)
        case .playlists: row = try? one(context, uid: uid, playlistId: id)
        case .favorites: row = try? one(context, uid: uid, videoId: id)
        }
        guard let row else { return }
        body(row)
        try? context.save()
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
