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
    private let directory: URL

    /// Ids with an engine task in flight (or a resolve on the way to one).
    private var active: Set<String> = []
    /// Per-id attempt generation, bumped when `begin` claims the slot. `stillCurrent` re-checks
    /// it after every suspension: a cancel/delete (drops `active`) or a cancel→retry re-claim
    /// (bumps the counter) invalidates the older attempt mid-flight. Never cleared — clearing
    /// would reissue token 1 to a retry and let a stale attempt pass; one `Int` per id ever saved
    /// this session is the cost.
    private var attempts: [String: Int] = [:]
    /// Ids whose next attempt is timer-scheduled (limiter delay/block, resolver cooldown).
    private var retries: [String: Task<Void, Never>] = [:]
    /// Ids that already spent their one re-resolve on a 403.
    private var reResolvedAfter403: Set<String> = []
    private var lastProgressPersist: [String: Date] = [:]

    /// Test hook: which ids currently wait on a retry timer.
    var pendingRetryIds: Set<String> { Set(retries.keys) }

    init(store: OfflineStore, engine: any OfflineEngine, resolver: any StreamResolving,
         limiterCheck: @escaping @Sendable (String) async -> Decision,
         wifiOnly: @escaping @MainActor @Sendable () -> Bool,
         isOnCellular: @escaping @MainActor @Sendable () -> Bool,
         baseDirectory: URL,
         gate: @escaping @Sendable (String) async -> GateAnswer,
         now: @escaping @Sendable () -> Date) {
        self.store = store
        self.engine = engine
        self.resolver = resolver
        self.limiterCheck = limiterCheck
        self.wifiOnly = wifiOnly
        self.isOnCellular = isOnCellular
        self.gate = gate
        self.now = now
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

    func pause(_ id: String) async {
        guard let row = await read(id: id), row.status == .running else { return }
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
    }

    func resume(_ id: String) async {
        guard let row = await read(id: id), row.status == .paused || row.status == .queued else { return }
        // ponytail: a user Resume runs immediately even if the scheduler has something active;
        // the serial floor (CF-D-5) applies to queue picks, not to explicit user intent.
        await begin(row)
    }

    func cancel(_ id: String) async {
        guard let row = await read(id: id), OfflineStateMachine.transition(from: row.status, on: .cancel) != nil else { return }
        await engine.cancel(id: id)
        forget(id)
        removeFiles(row)
        await write { store in
            guard let item = store.item(id: id), let status = OfflineStatus(rawValue: item.status),
                  let next = OfflineStateMachine.transition(from: status, on: .cancel) else { return }
            item.status = next.rawValue
            item.resumeData = nil
            item.bytesWritten = 0
            try store.save()
        }
        await schedule()
    }

    func retry(_ id: String) async {
        guard let row = await read(id: id), OfflineStateMachine.transition(from: row.status, on: .retry) != nil else { return }
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

    func reattach() async {
        let live = await engine.liveIds()
        let rows = await readAll()
        for row in rows where row.status == .running {
            if live.contains(row.id) {
                active.insert(row.id)
            } else {
                // Orphaned: the app died mid-download. Resume data (from a pause before the death)
                // resumes as paused; without it the row queues and downloads from zero.
                let next: OfflineStatus = row.resumeData == nil ? .queued : .paused
                await write { store in
                    guard let item = store.item(id: row.id) else { return }
                    item.status = next.rawValue
                    try store.save()
                }
            }
        }
        await schedule()
    }

    func sweep() async {
        let current = now()
        for row in await readAll() where row.status == .completed {
            guard let completedAt = row.completedAt else { continue }
            let action: SweepAction
            if OfflineSweep.isExpired(completedAt: completedAt, now: current) {
                action = .deleteExpired   // TTL first, before any network
            } else {
                action = OfflineSweep.decide(completedAt: completedAt, now: current, gate: await gate(row.videoId))
            }
            if action != .keep { await tearDown(row) }
        }
    }

    /// Reconciliation note 6: re-evaluate the cellular gate after `wifiOnlyDownloads` or the path
    /// changes — pause running tasks the gate now refuses, start queued ones it now allows.
    func gateDidChange() async {
        if await gateAllows() {
            await schedule()
            return
        }
        for row in await readAll() where row.status == .running && active.contains(row.id) {
            await pause(row.id)
        }
    }

    // MARK: - Engine events

    /// Also the test seam: tests call this directly instead of racing the stream consumer.
    func handle(_ event: OfflineDownloadEvent) async {
        switch event {
        case .progress(let id, let bytesWritten, let totalBytes):
            let current = now()
            // ponytail: persist at most twice a second; the in-memory @Model mutation already
            // re-renders an observing row, the save is for relaunch/footer accuracy.
            let persist = current.timeIntervalSince(lastProgressPersist[id] ?? .distantPast) >= 0.5
            if persist { lastProgressPersist[id] = current }
            await write { store in
                guard let item = store.item(id: id), item.status == OfflineStatus.running.rawValue else { return }
                item.bytesWritten = bytesWritten
                if let totalBytes { item.totalBytes = totalBytes }
                if persist { try store.save() }
            }

        case .finished(let id):
            let tmp = directory.appending(path: "\(id).tmp")
            // `.paused` too: `pause()` writes `paused` before the engine hop returns, so a final
            // chunk racing the pause can deliver `.finished` for a paused row — those bytes are
            // the complete file and must be kept, not deleted.
            guard let row = await read(id: id), row.status == .running || row.status == .paused else {
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
            let size = (try? FileManager.default.attributesOfItem(atPath: destination.path())[.size] as? Int64) ?? 0
            let completedAt = now()
            forget(id)
            await write { store in
                guard let item = store.item(id: id) else { return }
                item.status = OfflineStatus.completed.rawValue
                item.localPath = name
                item.bytesWritten = size
                item.totalBytes = size
                item.resumeData = nil
                item.completedAt = completedAt
                try store.save()
            }
            await schedule()

        case .failed(let id, let failure):
            guard let row = await read(id: id), row.status == .running else { return }
            switch failure {
            case .http(403) where !reResolvedAfter403.contains(id):
                // `DownloadWorker.kt:266-276`: one forced re-resolve, restart from zero — the old
                // resume data names the dead URL.
                reResolvedAfter403.insert(id)
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
            case .http(403): await fail(id, .http403)
            case .http(429): await fail(id, .http429)
            case .http: await fail(id, .network)
            case .network(let resumeData): await fail(id, .network, resumeData: resumeData)
            }
        }
    }

    // MARK: - Scheduling

    // ponytail: ONE active download at a time (CF-D-5) — per-item concurrency when saves
    // actually queue up in practice; serial is the rate-limit-friendly floor and keeps the single
    // background session's re-attach trivial.
    private func schedule() async {
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

    /// Runs one row: the cellular gate, then either a resume-data restart or the resolve path.
    private func begin(_ row: Row) async {
        // Claim the slot SYNCHRONOUSLY, before any suspension: two interleaved schedule() passes
        // (or a double-tap Resume) both used to pass their checks and start the same row twice.
        guard !active.contains(row.id) else { return }
        active.insert(row.id)
        attempts[row.id, default: 0] += 1
        let attempt = attempts[row.id]!
        guard await gateAllows() else {
            // Stays queued/paused. A queued row is re-picked by the schedule() a gateDidChange
            // runs; a paused row waits for the user's Resume (schedule() picks only queued rows).
            active.remove(row.id)
            return
        }
        guard stillCurrent(row.id, attempt) else { return }   // cancelled/deleted during the hop
        if let resumeData = row.resumeData {
            await transition(row.id, row.status == .paused ? .resume : .start)
            try? Self.prepareDirectory(directory)
            guard stillCurrent(row.id, attempt) else { return }
            await engine.resume(id: row.id, resumeData: resumeData, allowsCellular: !(await wifiOnly()))
            return
        }
        await resolveAndStart(row, forceRefresh: false)
    }

    /// True while `id`'s claim from `begin` (or the 403 re-resolve continuing it) is the live
    /// attempt: cancel/delete/fail/pause drop `active`, a re-claim bumps the generation.
    private func stillCurrent(_ id: String, _ attempt: Int) -> Bool {
        active.contains(id) && attempts[id] == attempt
    }

    private func resolveAndStart(_ row: Row, forceRefresh: Bool) async {
        let attempt = attempts[row.id] ?? 0
        switch await limiterCheck(row.videoId) {
        case .allowed:
            break
        case .delayed(let delay, _):
            // The head row parking on a timer frees the serial slot — re-run the scheduler so a
            // downloadable younger row proceeds instead of starving behind the timer. (Not in the
            // cooldown arm below: that cooldown is global, every row would hit the same wall.)
            scheduleRetry(row.id, after: delay); await schedule(); return
        case .blocked(_, let retryAfter):
            scheduleRetry(row.id, after: retryAfter); await schedule(); return
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
            scheduleRetry(row.id, after: .seconds(max(1, until.timeIntervalSince(now())))); return
        } catch {
            await fail(row.id, Self.code(for: error)); return
        }
        // The resolve suspended for up to the whole ladder walk: a cancel/delete landing in that
        // window already tore the row down — starting the engine now would download a full file
        // for a dead row, in parallel with whatever the freed slot picked up next.
        guard stillCurrent(row.id, attempt) else { return }
        guard let url = Self.sourceURL(resolved.stream, audioOnly: row.audioOnly) else {
            if case .embed = resolved.stream { await fail(row.id, .notSaveable) } else { await fail(row.id, .noStream) }
            return
        }
        if row.status != .running { await transition(row.id, row.status == .paused ? .resume : .start) }
        do {
            try Self.prepareDirectory(directory)
        } catch {
            await fail(row.id, .unknown); return
        }
        guard stillCurrent(row.id, attempt) else { return }   // cancel during the transition hop
        await engine.start(id: row.id, url: url, userAgent: resolved.userAgent, allowsCellular: !(await wifiOnly()))
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

    private func scheduleRetry(_ id: String, after delay: Duration) {
        active.remove(id)
        retries[id]?.cancel()
        retries[id] = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled else { return }
            await self?.retryNow(id)
        }
    }

    private func retryNow(_ id: String) async {
        retries[id] = nil
        await schedule()
    }

    private func gateAllows() async -> Bool {
        await MainActor.run { OfflineStateMachine.allowedToRun(wifiOnly: wifiOnly(), isOnCellular: isOnCellular()) }
    }

    private func fail(_ id: String, _ code: ErrorCode, resumeData: Data? = nil) async {
        forget(id)
        await write { store in
            guard let item = store.item(id: id), let status = OfflineStatus(rawValue: item.status),
                  let next = OfflineStateMachine.transition(from: status, on: .fail) else { return }
            item.status = next.rawValue
            item.errorCode = code.rawValue
            // `bytesWritten` stays: the partial `.tmp` is still on disk (the engine's resume point).
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
        retries[id]?.cancel()
        retries[id] = nil
        reResolvedAfter403.remove(id)
        lastProgressPersist[id] = nil
    }

    private func removeFiles(_ row: Row) {
        try? FileManager.default.removeItem(at: directory.appending(path: "\(row.id).tmp"))
        if let localPath = row.localPath {
            try? FileManager.default.removeItem(at: directory.appending(path: localPath))
        }
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
