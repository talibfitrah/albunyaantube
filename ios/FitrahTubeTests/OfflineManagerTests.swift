import AVFoundation
import Foundation
import InnerTubeKit
import SwiftData
import Testing
@testable import FitrahTube

/// Phase 3 Task 4: `OfflineManager` — the actor that turns a save request into bytes on disk.
/// The resolver is `RecordingResolver` (PlayerTestDoubles), the limiter is a closure, the
/// download layer is `FakeOfflineEngine`; the real background `URLSession` is glue, proven by
/// the `OFFLINE_LIVE=1` smoke at the bottom.
@Suite(.perTest)
struct OfflineManagerTests {
    private static let lectureVideoId = "xc7keR2piUM"

    /// Records every engine call; a test drives completions by calling `manager.handle(_:)`
    /// directly (deterministic) or by `emit(_:)` into the stream (proves the consumer loop).
    nonisolated final class FakeOfflineEngine: OfflineEngine, @unchecked Sendable {
        struct Start: Equatable, Sendable { var id: String; var url: URL; var userAgent: String; var allowsCellular: Bool }
        private let lock = NSLock()
        private var _starts: [Start] = []
        private var _resumes: [(id: String, resumeData: Data)] = []
        private var _pauses: [String] = []
        private var _cancels: [String] = []
        private var _live: Set<String> = []
        /// What `pause` hands back (canned resume data).
        var pauseResumeData: Data? = Data("RD".utf8)
        let events: AsyncStream<OfflineDownloadEvent>
        private let continuation: AsyncStream<OfflineDownloadEvent>.Continuation

        init() {
            (events, continuation) = AsyncStream.makeStream(of: OfflineDownloadEvent.self)
        }

        var starts: [Start] { lock.withLock { _starts } }
        var resumes: [(id: String, resumeData: Data)] { lock.withLock { _resumes } }
        var pauses: [String] { lock.withLock { _pauses } }
        var cancels: [String] { lock.withLock { _cancels } }
        var live: Set<String> {
            get { lock.withLock { _live } }
            set { lock.withLock { _live = newValue } }
        }

        func emit(_ event: OfflineDownloadEvent) { continuation.yield(event) }

        func start(id: String, url: URL, userAgent: String, allowsCellular: Bool) async {
            lock.withLock { _starts.append(Start(id: id, url: url, userAgent: userAgent, allowsCellular: allowsCellular)); _ = _live.insert(id) }
        }
        func resume(id: String, resumeData: Data, allowsCellular: Bool) async {
            lock.withLock { _resumes.append((id, resumeData)); _ = _live.insert(id) }
        }
        func pause(id: String) async -> Data? {
            lock.withLock { _pauses.append(id); _live.remove(id) }
            return pauseResumeData
        }
        func cancel(id: String) async {
            lock.withLock { _cancels.append(id); _live.remove(id) }
        }
        func liveIds() async -> Set<String> { live }
    }

    /// Cellular-gate inputs the closures read live.
    final class Flags: @unchecked Sendable {
        nonisolated(unsafe) var wifiOnly = false
        nonisolated(unsafe) var cellular = false
        nonisolated(unsafe) var gate: GateAnswer = .unreachable
        nonisolated(unsafe) var decision: Decision = .allowed
        /// Per-video overrides of `decision` (the starvation test blocks one id, allows the rest).
        nonisolated(unsafe) var decisions: [String: Decision] = [:]
        /// Ids whose limiter check suspends until removed (same 1 ms-poll gate as
        /// `RecordingResolver.hold`); `limiterEntered` records arrival so a test can wait for it.
        nonisolated(unsafe) var limiterHeld: Set<String> = []
        nonisolated(unsafe) var limiterEntered: Set<String> = []
        nonisolated(unsafe) var now = Date()
    }

    private struct Rig {
        let manager: OfflineManager
        let store: OfflineStore
        let container: ModelContainer
        let engine: FakeOfflineEngine
        let resolver: RecordingResolver
        let flags: Flags
        let base: URL
        var directory: URL { OfflineStorage.directoryURL(base: base) }

