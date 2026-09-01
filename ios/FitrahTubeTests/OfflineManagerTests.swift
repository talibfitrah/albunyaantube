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
            limiterCheck: { _ in flags.decision },
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

    /// Outcome B gap, pinned so Task 5's picker can't pretend otherwise: the VISIONOS rung returns
    /// `.hls` with NO muxed itag 18 (`player-ok-hls.json` carries only `adaptiveFormats`), and
    /// the HLS engine is dormant — a video save on that rung has no source.
    @Test func aVideoSaveOnTheHLSRungFailsNoStreamWithoutAnEngineCall() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let id = await save(rig, audioOnly: false)
        #expect(rig.engine.starts.isEmpty)
        #expect(rig.persisted(id: id)?.status == OfflineStatus.failed.rawValue)
        #expect(rig.persisted(id: id)?.errorCode == "NO_STREAM")
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
