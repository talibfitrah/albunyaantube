import Foundation
import InnerTubeKit

/// What a save request carries besides its ids (the Saved-screen row's text + thumbnail).
nonisolated struct OfflineMetadata: Sendable {
    var title: String
    var channelName: String?
    var thumbnailUrl: String?
}

/// The save API Task 5 (Save button), Task 6 (Saved screen rows) and Task 7 (sweep cadence) call.
/// Every argument is an `OfflineItem.id` except `save`'s videoId; files are only ever removed
/// through `delete`/`cancel` here — never by a caller touching `FileManager`.
nonisolated protocol OfflineSaving: Sendable {
    func save(videoId: String, quality: String, audioOnly: Bool, metadata: OfflineMetadata) async
    func pause(_ id: String) async
    func resume(_ id: String) async
    func cancel(_ id: String) async
    func retry(_ id: String) async
    func delete(_ id: String) async
    /// Relaunch: re-bind rows to live background tasks, demote orphans, resume scheduling.
    func reattach() async
    /// TTL + gate revalidation over completed rows (`OfflineSweep.decide`), then delete.
    func sweep() async
}

/// Phase 3 Task 4: resolve → download → persist, executing Task 3's pure decisions. The
/// resolver (`.prefetch` lane, reconciliation note 4: wait-don't-skip), the cellular gate
/// (note 6: consulted at task creation/resume), the ≥10 min expiry guard, one re-resolve on 403,
/// and the background-session re-attach all live here; the engine only moves bytes.
///
/// Persistence: `OfflineStore` is `@MainActor`; every read/write hops with `MainActor.run` and
/// returns a `Sendable` snapshot — after any engine event the row is RE-READ before deciding
/// (SwiftData drops writes made into the wrong context silently).
actor OfflineManager: OfflineSaving {
    /// `DownloadErrorCode.kt:12-39` minus the FFmpeg codes, plus `NOT_SAVEABLE` (embed outcome).
    nonisolated enum ErrorCode: String, Sendable {
        case http403 = "HTTP_403", http429 = "HTTP_429", network = "NETWORK", noStream = "NO_STREAM"
        case invalidInput = "INVALID_INPUT", unknown = "UNKNOWN", notSaveable = "NOT_SAVEABLE"
    }

    /// Below this remaining lifetime a resolved URL is refreshed once before the engine sees it.
    private static let minimumRemainingLifetime: TimeInterval = 10 * 60

    /// How long a start the per-video gate could not answer waits before `schedule()` re-picks it.
    /// ponytail: one flat delay, no escalation — the row reads "Waiting" either way and the sweep's
    /// own 15-min cadence is the other prong; add a backoff curve if a long offline stretch ever
    /// makes the once-a-minute re-ask visible.
    private static let gateRetryDelay: Duration = .seconds(60)

    nonisolated private struct Row: Sendable {
        let id: String
        let videoId: String
        let status: OfflineStatus
        let audioOnly: Bool
        let resumeData: Data?
        let localPath: String?
        let completedAt: Date?
        let createdAt: Date

        init?(_ item: OfflineItem) {
            guard let status = OfflineStatus(rawValue: item.status) else { return nil }
            id = item.id; videoId = item.videoId; self.status = status; audioOnly = item.audioOnly
            resumeData = item.resumeData; localPath = item.localPath; completedAt = item.completedAt
            createdAt = item.createdAt
        }
    }

    private let store: OfflineStore
    private let engine: any OfflineEngine
    private let resolver: any StreamResolving
    private let limiterCheck: @Sendable (String) async -> Decision
    private let wifiOnly: @MainActor @Sendable () -> Bool
    private let isOnCellular: @MainActor @Sendable () -> Bool
    private let gate: @Sendable (String) async -> GateAnswer
    private let now: @Sendable () -> Date
    /// The remote kill-switch, and the second config consult beside `SaveAffordance` hiding the
    /// Save button (which alone leaves Saved-screen Retry/Resume unguarded).
    /// Consulted wherever new work would START (`retry`, `begin` — which schedule/resume
    /// route through); NEVER by delete/cancel/pause/sweep or offline playback (fork D: the switch
    /// governs saving, not access to what's already saved). Refusal is silent — the row stays as
    /// it was, exactly like the kill-switch's hidden-affordance rule.
    private let downloadsEnabled: @Sendable () async -> Bool
    private let directory: URL

    /// Ids with an engine task in flight (or a resolve on the way to one).
    private var active: Set<String> = []
    /// Per-id attempt generation, bumped when `begin` claims the slot. `stillCurrent` re-checks
    /// it after every suspension: a cancel/delete (drops `active`) or a cancel→retry re-claim
    /// (bumps the counter) invalidates the older attempt mid-flight. Never cleared — clearing
    /// would reissue token 1 to a retry and let a stale attempt pass; one `Int` per id ever saved
    /// this session is the cost.
    private var attempts: [String: Int] = [:]
    /// Ids whose next attempt is timer-scheduled (limiter delay/block, resolver cooldown), each with
    /// the attempt that parked it — a timer already past its cancellation check must not untrack the
    /// one that REPLACED it. Consecutive parks of an id always carry different attempts:
    /// `scheduleRetry` drops the claim, so the next park needs a fresh `claim` and its bump.
    private var retries: [String: (attempt: Int, task: Task<Void, Never>)] = [:]
    /// Ids the CELLULAR GATE paused — never a user's pause. `gateDidChange` resumes exactly these
    /// when the gate re-opens, and `pause(_:)` drops one, because a user pause outranks this
    /// bookkeeping.
    ///
    /// In-memory, and `reattach()` does NOT cover the loss: a gate-paused row persists as
    /// `.paused`, and `reattach()` iterates `.running` rows only, so it never sees one. A gate
    /// pause that survives a force-quit therefore still reads Paused until the user's Resume.
    /// That is the safe direction — the
    /// alternative is a relaunch that silently starts downloads nobody asked for — and it is why
    /// this stays in memory instead of becoming a persisted column.
    private var gatePausedIds: Set<String> = []
    /// Ids that already spent their one re-resolve on a 403.
    private var reResolvedAfter403: Set<String> = []
    /// Ids whose next resolve must bypass the manifest cache — the 403 re-resolve's intent, held
    /// across a limiter park (`scheduleRetry` drops the claim, and the timer's `begin` re-enters
    /// `resolveAndStart` with `forceRefresh: false`). Moved into `reResolvedAfter403` by the walk
    /// that actually answers, so a parked re-resolve does not spend the one budget it never used.
    private var pendingForceRefresh: Set<String> = []
    private var lastProgressPersist: [String: Date] = [:]

    /// Test hook: which ids currently wait on a retry timer.
    var pendingRetryIds: Set<String> { Set(retries.keys) }
    /// Test hook: how many times `reattach()` has run — the background-events relaunch
    /// wiring is asserted at this flag level; the real relaunch is device territory.
    private(set) var reattachCount = 0

    init(store: OfflineStore, engine: any OfflineEngine, resolver: any StreamResolving,
         limiterCheck: @escaping @Sendable (String) async -> Decision,
         wifiOnly: @escaping @MainActor @Sendable () -> Bool,
         isOnCellular: @escaping @MainActor @Sendable () -> Bool,
         baseDirectory: URL,
         gate: @escaping @Sendable (String) async -> GateAnswer,
         now: @escaping @Sendable () -> Date,
         downloadsEnabled: @escaping @Sendable () async -> Bool = { true }) {
        self.store = store
        self.engine = engine
        self.resolver = resolver
        self.limiterCheck = limiterCheck
        self.wifiOnly = wifiOnly
        self.isOnCellular = isOnCellular
        self.gate = gate
        self.now = now
        self.downloadsEnabled = downloadsEnabled
        directory = OfflineStorage.directoryURL(base: baseDirectory)
        Task { [engine] in
            for await event in engine.events { await self.handle(event) }
        }
    }

    // MARK: - OfflineSaving

    func save(videoId: String, quality: String, audioOnly: Bool, metadata: OfflineMetadata) async {
        // The upsert trap (`OfflineStore.insert`): the old task and file go BEFORE the row is replaced.
        if let old = await read(videoId: videoId) {
            await tearDown(old)
        }
        let item = OfflineItem(videoId: videoId, title: metadata.title, channelName: metadata.channelName,
                               thumbnailUrl: metadata.thumbnailUrl, qualityLabel: quality, audioOnly: audioOnly,
                               createdAt: now())
        await write { store in try store.insert(item) }
        await schedule()
    }

    func pause(_ id: String) async { _ = await pauseIfRunning(id) }

    /// Returns whether THIS call is what paused the row — the gate's close leg needs to know, and
    /// nothing else does.
    @discardableResult
    private func pauseIfRunning(_ id: String) async -> Bool {
        // A USER pause outranks any gate bookkeeping — ABOVE the guard below, because it outranks it
        // whether or not there is any work left to do. The gate's close leg may have paused this row
        // a moment earlier (its own `await engine.pause` is a whole suspension long, and the tap was
        // made while the row still read `.running`), and returning `false` without dropping the id
        // left the gate believing IT parked a row the user asked to stop: the next re-open resumed
        // it. Whatever the gate still believes it parked, this row now waits for the user's Resume.
        // `gateDidChange` re-inserts right after its own call returns TRUE, so the gate's leg is
        // unaffected; this is what stops a STALE entry (an id that left `.paused` by another route,
        // or one the refuse leg inserted after a pause that no-oped inside its own suspension)
        // turning the next gate re-open into an unrequested resume.
        gatePausedIds.remove(id)
        guard let row = await read(id: id), row.status == .running else { return false }
        await transition(id, .pause)
        let resumeData = await engine.pause(id: id)
        active.remove(id)
        await write { store in
            // Still-paused guard: a `.finished` racing this pause may have completed the row
            // while `engine.pause` was in flight — never scribble resume data onto it.
            guard let item = store.item(id: id), item.status == OfflineStatus.paused.rawValue else { return }
            item.resumeData = resumeData
            try store.save()
        }
        await schedule()
        return true
    }

    func resume(_ id: String) async { await resume(id, userInitiated: true) }

    /// `userInitiated: false` is the GATE's re-open (`resumeGateParked`), not a tap: a refusal it
    /// meets must stay silent, because "leave the reason on the row" exists for a button that
    /// appears to do nothing. Going through the public `resume` would note NETWORK on a row nobody
    /// touched whenever the per-video gate is unreachable at re-open time.
    private func resume(_ id: String, userInitiated: Bool) async {
        guard let row = await read(id: id), row.status == .paused || row.status == .queued else { return }
        // ponytail: a user Resume runs immediately even if the scheduler has something active;
        // the serial floor (CF-D-5) applies to queue picks, not to explicit user intent.
        await begin(row, userInitiated: userInitiated)
    }

    func cancel(_ id: String) async {
        guard let row = await read(id: id), OfflineStateMachine.transition(from: row.status, on: .cancel) != nil else { return }
        await engine.cancel(id: id)
        forget(id)
        removeFiles(row)
        await write { store in
            guard let item = store.item(id: id), let status = OfflineStatus(rawValue: item.status) else { return }
            // A `.finished` racing the engine hop above can have moved the bytes to `<id>.<ext>`
            // and written `.completed` — `removeFiles` has just unlinked that copy, and
            // `(completed, .cancel)` is nil, so leaving the row would leave it Saved and pointing
            // at nothing while its bytes still counted in the storage footer. The user asked for
            // this copy to go, so the row goes with the file: the same end state `delete` produces,
            // decided inside the write that already re-reads the row rather than behind another
            // main-actor hop (which would land after `removeFiles` no matter where it went, and
            // stall a cancel that races a blocked main actor).
            guard let next = OfflineStateMachine.transition(from: status, on: .cancel) else {
                if status == .completed { try store.delete(item) }
                return
            }
            item.status = next.rawValue
            item.resumeData = nil
            item.bytesWritten = 0
            try store.save()
        }
        await schedule()
    }

    func retry(_ id: String) async {
        // Kill-switch guard BEFORE the status write, so a refused retry leaves the row failed/
        // cancelled as it was — not silently re-queued for a start that will never come.
        guard await downloadsEnabled() else { return }
        guard let row = await read(id: id), OfflineStateMachine.transition(from: row.status, on: .retry) != nil else { return }
        // Retry is a SAVE, so it consults the per-video gate on the Save affordance's fail-CLOSED
        // table — nothing else revalidates a failed/cancelled row before it re-queues after an
        // admin flips `offlineAllowed` off (fork C's same-day remedy). `begin`'s own fail-closed
        // consult and the belted sweep stop the re-download too; this is the one that stops it
        // HERE, with the row's own status and partial still to answer for. `notAllowed`/`gone`
        // take the row and its partial with them, the way the sweep does for a completed copy;
        // `unreachable` is no answer, so the retry is refused and the row stays exactly as it was.
        //
        // Distinct from `begin`'s own consult: `begin` treats `.unreachable` as wait-don't-skip and
        // promotes the row to `.queued`, while a Retry must leave a FAILED row failed with its
        // reason and its partial. This one also sits ABOVE the `.queued` write below, so a refused
        // Retry never re-queues for a start that will never come — and `retry` hands off to
        // `schedule()`, which may pick an older row entirely. A Retry that proceeds therefore
        // spends two gate GETs; caching the answer would be per-id state neither path has, and one
        // extra conditional GET per tap is the cheaper side of that trade.
        switch await gate(row.videoId) {
        case .allowed: break
        case .notAllowed, .gone: await delete(row.id); return
        // A refused Retry leaves the reason on the row, so the button never reads as broken: the
        // status and the partial stay put, and the row carries the network code its caption
        // renders.
        case .unreachable: await note(row.id, .network); return
        }
        await write { store in
            guard let item = store.item(id: id) else { return }
            item.status = OfflineStatus.queued.rawValue
            item.errorCode = nil
            try store.save()
        }
        await schedule()
    }

    func delete(_ id: String) async {
        guard let row = await read(id: id) else { return }
        await tearDown(row)
        await schedule()
    }

    /// Settings' Clear-all: every row tears down first, then ONE `schedule()`. A `schedule()` per
    /// row (what looping `delete(_:)` would do) picks a still-existing queued row between two
    /// deletes and begins its resolve — a real, rate-limited InnerTube POST for a row the very next
    /// iteration deletes.
    ///
    /// `deleteAll`, not a `delete` overload: `manager.delete(x)` would no longer say
    /// at the call site which of the two semantics applies.
    func deleteAll(_ ids: [String]) async {
        for id in ids {
            guard let row = await read(id: id) else { continue }
            await tearDown(row)
        }
        await schedule()
    }

    func reattach() async {
        reattachCount += 1
        let live = await engine.liveIds()
        let rows = await readAll()
        // A row in `active` belongs to a live attempt of THIS session — two launch callers run
        // this (the AppDelegate background-events hook and RootView's `.task`), and re-queueing a
        // row whose resolve is still in flight would feed the engine start's `.progress` events to
        // a `.queued` row, which the status guard drops: "Waiting" with a frozen bar until
        // `.finished`. Re-claiming it would be just as wrong — the bump invalidates its own
        // continuation.
        for row in rows where row.status == .running && !active.contains(row.id) {
            if live.contains(row.id) {
                _ = claim(row.id)
            } else {
                // Orphaned: the app died mid-download. It queues, never pauses — a row that was
                // RUNNING was never paused by a user, and `.paused` is the state that waits for a
                // user's Resume. `begin` continues it from its resume data when it carries any,
                // else from zero.
                await write { store in
                    guard let item = store.item(id: row.id) else { return }
                    item.status = OfflineStatus.queued.rawValue
                    try store.save()
                }
            }
        }
        await schedule()
    }

    /// EVERY row, not only completed ones: the ruling's auto-delete covers a video pulled from the
    /// catalog while its save was queued/running/paused/failed too, and skipping those would let
    /// `reattach()` resume one on the next launch. The TTL half still needs a `completedAt`, which
    /// is exactly what an unfinished row does not have; the gate half applies to all of them.
    func sweep() async {
        let current = now()
        var expired: [String] = []
        var gateDeletes: [String] = []
        var checked = 0
        var unreachable = 0
        for row in await readAll() {
            let action: SweepAction
            if let completedAt = row.completedAt, OfflineSweep.isExpired(completedAt: completedAt, now: current) {
                action = .deleteExpired   // TTL first, before any network
            } else {
                checked += 1
                let answer = await gate(row.videoId)
                if case .unreachable = answer { unreachable += 1 }
                action = OfflineSweep.decide(completedAt: row.completedAt, now: current, gate: answer)
            }
            switch action {
            case .keep: break
            // Both gate DELETE verdicts share one bucket, so the belt below covers both.
            // `.notAllowed` is the likelier drift of the two — `Video.offlineAllowed`
            // is a boxed `Boolean` and `VideoUpdateRequest` carries it nullable, so one admin edit
            // or migration can null it across every video at once, and an absent flag is the
            // ruling's default-false.
            case .deleteRemoved, .deleteGateRevoked: gateDeletes.append(row.id)
            // The TTL is a LOCAL decision — no network said anything — so it is never belted.
            case .deleteExpired: expired.append(row.id)
            }
        }
        // The belt, behind the gate client's envelope + Video-model checks: EVERY gate-checked row
        // in one pass answering DELETE is not a same-day whole-catalog removal, it is a broken edge
        // or backend drift. Keep them all and let the next sweep retry; the delete is irreversible,
        // the wait is not. One row alone still goes: a single video really does leave the catalog,
        // and a single admin revocation really is fork C's same-day remedy.
        //
        // `.unreachable` rows are discounted from the denominator, because a broken edge does not
        // have to answer uniformly — 404 for some rows and a transport error for others would
        // otherwise leave `gateDeletes.count < checked` and delete the 404 half. What is being
        // asked is "did every row that got an ANSWER say delete", not "did every row".
        let answered = checked - unreachable
        if answered >= 2 && gateDeletes.count == answered { gateDeletes = [] }
        // Through `deleteAll` (Task 7): files + rows together, the same teardown a user Delete
        // runs — never a second removal path — and ONE `schedule()`. A per-row `delete` re-runs the
        // scheduler between deletions, which picks a still-existing queued row and begins its
        // resolve: a real, rate-limited InnerTube POST for a row this same loop then deletes.
        await deleteAll(expired + gateDeletes)
    }

    /// Reconciliation note 6: re-evaluate the cellular gate after `wifiOnlyDownloads` or the path
    /// changes — pause running tasks the gate now refuses, start queued ones it now allows.
    func gateDidChange() async {
        guard await gateAllows() else {
            for row in await readAll() where row.status == .running && active.contains(row.id) {
                // Only a pause this leg PERFORMED is the gate's to undo. The `where` clause re-reads
                // `active` live, so a user pause that already finished drops out of the loop by
                // itself — but one still suspended in `engine.pause` leaves the row reading
                // `.paused` with its claim intact, and marking that one would hand the next gate
                // re-open a row the user had paused (the invariant `pause` protects above).
                if await pauseIfRunning(row.id) { gatePausedIds.insert(row.id) }
            }
            // The gate can re-open inside those awaits, and that re-open's own allow leg
            // snapshotted the parked set BEFORE this leg inserted into it — so re-checking here is
            // what stops a row staying Paused until the next gate change or a manual Resume. The
            // leg that finishes last reconciles the rows with the gate as it now reads.
            guard await gateAllows() else { return }
            await resumeGateParked()
            return
        }
        await resumeGateParked()
    }

    /// A GATE pause is not a user pause: `schedule()` picks only `.queued` rows, so without this a
    /// brief Wi-Fi drop under Wi-Fi-only would leave the save at "Paused" until the user tapped
    /// Resume. Resumes exactly the ids the GATE parked; a row the user paused still waits for them.
    /// ponytail: restores every parked row, which briefly exceeds the serial floor (CF-D-5) if two
    /// were somehow running — that is a faithful restore of the pre-gate state; add a
    /// one-at-a-time drain if per-item concurrency ever lands.
    private func resumeGateParked() async {
        let parked = gatePausedIds
        gatePausedIds.removeAll()
        for id in parked { await resume(id, userInitiated: false) }
        await schedule()
    }

    // MARK: - Engine events

    /// Also the test seam: tests call this directly instead of racing the stream consumer.
    func handle(_ event: OfflineDownloadEvent) async {
        switch event {
        case .progress(let id, let bytesWritten, let totalBytes):
            let current = now()
            // ponytail: 2 Hz, and the throttle covers the WHOLE main-actor hop rather than just
            // `store.save()` — a live enough bar for the row without a `store.item(id:)` predicate
            // fetch per engine packet. Raise the rate if the bar ever looks laggy.
            guard current.timeIntervalSince(lastProgressPersist[id] ?? .distantPast) >= 0.5 else { return }
            lastProgressPersist[id] = current
            await write { store in
                guard let item = store.item(id: id), item.status == OfflineStatus.running.rawValue else { return }
                item.bytesWritten = bytesWritten
                if let totalBytes { item.totalBytes = totalBytes }
                try store.save()
            }

        case .finished(let id):
            let tmp = directory.appending(path: "\(id).tmp")
            // `.paused` too: `pause()` writes `paused` before the engine hop returns, so a final
            // chunk racing the pause can deliver `.finished` for a paused row — those bytes are
            // the complete file and must be kept, not deleted. `.queued` too: on a
            // background-events relaunch the pending delegate callbacks and `liveIds()`'s
            // `getAllTasks` are both async on the delegate queue with no ordering guarantee, so
            // the final chunk's finish can land AFTER `reattach()` already found no live task and
            // queued the row — deleting a file that is complete costs a full re-download.
            // Only a cancelled/deleted row's bytes are actually garbage.
            guard let row = await read(id: id), Self.acceptsCompletion(row.status) else {
                try? FileManager.default.removeItem(at: tmp)   // cancelled/deleted while finishing
                return
            }
            let name = OfflineStorage.fileName(itemId: id, kind: row.audioOnly ? .m4a : .mp4)
            let destination = directory.appending(path: name)
            do {
                try? FileManager.default.removeItem(at: destination)
                try FileManager.default.moveItem(at: tmp, to: destination)
            } catch {
                await fail(id, .network)
                return
            }
            // Task 7: zero the lying mvhd/mdhd durations of a fragmented save (no-op for a plain
            // itag-18 mp4) — see `FragmentedMP4Durations`. Once, here, so playback needs no fix-up.
            FragmentedMP4Durations.normalize(at: destination)
            let size = (try? FileManager.default.attributesOfItem(atPath: destination.path())[.size] as? Int64) ?? 0
            let completedAt = now()
            forget(id)
            await write { store in
                // `localPath` is written on the NEXT line and nowhere else, so a Delete/Cancel that
                // landed while this completion was suspended leaves `<id>.<ext>` named by nothing:
                // `removeFiles` never sees a path the row does not carry yet, and nothing in the
                // app enumerates the offline directory. Unlike a `.tmp` orphan (the next
                // `engine.start` deletes that one) this has no healer, so the bytes go here.
                guard let item = store.item(id: id), let status = OfflineStatus(rawValue: item.status),
                      Self.acceptsCompletion(status) else {
                    try? FileManager.default.removeItem(at: destination)
                    return
                }
                item.status = OfflineStatus.completed.rawValue
                item.localPath = name
                item.bytesWritten = size
                item.totalBytes = size
                item.resumeData = nil
                // Belt: a completed row can carry no error, whatever route it took here.
                // `transition` already clears the code on the way into `.running`, and a `.finished`
                // for a `.queued`/`.paused` row (the relaunch and pause races) never passes through
                // one — so this is the second half of the same invariant, not a duplicate.
                item.errorCode = nil
                item.completedAt = completedAt
                try store.save()
            }
            await schedule()

        case .failed(let id, let failure):
            guard let row = await read(id: id), row.status == .running else { return }
            switch failure {
            case .http(403, _) where !reResolvedAfter403.contains(id):
                // `DownloadWorker.kt:266-276`: one forced re-resolve, restart from zero — the old
                // resume data names the dead URL. The intent is armed here and SPENT by the walk
                // that answers, so a limiter park between the two cannot swallow it.
                pendingForceRefresh.insert(id)
                await write { store in
                    guard let item = store.item(id: id) else { return }
                    // Back to queued directly (no running→queued transition exists): a limiter
                    // block on the re-resolve then leaves an honest queued row, not a taskless
                    // running one.
                    item.status = OfflineStatus.queued.rawValue
                    item.resumeData = nil
                    item.bytesWritten = 0
                    try store.save()
                }
                guard let requeued = await read(id: id) else { return }
                await resolveAndStart(requeued, forceRefresh: true)
            case .http(403, _): await fail(id, .http403)
            // A throttle leaves the `.tmp` untouched like any other transient status, so the token
            // rides the failure and the retry continues the walk instead of re-downloading it.
            case .http(429, let resumeData): await fail(id, .http429, resumeData: resumeData)
            // A transient status (5xx, 416) is a transport failure like any other: the `.tmp` is
            // untouched, so keep the token — `retry` → `begin` resumes only when the row carries
            // one, and `engine.start` deletes the partial.
            case .http(_, let resumeData), .network(let resumeData):
                await fail(id, .network, resumeData: resumeData)
            }
        }
    }

    // MARK: - Scheduling

    // ponytail: ONE active download at a time (CF-D-5) — per-item concurrency when saves
    // actually queue up in practice; serial is the rate-limit-friendly floor and keeps the single
    // background session's re-attach trivial.
    //
    /// Not `private`: the smallest kick the remote-config refresh path can give the
    /// queue when the kill-switch flips back ON. Nothing else observes that flip
    /// (`observeOfflineGate` watches only Wi-Fi/cellular), so without the kick rows queued during
    /// an off-window sit at "Waiting" until the next launch's `reattach()`. A no-op when nothing is
    /// queued, and `begin` still re-consults the switch — so kicking while it is OFF changes
    /// nothing.
    func schedule() async {
        guard active.isEmpty else { return }
        let waiting = pendingRetryIds
        let next = await readAll()
            .filter { $0.status == .queued && !waiting.contains($0.id) }
            .min { $0.createdAt < $1.createdAt }
        guard let next else { return }
        // `readAll` suspended: a concurrent schedule()/resume() may have claimed the slot since
        // the guard above — begin's own synchronous claim closes the same window for one row.
        guard active.isEmpty else { return }
        await begin(next)
    }

    /// Runs one row: the kill-switch, the cellular gate and the per-video gate, then either a
    /// resume-data restart or the resolve path.
    ///
    /// `userInitiated` is a USER's Resume, not a queue pick: only that one needs a refusal to leave
    /// a trace. A queued row the scheduler picked already reads "Waiting", which is honest.
    private func begin(_ row: Row, userInitiated: Bool = false) async {
        // Claim the slot SYNCHRONOUSLY, before any suspension: otherwise two interleaved
        // schedule() passes (or a double-tap Resume) both pass their checks and start one row twice.
        guard !active.contains(row.id) else { return }
        let attempt = claim(row.id)
        // Kill-switch: `begin` is the one funnel every start rides — schedule picks,
        // user Resume, reattach's re-queue. Refusal leaves the row queued/paused untouched.
        // Both refusals release the claim only if it is still THIS attempt's: a cancel plus retry
        // landing inside either await re-claims the row, and stripping that newer claim would leave
        // the retry's own continuation failing `stillCurrent` — the row queued with nothing
        // running.
        guard await downloadsEnabled() else {
            release(row.id, attempt)
            return
        }
        guard await gateAllows() else {
            // Stays queued/paused. A queued row is re-picked by the schedule() a gateDidChange
            // runs; a paused row waits for the user's Resume (schedule() picks only queued rows).
            release(row.id, attempt)
            // A user's Resume the cellular gate refuses leaves the reason on the row, so the button
            // does not read as broken. The status is still the user's to own — a paused row stays
            // paused. There is no Wi-Fi-only paused wording in the catalog, so NETWORK is the copy.
            if userInitiated { await note(row.id, .network) }
            return
        }
        // The per-video gate, fail-CLOSED. This funnel is what every byte-writing walk rides — a
        // scheduler pick, a user Resume, `reattach()`'s re-queue, a cellular re-open, the
        // kill-switch kick — and without it they would all start on authorization that could be
        // hours stale, since only `retry` and the sweep revalidate otherwise. BELOW the cellular
        // gate on purpose: a row that gate refuses writes no bytes, so it needs no authorization
        // and costs no GET.
        //
        // Only an affirmative answer starts bytes, and `begin` deletes NOTHING. A per-row delete
        // here ends in `schedule()`, which picks the next queued row and re-enters this function —
        // so one drifted backend answer would cascade through every queued/paused/failed row in a
        // single pass, with none of the belt the sweep applies to the identical verdict 200 lines
        // below. `Video.offlineAllowed` is a boxed `Boolean` that one migration can null across the
        // whole catalog, which is precisely the drift that belt exists to refuse. Telling drift
        // from a real revocation needs whole-library evidence, and only the sweep has it — so the
        // BELTED sweep owns every deletion, at its next launch/foreground run. `retry` keeps its
        // own single user-initiated delete: with `begin` unable to delete, nothing cascades.
        switch await gate(row.videoId) {
        case .allowed: break

        // A PER-VIDEO verdict is not a wall: this row is refused, younger ones are not. Parking it
        // on a timer would starve them — `schedule()` picks the OLDEST queued row and a parked row
        // only ages, so a revoked head row is re-picked every 60 s forever while every younger row
        // sits at "Waiting" with no bytes and no error. The row FAILS instead: `fail` frees the
        // slot and re-runs `schedule()` for the younger rows (the `.blocked` arm's reasoning), and
        // `schedule()` never picks a `.failed` row again. No delete and no timer — the belted sweep
        // collects it, or the user's Retry does through `retry`'s own single delete.
        //
        // `.notSaveable` is the code the failed caption already renders as "This video can't be
        // saved for offline": the WHAT, never the why, and no new string. The resume token rides
        // along so a transient drift that clears costs no re-download.
        case .notAllowed, .gone:
            guard stillCurrent(row.id, attempt) else { return }
            await fail(row.id, .notSaveable, resumeData: row.resumeData)
            return

        // TRANSPORT, not a verdict: the wall is global, so re-scheduling would only walk every
        // younger row into the same dead edge — the `.cooldown` arm's reasoning, and the one place
        // that argument actually holds. Wait-don't-skip: `.queued` ("Waiting"), the partial and any
        // resume token untouched, a timer to re-ask.
        case .unreachable:
            // Read BEFORE the park: `scheduleRetry` drops the claim, so `stillCurrent` is false
            // afterwards by construction and could not tell a stale attempt from a live one.
            let mine = stillCurrent(row.id, attempt)
            await scheduleRetry(row.id, attempt, after: Self.gateRetryDelay)
            // The promotion to "Waiting" is a visible change only the FIRST time, for a `.paused`
            // row — a Resume on an already-parked row rewrites the state it already has, which is
            // what would read as a broken button. So a user action leaves the reason on the row
            // (NETWORK — what failed is reaching the gate at all), after the park because the park
            // clears the code; `transition` into `.running` clears it again the moment real work
            // starts. A scheduler pick stays silent: "Waiting" is already honest for one of those.
            if userInitiated, mine { await note(row.id, .network) }
            return
        }
        // The snapshot `schedule()` picked predates three awaits, and the CLAIM is what makes a
        // read authoritative: a relaunch `.finished` that completed the row BEFORE this attempt
        // claimed the slot found no claim to strip in its `forget`, so `stillCurrent` alone still
        // answers true here. Re-read under the claim, and a REFUSED transition releases the slot
        // and re-runs the scheduler (nothing else would: the completion's own `schedule()` ran while
        // this claim was held, and without the release every later one exits at
        // `guard active.isEmpty`).
        guard let row = await read(id: row.id),
              OfflineStateMachine.transition(from: row.status,
                                             on: row.status == .paused ? .resume : .start) != nil
        else {
            release(row.id, attempt)
            await schedule()
            return
        }
        guard stillCurrent(row.id, attempt) else { return }   // cancelled/deleted during the hops
        if let resumeData = row.resumeData {
            await transition(row.id, row.status == .paused ? .resume : .start)
            try? Self.prepareDirectory(directory)
            // The cellular read is its own main-actor hop, so it goes ABOVE the guard: evaluated
            // inside the engine call's argument list it suspended AFTER the last check, and a
            // cancel completing in that window found nothing behind it.
            let allowsCellular = !(await wifiOnly())
            guard stillCurrent(row.id, attempt) else { return }
            await engine.resume(id: row.id, resumeData: resumeData, allowsCellular: allowsCellular)
            await stopWalkIfNotRunning(row.id, attempt)
            return
        }
        await resolveAndStart(row, forceRefresh: false)
    }

    /// `pause()` writes `.paused` and awaits `engine.pause` BEFORE it drops the claim, so a start
    /// completing inside that window still passes `stillCurrent` and issues its chunk under the
    /// NEWEST generation. The engine hop is the last thing a start does, so the row it started for
    /// is re-read after it: a row that is no longer `.running` (or no longer this attempt's) has
    /// its fresh walk stopped, rather than downloading on — possibly over cellular — until it flips
    /// to Saved on its own. `pause` rather than `cancel` for a deleted row too: the bump is what
    /// stops the walk, and `tearDown` already removed the files.
    private func stopWalkIfNotRunning(_ id: String, _ attempt: Int) async {
        // A non-current attempt touches NOTHING — not even a read. `stillCurrent` alone cannot say
        // that: it is false both when this attempt's own claim was merely dropped (the pause below
        // exists for exactly that) and when a NEWER attempt owns the row, and pausing in the second
        // case bumps the generation out from under a walk that attempt just started, leaving a
        // `.running`, claimed row with no live task and nothing left to reschedule it. Re-checked
        // after the read for the same reason: the claim is free across that hop, so a re-claim
        // lands precisely there. The pause this exists to catch still passes both — `pause` drops
        // `active` without ever bumping `attempts`.
        guard attempts[id] == attempt else { return }
        let status = await read(id: id)?.status
        guard attempts[id] == attempt, status != .running || !stillCurrent(id, attempt) else { return }
        _ = await engine.pause(id: id)
    }

    /// The ONLY way to claim the serial slot: every `active.insert` must carry an attempt bump, or
    /// `stillCurrent` reads a nil generation against the claimant's own token and silently discards
    /// its continuation — which wedges the scheduler, since the claim is never released either.
    private func claim(_ id: String) -> Int {
        active.insert(id)
        attempts[id, default: 0] += 1
        return attempts[id]!
    }

    /// Drops the serial claim only while it is still `attempt`'s — the mirror of `claim`.
    private func release(_ id: String, _ attempt: Int) {
        guard stillCurrent(id, attempt) else { return }
        active.remove(id)
    }

    /// True while `id`'s claim from `begin`/`reattach` (or the 403 re-resolve continuing it) is the live
    /// attempt: cancel/delete/fail/pause drop `active`, a re-claim bumps the generation.
    private func stillCurrent(_ id: String, _ attempt: Int) -> Bool {
        active.contains(id) && attempts[id] == attempt
    }

    private func resolveAndStart(_ row: Row, forceRefresh: Bool) async {
        let attempt = attempts[row.id] ?? 0
        // The 403 re-resolve's intent has to survive a park: `scheduleRetry` drops the claim, and
        // the timer's `begin` re-enters here with `forceRefresh: false`, which would serve an
        // audio-only save the DEAD URL straight back out of the shared `ManifestCache` and hard-fail
        // the next 403 as HTTP_403 instead of re-resolving. The set carries the intent; the budget
        // below is spent only once a forced walk has actually answered.
        let forceRefresh = forceRefresh || pendingForceRefresh.contains(row.id)
        switch await limiterCheck(row.videoId) {
        case .allowed:
            break
        case .delayed(let delay, _):
            // The head row parking on a timer frees the serial slot — re-run the scheduler so a
            // downloadable younger row proceeds instead of starving behind the timer. (Not in the
            // cooldown arm below: that cooldown is global, every row would hit the same wall.)
            await scheduleRetry(row.id, attempt, after: delay); await schedule(); return
        case .blocked(_, let retryAfter):
            await scheduleRetry(row.id, attempt, after: retryAfter); await schedule(); return
        }
        // A VIDEO save needs the muxed itag 18 (save-purpose walk, owner ruling 2026-09-01);
        // an audio-only save stays on the plain walk — it needs visionos's itag-140 `audioOnlyURL`.
        let requiresMuxed = !row.audioOnly
        let resolved: Resolved
        do {
            var first = try await resolver.resolve(row.videoId, purpose: .prefetch, kind: .prefetch,
                                                   sourceChannelId: nil, forceRefresh: forceRefresh,
                                                   requiresMuxed: requiresMuxed)
            if !forceRefresh, let expiresAt = first.expiresAt,
               expiresAt.timeIntervalSince(now()) < Self.minimumRemainingLifetime {
                first = try await resolver.resolve(row.videoId, purpose: .prefetch, kind: .prefetch,
                                                   sourceChannelId: nil, forceRefresh: true,
                                                   requiresMuxed: requiresMuxed)
            }
            resolved = first
        } catch ExtractionError.cooldown(let until) {
            // CF-D-9 reverse direction: never a failed row, never a retry into the cooldown.
            await scheduleRetry(row.id, attempt, after: .seconds(max(1, until.timeIntervalSince(now())))); return
        } catch ExtractionError.botCheck {
            // A bot-checked walk (the muxed save-walk's shape — its walk-end trip just
            // armed the persisted cooldown) is a temporary block, never a failed row. `.botCheck`
            // carries no date, so park briefly: the retry's own resolve hits the resolver's
            // cooldown self-gate BEFORE any rung or network call and lands in the `.cooldown` arm
            // above with the cooldown's exact end.
            await scheduleRetry(row.id, attempt, after: .seconds(1)); return
        } catch {
            // An OLD attempt's error landing after a cancel plus a retry re-claimed the
            // row would `forget` the newer attempt and mark the row failed — and that attempt's own
            // resolve then discarded its continuation on the guard this failure had just
            // invalidated, leaving the row failed with nothing running and nothing to reschedule it.
            // The other two arms park through `scheduleRetry`, which carries the same guard already.
            guard stillCurrent(row.id, attempt) else { return }
            await fail(row.id, Self.code(for: error)); return
        }
        // The resolve suspended for up to the whole ladder walk: a cancel/delete landing in that
        // window already tore the row down — starting the engine now would download a full file
        // for a dead row, in parallel with whatever the freed slot picked up next.
        guard stillCurrent(row.id, attempt) else { return }
        // The forced walk answered AND it is still this attempt's row, so the 403 budget is spent.
        // BELOW the currency guard, not above it: only `forget` clears `pendingForceRefresh`, so a
        // row re-claimed through `pause`/`scheduleRetry`/`release` keeps the intent armed — and a
        // DISCARDED attempt spending it there would leave the live attempt resolving unforced
        // against the dead URL, the one thing the intent exists to survive. A discarded attempt
        // spends nothing. Still bounded: a second 403 needs a walk, a walk needs a resolve that got
        // past this line, and that resolve spent the intent.
        if pendingForceRefresh.remove(row.id) != nil { reResolvedAfter403.insert(row.id) }
        guard let url = Self.sourceURL(resolved.stream, audioOnly: row.audioOnly) else {
            if case .embed = resolved.stream { await fail(row.id, .notSaveable); return }
            // An audio-only save resolves without `requiresMuxed`, so this answer may be
            // the shared `ManifestCache`'s — and a player fallback walk caches `.progressive`
            // (nil `audioOnlyURL`) for its whole 1 h TTL even though a fresh visionos walk would
            // return `.hls` with itag-140. `!forceRefresh` is exactly "plausibly from cache" (a
            // forced walk never reads it), so re-resolve once forced — same shape as the
            // <10-min-expiry refresh above and the 403 re-resolve; forceRefresh bounds it.
            if row.audioOnly, !forceRefresh {
                await resolveAndStart(row, forceRefresh: true)
                return
            }
            await fail(row.id, .noStream)
            return
        }
        if row.status != .running { await transition(row.id, row.status == .paused ? .resume : .start) }
        do {
            try Self.prepareDirectory(directory)
        } catch {
            await fail(row.id, .unknown); return
        }
        // Above the guard, not inside the call's argument list — see `begin`'s resume leg.
        let allowsCellular = !(await wifiOnly())
        guard stillCurrent(row.id, attempt) else { return }   // cancel during the transition hop
        let token = await engine.start(id: row.id, url: url, userAgent: resolved.userAgent, allowsCellular: allowsCellular)
        // The engine's `walks` are in memory, so after a relaunch `start` cannot know the `.tmp` on
        // disk is this exact stream's and deletes it — without a persisted token `reattach()`'s
        // orphan path throws away a 90 %-complete partial and re-downloads the file. The token is
        // constant for the whole walk (every `issueChunk` re-registers the same one), so persisting
        // it ONCE here is the same value a per-chunk write would produce, minus a store hop twice a
        // second: `begin` then takes its resume leg for the orphan instead of resolving.
        // An expired URL still 403s, which is the existing forced re-resolve — never worse than
        // today, and a whole file better when the token is still good.
        await write { store in
            // The pause write's still-running guard: a `.finished` racing this start already
            // cleared `resumeData` on a completed row, and a token there names bytes that are gone.
            guard let item = store.item(id: row.id), item.status == OfflineStatus.running.rawValue else { return }
            item.resumeData = token
            try store.save()
        }
        await stopWalkIfNotRunning(row.id, attempt)
    }

    /// itag 140 for audio-only (only the VISIONOS `.hls` rung carries it), itag 18 for video (the
    /// ANDROID `.progressive` rung — the muxed save-walk skips the `.hls` rung for video saves, so
    /// `(.hls, audioOnly: false)` is unreachable there and stays a defensive nil; video saves are
    /// always 360p/mp4, Task 5's picker must reflect this).
    nonisolated static func sourceURL(_ stream: ResolvedStream, audioOnly: Bool) -> URL? {
        switch (stream, audioOnly) {
        case (.hls(_, _, let audioOnlyURL, _), true): return audioOnlyURL
        case (.progressive(let url, _), false): return url
        case (.hls, false), (.progressive, true), (.embed, _): return nil
        }
    }

    nonisolated static func code(for error: Error) -> ErrorCode {
        switch error as? ExtractionError {
        case .transport?: return .network
        case .invalidVideoId?: return .invalidInput
        case nil: return .unknown
        default: return .noStream
        }
    }

    /// Parks `id` on a timer whose expiry re-runs `schedule()`.
    ///
    /// `schedule()` picks `.queued` rows ONLY, so a park that left the row `.paused` would drop it:
    /// a user Resume that lands on a limiter delay, a limiter block, a resolver cooldown or a
    /// bot-check would read "Paused" forever with no timer that could ever pick it up.
    /// Wait-don't-skip, exactly like a save: the row becomes `.queued` ("Waiting", not an error)
    /// and the existing queued-row mechanism resumes it when the timer fires. Here rather than at
    /// the four call sites: every park routes through this one function, and a row that is already
    /// `.queued` only needs its stale reason cleared (below).
    ///
    /// ENTRY CONTRACT: `attempt` must be a claim this caller holds — `begin`'s own
    /// `claim`, or the live generation `resolveAndStart` continues. An unclaimed caller passing
    /// `attempts[id] ?? 0` fails the guard below and parks NOTHING; both callers claim today, and a
    /// third must claim before it parks.
    private func scheduleRetry(_ id: String, _ attempt: Int, after delay: Duration) async {
        // The claim is `attempt`'s to drop, exactly as in `begin`'s two other refusal paths
        // (`release`): an unconditional `active.remove(id)` here would strip the claim of a cancel
        // plus a retry that re-claimed the row INSIDE the caller's own await, and that retry's
        // `stillCurrent` would then discard its own continuation, leaving the row queued with
        // nothing running.
        //
        // A stale attempt parks NOTHING at all, not just "keeps the claim": writing `.queued` and
        // arming a timer over a walk the newer attempt has already started is the frozen-bar bug
        // `reattach()` documents, and a cancelled or deleted row must not be re-queued behind a
        // timer either (which also leaks a live `Task` per abandoned park).
        guard stillCurrent(id, attempt) else { return }
        active.remove(id)
        // Armed BEFORE the write. `active.remove` and the write's MainActor hop otherwise leave one
        // suspension in which the row is neither `active` nor in `pendingRetryIds`, and a
        // concurrent `schedule()` could `begin` an already-`.queued` row despite the limiter's
        // answer.
        //
        // A ~0 delay CAN fire this timer while the row below still reads `.paused`, which
        // `schedule()` skips (`ExtractionRateLimiter.retryAfter` can return `.zero` at a window
        // boundary, so there is no 1 s floor to lean on). The park is
        // not lost, because the two arms that can emit a ~0 delay — `.delayed` and `.blocked` —
        // both `await schedule()` immediately after this returns, by which time the write below has
        // landed and the row is `.queued`. The cooldown, bot-check and unreachable-gate arms all
        // floor at ≥1 s and need no such argument.
        // ponytail: pass the park through a single write if a caller ever parks with
        // a ~0 delay and does NOT re-schedule behind it.
        retries[id]?.task.cancel()
        retries[id] = (attempt, Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled else { return }
            await self?.retryNow(id, attempt)
        })
        await write { store in
            // `.queued` too, not `.paused` alone: `resume()` admits queued rows, so a queued row can
            // reach a park carrying a noted code and the paused-only guard skipped it entirely.
            guard let item = store.item(id: id),
                  item.status == OfflineStatus.paused.rawValue || item.status == OfflineStatus.queued.rawValue
            else { return }
            item.status = OfflineStatus.queued.rawValue
            // Parking is not an error, and `captionKey` lets ANY non-nil code outrank the status —
            // so a row parked on a limiter after a refused Resume (which notes NETWORK) would read
            // "Network error. Check your connection" for the whole cooldown. The old reason is
            // stale the moment work re-queues, exactly as it is when work starts.
            item.errorCode = nil
            try store.save()
        }
    }

    /// A park's timer firing. Not `private` for the same reason `handle(_:)` is not: the window this
    /// guard closes is between a timer's own cancellation check and its hop onto this actor, which
    /// no seam in the rig can hold open — the test drives the stale call directly.
    ///
    /// Only the timer that is still tracked may clear the entry: nil-ing `retries[id]`
    /// unconditionally lets a timer already past its cancellation check untrack the one that
    /// REPLACED it, which then sits outside `pendingRetryIds` (so `schedule()` picks the row it is
    /// parking) and outside `forget`'s reach, leaking a live `Task` that runs its own `schedule()`.
    func retryNow(_ id: String, _ attempt: Int) async {
        guard retries[id]?.attempt == attempt else { return }
        retries[id] = nil
        await schedule()
    }

    private func gateAllows() async -> Bool {
        await MainActor.run { OfflineStateMachine.allowedToRun(wifiOnly: wifiOnly(), isOnCellular: isOnCellular()) }
    }

    /// A refused user action leaves the reason on the row without touching its status — the
    /// Saved row renders an error code whatever its status, so a Retry/Resume that does nothing
    /// visible stops reading as a broken button. NOT used by the kill-switch refusals: that one
    /// never announces itself (fork D). The completed guard is the `pause` write's: a `.finished`
    /// racing the refusal's own awaits must not end up captioned "Network error"; `transition` and
    /// the completion write clear the code again the moment real work starts or lands.
    ///
    /// On the asymmetry: `.cancelled` is deliberately NOT guarded alongside
    /// `.completed`. A refused Retry of an already-cancelled row is the main way this is reached,
    /// and that row SHOULD say why its button did nothing — a saved file has nothing left to
    /// explain. A cancel racing a refusal ends in exactly the state a refused retry does.
    private func note(_ id: String, _ code: ErrorCode) async {
        await write { store in
            guard let item = store.item(id: id), item.status != OfflineStatus.completed.rawValue else { return }
            item.errorCode = code.rawValue
            try store.save()
        }
    }

    private func fail(_ id: String, _ code: ErrorCode, resumeData: Data? = nil) async {
        forget(id)
        // `bytesWritten` feeds both the progress bar and the storage footer
        // (`OfflineStorage.usedBytes`), so a failure must leave it describing what is ACTUALLY on
        // disk. Most arms leave the partial untouched — but the engine's one-time 416 restart
        // deletes it, and a carried-over count then reads near-full for a file that is gone until
        // the restarted walk's first tick. The `.tmp`'s own size answers for every arm at once
        // (0 when it is gone), where "clear it when there is no resume token" would silently
        // under-report the footer for a 403/network failure whose partial IS still there.
        let partial = Int64((try? directory.appending(path: "\(id).tmp")
            .resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        await write { store in
            guard let item = store.item(id: id), let status = OfflineStatus(rawValue: item.status),
                  let next = OfflineStateMachine.transition(from: status, on: .fail) else { return }
            item.status = next.rawValue
            item.errorCode = code.rawValue
            item.bytesWritten = partial
            item.resumeData = resumeData
            try store.save()
        }
        await schedule()
    }

    /// Cancels the task, removes every file, deletes the row — `delete`, re-save and sweep share it.
    private func tearDown(_ row: Row) async {
        await engine.cancel(id: row.id)   // no-op when nothing is live; cheaper than tracking it
        forget(row.id)
        removeFiles(row)
        await write { store in
            guard let item = store.item(id: row.id) else { return }
            try store.delete(item)
        }
    }

    private func forget(_ id: String) {
        active.remove(id)
        retries[id]?.task.cancel()
        retries[id] = nil
        gatePausedIds.remove(id)
        reResolvedAfter403.remove(id)
        pendingForceRefresh.remove(id)
        lastProgressPersist[id] = nil
    }

    /// Every name this id can own, not just the one the row SNAPSHOT carried: a delete whose read
    /// predates a completion's `localPath` write sees nil there and would leave the finished file
    /// behind (the other half of the orphan the completion's own guard closes). The names are derived,
    /// not stored, so asking for all three costs three `unlink`s that miss.
    private func removeFiles(_ row: Row) {
        try? FileManager.default.removeItem(at: directory.appending(path: "\(row.id).tmp"))
        for kind in OfflineFileKind.allCases {
            try? FileManager.default.removeItem(
                at: directory.appending(path: OfflineStorage.fileName(itemId: row.id, kind: kind)))
        }
    }

    /// The statuses whose bytes a `.finished` may still claim — re-read after the file move, so the
    /// two checks cannot drift.
    nonisolated private static func acceptsCompletion(_ status: OfflineStatus) -> Bool {
        status == .running || status == .paused || status == .queued
    }

    /// `Application Support/offline/`, excluded from backup (owner ruling; pinned in Task 7).
    nonisolated static func prepareDirectory(_ directory: URL) throws {
        var directory = directory
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try directory.setResourceValues(values)
    }

    // MARK: - Store access (every hop returns a Sendable snapshot)

    private func transition(_ id: String, _ event: OfflineEvent) async {
        await write { store in
            guard let item = store.item(id: id), let status = OfflineStatus(rawValue: item.status),
                  let next = OfflineStateMachine.transition(from: status, on: event) else { return }
            item.status = next.rawValue
            // `note()` and `fail()` write an error code and `retry()` clears one, so without this a
            // row that resumes successfully after a refused Resume keeps `NETWORK` and — since
            // `captionKey` prefers a code over any status — renders "Network error. Check your
            // connection" while it downloads and permanently after it completes. Work actually
            // starting is what makes the old reason stale, and this is the ONE place every entry
            // into `.running` goes through.
            if next == .running { item.errorCode = nil }
            try store.save()
        }
    }

    private func read(id: String) async -> Row? {
        await MainActor.run { store.item(id: id).flatMap(Row.init) }
    }

    private func read(videoId: String) async -> Row? {
        await MainActor.run { store.item(videoId: videoId).flatMap(Row.init) }
    }

    private func readAll() async -> [Row] {
        await MainActor.run { store.items.compactMap(Row.init) }
    }

    /// A failed SwiftData save already rolled back and re-read inside the store; nothing to do here.
    private func write(_ body: @MainActor @Sendable (OfflineStore) throws -> Void) async {
        await MainActor.run { try? body(store) }
    }
}