        /// Re-read through a FRESH context: what actually persisted, never in-memory state.
        func persisted(id: String) -> OfflineItem? {
            var d = FetchDescriptor<OfflineItem>(predicate: #Predicate { $0.id == id })
            d.fetchLimit = 1
            return try? ModelContext(container).fetch(d).first
        }
        func persisted(videoId: String) -> OfflineItem? {
            var d = FetchDescriptor<OfflineItem>(predicate: #Predicate { $0.videoId == videoId })
            d.fetchLimit = 1
            return try? ModelContext(container).fetch(d).first
        }
        func rowCount() -> Int { (try? ModelContext(container).fetchCount(FetchDescriptor<OfflineItem>())) ?? -1 }
        func cleanUp() { try? FileManager.default.removeItem(at: base) }
    }

    private func makeRig(_ outcome: RecordingResolver.Outcome = .hls) -> Rig {
        let container = AppContainer.makeModelContainer(inMemory: true)
        let store = OfflineStore(modelContainer: container)
        let engine = FakeOfflineEngine()
        let resolver = RecordingResolver(outcome)
        let flags = Flags()
        let base = FileManager.default.temporaryDirectory
            .appending(path: "OfflineManagerTests-\(UUID().uuidString)", directoryHint: .isDirectory)
        let manager = OfflineManager(
            store: store, engine: engine, resolver: resolver,
            limiterCheck: { id in
                flags.limiterEntered.insert(id)
                while flags.limiterHeld.contains(id) { try? await Task.sleep(for: .milliseconds(1)) }
                return flags.decisions[id] ?? flags.decision
            },
            wifiOnly: { flags.wifiOnly }, isOnCellular: { flags.cellular },
            baseDirectory: base, gate: { _ in flags.gate }, now: { flags.now })
        return Rig(manager: manager, store: store, container: container, engine: engine,
                   resolver: resolver, flags: flags, base: base)
    }

    private static let metadata = OfflineMetadata(title: "Lecture", channelName: "Channel", thumbnailUrl: nil)

    private func save(_ rig: Rig, videoId: String = lectureVideoId, audioOnly: Bool = true) async -> String {
        await rig.manager.save(videoId: videoId, quality: "360p", audioOnly: audioOnly, metadata: Self.metadata)
        return rig.persisted(videoId: videoId)?.id ?? "missing"
    }

    /// Bounded poll (the `RecordingResolver.waitUntilCalled` shape, ~2 s).
    private func waitUntil(_ condition: () -> Bool, sourceLocation: SourceLocation = #_sourceLocation) async {
        for _ in 0..<2000 {
            if condition() { return }
            try? await Task.sleep(for: .milliseconds(1))
        }
        #expect(condition(), "condition never became true", sourceLocation: sourceLocation)
    }

    // MARK: - Resolve path

    @Test func aCacheWarmAudioOnlySaveReachesTheEngineWithTheItag140URL() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let id = await save(rig)
        #expect(rig.resolver.calls == [.init(videoId: Self.lectureVideoId, kind: .prefetch, purpose: .prefetch, forceRefresh: false)])
        #expect(rig.engine.starts.count == 1)
        #expect(rig.engine.starts.first?.id == id)
        #expect(rig.engine.starts.first?.url.absoluteString == "https://r1/a140")
        #expect(rig.engine.starts.first?.userAgent == "UA")
        #expect(rig.persisted(id: id)?.status == OfflineStatus.running.rawValue)
    }

    @Test func aVideoSaveOnTheProgressiveRungReachesTheEngineWithTheItag18URL() async throws {
        let rig = makeRig(.progressive); defer { rig.cleanUp() }
        _ = await save(rig, audioOnly: false)
        #expect(rig.engine.starts.first?.url.absoluteString == "https://manifest.googlevideo.com/x.m3u8")
    }

    /// Muxed save-walk (owner ruling 2026-09-01, replaces the old "HLS rung → NO_STREAM" pin):
    /// a VIDEO save demands the muxed itag 18 from the resolver (`requiresMuxed: true` — the
    /// resolver's ladder skips the HLS rung for it), and the `.progressive` answer reaches the
    /// engine. An audio-only save keeps `requiresMuxed: false` (it needs visionos's itag-140
    /// `audioOnlyURL`) — pinned by `aCacheWarmAudioOnlySaveReachesTheEngineWithTheItag140URL`.
    @Test func aVideoSaveRequiresMuxedAndTheProgressiveAnswerReachesTheEngine() async throws {
        let rig = makeRig(.progressive); defer { rig.cleanUp() }
        let id = await save(rig, audioOnly: false)
        #expect(rig.resolver.calls == [.init(videoId: Self.lectureVideoId, kind: .prefetch,
                                             purpose: .prefetch, forceRefresh: false, requiresMuxed: true)])
        #expect(rig.engine.starts.count == 1)
        #expect(rig.engine.starts.first?.url.absoluteString == "https://manifest.googlevideo.com/x.m3u8")
        #expect(rig.persisted(id: id)?.status == OfflineStatus.running.rawValue)
    }

    @Test func aBlockedLimiterDecisionLeavesTheItemQueuedWithARetryAndNoEngineCall() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        rig.flags.decision = .blocked(reason: "prefetch blocked", retryAfter: .seconds(300))
        let id = await save(rig)
        #expect(rig.engine.starts.isEmpty)
        #expect(rig.resolver.calls.isEmpty)
        #expect(rig.persisted(id: id)?.status == OfflineStatus.queued.rawValue)
        #expect(await rig.manager.pendingRetryIds == [id])
    }

    @Test func anEmbedOutcomeFailsNotSaveableWithNoEngineCall() async throws {
        let rig = makeRig(.embed); defer { rig.cleanUp() }
        let id = await save(rig)
        #expect(rig.engine.starts.isEmpty)
        let row = try #require(rig.persisted(id: id))
        #expect(row.status == OfflineStatus.failed.rawValue)
        #expect(row.errorCode == "NOT_SAVEABLE")
    }

    /// Cubic P2: an audio-only save resolves WITHOUT `requiresMuxed`, so it reads the shared
    /// `ManifestCache` — which may hold a player fallback walk's `.progressive` (nil
    /// `audioOnlyURL`) for its whole 1 h TTL even though a fresh visionos walk would return
    /// `.hls` with itag-140. The manager must re-resolve ONCE with `forceRefresh: true` (a
    /// forced walk never reads the cache) before failing.
    @Test func anAudioOnlySaveHittingAProgressiveCacheShapeReResolvesForcedAndStarts() async throws {
        let rig = makeRig(.progressive); defer { rig.cleanUp() }
        rig.resolver.hold(Self.lectureVideoId)
        let saveTask = Task { await save(rig) }
        await rig.resolver.waitUntilCalled(count: 1)   // call 1 captured .progressive (the cache shape)
        rig.resolver.outcome = .hls                    // what a fresh walk would return
        rig.resolver.release(id: Self.lectureVideoId)
        await rig.resolver.waitUntilCalled(count: 2)
        rig.resolver.release(id: Self.lectureVideoId)
        let id = await saveTask.value
        #expect(rig.resolver.calls.map(\.forceRefresh) == [false, true])
        #expect(rig.engine.starts.count == 1)
        #expect(rig.engine.starts.first?.url.absoluteString == "https://r1/a140")
        #expect(rig.persisted(id: id)?.status == OfflineStatus.running.rawValue)
    }

    /// Cubic P2, the bound: when the FORCED re-resolve still yields no audio URL, the row fails
    /// NO_STREAM after exactly two resolves — never a loop.
    @Test func anAudioOnlySaveWhoseForcedReResolveStillLacksAudioFailsNoStream() async throws {
        let rig = makeRig(.progressive); defer { rig.cleanUp() }
        let id = await save(rig)
        #expect(rig.resolver.calls.map(\.forceRefresh) == [false, true])
        #expect(rig.engine.starts.isEmpty)
        let row = try #require(rig.persisted(id: id))
        #expect(row.status == OfflineStatus.failed.rawValue)
        #expect(row.errorCode == "NO_STREAM")
    }

    @Test func anExpiryUnderTenMinutesForcesExactlyOneRefreshBeforeTheEngine() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        // RecordingResolver answers expiresAt = now + 3600; a clock 3300 s ahead leaves 300 s.
        rig.flags.now = Date().addingTimeInterval(3300)
        _ = await save(rig)
        #expect(rig.resolver.calls.map(\.forceRefresh) == [false, true])
        #expect(rig.engine.starts.count == 1)
    }

    /// CF-D-9 reverse direction: a save during an active cooldown never becomes a failed row.
    @Test func aResolverCooldownKeepsTheItemQueuedWithARetryAtTheCooldownEnd() async throws {
        let rig = makeRig(.failure(.cooldown(until: Date().addingTimeInterval(1800)))); defer { rig.cleanUp() }
        let id = await save(rig)
        #expect(rig.engine.starts.isEmpty)
        #expect(rig.persisted(id: id)?.status == OfflineStatus.queued.rawValue)
        #expect(rig.persisted(id: id)?.errorCode == nil)
        #expect(await rig.manager.pendingRetryIds == [id])
    }

    /// Review F2, app side: a bot-checked muxed walk now fails overall with `.botCheck` (the
    /// walk-end trip armed the persisted cooldown). The row must stay queued with a retry —
    /// never a failed NOT_SAVEABLE/NO_STREAM row. The parked retry's own resolve then hits the
    /// resolver's cooldown self-gate (zero network) and the `.cooldown` arm reschedules it at
    /// the cooldown's exact end.
    @Test func aBotCheckedVideoSaveStaysQueuedWithARetryNeverFailed() async throws {
        let rig = makeRig(.failure(.botCheck)); defer { rig.cleanUp() }
        let id = await save(rig, audioOnly: false)
        #expect(rig.engine.starts.isEmpty)
        #expect(rig.persisted(id: id)?.status == OfflineStatus.queued.rawValue)
        #expect(rig.persisted(id: id)?.errorCode == nil)
        #expect(await rig.manager.pendingRetryIds == [id])
    }

    @Test func aTransportFailureFailsWithNetwork() async throws {
        let rig = makeRig(.failure(.transport("boom"))); defer { rig.cleanUp() }
        let id = await save(rig)
        #expect(rig.persisted(id: id)?.status == OfflineStatus.failed.rawValue)
        #expect(rig.persisted(id: id)?.errorCode == "NETWORK")
    }

    // MARK: - 403 handling (DownloadWorker.kt:266-276 parity)

    @Test func aFirst403ReResolvesForcedAndRestartsASecondFailsHTTP403() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let id = await save(rig)
        await rig.manager.handle(.failed(id: id, failure: .http(status: 403)))
        #expect(rig.resolver.calls.map(\.forceRefresh) == [false, true])
        #expect(rig.engine.starts.count == 2)
        #expect(rig.persisted(id: id)?.status == OfflineStatus.running.rawValue)

        await rig.manager.handle(.failed(id: id, failure: .http(status: 403)))
        #expect(rig.engine.starts.count == 2)
        #expect(rig.persisted(id: id)?.status == OfflineStatus.failed.rawValue)
        #expect(rig.persisted(id: id)?.errorCode == "HTTP_403")
    }

    @Test func a429FailsHTTP429() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let id = await save(rig)
        await rig.manager.handle(.failed(id: id, failure: .http(status: 429)))
        #expect(rig.persisted(id: id)?.errorCode == "HTTP_429")
    }

    // MARK: - Pause / resume / cancel / delete

    @Test func pauseCapturesResumeDataAndResumeHandsItBackWithoutReResolving() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let id = await save(rig)
        await rig.manager.handle(.progress(id: id, bytesWritten: 500, totalBytes: 1_000))
        await rig.manager.pause(id)
        #expect(rig.engine.pauses == [id])
        let paused = try #require(rig.persisted(id: id))
        #expect(paused.status == OfflineStatus.paused.rawValue)
        #expect(paused.resumeData == Data("RD".utf8))
        #expect(paused.bytesWritten == 500)

        await rig.manager.resume(id)
        #expect(rig.engine.resumes.map(\.id) == [id])
        #expect(rig.engine.resumes.first?.resumeData == Data("RD".utf8))
        #expect(rig.resolver.calls.count == 1)
        #expect(rig.persisted(id: id)?.status == OfflineStatus.running.rawValue)
    }

    @Test func cancelStopsTheTaskAndZeroesTheBytes() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let id = await save(rig)
        await rig.manager.handle(.progress(id: id, bytesWritten: 500, totalBytes: 1_000))
        await rig.manager.cancel(id)
        #expect(rig.engine.cancels == [id])
        let row = try #require(rig.persisted(id: id))
        #expect(row.status == OfflineStatus.cancelled.rawValue)
        #expect(row.bytesWritten == 0)
        #expect(row.resumeData == nil)
    }

    @Test func retryRequeuesAFailedRowAndResolvesAgain() async throws {
        let rig = makeRig(.embed); defer { rig.cleanUp() }
        let id = await save(rig)
        #expect(rig.persisted(id: id)?.status == OfflineStatus.failed.rawValue)
        rig.resolver.outcome = .hls
        await rig.manager.retry(id)
        #expect(rig.persisted(id: id)?.status == OfflineStatus.running.rawValue)
        #expect(rig.persisted(id: id)?.errorCode == nil)
        #expect(rig.engine.starts.count == 1)
    }

    @Test func aFinishedDownloadLandsAtTheRelativePathAndReadsCompletedOnReRead() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let id = await save(rig)
        let bytes = Data(repeating: 7, count: 4_321)
        try bytes.write(to: rig.directory.appending(path: "\(id).tmp"))
        await rig.manager.handle(.finished(id: id))
        let row = try #require(rig.persisted(id: id))
        #expect(row.status == OfflineStatus.completed.rawValue)
        #expect(row.localPath == "\(id).m4a")
        #expect(row.bytesWritten == 4_321)
        #expect(row.completedAt != nil)
        let file = OfflineStorage.fileURL(relativePath: "\(id).m4a", base: rig.base)
        #expect(FileManager.default.fileExists(atPath: file.path()))
        #expect(!FileManager.default.fileExists(atPath: rig.directory.appending(path: "\(id).tmp").path()))
    }

    @Test func eventsFromTheEngineStreamReachTheManager() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let id = await save(rig)
        rig.engine.emit(.progress(id: id, bytesWritten: 42, totalBytes: 100))
        await waitUntil { rig.store.item(id: id)?.bytesWritten == 42 }
        #expect(rig.store.item(id: id)?.totalBytes == 100)
    }

    @Test func deleteRemovesTheFileThenTheRow() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let id = await save(rig)
        try Data("x".utf8).write(to: rig.directory.appending(path: "\(id).tmp"))
        await rig.manager.handle(.finished(id: id))
        let file = OfflineStorage.fileURL(relativePath: "\(id).m4a", base: rig.base)
        #expect(FileManager.default.fileExists(atPath: file.path()))
        await rig.manager.delete(id)
        #expect(!FileManager.default.fileExists(atPath: file.path()))
        #expect(rig.persisted(id: id) == nil)
        #expect(rig.rowCount() == 0)
    }

    /// The upsert trap (`OfflineStore.insert` doc): a re-save of the same videoId must cancel
    /// the old task and drop the old file BEFORE the row is replaced.
    @Test func aReSaveCancelsTheOldTaskAndDeletesTheOldFileBeforeUpserting() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let old = await save(rig)
        try Data("x".utf8).write(to: rig.directory.appending(path: "\(old).tmp"))
        await rig.manager.handle(.finished(id: old))
        let oldFile = OfflineStorage.fileURL(relativePath: "\(old).m4a", base: rig.base)
        #expect(FileManager.default.fileExists(atPath: oldFile.path()))

        let new = await save(rig, audioOnly: true)
        #expect(new != old)
        #expect(rig.rowCount() == 1)
        #expect(rig.engine.cancels == [old])
        #expect(!FileManager.default.fileExists(atPath: oldFile.path()))
        #expect(rig.engine.starts.map(\.id) == [old, new])
    }

    // MARK: - Relaunch

    @Test func reattachKeepsLiveTasksAndDemotesOrphanedRunningRows() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        func insert(_ videoId: String, resumeData: Data?) throws -> String {
            let item = OfflineItem(videoId: videoId, title: videoId, channelName: nil, thumbnailUrl: nil,
                                   qualityLabel: "360p", audioOnly: true, status: OfflineStatus.running.rawValue,
                                   resumeData: resumeData)
            try rig.store.insert(item)
            return item.id
        }
        let live = try insert("vidLive000A", resumeData: nil)
        let orphanNoData = try insert("vidOrphan0B", resumeData: nil)
        let orphanWithData = try insert("vidOrphan0C", resumeData: Data("RD".utf8))
        rig.engine.live = [live]

        await rig.manager.reattach()

        #expect(rig.persisted(id: live)?.status == OfflineStatus.running.rawValue)
        #expect(rig.persisted(id: orphanWithData)?.status == OfflineStatus.paused.rawValue)
        // No resume data → queued, and it STAYS queued: the re-bound live task holds the serial
        // slot (CF-D-5), so nothing resolves until it finishes.
        #expect(rig.persisted(id: orphanNoData)?.status == OfflineStatus.queued.rawValue)
        #expect(rig.resolver.calls.isEmpty)
        #expect(rig.engine.starts.isEmpty)
    }

    /// Fix-first F1: a re-attached claim must carry an attempt token like `begin`'s. Without it,
    /// the designed-for expired-URL 403 after a relaunch is silently discarded (`stillCurrent`
    /// reads nil against the re-resolve's token) AND the `active` claim is never released — every
    /// later save wedges behind `schedule()`'s serial guard for the whole session.
    @Test func aReattached403SurvivorReResolvesAndTheSchedulerIsNotWedged() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let item = OfflineItem(videoId: "vidRelaunch", title: "t", channelName: nil, thumbnailUrl: nil,
                               qualityLabel: "360p", audioOnly: true, status: OfflineStatus.running.rawValue,
                               resumeData: nil)
        try rig.store.insert(item)
        rig.engine.live = [item.id]
        await rig.manager.reattach()

        // A chunk 403s (the expired googlevideo URL): the one forced re-resolve must proceed
        // and restart the engine with the fresh URL.
        await rig.manager.handle(.failed(id: item.id, failure: .http(status: 403)))
        #expect(rig.resolver.calls.map(\.forceRefresh) == [true])
        #expect(rig.engine.starts.map(\.id) == [item.id])
        #expect(rig.persisted(id: item.id)?.status == OfflineStatus.running.rawValue)

        // And once the row completes, an unrelated save is not blocked by a leaked claim.
        try Data("x".utf8).write(to: rig.directory.appending(path: "\(item.id).tmp"))
        await rig.manager.handle(.finished(id: item.id))
        let other = await save(rig, videoId: "vidOther000")
        #expect(rig.engine.starts.map(\.id) == [item.id, other])
    }

    // MARK: - Cellular gate (reconciliation note 6)

    @Test func wifiOnlyPlusCellularRefusesToStartAndTheGateFlipStartsIt() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        rig.flags.wifiOnly = true
        rig.flags.cellular = true
        let id = await save(rig)
        #expect(rig.engine.starts.isEmpty)
        #expect(rig.resolver.calls.isEmpty)
        #expect(rig.persisted(id: id)?.status == OfflineStatus.queued.rawValue)

        rig.flags.cellular = false
        await rig.manager.gateDidChange()
        #expect(rig.engine.starts.count == 1)
        #expect(rig.engine.starts.first?.allowsCellular == false)
        #expect(rig.persisted(id: id)?.status == OfflineStatus.running.rawValue)
    }

    @Test func flippingWifiOnlyOnWhileRunningOnCellularPausesTheTask() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        rig.flags.cellular = true
        let id = await save(rig)
        #expect(rig.engine.starts.first?.allowsCellular == true)
        rig.flags.wifiOnly = true
        await rig.manager.gateDidChange()
        #expect(rig.engine.pauses == [id])
        #expect(rig.persisted(id: id)?.status == OfflineStatus.paused.rawValue)
        #expect(rig.persisted(id: id)?.resumeData == Data("RD".utf8))
    }

    // MARK: - Race windows (Task 4 review fix round)

    /// Finding 1 (double-tap `resume`): two concurrent resumes of the same paused row must hand
    /// the engine exactly one task — both used to pass the status guard before either claimed
    /// the serial slot.
    @Test func aDoubleTapResumeStartsTheEngineOnce() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let id = await save(rig)
        await rig.manager.handle(.progress(id: id, bytesWritten: 500, totalBytes: 1_000))
        await rig.manager.pause(id)
        async let first: Void = rig.manager.resume(id)
        async let second: Void = rig.manager.resume(id)
        _ = await (first, second)
        #expect(rig.engine.resumes.map(\.id) == [id])
    }

    /// Finding 1 (serial-slot TOCTOU): `schedule()` used to check `active.isEmpty`, then suspend
    /// (`readAll`, the gate hop) before claiming — two interleaved passes both began the same row.
    @Test func twoConcurrentGateFlipsStartTheQueuedRowOnce() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        rig.flags.wifiOnly = true
        rig.flags.cellular = true
        let id = await save(rig)
        #expect(rig.engine.starts.isEmpty)

        rig.flags.cellular = false
        async let first: Void = rig.manager.gateDidChange()
        async let second: Void = rig.manager.gateDidChange()
        _ = await (first, second)
        #expect(rig.engine.starts.map(\.id) == [id])
        #expect(rig.resolver.calls.count == 1)
    }

    /// Finding 2: a cancel landing while the resolve is in flight must not start the engine for
    /// the dead row when the resolve finally answers.
    @Test func cancelDuringAnInFlightResolveNeverStartsTheEngine() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        rig.resolver.hold(Self.lectureVideoId)
        let saveTask = Task { await rig.manager.save(videoId: Self.lectureVideoId, quality: "360p",
                                                     audioOnly: true, metadata: Self.metadata) }
        await rig.resolver.waitUntilCalled(count: 1)
        let id = try #require(rig.persisted(videoId: Self.lectureVideoId)?.id)

        await rig.manager.cancel(id)
        rig.resolver.release(id: Self.lectureVideoId)
        await saveTask.value

        #expect(rig.engine.starts.isEmpty)
        #expect(rig.persisted(id: id)?.status == OfflineStatus.cancelled.rawValue)
    }

    /// Finding 4: a head-of-queue row parked by a limiter block must not starve a younger queued
    /// row that the limiter would allow — the block frees the slot AND re-runs the scheduler.
    @Test func aLimiterBlockedHeadRowDoesNotStarveAYoungerQueuedRow() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let older = "vidOlder000", younger = "vidYounger0"
        rig.flags.decisions[older] = .blocked(reason: "prefetch blocked", retryAfter: .seconds(300))
        rig.flags.limiterHeld = [older]

        let saveOlder = Task { await rig.manager.save(videoId: older, quality: "360p",
                                                      audioOnly: true, metadata: Self.metadata) }
        await waitUntil { rig.flags.limiterEntered.contains(older) }
        // The younger save's own schedule() bails: the older row holds the serial slot.
        _ = await save(rig, videoId: younger)
        #expect(rig.engine.starts.isEmpty)

        rig.flags.limiterHeld = []
        await saveOlder.value
        let youngerId = try #require(rig.persisted(videoId: younger)?.id)
        await waitUntil { rig.engine.starts.map(\.id) == [youngerId] }
        let olderId = try #require(rig.persisted(videoId: older)?.id)
        #expect(await rig.manager.pendingRetryIds == [olderId])
    }

    /// Finding 6: `pause()` writes `paused` before the engine hop returns, so a queued final
    /// `.finished` can land on a paused row — the completed bytes must be kept, not deleted.
    @Test func aFinishedEventForAPausedRowCompletesItInsteadOfDeletingTheFile() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let id = await save(rig)
        await rig.manager.handle(.progress(id: id, bytesWritten: 500, totalBytes: 1_000))
        await rig.manager.pause(id)
        #expect(rig.persisted(id: id)?.status == OfflineStatus.paused.rawValue)

        try Data(repeating: 7, count: 1_000).write(to: rig.directory.appending(path: "\(id).tmp"))
        await rig.manager.handle(.finished(id: id))

        let row = try #require(rig.persisted(id: id))
        #expect(row.status == OfflineStatus.completed.rawValue)
        #expect(row.localPath == "\(id).m4a")
        #expect(row.resumeData == nil)
        let file = OfflineStorage.fileURL(relativePath: "\(id).m4a", base: rig.base)
        #expect(FileManager.default.fileExists(atPath: file.path()))
    }

    // MARK: - Engine kill switch (finding 3: cancel/pause must hold at a chunk boundary,
    // when no task is live because the delegate is between chunks)

    /// URLProtocol that fails every load immediately — drives a started walk into the
    /// no-live-task state while its walk bookkeeping still exists.
    nonisolated final class FailingURLProtocol: URLProtocol {
        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
        override func startLoading() {
            client?.urlProtocol(self, didFailWithError: URLError(.notConnectedToInternet))
        }
        override func stopLoading() {}
    }

    private func makeStubbedEngine() -> (engine: ProgressiveEngine, directory: URL) {
        let base = FileManager.default.temporaryDirectory
            .appending(path: "OfflineEngineKillSwitch-\(UUID().uuidString)", directoryHint: .isDirectory)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [FailingURLProtocol.self]
        let directory = OfflineStorage.directoryURL(base: base)
        return (ProgressiveEngine(directory: directory, configuration: configuration), directory)
    }

    /// A cancel issued at a chunk boundary (`task(id)` nil) must stop the walk: the delegate
    /// consults the kill switch before touching the finished chunk, so a straggler completion
    /// writes nothing and issues nothing.
    @Test func engineCancelIsAuthoritativeAtAChunkBoundary() async throws {
        let (engine, directory) = makeStubbedEngine()
        defer { try? FileManager.default.removeItem(at: directory.deletingLastPathComponent()) }
        let id = "boundary-cancel"
        await engine.cancel(id: id)   // boundary: no live task to find

        // The straggler chunk completion the real session would deliver next.
        var request = URLRequest(url: URL(string: "https://example.invalid/file")!)
        request.setValue("bytes=0-10485759", forHTTPHeaderField: "Range")
        request.setValue("UA", forHTTPHeaderField: "User-Agent")
        let side = URLSession(configuration: .ephemeral)
        let task = side.downloadTask(with: request)   // never resumed; carries the request shape
        task.taskDescription = id
        let location = FileManager.default.temporaryDirectory.appending(path: "chunk-\(UUID().uuidString)")
        try Data("chunk".utf8).write(to: location)
        defer { try? FileManager.default.removeItem(at: location) }

        engine.urlSession(side, downloadTask: task, didFinishDownloadingTo: location)

        #expect(!FileManager.default.fileExists(atPath: directory.appending(path: "\(id).tmp").path()),
                "a cancelled walk must not append the straggler chunk")
        #expect(FileManager.default.fileExists(atPath: location.path()),
                "the delegate must bail before consuming the chunk")
    }

    /// `pause` must hand back a resume token even when no task is live (a chunk boundary, or a
    /// pause racing a transient failure) — the walk state, not the live task, carries it.
    @Test func enginePauseReturnsTheWalkTokenWhenNoTaskIsLive() async throws {
        let (engine, directory) = makeStubbedEngine()
        defer { try? FileManager.default.removeItem(at: directory.deletingLastPathComponent()) }
        let id = "boundary-pause"
        let url = URL(string: "https://example.invalid/media?itag=140")!
        await engine.start(id: id, url: url, userAgent: "UA", allowsCellular: true)
        for _ in 0..<2000 {   // the stubbed failure retires the task; wait for the no-live window
            if await engine.liveIds().isEmpty { break }
            try await Task.sleep(for: .milliseconds(1))
        }
        #expect(await engine.liveIds().isEmpty)

        let token = try #require(await engine.pause(id: id))
        struct Token: Decodable { var url: URL; var userAgent: String }
        let decoded = try JSONDecoder().decode(Token.self, from: token)
        #expect(decoded.url == url)
        #expect(decoded.userAgent == "UA")
    }

    /// Serves a 206 partial chunk (with a Content-Range naming more to come) so a side-session
    /// task carries the real response shape a mid-walk chunk has.
    nonisolated final class PartialChunkURLProtocol: URLProtocol {
        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
        override func startLoading() {
            let response = HTTPURLResponse(url: request.url!, statusCode: 206, httpVersion: nil,
                                           headerFields: ["Content-Range": "bytes 0-4/1000000"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data("chunk".utf8))
            client?.urlProtocolDidFinishLoading(self)
        }
        override func stopLoading() {}
    }

    /// Review F4: only `start`/`resume` registered `walks[id]` — a relaunch-re-attached walk
    /// enters through the delegate's `issueChunk` and never did, so a boundary `pause` returned
    /// a nil token and the later resume restarted from zero via `engine.start` (deleting the
    /// `.tmp`). `issueChunk` must register the token itself.
    @Test func aReattachedWalkRegistersItsTokenSoABoundaryPauseCanResume() async throws {
        let (engine, directory) = makeStubbedEngine()
        defer { try? FileManager.default.removeItem(at: directory.deletingLastPathComponent()) }
        let id = "boundary-reattach"
        let url = URL(string: "https://example.invalid/media?itag=140")!

        // The live task from the PREVIOUS launch — `start` was never called this session. Run it
        // on a side session so it carries a real 206 + Content-Range when the delegate sees it.
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [PartialChunkURLProtocol.self]
        let side = URLSession(configuration: configuration)
        var request = URLRequest(url: url)
        request.setValue("bytes=0-10485759", forHTTPHeaderField: "Range")
        request.setValue("UA", forHTTPHeaderField: "User-Agent")
        let task = side.downloadTask(with: request) { _, _, _ in }
        task.taskDescription = id
        task.resume()
        for _ in 0..<2000 {
            if task.state == .completed { break }
            try await Task.sleep(for: .milliseconds(1))
        }
        #expect(task.state == .completed)

        // The delegate appends the chunk and crosses the boundary (issues the next chunk, which
        // the engine's failing protocol immediately retires — the no-live-task window).
        let location = FileManager.default.temporaryDirectory.appending(path: "chunk-\(UUID().uuidString)")
        try Data("chunk".utf8).write(to: location)
        engine.urlSession(side, downloadTask: task, didFinishDownloadingTo: location)
        for _ in 0..<2000 {
            if await engine.liveIds().isEmpty { break }
            try await Task.sleep(for: .milliseconds(1))
        }

        let token = try #require(await engine.pause(id: id), "boundary pause on a reattached walk lost its token")
        struct Token: Decodable { var url: URL; var userAgent: String }
        let decoded = try JSONDecoder().decode(Token.self, from: token)
        #expect(decoded.url == url)
        #expect(decoded.userAgent == "UA")

        // And the resume continues from the `.tmp` (no `start`, which would have deleted it).
        await engine.resume(id: id, resumeData: token, allowsCellular: true)
        let tmp = directory.appending(path: "\(id).tmp")
        #expect((try? Data(contentsOf: tmp))?.count == 5)
    }

    // MARK: - Chunked engine arithmetic (googlevideo throttles single long GETs on adaptive
    // formats to ~playback rate — measured 31 KB/s plain vs 11.4 MB/s for a 10 MB Range, 2026-09-01)

    @Test func theEngineWalksTheFileInTenMegabyteRanges() {
        #expect(ProgressiveEngine.rangeHeader(offset: 0) == "bytes=0-10485759")
        #expect(ProgressiveEngine.rangeHeader(offset: 10_485_760) == "bytes=10485760-20971519")
    }

    @Test func theEngineReadsTheTotalAndOffsetFromContentRange() {
        #expect(ProgressiveEngine.total(fromContentRange: "bytes 0-10485759/136852236") == 136_852_236)
        #expect(ProgressiveEngine.total(fromContentRange: "bytes */136852236") == 136_852_236)
        #expect(ProgressiveEngine.total(fromContentRange: nil) == nil)
        #expect(ProgressiveEngine.offset(fromRangeHeader: "bytes=10485760-20971519") == 10_485_760)
        #expect(ProgressiveEngine.offset(fromRangeHeader: nil) == 0)
    }

    // MARK: - Storage compliance

    @Test func theFirstEngineStartCreatesTheOfflineDirectoryExcludedFromBackup() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        #expect(!FileManager.default.fileExists(atPath: rig.directory.path()))
        _ = await save(rig)
        let values = try rig.directory.resourceValues(forKeys: [.isExcludedFromBackupKey, .isDirectoryKey])
        #expect(values.isDirectory == true)
        #expect(values.isExcludedFromBackup == true)
    }

    // MARK: - Sweep (Task 7 calls it; the gate answer is injected)

    @Test func sweepDeletesExpiredAndGoneAndKeepsUnreachable() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        func completed(_ videoId: String, daysAgo: Double) throws -> (id: String, file: URL) {
            let item = OfflineItem(videoId: videoId, title: videoId, channelName: nil, thumbnailUrl: nil,
                                   qualityLabel: "360p", audioOnly: true, status: OfflineStatus.completed.rawValue,
                                   completedAt: rig.flags.now.addingTimeInterval(-daysAgo * 86_400))
            item.localPath = "\(item.id).m4a"
            try FileManager.default.createDirectory(at: rig.directory, withIntermediateDirectories: true)
            let file = OfflineStorage.fileURL(relativePath: item.localPath!, base: rig.base)
            try Data("x".utf8).write(to: file)
            try rig.store.insert(item)
            return (item.id, file)
        }
        let expired = try completed("vidExpired0", daysAgo: 40)
        let fresh = try completed("vidFresh000", daysAgo: 1)
        rig.flags.gate = .unreachable
        await rig.manager.sweep()
        #expect(rig.persisted(id: expired.id) == nil)
        #expect(!FileManager.default.fileExists(atPath: expired.file.path()))
        #expect(rig.persisted(id: fresh.id) != nil)

        rig.flags.gate = .gone
        await rig.manager.sweep()
        #expect(rig.persisted(id: fresh.id) == nil)
        #expect(!FileManager.default.fileExists(atPath: fresh.file.path()))
    }

    // MARK: - Live smoke (plan Task 4 step 2; §15 "simulator download" evidence)

    private struct AlwaysAvailable: AvailabilityGate {
        func verify(videoId: String, sourceChannelId: String?) async throws -> Bool { true }
    }

    private nonisolated final class MemoryKV: KeyValueStore, @unchecked Sendable {
        private let lock = NSLock()
        private var storage: [String: Data] = [:]
        func get(_ key: String) -> Data? { lock.withLock { storage[key] } }
        func set(_ key: String, _ value: Data) { lock.withLock { storage[key] = value } }
    }

    /// Saves the approved lecture through the REAL resolver + limiter + `ProgressiveEngine` over a
    /// non-background session (the simulator test host cannot set up a background session —
    /// `OfflineEngineSupport` records the 4097 XPC error). Audio-only when the ladder lands on
    /// `.hls` (itag 140), else progressive 360p (itag 18).
    @Test(.enabled(if: ProcessInfo.processInfo.environment["OFFLINE_LIVE"] == "1"))
    func liveSaveLandsAPlayableFileAndTheRowReadsCompleted() async throws {
        let innerTube = InnerTube(
            keyValueStore: MemoryKV(), availabilityGate: AlwaysAvailable(),
            locale: InnerTubeLocale(hl: "en", gl: "US"),
            remoteConfigURL: URL(string: "https://example.invalid/remote-config.json")!)
        let probe = try await innerTube.resolver.resolve(Self.lectureVideoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
        let audioOnly: Bool
        switch probe.stream {
        case .hls(_, _, let audioOnlyURL, _): audioOnly = audioOnlyURL != nil
        case .progressive, .embed: audioOnly = false
        }
        print("[offline-live] probe client=\(probe.client) stream=\(probe.stream) → audioOnly=\(audioOnly)")

        let container = AppContainer.makeModelContainer(inMemory: true)
        let store = OfflineStore(modelContainer: container)
        let base = FileManager.default.temporaryDirectory
            .appending(path: "OfflineManagerLive-\(UUID().uuidString)", directoryHint: .isDirectory)
        defer { try? FileManager.default.removeItem(at: base) }
        let engine = ProgressiveEngine(directory: OfflineStorage.directoryURL(base: base), configuration: .default)
        let manager = OfflineManager(
            store: store, engine: engine, resolver: LiveStreamResolver(resolver: innerTube.resolver),
            limiterCheck: { await innerTube.rateLimiter.check($0, kind: .prefetch, now: innerTube.clock.now) },
            wifiOnly: { false }, isOnCellular: { false }, baseDirectory: base,
            gate: { _ in .unreachable }, now: { Date() })

        await manager.save(videoId: Self.lectureVideoId, quality: "360p", audioOnly: audioOnly, metadata: Self.metadata)
        let id = try #require(store.item(videoId: Self.lectureVideoId)?.id)
        let deadline = ContinuousClock.now + .seconds(50)
        while ContinuousClock.now < deadline {
            let status = store.item(id: id)?.status
            if status == OfflineStatus.completed.rawValue || status == OfflineStatus.failed.rawValue { break }
            try await Task.sleep(for: .milliseconds(200))
        }
        var d = FetchDescriptor<OfflineItem>(predicate: #Predicate { $0.id == id })
        d.fetchLimit = 1
        let row = try #require(try ModelContext(container).fetch(d).first)
        print("[offline-live] row status=\(row.status) error=\(row.errorCode ?? "nil") bytes=\(row.bytesWritten) localPath=\(row.localPath ?? "nil")")
        #expect(row.status == OfflineStatus.completed.rawValue)
        let file = OfflineStorage.fileURL(relativePath: try #require(row.localPath), base: base)
        let size = (try? FileManager.default.attributesOfItem(atPath: file.path())[.size] as? Int64) ?? 0
        #expect(size > 0)
        let asset = AVURLAsset(url: file)
        let (isPlayable, duration) = try await asset.load(.isPlayable, .duration)
        print("[offline-live] file=\(file.lastPathComponent) size=\(size) isPlayable=\(isPlayable) duration=\(duration.seconds)s")
        #expect(isPlayable)
        #expect(duration.seconds > 0)
    }
}
