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
        /// What `pause` — and, since R5-8, `start` — hands back (canned resume data).
        var pauseResumeData: Data? = Data("RD".utf8)
        /// Ids whose `pause` suspends until released (the gate-flap interleaving); `pauseEntered`
        /// records arrival so a test can wait for the close leg to be inside its own `await`.
        var pauseHeld: Set<String> {
            get { lock.withLock { _pauseHeld } }
            set { lock.withLock { _pauseHeld = newValue } }
        }
        var pauseEntered: Set<String> { lock.withLock { _pauseEntered } }
        private var _pauseHeld: Set<String> = []
        private var _pauseEntered: Set<String> = []
        /// Same shape for `cancel`: the R4-4 interleaving needs a cancel parked mid-flight, past
        /// its own row read but before it drops the manager's claim.
        var cancelHeld: Set<String> {
            get { lock.withLock { _cancelHeld } }
            set { lock.withLock { _cancelHeld = newValue } }
        }
        var cancelEntered: Set<String> { lock.withLock { _cancelEntered } }
        private var _cancelHeld: Set<String> = []
        private var _cancelEntered: Set<String> = []
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

        func start(id: String, url: URL, userAgent: String, allowsCellular: Bool) async -> Data? {
            lock.withLock { _starts.append(Start(id: id, url: url, userAgent: userAgent, allowsCellular: allowsCellular)); _ = _live.insert(id) }
            return pauseResumeData
        }
        func resume(id: String, resumeData: Data, allowsCellular: Bool) async {
            lock.withLock { _resumes.append((id, resumeData)); _ = _live.insert(id) }
        }
        func pause(id: String) async -> Data? {
            lock.withLock { _pauses.append(id); _live.remove(id); _ = _pauseEntered.insert(id) }
            await Self.hold(while: { self.lock.withLock { self._pauseHeld.contains(id) } }, what: "pause of \(id)")
            return pauseResumeData
        }
        func cancel(id: String) async {
            lock.withLock { _cancels.append(id); _live.remove(id); _ = _cancelEntered.insert(id) }
            await Self.hold(while: { self.lock.withLock { self._cancelHeld.contains(id) } }, what: "cancel of \(id)")
        }
        func liveIds() async -> Set<String> { live }

        /// Cubic R5-10: these holds were `while held { Task.sleep(1ms) }` with no deadline, so a
        /// `#require` failing before the release line left an unstructured task polling at 1 ms for
        /// the whole process. Capped like `RecordingResolver.hold`, and it reports rather than
        /// spinning forever.
        static func hold(while held: @Sendable () -> Bool, what: String,
                         sourceLocation: SourceLocation = #_sourceLocation) async {
            for _ in 0..<2000 {
                if !held() { return }
                do { try await Task.sleep(for: .milliseconds(1)) } catch { return }
            }
            #expect(!held(), "held \(what) was never released", sourceLocation: sourceLocation)
        }
    }

    /// Drains an engine's event stream so a test can assert what it emitted — and, just as
    /// importantly, what it did NOT (the `.cancelled` and no-total findings are both "one event
    /// too many/few").
    nonisolated final class EventCollector: @unchecked Sendable {
        private let lock = NSLock()
        private var _events: [OfflineDownloadEvent] = []
        var events: [OfflineDownloadEvent] { lock.withLock { _events } }

        func consume(_ stream: AsyncStream<OfflineDownloadEvent>) -> Task<Void, Never> {
            Task { for await event in stream { self.lock.withLock { self._events.append(event) } } }
        }
    }

    /// Cellular-gate inputs the closures read live. `nonisolated` (the `FakeOfflineEngine` shape):
    /// the manager's closures call into it from the actor, not from the main actor.
    nonisolated final class Flags: @unchecked Sendable {
        nonisolated(unsafe) var wifiOnly = false
        nonisolated(unsafe) var cellular = false
        /// `.allowed`, not `.unreachable` (adversarial r1 P0-2): the old default was harmless only
        /// because `begin` never asked, and the suite grew around that — a fixture that answers
        /// "no answer" while asserting a start is the violation written down as a baseline. Every
        /// test that wants a refusal now says so.
        nonisolated(unsafe) var gate: GateAnswer = .allowed
        /// Per-video overrides of `gate` (the sweep's whole-library belt needs one row to answer
        /// something other than `.gone`).
        nonisolated(unsafe) var gates: [String: GateAnswer] = [:]
        nonisolated(unsafe) var decision: Decision = .allowed
        /// Per-video overrides of `decision` (the starvation test blocks one id, allows the rest).
        nonisolated(unsafe) var decisions: [String: Decision] = [:]
        /// Ids whose limiter check suspends until removed (same 1 ms-poll gate as
        /// `RecordingResolver.hold`); `limiterEntered` records arrival so a test can wait for it.
        ///
        /// Cubic R5-5: both are inserted into from the `@Sendable` limiter closure ON THE ACTOR
        /// while the test task reads/reassigns them — `Set` is not thread-safe, so the plain
        /// `nonisolated(unsafe)` stored properties were a genuine (TSan-detectable) data race.
        /// Behind a lock, the `FakeOfflineEngine.pauseHeld` shape.
        private let lock = NSLock()
        private var _limiterHeld: Set<String> = []
        private var _limiterEntered: Set<String> = []
        var limiterHeld: Set<String> {
            get { lock.withLock { _limiterHeld } }
            set { lock.withLock { _limiterHeld = newValue } }
        }
        var limiterEntered: Set<String> { lock.withLock { _limiterEntered } }
        func enterLimiter(_ id: String) { lock.withLock { _ = _limiterEntered.insert(id) } }
        func limiterIsHeld(_ id: String) -> Bool { lock.withLock { _limiterHeld.contains(id) } }
        nonisolated(unsafe) var now = Date()
        /// Fires once inside the manager's `now()` read — the one deterministic seam into the
        /// actor's own execution between a completion's file move and its row write (G-P1b).
        nonisolated(unsafe) var onNow: (@Sendable () -> Void)?
        /// Blocks the MAIN ACTOR inside the `wifiOnly` read so a cancel already parked in
        /// `engine.cancel` can finish while `begin` sits in that hop (R4-4); `wifiOnlyEntered` is
        /// how the test knows `begin` has reached it.
        ///
        /// Cubic R5-6: the block used to be a `while … Thread.sleep(0.001)` wall-clock spin, which
        /// silently ABANDONED the block after 2 s (the engine then started and the test failed for
        /// the wrong reason). `wifiOnly` is a synchronous `@MainActor` closure returning `Bool`, so
        /// it cannot suspend — holding the main actor IS the mechanism this scenario needs. A
        /// semaphore is the honest shape of that hold: it wakes the instant the releaser signals,
        /// and the 2 s is a FAILURE GUARD (`wifiOnlyTimedOut`), never the release mechanism.
        nonisolated(unsafe) var wifiOnlyBlocked = false
        nonisolated(unsafe) var wifiOnlyEntered = false
        let wifiOnlyRelease = DispatchSemaphore(value: 0)
        nonisolated(unsafe) var wifiOnlyTimedOut = false
        /// The remote kill-switch as the manager reads it; a test replaces it to hold a refusal
        /// inside `begin`'s own `await`.
        nonisolated(unsafe) var downloadsEnabled: @Sendable () async -> Bool = { true }
        /// Review Minor 4: the same race class R5-5 fixed, two fields away — the closure runs its
        /// read-modify-write on the ACTOR while the test's `waitUntil` reads it. Behind the lock.
        private var _killSwitchCalls = 0
        var killSwitchCalls: Int { lock.withLock { _killSwitchCalls } }
        /// Increments and returns the new count in one critical section, so no caller can read a
        /// value it then acts on out of date.
        func countKillSwitchCall() -> Int { lock.withLock { _killSwitchCalls += 1; return _killSwitchCalls } }
        nonisolated(unsafe) var killSwitchHeld = false
        /// Every videoId the manager asked the per-video gate about, in order — how a test proves a
        /// start path CONSULTED the gate rather than merely surviving it. Behind the same lock as
        /// the rest: the closure appends on the actor while the test reads.
        private var _gateCalls: [String] = []
        var gateCalls: [String] { lock.withLock { _gateCalls } }
        func recordGateCall(_ videoId: String) { lock.withLock { _gateCalls.append(videoId) } }
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

    /// `relaunching:` reuses a previous rig's STORE, model container and files behind a fresh
    /// manager, engine and resolver — the app dying and coming back up, which is the only way to
    /// observe what a walk left persisted (R5-8).
    private func makeRig(_ outcome: RecordingResolver.Outcome = .hls, relaunching previous: Rig? = nil) -> Rig {
        let container = previous?.container ?? AppContainer.makeModelContainer(inMemory: true)
        let store = previous?.store ?? OfflineStore(modelContainer: container)
        let engine = FakeOfflineEngine()
        let resolver = RecordingResolver(outcome)
        let flags = Flags()
        let base = previous?.base ?? FileManager.default.temporaryDirectory
            .appending(path: "OfflineManagerTests-\(UUID().uuidString)", directoryHint: .isDirectory)
        let manager = OfflineManager(
            store: store, engine: engine, resolver: resolver,
            limiterCheck: { id in
                flags.enterLimiter(id)
                await FakeOfflineEngine.hold(while: { flags.limiterIsHeld(id) }, what: "limiter check of \(id)")
                return flags.decisions[id] ?? flags.decision
            },
            wifiOnly: {
                flags.wifiOnlyEntered = true
                if flags.wifiOnlyBlocked {
                    flags.wifiOnlyBlocked = false   // one-shot: only the read the test armed blocks
                    if flags.wifiOnlyRelease.wait(timeout: .now() + 2) == .timedOut { flags.wifiOnlyTimedOut = true }
                }
                return flags.wifiOnly
            },
            isOnCellular: { flags.cellular },
            baseDirectory: base,
            gate: { flags.recordGateCall($0); return flags.gates[$0] ?? flags.gate },
            now: {
                if let onNow = flags.onNow { flags.onNow = nil; onNow() }
                return flags.now
            },
            downloadsEnabled: { await flags.downloadsEnabled() })
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
        await rig.manager.handle(.failed(id: id, failure: .http(status: 403, resumeData: nil)))
        #expect(rig.resolver.calls.map(\.forceRefresh) == [false, true])
        #expect(rig.engine.starts.count == 2)
        #expect(rig.persisted(id: id)?.status == OfflineStatus.running.rawValue)

        await rig.manager.handle(.failed(id: id, failure: .http(status: 403, resumeData: nil)))
        #expect(rig.engine.starts.count == 2)
        #expect(rig.persisted(id: id)?.status == OfflineStatus.failed.rawValue)
        #expect(rig.persisted(id: id)?.errorCode == "HTTP_403")
    }

    /// A transient 5xx/416 is a NETWORK failure with the `.tmp` still on disk, so the row must keep
    /// the resume token: `retry` → `begin` resumes only when the row carries one, and `engine.start`
    /// deletes the partial — a 503 used to throw away the whole download.
    @Test func aTransientHttpFailureKeepsTheResumeTokenSoTheRetryContinues() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        rig.flags.gate = .allowed   // retry re-consults the per-video gate (gstack P0)
        let id = await save(rig)
        try Data(repeating: 7, count: 500).write(to: rig.directory.appending(path: "\(id).tmp"))
        await rig.manager.handle(.failed(id: id, failure: .http(status: 503, resumeData: Data("RD".utf8))))
        let row = try #require(rig.persisted(id: id))
        #expect(row.status == OfflineStatus.failed.rawValue)
        #expect(row.errorCode == "NETWORK")
        #expect(row.resumeData == Data("RD".utf8), "without the token the retry restarts the walk from zero")
        #expect(row.bytesWritten == 500, "this failure kept its partial, so the footer still counts it")

        await rig.manager.retry(id)
        #expect(rig.engine.resumes.map(\.id) == [id])
        #expect(rig.engine.starts.count == 1, "the retry continues the walk, it does not re-start it")
    }

    /// The 416 leftover from `86affdc8` (= re-review RR-m3): 416 is the ONE http failure whose
    /// partial the engine has already deleted (its one-time clean restart, `ProgressiveEngine`
    /// R6-3) — so unlike every other transient status the row's `bytesWritten` no longer describes
    /// anything on disk, and the Saved row's bar read near-full (and the storage footer counted
    /// bytes that were gone) until the restarted walk's first tick. Fixed at the `fail()` funnel,
    /// which now reads the `.tmp`'s own size; the 503 test above pins the other direction.
    @Test func a416ZeroesTheByteCountBecauseTheEngineDroppedThePartial() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let id = await save(rig)
        await rig.manager.handle(.progress(id: id, bytesWritten: 900, totalBytes: 1_000))
        await rig.manager.handle(.failed(id: id, failure: .http(status: 416, resumeData: nil)))
        let row = try #require(rig.persisted(id: id))
        #expect(row.status == OfflineStatus.failed.rawValue)
        #expect(row.errorCode == "NETWORK")
        #expect(row.resumeData == nil)
        #expect(row.bytesWritten == 0, "the partial is gone, so the bar must not still read 900")
    }

    @Test func a429FailsHTTP429() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let id = await save(rig)
        await rig.manager.handle(.failed(id: id, failure: .http(status: 429, resumeData: nil)))
        #expect(rig.persisted(id: id)?.errorCode == "HTTP_429")
    }

    /// Cubic r3 Part A review (Minor): a 429 threw its partial away the way a 5xx used to. The
    /// `.tmp` is untouched by a throttle response, so the token rides the failure like every other
    /// `.http` arm and the retry continues the walk instead of re-downloading the file.
    @Test func a429KeepsTheResumeTokenSoTheRetryContinuesTheWalk() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        rig.flags.gate = .allowed
        let id = await save(rig)
        await rig.manager.handle(.failed(id: id, failure: .http(status: 429, resumeData: Data("RD".utf8))))
        let row = try #require(rig.persisted(id: id))
        #expect(row.errorCode == "HTTP_429")
        #expect(row.resumeData == Data("RD".utf8), "without the token the retry restarts the walk from zero")

        await rig.manager.retry(id)
        #expect(rig.engine.resumes.map(\.id) == [id])
        #expect(rig.engine.starts.count == 1, "the retry continues the walk, it does not re-start it")
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
        rig.flags.gate = .allowed   // retry re-consults the per-video gate (gstack P0)
        let id = await save(rig)
        #expect(rig.persisted(id: id)?.status == OfflineStatus.failed.rawValue)
        rig.resolver.outcome = .hls
        await rig.manager.retry(id)
        #expect(rig.persisted(id: id)?.status == OfflineStatus.running.rawValue)
        #expect(rig.persisted(id: id)?.errorCode == nil)
        #expect(rig.engine.starts.count == 1)
    }

    // MARK: - The per-video gate on retry (gstack P0 — fail-CLOSED, the Save affordance's table)

    /// `retry` started a full from-zero save with no `offlineAllowed` consult, and nothing else
    /// revalidates a failed/cancelled row — so an admin flipping the flag off (fork C's same-day
    /// remedy) had no path that stopped the re-download. A refusing gate takes the row and its
    /// partial with it: a failed row has nothing worth keeping, and a revoked gate removes copies.
    @Test(arguments: [GateAnswer.notAllowed, GateAnswer.gone])
    func aRetryWhoseGateRefusesDeletesTheRowAndItsPartial(gate: GateAnswer) async throws {
        let rig = makeRig(.embed); defer { rig.cleanUp() }
        let id = await save(rig)
        #expect(rig.persisted(id: id)?.status == OfflineStatus.failed.rawValue)
        try FileManager.default.createDirectory(at: rig.directory, withIntermediateDirectories: true)
        let tmp = rig.directory.appending(path: "\(id).tmp")
        try Data("partial".utf8).write(to: tmp)
        rig.resolver.outcome = .hls
        rig.flags.gate = gate

        await rig.manager.retry(id)

        #expect(rig.persisted(id: id) == nil, "a revoked gate removes the row, not just the start")
        #expect(!FileManager.default.fileExists(atPath: tmp.path()))
        #expect(rig.engine.starts.isEmpty)
        #expect(rig.resolver.calls.count == 1, "the refused retry never resolved")
    }

    /// `unreachable` is no answer, not a "no": the retry is refused (fail-closed for SAVING) and
    /// the row stays failed with whatever is on disk — never deleted on a transport error.
    ///
    /// Cubic R5-3: it used to return with NO state change at all, which is exactly the situation a
    /// row failed with "Network error. Check your connection" already leaves the user in — the
    /// Retry button read as broken. The status and the partial are untouched; the error code
    /// becomes the network one, which is what the row's caption renders.
    @Test func aRetryWithAnUnreachableGateIsRefusedAndTheRowStaysFailed() async throws {
        let rig = makeRig(.embed); defer { rig.cleanUp() }
        let id = await save(rig)
        try FileManager.default.createDirectory(at: rig.directory, withIntermediateDirectories: true)
        let tmp = rig.directory.appending(path: "\(id).tmp")
        try Data("partial".utf8).write(to: tmp)
        rig.resolver.outcome = .hls
        rig.flags.gate = .unreachable

        await rig.manager.retry(id)

        let row = try #require(rig.persisted(id: id))
        #expect(row.status == OfflineStatus.failed.rawValue, "the refusal changes no status")
        #expect(row.errorCode == "NETWORK", "a refused retry must leave the reason on the row")
        #expect(FileManager.default.fileExists(atPath: tmp.path()))
        #expect(rig.engine.starts.isEmpty)
        #expect(rig.resolver.calls.count == 1)
    }

    // MARK: - The per-video gate at `begin` (adversarial r1 P0-2 — every start, one funnel)

    /// `begin` consulted the kill-switch and the cellular gate but never `offlineAllowed`, so every
    /// start that is not a Retry — a scheduler pick, a user Resume, `reattach()`'s re-queue, the
    /// cellular re-open, the kill-switch kick — began writing bytes on authorization that could be
    /// hours stale. `.unreachable` is no answer, so the row waits: `.queued` ("Waiting"), no error
    /// code, a timer. This is also the fakes half of P1-2 — the live rigs used to assert a save
    /// COMPLETING under exactly this gate answer.
    @Test func aStartWhoseGateIsUnreachableParksTheRowInsteadOfWalking() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        rig.flags.gate = .unreachable

        let id = await save(rig)

        let row = try #require(rig.persisted(id: id))
        #expect(row.status == OfflineStatus.queued.rawValue, "no answer is wait-don't-skip, never a start")
        #expect(row.errorCode == nil, "waiting is not an error")
        #expect(await rig.manager.pendingRetryIds == [id], "and it must actually be on a timer")
        #expect(rig.engine.starts.isEmpty)
        #expect(rig.resolver.calls.isEmpty, "the refusal lands before the resolve, not after it")
        #expect(rig.flags.gateCalls == [Self.lectureVideoId])
        #expect(SavedRowText.captionKey(status: .queued, errorCode: row.errorCode) == "offline_status_queued")
    }

    /// Fork C's same-day remedy on the start path, the table `retry` already used: a refusing gate
    /// takes the row and its partial with it — the sweep would only get to it on the next launch,
    /// and `reattach()` would have re-started the walk before that.
    @Test(arguments: [GateAnswer.notAllowed, GateAnswer.gone])
    func aStartWhoseGateRefusesDeletesTheRowAndItsPartial(gate: GateAnswer) async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let item = OfflineItem(videoId: "vidRevoked0", title: "Lecture", channelName: nil, thumbnailUrl: nil,
                               qualityLabel: "360p", audioOnly: true, status: OfflineStatus.queued.rawValue)
        try rig.store.insert(item)
        try FileManager.default.createDirectory(at: rig.directory, withIntermediateDirectories: true)
        let tmp = rig.directory.appending(path: "\(item.id).tmp")
        try Data("partial".utf8).write(to: tmp)
        rig.flags.gate = gate

        await rig.manager.schedule()

        #expect(rig.persisted(id: item.id) == nil, "a revoked gate removes the row, not just the start")
        #expect(!FileManager.default.fileExists(atPath: tmp.path()))
        #expect(rig.engine.starts.isEmpty)
        #expect(rig.resolver.calls.isEmpty)
    }

    /// The cellular gate re-opening is a START. It used to walk straight into `resolveAndStart` on
    /// whatever authorization the save was granted under, however long ago the Wi-Fi-only park was.
    @Test func theCellularGateReOpeningConsultsThePerVideoGate() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        rig.flags.wifiOnly = true
        rig.flags.cellular = true
        let id = await save(rig)
        #expect(rig.flags.gateCalls.isEmpty,
                "a row the CELLULAR gate refuses writes no bytes, so it needs no authorization and costs no GET")
        #expect(rig.persisted(id: id)?.status == OfflineStatus.queued.rawValue)

        rig.flags.gate = .notAllowed   // revoked while the phone sat on cellular
        rig.flags.cellular = false
        await rig.manager.gateDidChange()

        #expect(rig.flags.gateCalls == [Self.lectureVideoId], "the re-open is a start, and a start asks")
        #expect(rig.engine.starts.isEmpty)
        #expect(rig.persisted(id: id) == nil)
    }

    /// The kill-switch kick (`schedule()` after the remote-config refresh flips it back on) is the
    /// same shape: rows queued through an off-window are started by it, and the switch coming back
    /// says nothing about whether those videos are still saveable.
    @Test func theKillSwitchKickConsultsThePerVideoGate() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        rig.flags.downloadsEnabled = { false }
        let item = OfflineItem(videoId: "vidQueued00", title: "Lecture", channelName: nil, thumbnailUrl: nil,
                               qualityLabel: "360p", audioOnly: true, status: OfflineStatus.queued.rawValue)
        try rig.store.insert(item)

        await rig.manager.schedule()
        #expect(rig.flags.gateCalls.isEmpty, "the kill-switch is consulted first and refuses silently")

        rig.flags.downloadsEnabled = { true }
        rig.flags.gate = .notAllowed
        await rig.manager.schedule()

        #expect(rig.flags.gateCalls == ["vidQueued00"])
        #expect(rig.engine.starts.isEmpty)
        #expect(rig.persisted(id: item.id) == nil)
    }

    /// `reattach()`'s orphan re-queue is the staleest start of all — the authorization is from
    /// whenever the previous launch saved the row — and it takes `begin`'s RESUME leg, which walks
    /// bytes without resolving anything. It must ask too.
    @Test func theReattachRequeueConsultsThePerVideoGate() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let item = OfflineItem(videoId: "vidOrphan0D", title: "t", channelName: nil, thumbnailUrl: nil,
                               qualityLabel: "360p", audioOnly: true, status: OfflineStatus.running.rawValue,
                               resumeData: Data("RD".utf8))
        try rig.store.insert(item)
        rig.engine.live = []
        rig.flags.gate = .notAllowed

        await rig.manager.reattach()

        #expect(rig.flags.gateCalls == ["vidOrphan0D"])
        #expect(rig.engine.resumes.isEmpty, "the resume leg walks bytes like any other start")
        #expect(rig.engine.starts.isEmpty)
        #expect(rig.persisted(id: item.id) == nil)
    }

    /// R5-3, the other refusal: a user Resume the cellular gate refuses left the row EXACTLY as it
    /// was — Paused, no error, nothing started — so the Resume button read as broken too. The
    /// status is untouched (a paused row is still paused, waiting for the user) and the row gets
    /// the network code; there is no Wi-Fi-only paused wording in the catalog to prefer.
    @Test func aResumeTheCellularGateRefusesLeavesTheReasonOnTheRow() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let id = await save(rig)
        await rig.manager.pause(id)
        rig.flags.wifiOnly = true
        rig.flags.cellular = true

        await rig.manager.resume(id)

        let row = try #require(rig.persisted(id: id))
        #expect(row.status == OfflineStatus.paused.rawValue)
        #expect(row.errorCode == "NETWORK", "a refused Resume must leave a trace on the row")
        #expect(rig.engine.resumes.isEmpty)
    }

    /// Cubic R6-1 (the R5-3 regression): `note()` wrote an error code and only `retry()` ever
    /// cleared one, while `captionKey` prefers a code over ANY status — so a row that resumed
    /// successfully after a refused Resume rendered "Network error. Check your connection" while it
    /// downloaded, and permanently after it completed. Work actually starting is what makes the old
    /// reason stale.
    @Test func aRowThatResumesAfterARefusedResumeLosesTheStaleErrorCode() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let id = await save(rig)
        await rig.manager.pause(id)
        rig.flags.wifiOnly = true
        rig.flags.cellular = true
        await rig.manager.resume(id)                       // refused: the row now carries NETWORK
        #expect(rig.persisted(id: id)?.errorCode == "NETWORK")

        rig.flags.cellular = false                         // the user joins Wi-Fi and taps Resume
        await rig.manager.resume(id)

        let running = try #require(rig.persisted(id: id))
        #expect(running.status == OfflineStatus.running.rawValue)
        #expect(running.errorCode == nil, "a running row cannot still be captioned with why it once refused")
        #expect(SavedRowText.captionKey(status: .running, errorCode: running.errorCode) == "offline_status_saving")

        try Data(repeating: 7, count: 128).write(to: rig.directory.appending(path: "\(id).tmp"))
        await rig.manager.handle(.finished(id: id))

        let done = try #require(rig.persisted(id: id))
        #expect(done.status == OfflineStatus.completed.rawValue)
        #expect(done.errorCode == nil)
        #expect(SavedRowText.captionKey(status: .completed, errorCode: done.errorCode) == "offline_status_completed")
    }

    /// Cubic R6-2: a user Resume that lands on a limiter delay/block, a resolver cooldown or a
    /// bot-check parked the row on a timer — but `schedule()` picks `.queued` rows ONLY, so a row
    /// still reading `.paused` was dropped: no start, no retry, no error, exactly the broken-button
    /// shape R5-3 set out to remove. Wait-don't-skip, like a save: the row goes to "Waiting" and
    /// the timer picks it up.
    @Test(arguments: [Decision.delayed(.seconds(30), reason: "prefetch delayed"),
                      Decision.blocked(reason: "prefetch blocked", retryAfter: .seconds(300))])
    func aResumeTheLimiterParksBecomesQueuedRatherThanBeingDropped(decision: Decision) async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let id = await save(rig)
        await rig.manager.pause(id)
        // A paused row WITH resume data takes `begin`'s resume leg, which never consults the
        // limiter; the row that reaches `resolveAndStart` is the one whose pause landed during the
        // resolve, i.e. one with no token yet.
        rig.store.item(id: id)?.resumeData = nil
        try rig.store.save()
        rig.flags.decision = decision

        await rig.manager.resume(id)

        let row = try #require(rig.persisted(id: id))
        #expect(row.status == OfflineStatus.queued.rawValue, "a parked Resume must read Waiting, not Paused")
        #expect(row.errorCode == nil, "waiting is not an error")
        #expect(await rig.manager.pendingRetryIds == [id], "and it must actually be on a timer")
        #expect(SavedRowText.captionKey(status: .queued, errorCode: row.errorCode) == "offline_status_queued")
    }

    /// Re-review RR-I1: R6-1 (work STARTING clears the code) and R6-2 (a parked Resume becomes
    /// `.queued`) are individually green and jointly broken — a park is not a start, so the code
    /// noted by the Wi-Fi-only refusal rode the promotion, and `captionKey` lets any non-nil code
    /// outrank any status. The row sat "Waiting" while rendering "Network error. Check your
    /// connection", for as long as the limiter or resolver cooldown kept re-parking it.
    @Test func aParkedResumeDropsTheReasonTheEarlierRefusalNoted() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let id = await save(rig)
        await rig.manager.pause(id)
        // No token: the row whose pause landed during the resolve, which is the one that reaches
        // `resolveAndStart` (and therefore the limiter) on Resume.
        rig.store.item(id: id)?.resumeData = nil
        try rig.store.save()
        rig.flags.wifiOnly = true
        rig.flags.cellular = true
        await rig.manager.resume(id)                       // refused: the row now carries NETWORK
        #expect(rig.persisted(id: id)?.errorCode == "NETWORK")

        rig.flags.cellular = false                         // the user joins Wi-Fi and taps Resume
        rig.flags.decision = .delayed(.seconds(30), reason: "prefetch delayed")
        await rig.manager.resume(id)

        let row = try #require(rig.persisted(id: id))
        #expect(row.status == OfflineStatus.queued.rawValue)
        #expect(row.errorCode == nil, "parking is not an error and the old reason is stale")
        #expect(SavedRowText.captionKey(status: .queued, errorCode: row.errorCode) == "offline_status_queued")
        #expect(await rig.manager.pendingRetryIds == [id])
    }

    /// Batch A review SR-m2: the same RR-I1 drop through the OTHER mouth. `resume()` admits
    /// `.queued` rows as well as `.paused` ones, and `scheduleRetry`'s write guard was
    /// paused-only — so a queued row that had been noted by a refusal kept the stale code through
    /// its park. The guard admits both; this is the queued half of it.
    @Test func aQueuedRowParkedAfterARefusedResumeAlsoDropsTheStaleReason() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let item = OfflineItem(videoId: "vidQueuedRR", title: "Lecture", channelName: nil, thumbnailUrl: nil,
                               qualityLabel: "360p", audioOnly: true, status: OfflineStatus.queued.rawValue)
        try rig.store.insert(item)
        rig.flags.wifiOnly = true
        rig.flags.cellular = true
        await rig.manager.resume(item.id)                  // refused: the row now carries NETWORK
        #expect(rig.persisted(id: item.id)?.errorCode == "NETWORK")

        rig.flags.cellular = false                         // the user joins Wi-Fi and taps Resume
        rig.flags.decision = .delayed(.seconds(30), reason: "prefetch delayed")
        await rig.manager.resume(item.id)

        let row = try #require(rig.persisted(id: item.id))
        #expect(row.status == OfflineStatus.queued.rawValue)
        #expect(row.errorCode == nil, "a queued row's park drops the stale reason too")
        #expect(await rig.manager.pendingRetryIds == [item.id])
        #expect(SavedRowText.captionKey(status: .queued, errorCode: row.errorCode) == "offline_status_queued")
    }

    /// The same drop through the resolver's own cooldown arm (CF-D-9's direction).
    @Test func aResumeIntoAResolverCooldownBecomesQueuedRatherThanBeingDropped() async throws {
        let rig = makeRig(.failure(.cooldown(until: Date().addingTimeInterval(1800))))
        defer { rig.cleanUp() }
        // A row the user paused mid-resolve: `.paused`, no token, so Resume takes the resolve leg.
        let item = OfflineItem(videoId: Self.lectureVideoId, title: "Lecture", channelName: nil,
                               thumbnailUrl: nil, qualityLabel: "360p", audioOnly: true,
                               status: OfflineStatus.paused.rawValue)
        try rig.store.insert(item)
        let id = item.id

        await rig.manager.resume(id)

        let row = try #require(rig.persisted(id: id))
        #expect(row.status == OfflineStatus.queued.rawValue)
        #expect(row.errorCode == nil)
        #expect(await rig.manager.pendingRetryIds == [id])
    }

    /// The preserve clause (fork D): the kill-switch refusal NEVER announces itself, so the same
    /// Resume under an off switch leaves the row completely untouched.
    @Test func aResumeTheKillSwitchRefusesStaysSilent() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let id = await save(rig)
        await rig.manager.pause(id)
        rig.flags.downloadsEnabled = { false }

        await rig.manager.resume(id)

        let row = try #require(rig.persisted(id: id))
        #expect(row.status == OfflineStatus.paused.rawValue)
        #expect(row.errorCode == nil, "the kill-switch governs saving silently")
        #expect(rig.engine.resumes.isEmpty)
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

    /// Cubic R4-8 moved the 2 Hz throttle from the `store.save()` to the whole main-actor hop (the
    /// hop's other half is a `store.item(id:)` predicate fetch, run per engine packet). The fetch
    /// count itself has no seam to assert against — `OfflineStore` is a concrete final class and a
    /// protocol over one implementation is not worth the abstraction — so what this pins is the
    /// cadence the hop now rides: one row write per window, none inside it, unchanged from before.
    @Test func progressPersistsAtMostOncePerThrottleWindow() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let id = await save(rig)
        await rig.manager.handle(.progress(id: id, bytesWritten: 100, totalBytes: 1_000))
        #expect(rig.persisted(id: id)?.bytesWritten == 100)

        await rig.manager.handle(.progress(id: id, bytesWritten: 200, totalBytes: 1_000))
        await rig.manager.pause(id)   // its own save() would flush a tick that got through
        #expect(rig.persisted(id: id)?.bytesWritten == 100, "a tick inside the window persists nothing")

        await rig.manager.resume(id)
        rig.flags.now = rig.flags.now.addingTimeInterval(1)
        await rig.manager.handle(.progress(id: id, bytesWritten: 300, totalBytes: 1_000))
        #expect(rig.persisted(id: id)?.bytesWritten == 300, "the next window persists exactly as before")
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

    /// gstack P1: `<id>.m4a` is named only by the completion's own row write, so a Delete landing
    /// in the suspension between the file move and that write left the finished file on disk
    /// referenced by nothing — and nothing in the app ever enumerates the offline directory, so it
    /// was unreclaimable for the life of the install (and `usedBytes`, row-summed, under-reported
    /// it). `now()` is read on the actor between the move and the write: the one deterministic
    /// seam into that window.
    @Test func aDeleteInsideTheCompletionWindowLeavesNoOrphanedFile() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let id = await save(rig)
        try Data(repeating: 7, count: 2_048).write(to: rig.directory.appending(path: "\(id).tmp"))
        let container = rig.container
        rig.flags.onNow = {
            var descriptor = FetchDescriptor<OfflineItem>(predicate: #Predicate { $0.id == id })
            descriptor.fetchLimit = 1
            let context = ModelContext(container)
            guard let item = try? context.fetch(descriptor).first else { return }
            context.delete(item)
            try? context.save()
        }

        await rig.manager.handle(.finished(id: id))

        #expect(rig.persisted(id: id) == nil)
        #expect(!FileManager.default.fileExists(
            atPath: OfflineStorage.fileURL(relativePath: "\(id).m4a", base: rig.base).path()),
                "a moved file whose row is gone is unreachable — nothing enumerates the directory")
    }

    /// The same orphan from the other side: `removeFiles` worked off a row SNAPSHOT, so a delete
    /// whose read predates the completion's `localPath` write removed only `<id>.tmp` and left the
    /// finished file. Every name the id can own goes, snapshot or not.
    @Test func deleteRemovesTheSavedFileEvenWhenTheRowNeverRecordedIt() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let id = await save(rig)
        let file = OfflineStorage.fileURL(relativePath: "\(id).m4a", base: rig.base)
        try Data("x".utf8).write(to: file)   // the completed bytes, before `localPath` is written

        await rig.manager.delete(id)

        #expect(!FileManager.default.fileExists(atPath: file.path()))
        #expect(rig.persisted(id: id) == nil)
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
        // Both orphans queue (cubic R2-2): `.paused` is the USER's state, and neither of these
        // rows was paused by a user — they were running when the app died. They STAY queued
        // here: the re-bound live task holds the serial slot (CF-D-5), so nothing resolves
        // until it finishes.
        #expect(rig.persisted(id: orphanWithData)?.status == OfflineStatus.queued.rawValue)
        #expect(rig.persisted(id: orphanNoData)?.status == OfflineStatus.queued.rawValue)
        #expect(rig.resolver.calls.isEmpty)
        #expect(rig.engine.starts.isEmpty)
    }

    /// Cubic R2-2, second leg: a relaunched row carrying stale resume data was parked as
    /// `.paused` — the state only a USER pause should produce — so the save sat at "Paused"
    /// until the user tapped Resume. With no live task holding the serial slot it continues
    /// from its `.tmp` on its own.
    @Test func reattachContinuesAnOrphanedRowThatCarriesResumeData() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let item = OfflineItem(videoId: "vidOrphan0C", title: "t", channelName: nil, thumbnailUrl: nil,
                               qualityLabel: "360p", audioOnly: true, status: OfflineStatus.running.rawValue,
                               resumeData: Data("RD".utf8))
        try rig.store.insert(item)
        rig.engine.live = []

        await rig.manager.reattach()

        #expect(rig.engine.resumes.map(\.id) == [item.id])
        #expect(rig.engine.starts.isEmpty, "a continued walk resumes from the `.tmp`, never restarts")
        #expect(rig.persisted(id: item.id)?.status == OfflineStatus.running.rawValue)
    }

    /// Cubic R5-8: `ProgressiveEngine.start` continues a partial only when the incoming token IS
    /// the walk already registered for the id, and `walks` is empty after a relaunch — so
    /// `reattach()`'s orphan path re-resolved and `engine.start` deleted a 90 %-complete `.tmp`.
    /// The walk's `{url, userAgent}` token is persisted into the row's existing `resumeData` column
    /// while it RUNS (not only when it pauses), so the orphan takes `begin`'s resume leg instead.
    @Test func aRelaunchedOrphanContinuesItsPartialInsteadOfRestartingFromZero() async throws {
        let first = makeRig(.hls); defer { first.cleanUp() }
        let id = await save(first)
        #expect(first.persisted(id: id)?.status == OfflineStatus.running.rawValue)
        #expect(first.persisted(id: id)?.resumeData == Data("RD".utf8),
                "a running walk's token must be persisted, not only written on pause")

        // The relaunch: a fresh manager, engine and resolver over the SAME store and files, the
        // row still RUNNING and no live task to re-bind (the app died mid-download).
        let relaunched = makeRig(.hls, relaunching: first)
        await relaunched.manager.reattach()

        #expect(relaunched.engine.resumes.map(\.id) == [id], "the orphan continues from its `.tmp`")
        #expect(relaunched.engine.starts.isEmpty, "a restart deletes a nearly complete partial")
        #expect(relaunched.resolver.calls.isEmpty, "and it costs no re-resolve")
    }

    /// Cubic R2-3: on a background-events relaunch the session's pending delegate callbacks and
    /// `liveIds()`'s `getAllTasks` are both async on the delegate queue with no ordering
    /// guarantee. When the final chunk's finish lands first, `reattach()` sees no live task and
    /// queues the row — and the queued `.finished` then hit the status guard, deleting a
    /// COMPLETE file for a full re-download.
    @Test func aFinishedEventForAQueuedRowCompletesItInsteadOfDeletingTheFile() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let item = OfflineItem(videoId: "vidRelaunch", title: "t", channelName: nil, thumbnailUrl: nil,
                               qualityLabel: "360p", audioOnly: true, status: OfflineStatus.queued.rawValue)
        try rig.store.insert(item)
        try FileManager.default.createDirectory(at: rig.directory, withIntermediateDirectories: true)
        try Data(repeating: 7, count: 2_048).write(to: rig.directory.appending(path: "\(item.id).tmp"))

        await rig.manager.handle(.finished(id: item.id))

        let row = try #require(rig.persisted(id: item.id))
        #expect(row.status == OfflineStatus.completed.rawValue)
        #expect(row.localPath == "\(item.id).m4a")
        #expect(row.bytesWritten == 2_048)
        let file = OfflineStorage.fileURL(relativePath: "\(item.id).m4a", base: rig.base)
        #expect(FileManager.default.fileExists(atPath: file.path()))
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
        await rig.manager.handle(.failed(id: item.id, failure: .http(status: 403, resumeData: nil)))
        #expect(rig.resolver.calls.map(\.forceRefresh) == [true])
        #expect(rig.engine.starts.map(\.id) == [item.id])
        #expect(rig.persisted(id: item.id)?.status == OfflineStatus.running.rawValue)

        // And once the row completes, an unrelated save is not blocked by a leaked claim.
        try Data("x".utf8).write(to: rig.directory.appending(path: "\(item.id).tmp"))
        await rig.manager.handle(.finished(id: item.id))
        let other = await save(rig, videoId: "vidOther000")
        #expect(rig.engine.starts.map(\.id) == [item.id, other])
    }

    /// Two launch callers run `reattach()` (the AppDelegate background-events hook and RootView's
    /// `.task`). A `.running` row the first one already claimed has no live engine task while its
    /// resolve is in flight, so the second re-queued it — and the engine start that followed then
    /// fed `.progress` events to a `.queued` row, which the status guard drops: the row reads
    /// "Waiting" with a frozen bar until `.finished`. A row in `active` belongs to a live attempt.
    @Test func aSecondReattachLeavesAnAlreadyClaimedRowAlone() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let item = OfflineItem(videoId: "vidRelaunch", title: "t", channelName: nil, thumbnailUrl: nil,
                               qualityLabel: "360p", audioOnly: true, status: OfflineStatus.running.rawValue)
        try rig.store.insert(item)
        rig.engine.live = [item.id]
        await rig.manager.reattach()   // claims the live row

        rig.engine.live = []           // the no-live-task window of an in-flight re-resolve
        await rig.manager.reattach()

        #expect(rig.persisted(id: item.id)?.status == OfflineStatus.running.rawValue,
                "a claimed row must not be re-queued behind its own in-flight attempt")
        #expect(rig.engine.starts.isEmpty)
    }

    /// Both refusal paths in `begin` dropped the `active` claim unconditionally after an await. A
    /// cancel plus retry that re-claims the row INSIDE that await had its claim removed by the
    /// older attempt's refusal continuation, and its own `stillCurrent` check then discarded the
    /// continuation — leaving the row queued with nothing running.
    @Test func aRefusedStartNeverStripsANewerAttemptsClaim() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let flags = rig.flags
        flags.gate = .allowed   // retry re-consults the per-video gate (gstack P0)
        // The FIRST consult holds until the test releases it and then refuses; every later consult
        // (the retry's own guard, the new attempt's) allows.
        flags.killSwitchHeld = true
        flags.downloadsEnabled = { @Sendable in
            guard flags.countKillSwitchCall() == 1 else { return true }
            await FakeOfflineEngine.hold(while: { flags.killSwitchHeld }, what: "kill-switch consult")
            return false
        }

        let saveTask = Task { await rig.manager.save(videoId: Self.lectureVideoId, quality: "360p",
                                                     audioOnly: true, metadata: Self.metadata) }
        await waitUntil { flags.killSwitchCalls == 1 }
        let id = try #require(rig.persisted(videoId: Self.lectureVideoId)?.id)

        await rig.manager.cancel(id)                    // drops the first attempt's claim
        rig.resolver.hold(Self.lectureVideoId)          // park the new attempt inside its resolve
        let retryTask = Task { await rig.manager.retry(id) }
        await rig.resolver.waitUntilCalled(count: 1)

        flags.killSwitchHeld = false                    // the refusal continuation runs now
        await saveTask.value
        rig.resolver.release(id: Self.lectureVideoId)
        await retryTask.value

        #expect(rig.engine.starts.map(\.id) == [id],
                "the retry's claim must survive the older attempt's refusal")
        #expect(rig.persisted(id: id)?.status == OfflineStatus.running.rawValue)
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
        // R5-3's bound: a row the SCHEDULER picked is not marked — "Waiting" is already honest,
        // and only a user action that appears to do nothing needs a reason.
        #expect(rig.persisted(id: id)?.errorCode == nil)

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

    /// Cubic R2-2: a gate pause is not a user pause. `gateDidChange` parked running rows as
    /// `.paused` but on re-open only ran `schedule()`, which picks `.queued` rows — so a brief
    /// Wi-Fi drop under Wi-Fi-only left the save at "Paused" until the user tapped Resume.
    @Test func theGateResumesExactlyTheRowsItPaused() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        rig.flags.cellular = true
        let id = await save(rig)
        #expect(rig.persisted(id: id)?.status == OfflineStatus.running.rawValue)

        rig.flags.wifiOnly = true
        await rig.manager.gateDidChange()
        #expect(rig.persisted(id: id)?.status == OfflineStatus.paused.rawValue)

        rig.flags.cellular = false
        await rig.manager.gateDidChange()
        #expect(rig.engine.resumes.map(\.id) == [id])
        #expect(rig.persisted(id: id)?.status == OfflineStatus.running.rawValue)
    }

    /// Part A review, Important 1: the R2-2 ruling rests on the invariant "`gatePausedIds` holds
    /// only rows the GATE paused", and nothing enforced it — the set is written by the refuse leg
    /// and drained by the allow leg, so any id that leaves the paused state by another route (a
    /// user Resume getting to the re-opened gate first, or a `.finished` landing inside the
    /// refuse leg's own `await`) stayed behind as a live stale entry. The next genuine USER pause
    /// then looked like the gate's, and the following gate change resumed it. A user pause
    /// outranks the bookkeeping.
    @Test func aUserPauseOutranksStaleGateBookkeeping() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        rig.flags.cellular = true
        let id = await save(rig)
        #expect(rig.persisted(id: id)?.status == OfflineStatus.running.rawValue)

        // The gate parks it — the id goes into the gate-paused set.
        rig.flags.wifiOnly = true
        await rig.manager.gateDidChange()
        #expect(rig.persisted(id: id)?.status == OfflineStatus.paused.rawValue)

        // The gate re-opens, but the user's own Resume gets there before `gateDidChange` does, so
        // the set is never drained and its entry is now stale.
        rig.flags.cellular = false
        await rig.manager.resume(id)
        #expect(rig.persisted(id: id)?.status == OfflineStatus.running.rawValue)

        // A genuine USER pause, on a row the gate has no claim to any more.
        await rig.manager.pause(id)
        #expect(rig.persisted(id: id)?.status == OfflineStatus.paused.rawValue)
        let resumesBeforeTheGate = rig.engine.resumes.count

        await rig.manager.gateDidChange()   // the gate allows, and must resume nothing
        #expect(rig.engine.resumes.count == resumesBeforeTheGate,
                "a user pause must wait for the user's Resume, not for the gate")
        #expect(rig.persisted(id: id)?.status == OfflineStatus.paused.rawValue)
    }

    /// A gate flap that re-opens INSIDE the close leg's own `await pause(...)`: the re-open
    /// snapshots the gate-paused set before this leg has inserted into it, so it resumes nothing,
    /// and the late insert then left the row Paused until the next gate change or a manual Resume.
    /// Whichever leg finishes last has to reconcile the row with the gate as it now reads.
    @Test func aGateReOpeningInsideTheCloseLegNeverStrandsTheRow() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        rig.flags.cellular = true
        let id = await save(rig)
        #expect(rig.persisted(id: id)?.status == OfflineStatus.running.rawValue)

        rig.engine.pauseHeld = [id]
        rig.flags.wifiOnly = true
        let close = Task { await rig.manager.gateDidChange() }
        await waitUntil { rig.engine.pauseEntered.contains(id) }

        rig.flags.cellular = false          // the gate re-opens while the close leg is suspended
        await rig.manager.gateDidChange()   // its parked snapshot is empty: it resumes nothing
        rig.engine.pauseHeld = []
        await close.value

        #expect(rig.engine.resumes.map(\.id) == [id])
        #expect(rig.persisted(id: id)?.status == OfflineStatus.running.rawValue,
                "a row parked by a gate that has since re-opened must not stay Paused")
    }

    /// The preserve clause of the same finding: a row the USER paused still waits for the
    /// user's Resume — a gate change must never restart it.
    @Test func aUserPausedRowIsNeverResumedByAGateChange() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let id = await save(rig)
        await rig.manager.pause(id)
        #expect(rig.persisted(id: id)?.status == OfflineStatus.paused.rawValue)

        await rig.manager.gateDidChange()   // the gate allows and always did
        #expect(rig.engine.resumes.isEmpty)
        #expect(rig.persisted(id: id)?.status == OfflineStatus.paused.rawValue)
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

    /// Cubic R4-4: `!(await wifiOnly())` was evaluated inside the engine call's argument list — a
    /// main-actor hop AFTER the final `stillCurrent` guard. A cancel completing in that hop found
    /// no guard behind it and the engine started a full download for a dead row. The read is
    /// hoisted above the guard, so the guard is the last thing before the engine.
    ///
    /// The interleaving is scripted, not raced: the cancel parks inside `engine.cancel` (past its
    /// own row read, before it drops the claim), the `wifiOnly` read blocks the MAIN ACTOR, and a
    /// detached task — the only place off the main actor here — releases the cancel and unblocks
    /// the hop once the cancel has passed `removeFiles` (the `.tmp` disappearing is that signal).
    @Test func aCancelCompletingInsideTheWifiOnlyHopStartsNothing() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let flags = rig.flags, engine = rig.engine
        defer { flags.wifiOnlyBlocked = false; flags.wifiOnlyRelease.signal() }
        rig.resolver.hold(Self.lectureVideoId)
        let saveTask = Task { await rig.manager.save(videoId: Self.lectureVideoId, quality: "360p",
                                                     audioOnly: true, metadata: Self.metadata) }
        await rig.resolver.waitUntilCalled(count: 1)
        let id = try #require(rig.persisted(videoId: Self.lectureVideoId)?.id)
        try FileManager.default.createDirectory(at: rig.directory, withIntermediateDirectories: true)
        let tmp = rig.directory.appending(path: "\(id).tmp")
        try Data("partial".utf8).write(to: tmp)

        engine.cancelHeld = [id]
        let cancelTask = Task { await rig.manager.cancel(id) }
        await waitUntil { engine.cancelEntered.contains(id) }

        // `begin` already read the cellular gate before the resolve; only the read AFTER it counts.
        flags.wifiOnlyEntered = false
        flags.wifiOnlyBlocked = true
        let releaser = Task.detached {
            // Only once `begin` is INSIDE the hop — releasing earlier lets the post-resolve guard
            // catch the cancel, which is the window that was already closed.
            for _ in 0..<2000 {
                if flags.wifiOnlyEntered { break }
                try? await Task.sleep(for: .milliseconds(1))
            }
            engine.cancelHeld = []
            for _ in 0..<2000 {
                if !FileManager.default.fileExists(atPath: tmp.path()) { break }
                try? await Task.sleep(for: .milliseconds(1))
            }
            flags.wifiOnlyRelease.signal()
        }
        rig.resolver.release(id: Self.lectureVideoId)
        await saveTask.value
        await cancelTask.value
        await releaser.value

        #expect(!flags.wifiOnlyTimedOut, "the hold was abandoned on its guard, so the interleaving never happened")
        #expect(rig.engine.starts.isEmpty, "the guard must be the last thing before the engine")
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

    /// Serves a five-byte 206 chunk AT THE REQUEST'S OWN `Range` offset (with a Content-Range
    /// naming more to come) so a side-session task carries the real response shape a mid-walk
    /// chunk has — at offset 0 for the first chunk, mid-stream for a stale one.
    nonisolated final class PartialChunkURLProtocol: URLProtocol {
        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
        override func startLoading() {
            let offset = ProgressiveEngine.offset(fromRangeHeader: request.value(forHTTPHeaderField: "Range"))
            let response = HTTPURLResponse(url: request.url!, statusCode: 206, httpVersion: nil,
                                           headerFields: ["Content-Range": "bytes \(offset)-\(offset + 4)/1000000"])!
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

    /// A chunk request that never answers — the task stays live so a test can pause it — and
    /// records the engine's OWN `URLSessionTask` (keyed by `taskDescription`) so the test can hand
    /// the delegate that exact task's late completion.
    nonisolated final class HangingURLProtocol: URLProtocol {
        private static let lock = NSLock()
        nonisolated(unsafe) private static var tasks: [String: URLSessionTask] = [:]

        /// Keyed by the whole `taskDescription`, matched by row id — the description carries the
        /// walk's generation (`<id>#<n>`), and a test that wants the id's CURRENT task must not
        /// have to know which generation it is on.
        static func task(_ id: String) -> URLSessionTask? {
            lock.withLock { tasks.first { $0.key == id || $0.key.hasPrefix("\(id)#") }?.value }
        }

        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
        override func startLoading() {
            guard let task, let id = task.taskDescription else { return }
            Self.lock.withLock { Self.tasks[id] = task }
        }
        override func stopLoading() {}
    }

    /// Stop identity is the TASK, not the row: `resume` clears the row from the engine's stop set
    /// before the background daemon delivers the PAUSED task's `.cancelled` completion, so that
    /// late completion read as a system cancel and failed the freshly resumed row — while the
    /// chunk `resume` had just issued kept downloading until `.finished` deleted it as garbage.
    /// (A Wi-Fi/cellular flap driving the gate closed then open back-to-back reaches this.)
    @Test func aLateCancelFromThePausedTaskNeverFailsTheResumedWalk() async throws {
        let base = FileManager.default.temporaryDirectory
            .appending(path: "OfflineEngineStopIdentity-\(UUID().uuidString)", directoryHint: .isDirectory)
        defer { try? FileManager.default.removeItem(at: base) }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [HangingURLProtocol.self]
        let engine = ProgressiveEngine(directory: OfflineStorage.directoryURL(base: base), configuration: configuration)
        let collector = EventCollector()
        let consumer = collector.consume(engine.events)
        defer { consumer.cancel() }

        let id = "stop-identity-\(UUID().uuidString)"
        let url = URL(string: "https://example.invalid/media?itag=140")!
        await engine.start(id: id, url: url, userAgent: "UA", allowsCellular: true)
        await waitUntil { HangingURLProtocol.task(id) != nil }
        let pausedTask = try #require(HangingURLProtocol.task(id))

        let token = try #require(await engine.pause(id: id))
        await engine.resume(id: id, resumeData: token, allowsCellular: true)
        // The completion the daemon delivers for the task `pause` cancelled, arriving after the
        // resume already cleared the row.
        let side = URLSession(configuration: .ephemeral)
        engine.urlSession(side, task: pausedTask, didCompleteWithError: URLError(.cancelled))

        // FIFO sentinel: the stream preserves order, so once this failure arrives every earlier
        // yield has been collected too.
        var request = URLRequest(url: url)
        request.setValue("UA", forHTTPHeaderField: "User-Agent")
        let sentinel = side.downloadTask(with: request)   // never resumed; carries the request shape
        sentinel.taskDescription = "sentinel"
        engine.urlSession(side, task: sentinel, didCompleteWithError: URLError(.timedOut))
        await waitUntil { collector.events.contains { event in
            if case .failed(let failedId, _) = event { return failedId == "sentinel" }
            return false
        } }

        #expect(!collector.events.contains { event in
            if case .failed(let failedId, _) = event { return failedId == id }
            return false
        }, "the resumed walk must not be failed by its own paused task's late cancellation")
    }

    // MARK: - Walk generations (Cubic R4-3 / CF-D-10 / CF-D-18)

    /// A walk restarted after a cancel used to inherit its predecessor's callbacks: `start` cleared
    /// the row from the stop set, so the older task's straggler chunk sailed through the kill-switch
    /// guard, appended into the NEW walk's `.tmp` and could report it finished. Every
    /// start/resume/pause/cancel bumps the id's generation, every chunk carries the generation it
    /// was issued under, and a callback from an older one is dropped whole — no append, no next
    /// chunk, no `.finished`, no `.failed`.
    @Test func aStragglerChunkFromASupersededWalkIsDroppedWhole() async throws {
        let base = FileManager.default.temporaryDirectory
            .appending(path: "OfflineEngineGenerations-\(UUID().uuidString)", directoryHint: .isDirectory)
        defer { try? FileManager.default.removeItem(at: base) }
        let directory = OfflineStorage.directoryURL(base: base)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [HangingURLProtocol.self]
        let engine = ProgressiveEngine(directory: directory, configuration: configuration)
        let collector = EventCollector()
        let consumer = collector.consume(engine.events)
        defer { consumer.cancel() }

        let id = "superseded-\(UUID().uuidString)"
        let url = URL(string: "https://example.invalid/media?itag=140")!
        await engine.start(id: id, url: url, userAgent: "UA", allowsCellular: true)
        await waitUntil { HangingURLProtocol.task(id) != nil }
        let stale = try #require(HangingURLProtocol.task(id) as? URLSessionDownloadTask)
        await engine.cancel(id: id)
        await engine.start(id: id, url: url, userAgent: "UA", allowsCellular: true)   // a fresh walk

        let location = FileManager.default.temporaryDirectory.appending(path: "chunk-\(UUID().uuidString)")
        try Data("chunk".utf8).write(to: location)
        defer { try? FileManager.default.removeItem(at: location) }
        let side = URLSession(configuration: .ephemeral)
        engine.urlSession(side, downloadTask: stale, didFinishDownloadingTo: location)

        // FIFO sentinel: the stream preserves order, so once this failure arrives anything the
        // straggler yielded has been collected too.
        var request = URLRequest(url: url)
        request.setValue("UA", forHTTPHeaderField: "User-Agent")
        let sentinel = side.downloadTask(with: request)   // never resumed; carries the request shape
        sentinel.taskDescription = "sentinel"
        engine.urlSession(side, task: sentinel, didCompleteWithError: URLError(.timedOut))
        await waitUntil { collector.events.contains { event in
            if case .failed(let failedId, _) = event { return failedId == "sentinel" }
            return false
        } }

        #expect(!collector.events.contains { event in
            switch event {
            case .progress(let eventId, _, _), .finished(let eventId), .failed(let eventId, _): return eventId == id
            }
        }, "a superseded walk's chunk must yield nothing at all")
        #expect(!FileManager.default.fileExists(atPath: directory.appending(path: "\(id).tmp").path()),
                "the straggler's bytes must never land in the new walk's partial")
        #expect(FileManager.default.fileExists(atPath: location.path()),
                "the delegate must bail before consuming the chunk")
    }

    /// The other half of the same token: on a background-events relaunch the session re-delivers a
    /// previous launch's chunk before any `start`/`resume` runs, so the engine holds NO generation
    /// for that id. That callback must be ADOPTED, not dropped — the walk's `.tmp` is the only
    /// thing that survived the launch and it continues from there to completion.
    @Test func aRelaunchChunkWithNoGenerationContinuesTheWalkToCompletion() async throws {
        let base = FileManager.default.temporaryDirectory
            .appending(path: "OfflineEngineRelaunch-\(UUID().uuidString)", directoryHint: .isDirectory)
        defer { try? FileManager.default.removeItem(at: base) }
        let directory = OfflineStorage.directoryURL(base: base)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [SplitFileURLProtocol.self]
        let engine = ProgressiveEngine(directory: directory, configuration: configuration)
        let collector = EventCollector()
        let consumer = collector.consume(engine.events)
        defer { consumer.cancel() }

        let id = "relaunch-\(UUID().uuidString)"
        let url = URL(string: "https://example.invalid/media?itag=140")!
        // The previous launch's chunk 0, run on a side session so it carries a real 206 +
        // Content-Range; its description carries no generation, exactly like a pre-relaunch task.
        let side = URLSession(configuration: configuration)
        var request = URLRequest(url: url)
        request.setValue(ProgressiveEngine.rangeHeader(offset: 0), forHTTPHeaderField: "Range")
        request.setValue("UA", forHTTPHeaderField: "User-Agent")
        let task = side.downloadTask(with: request) { _, _, _ in }
        task.taskDescription = id
        task.resume()
        await waitUntil { task.state == .completed }

        let location = FileManager.default.temporaryDirectory.appending(path: "chunk-\(UUID().uuidString)")
        try Data("chunk".utf8).write(to: location)
        defer { try? FileManager.default.removeItem(at: location) }
        engine.urlSession(side, downloadTask: task, didFinishDownloadingTo: location)

        await waitUntil { collector.events.contains { if case .finished = $0 { return true }; return false } }
        #expect((try? Data(contentsOf: directory.appending(path: "\(id).tmp")))?.count == 10,
                "the adopted walk must continue the SAME partial, not restart it")
    }

    /// R4-3's wasted full download: `start` deleted the partial unconditionally, so the walk a
    /// relaunch or a retry restarts re-downloads bytes that are already on disk. With the
    /// generation token silencing the older walk, a start whose token IS the walk already
    /// registered for the id continues its partial — and a start on a DIFFERENT stream still
    /// begins clean, because two streams' bytes must never be spliced.
    @Test func aRestartWithTheSameTokenContinuesThePartialAndADifferentOneDoesNot() async throws {
        let (engine, directory) = makeStubbedEngine()
        defer { try? FileManager.default.removeItem(at: directory.deletingLastPathComponent()) }
        let id = "restart-partial"
        let url = URL(string: "https://example.invalid/media?itag=140")!
        await engine.start(id: id, url: url, userAgent: "UA", allowsCellular: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let tmp = directory.appending(path: "\(id).tmp")
        try Data(repeating: 7, count: 64).write(to: tmp)

        await engine.start(id: id, url: url, userAgent: "UA", allowsCellular: true)
        #expect((try? Data(contentsOf: tmp))?.count == 64, "the same walk's bytes are its resume point")

        await engine.start(id: id, url: URL(string: "https://example.invalid/other")!,
                           userAgent: "UA", allowsCellular: true)
        #expect(!FileManager.default.fileExists(atPath: tmp.path()),
                "a different stream must never splice onto the old bytes")
    }

    /// Serves a five-byte 206 chunk at the request's own offset out of a TEN-byte file, so a
    /// two-chunk walk actually completes.
    nonisolated final class SplitFileURLProtocol: URLProtocol {
        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
        override func startLoading() {
            let offset = ProgressiveEngine.offset(fromRangeHeader: request.value(forHTTPHeaderField: "Range"))
            let response = HTTPURLResponse(url: request.url!, statusCode: 206, httpVersion: nil,
                                           headerFields: ["Content-Range": "bytes \(offset)-\(offset + 4)/10"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data("chunk".utf8))
            client?.urlProtocolDidFinishLoading(self)
        }
        override func stopLoading() {}
    }

    /// The missing-partial branch took ANY chunk as the file start: the offset guard lived only in
    /// the append branch, so a stale relaunch task landing after the `.tmp` was removed produced a
    /// file beginning mid-stream that the walk then appended to and marked complete.
    @Test func aMidStreamChunkWithNoPartialOnDiskFailsInsteadOfBecomingTheFileStart() async throws {
        let (engine, directory) = makeStubbedEngine()
        defer { try? FileManager.default.removeItem(at: directory.deletingLastPathComponent()) }
        let collector = EventCollector()
        let consumer = collector.consume(engine.events)
        defer { consumer.cancel() }
        let id = "stale-offset"

        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [PartialChunkURLProtocol.self]
        let side = URLSession(configuration: configuration)
        var request = URLRequest(url: URL(string: "https://example.invalid/media?itag=140")!)
        request.setValue(ProgressiveEngine.rangeHeader(offset: 10_485_760), forHTTPHeaderField: "Range")
        request.setValue("UA", forHTTPHeaderField: "User-Agent")
        let task = side.downloadTask(with: request) { _, _, _ in }
        task.taskDescription = id
        task.resume()
        for _ in 0..<2000 {
            if task.state == .completed { break }
            try await Task.sleep(for: .milliseconds(1))
        }
        #expect(task.state == .completed)

        let location = FileManager.default.temporaryDirectory.appending(path: "chunk-\(UUID().uuidString)")
        try Data("chunk".utf8).write(to: location)
        defer { try? FileManager.default.removeItem(at: location) }
        let tmp = directory.appending(path: "\(id).tmp")
        #expect(!FileManager.default.fileExists(atPath: tmp.path()), "the premise: the partial is gone")

        engine.urlSession(side, downloadTask: task, didFinishDownloadingTo: location)

        await waitUntil { collector.events.contains { if case .failed = $0 { return true }; return false } }
        #expect(!collector.events.contains { if case .progress = $0 { return true }; return false },
                "a chunk that starts mid-stream must never become the file's first bytes")
        #expect(!FileManager.default.fileExists(atPath: tmp.path()),
                "the mismatched chunk must not be left on disk as the partial")
    }

    /// A server error carries the resume token the same way a transport failure does — the `.tmp`
    /// is untouched, so the manager's retry continues the walk from it instead of restarting.
    @Test func aServerErrorChunkCarriesTheResumeToken() async throws {
        let (engine, directory) = makeStubbedEngine()
        defer { try? FileManager.default.removeItem(at: directory.deletingLastPathComponent()) }
        let collector = EventCollector()
        let consumer = collector.consume(engine.events)
        defer { consumer.cancel() }
        let id = "server-error"

        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ServerErrorURLProtocol.self]
        let side = URLSession(configuration: configuration)
        var request = URLRequest(url: URL(string: "https://example.invalid/media?itag=140")!)
        request.setValue(ProgressiveEngine.rangeHeader(offset: 0), forHTTPHeaderField: "Range")
        request.setValue("UA", forHTTPHeaderField: "User-Agent")
        let task = side.downloadTask(with: request) { _, _, _ in }
        task.taskDescription = id
        task.resume()
        for _ in 0..<2000 {
            if task.state == .completed { break }
            try await Task.sleep(for: .milliseconds(1))
        }
        let location = FileManager.default.temporaryDirectory.appending(path: "chunk-\(UUID().uuidString)")
        try Data("body".utf8).write(to: location)
        defer { try? FileManager.default.removeItem(at: location) }

        engine.urlSession(side, downloadTask: task, didFinishDownloadingTo: location)

        await waitUntil { collector.events.contains { if case .failed = $0 { return true }; return false } }
        let failure = try #require(collector.events.compactMap { event -> OfflineDownloadFailure? in
            if case .failed(_, let failure) = event { return failure } else { return nil }
        }.first)
        guard case .http(let status, let resumeData) = failure else {
            Issue.record("expected an http failure, got \(failure)")
            return
        }
        #expect(status == 503)
        #expect(resumeData != nil, "without the token the manager's retry restarts the walk from zero")
    }

    /// Serves a 416 with `Content-Range: bytes */<total>` — what googlevideo answers when the walk
    /// asks for an offset at or past the end of the file. The total rides in the URL's `total`
    /// query item rather than a static, so two tests can drive different totals in parallel.
    nonisolated final class RangeNotSatisfiableURLProtocol: URLProtocol {
        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
        override func startLoading() {
            let total = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?
                .queryItems?.first { $0.name == "total" }?.value ?? "0"
            let response = HTTPURLResponse(url: request.url!, statusCode: 416, httpVersion: nil,
                                           headerFields: ["Content-Range": "bytes */\(total)"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocolDidFinishLoading(self)
        }
        override func stopLoading() {}
    }

    /// The engine's resume-token wire format, mirroring the `Token: Decodable` the boundary-pause
    /// tests already use — the engine's own struct is private, and `start` cannot hand one back
    /// without first deleting the partial these tests are about.
    private struct WireToken: Encodable { var url: URL; var userAgent: String }

    /// Cubic R6-3: if the app dies between the engine's `.finished` and the manager's file move,
    /// the row is re-queued carrying a token for a `.tmp` that is ALREADY whole — and `resume`
    /// then asks for `bytes=<total>-`, which is a 416. Failing with the token made every Retry
    /// re-issue the same 416 forever; the only exit was Remove plus a full re-download. A partial
    /// that matches the total is simply finished.
    @Test func a416OnACompletePartialFinishesTheWalkInsteadOfLooping() async throws {
        let (engine, directory) = makeStubbed416Engine()
        defer { try? FileManager.default.removeItem(at: directory.deletingLastPathComponent()) }
        let collector = EventCollector()
        let consumer = collector.consume(engine.events)
        defer { consumer.cancel() }
        let id = "range-complete"
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let tmp = directory.appending(path: "\(id).tmp")
        try Data("chunk".utf8).write(to: tmp)   // 5 bytes: the whole file

        // `resume`, not `start`: this is the relaunch shape — the row came back carrying a token
        // for a partial that is already whole, and `resume` walks from the `.tmp`'s own size.
        let token = try JSONEncoder().encode(
            WireToken(url: URL(string: "https://example.invalid/media?itag=140&total=5")!, userAgent: "UA"))
        await engine.resume(id: id, resumeData: token, allowsCellular: true)

        await waitUntil { collector.events.contains { if case .finished = $0 { return true }; return false } }
        #expect(!collector.events.contains { if case .failed = $0 { return true }; return false },
                "a complete partial must never be reported as a failure the retry re-issues")
        #expect(FileManager.default.fileExists(atPath: tmp.path()),
                "the finished bytes stay for the manager to move")
    }

    /// The other arm: a 416 whose partial does NOT match the total (or that carries no total at
    /// all) restarts clean ONCE — the `.tmp` and the token both go, so the next attempt walks from
    /// offset 0 and a second 416 is impossible.
    @Test func a416OnAMismatchedPartialRestartsCleanWithNoToken() async throws {
        let (engine, directory) = makeStubbed416Engine()
        defer { try? FileManager.default.removeItem(at: directory.deletingLastPathComponent()) }
        let collector = EventCollector()
        let consumer = collector.consume(engine.events)
        defer { consumer.cancel() }
        let id = "range-mismatch"
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let tmp = directory.appending(path: "\(id).tmp")
        try Data("chunk".utf8).write(to: tmp)

        let token = try JSONEncoder().encode(
            WireToken(url: URL(string: "https://example.invalid/media?itag=140&total=999")!, userAgent: "UA"))
        await engine.resume(id: id, resumeData: token, allowsCellular: true)

        await waitUntil { collector.events.contains { if case .failed = $0 { return true }; return false } }
        let failure = try #require(collector.events.compactMap { event -> OfflineDownloadFailure? in
            if case .failed(_, let failure) = event { return failure } else { return nil }
        }.first)
        guard case .http(let status, let resumeData) = failure else {
            Issue.record("expected an http failure, got \(failure)")
            return
        }
        #expect(status == 416)
        #expect(resumeData == nil, "keeping the token is what made the 416 loop forever")
        #expect(!FileManager.default.fileExists(atPath: tmp.path()),
                "the unusable partial must go, or the restart is not a restart")
    }

    private func makeStubbed416Engine() -> (engine: ProgressiveEngine, directory: URL) {
        let base = FileManager.default.temporaryDirectory
            .appending(path: "OfflineEngine416-\(UUID().uuidString)", directoryHint: .isDirectory)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [RangeNotSatisfiableURLProtocol.self]
        let directory = OfflineStorage.directoryURL(base: base)
        return (ProgressiveEngine(directory: directory, configuration: configuration), directory)
    }

    /// Serves a 503 with a body — the shape a transient googlevideo error has.
    nonisolated final class ServerErrorURLProtocol: URLProtocol {
        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
        override func startLoading() {
            let response = HTTPURLResponse(url: request.url!, statusCode: 503, httpVersion: nil, headerFields: [:])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data("body".utf8))
            client?.urlProtocolDidFinishLoading(self)
        }
        override func stopLoading() {}
    }

    /// Cubic R2-1: iOS cancels background tasks the engine never asked to stop (force-quit, a
    /// background-session disconnect). Swallowing EVERY `.cancelled` as "our own pause/cancel"
    /// left such a row at "Saving…" with no task, so the manager's `active` claim was never
    /// released and `schedule()` refused every other queued row for the rest of the session.
    /// Only an id in the engine's own stop set is silent.
    @Test func aSystemCancelFailsTheWalkWhileOurOwnStopStaysSilent() async throws {
        let (engine, directory) = makeStubbedEngine()
        defer { try? FileManager.default.removeItem(at: directory.deletingLastPathComponent()) }
        let collector = EventCollector()
        let consumer = collector.consume(engine.events)
        defer { consumer.cancel() }

        let side = URLSession(configuration: .ephemeral)
        func stoppedTask(_ id: String) -> URLSessionTask {
            var request = URLRequest(url: URL(string: "https://example.invalid/media?itag=140")!)
            request.setValue("UA", forHTTPHeaderField: "User-Agent")
            let task = side.downloadTask(with: request)   // never resumed; carries the request shape
            task.taskDescription = id
            return task
        }

        await engine.cancel(id: "ours")   // bumps the generation: this completion is ours, stay silent
        engine.urlSession(side, task: stoppedTask("ours"), didCompleteWithError: URLError(.cancelled))
        engine.urlSession(side, task: stoppedTask("theirs"), didCompleteWithError: URLError(.cancelled))

        await waitUntil { collector.events.count == 1 }
        let event = try #require(collector.events.first)
        guard case .failed(let id, .network(let resumeData)) = event else {
            Issue.record("expected a system cancel to fail the row, got \(event)")
            return
        }
        #expect(id == "theirs")
        #expect(resumeData != nil, "the failure must carry the resume token so the row can continue")
    }

    /// Serves a 206 whose `Content-Range` names no total (`bytes 0-4/*` — what a proxy or a CDN
    /// edge that strips the length produces).
    nonisolated final class UnboundedChunkURLProtocol: URLProtocol {
        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
        override func startLoading() {
            let response = HTTPURLResponse(url: request.url!, statusCode: 206, httpVersion: nil,
                                           headerFields: ["Content-Range": "bytes 0-4/*"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data("chunk".utf8))
            client?.urlProtocolDidFinishLoading(self)
        }
        override func stopLoading() {}
    }

    /// Cubic R2-7: `total(fromContentRange:)` is nil for `bytes 0-x/*` (and for a missing
    /// header), and the walk's `else` branch called that `.finished` — a 10 MB partial renamed
    /// to the final file and shown as a completed save. A total the walk cannot read is a
    /// failure, never a completion.
    @Test func aPartialChunkWithNoParseableTotalFailsInsteadOfFinishing() async throws {
        let (engine, directory) = makeStubbedEngine()
        defer { try? FileManager.default.removeItem(at: directory.deletingLastPathComponent()) }
        let collector = EventCollector()
        let consumer = collector.consume(engine.events)
        defer { consumer.cancel() }
        let id = "unbounded-total"
        let url = URL(string: "https://example.invalid/media?itag=140")!

        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [UnboundedChunkURLProtocol.self]
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

        let location = FileManager.default.temporaryDirectory.appending(path: "chunk-\(UUID().uuidString)")
        try Data("chunk".utf8).write(to: location)
        defer { try? FileManager.default.removeItem(at: location) }
        engine.urlSession(side, downloadTask: task, didFinishDownloadingTo: location)

        await waitUntil {
            collector.events.contains { if case .failed = $0 { return true } else { return false } }
        }
        #expect(!collector.events.contains { if case .finished = $0 { return true } else { return false } },
                "a partial with no readable total must never be reported finished")

        // Part A review, Minor 4: the point of putting the guard INSIDE the `do` rather than
        // reusing the `catch` (which deletes the partial) is that the walk stays resumable — the
        // row fails with Retry, and Retry continues from the bytes already on disk instead of
        // re-downloading the file. Assert both halves, or a refactor that folds the guard into the
        // `catch` passes green while silently losing the resume point.
        #expect((try? Data(contentsOf: directory.appending(path: "\(id).tmp")))?.count == 5,
                "the partial must stay on disk — it is the engine's resume point")
        let failure = try #require(collector.events.compactMap { event -> OfflineDownloadFailure? in
            if case .failed(_, let failure) = event { return failure } else { return nil }
        }.first)
        guard case .network(let resumeData) = failure else {
            Issue.record("expected a resumable transport failure, got \(failure)")
            return
        }
        #expect(resumeData != nil, "without the token the retry restarts the walk from zero")
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

    // MARK: - Batched teardown (Cubic P3-3)

    /// Settings' Clear-all used to loop `delete(id)`, and every `delete` ends with `schedule()` —
    /// which picks a still-existing queued row and begins its resolve (a real InnerTube POST)
    /// before the loop's next iteration deletes it. One teardown of the whole batch under the
    /// actor, one `schedule()` at the end: nothing starts, no rate-limited resolve is burned.
    @Test func clearingEveryRowTearsThemDownWithoutStartingAnyOfThem() async throws {
        let rig = makeRig(); defer { rig.cleanUp() }
        var ids: [String] = []
        for index in 0..<3 {
            let item = OfflineItem(videoId: "vid-queued-\(index)", title: "Lecture \(index)",
                                   channelName: nil, thumbnailUrl: nil, qualityLabel: "360p",
                                   audioOnly: true, status: OfflineStatus.queued.rawValue)
            try rig.store.insert(item)
            ids.append(item.id)
        }
        await rig.manager.deleteAll(ids)
        #expect(rig.rowCount() == 0)
        #expect(rig.resolver.calls.isEmpty, "a clear must not burn a resolve on a row it is deleting")
        #expect(rig.engine.starts.isEmpty, "a clear must never start a row it is deleting")
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

    /// gstack P1: the sweep walked `.completed` rows only, so a video pulled from the catalog while
    /// its save was queued/paused/failed/running was invisible to it — and `reattach()` resumed the
    /// save on the next launch, after which the NEXT sweep finally deleted it. The gate table
    /// applies to every row; only the TTL half still needs a `completedAt`, which only a completed
    /// row has.
    ///
    /// gstack P2 rides along: the loop used to call `delete(_:)` per row, and every `delete` ends
    /// with a `schedule()` that picks a still-existing queued row and begins its resolve — a real,
    /// rate-limited InnerTube POST for a row the next iteration deletes. One `deleteAll`, one
    /// `schedule()`, exactly like Settings' Clear-all.
    @Test(arguments: [GateAnswer.gone, GateAnswer.notAllowed])
    func theSweepAppliesTheGateTableToEveryRowNotOnlyCompletedOnes(gate: GateAnswer) async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        for (index, status) in [OfflineStatus.queued, .paused, .failed, .running].enumerated() {
            let item = OfflineItem(videoId: "vidSweep\(index)", title: "Lecture \(index)",
                                   channelName: nil, thumbnailUrl: nil, qualityLabel: "360p",
                                   audioOnly: true, status: status.rawValue)
            try rig.store.insert(item)
        }
        // One survivor, so the pass is not a WHOLE-library removal — R5-2's belt refuses those.
        let survivor = OfflineItem(videoId: "vidSurvivor", title: "Lecture", channelName: nil,
                                   thumbnailUrl: nil, qualityLabel: "360p", audioOnly: true,
                                   status: OfflineStatus.paused.rawValue)
        try rig.store.insert(survivor)
        rig.flags.gate = gate
        rig.flags.gates = ["vidSurvivor": .allowed]

        await rig.manager.sweep()

        #expect(rig.rowCount() == 1, "auto-delete on catalog removal is not a completed-rows-only rule")
        #expect(rig.persisted(id: survivor.id) != nil)
        #expect(rig.resolver.calls.isEmpty, "the sweep must not burn a resolve on a row it is deleting")
        #expect(rig.engine.starts.isEmpty)
    }

    /// Cubic R5-2's belt, behind the gate client's envelope check: if EVERY row the gate was asked
    /// about in one pass answers gone, that is a broken edge answering 404 for `/api/v1/videos/*`,
    /// not a same-day whole-catalog purge. Keep them all and let the next sweep retry — the delete
    /// is irreversible, the wait is not.
    @Test func aSweepWhereEveryCheckedRowAnswersGoneDeletesNothing() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        for index in 0..<3 {
            let item = OfflineItem(videoId: "vidGone\(index)", title: "Lecture \(index)",
                                   channelName: nil, thumbnailUrl: nil, qualityLabel: "360p",
                                   audioOnly: true, status: OfflineStatus.completed.rawValue,
                                   completedAt: rig.flags.now)
            try rig.store.insert(item)
        }
        rig.flags.gate = .gone

        await rig.manager.sweep()

        #expect(rig.rowCount() == 3, "a whole-library removal in one pass is an edge, not a purge")
    }

    /// Review Minor 1: a broken edge does not have to answer uniformly. With 404s on some rows and
    /// a transport error on others, `removed.count < checked` and the belt used to stand down —
    /// deleting the 404 half of a library on exactly the failure it exists to survive. The
    /// denominator is the rows that got an ANSWER, not every row asked.
    @Test func aSweepWhereEveryAnsweringRowIsGoneKeepsThemEvenAlongsideUnreachableRows() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        for index in 0..<3 {
            let item = OfflineItem(videoId: "vidMixed\(index)", title: "Lecture \(index)",
                                   channelName: nil, thumbnailUrl: nil, qualityLabel: "360p",
                                   audioOnly: true, status: OfflineStatus.completed.rawValue,
                                   completedAt: rig.flags.now)
            try rig.store.insert(item)
        }
        rig.flags.gate = .gone
        rig.flags.gates = ["vidMixed2": .unreachable]   // the same edge, failing differently

        await rig.manager.sweep()

        #expect(rig.rowCount() == 3, "an edge that 404s some rows and drops others is still one edge")
    }

    /// Security r1 P0-1, second half: the belt covered the `.gone` bucket only, so a backend that
    /// answers 200-with-no-flag for every row (a deploy serving a different model, a migration that
    /// nulled the boxed `Boolean`, a WAF's JSON) still deleted the whole library — the SAME
    /// whole-library-in-one-pass shape, through `deleteGateRevoked` instead of `deleteRemoved`.
    /// A gate DELETE verdict is a gate delete verdict: `.gone` and `.notAllowed` are belted
    /// together, on the same answered denominator.
    @Test(arguments: [GateAnswer.notAllowed, .gone])
    func aSweepWhereEveryAnsweringRowSaysDeleteKeepsThemAll(gate: GateAnswer) async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        for index in 0..<2 {
            let item = OfflineItem(videoId: "vidRevoked\(index)", title: "Lecture \(index)",
                                   channelName: nil, thumbnailUrl: nil, qualityLabel: "360p",
                                   audioOnly: true, status: OfflineStatus.completed.rawValue,
                                   completedAt: rig.flags.now)
            try rig.store.insert(item)
        }
        rig.flags.gate = gate

        await rig.manager.sweep()

        #expect(rig.rowCount() == 2, "a whole-library gate verdict in one pass is drift, not a purge")
    }

    /// The bound, on BOTH delete verdicts: two rows, one allowed and one refused, is a real
    /// per-video revocation (or a real catalog removal) and the refused row goes. Parameterised —
    /// adversarial r1 P2-2 asks for the `.gone`-plus-`.allowed` shape by name, and it is the same
    /// belt arithmetic as `.notAllowed`, not a second rule.
    @Test(arguments: [GateAnswer.notAllowed, GateAnswer.gone])
    func aSweepWithOneAllowedRowStillDeletesTheRefusedOne(gate: GateAnswer) async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        for name in ["vidRevokedOne", "vidAllowedOne"] {
            let item = OfflineItem(videoId: name, title: name, channelName: nil, thumbnailUrl: nil,
                                   qualityLabel: "360p", audioOnly: true,
                                   status: OfflineStatus.completed.rawValue, completedAt: rig.flags.now)
            try rig.store.insert(item)
        }
        rig.flags.gate = gate
        rig.flags.gates = ["vidAllowedOne": .allowed]

        await rig.manager.sweep()

        #expect(rig.rowCount() == 1)
        #expect(rig.persisted(videoId: "vidAllowedOne") != nil)
    }

    /// Batch A review SR-m2 / adversarial r1 P2-2: the belt's denominator is the ANSWERING rows,
    /// and the two delete verdicts share ONE bucket — so a pass that answers `.gone` for one row,
    /// `.notAllowed` for another and nothing else is still "every answer said delete", which is
    /// drift, not a purge. Both stay. The per-verdict tests above only ever drive one answer, so
    /// nothing pinned the mixed shape a half-broken backend actually produces.
    @Test func aSweepMixingGoneAndNotAllowedWithNoAllowedRowKeepsThemAll() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        for name in ["vidGoneMix", "vidRevokedMix", "vidDeadEdgeMix"] {
            let item = OfflineItem(videoId: name, title: name, channelName: nil, thumbnailUrl: nil,
                                   qualityLabel: "360p", audioOnly: true,
                                   status: OfflineStatus.completed.rawValue, completedAt: rig.flags.now)
            try rig.store.insert(item)
        }
        rig.flags.gates = ["vidGoneMix": .gone, "vidRevokedMix": .notAllowed,
                           // Discounted from the denominator, so it cannot rescue the other two.
                           "vidDeadEdgeMix": .unreachable]

        await rig.manager.sweep()

        #expect(rig.rowCount() == 3, "404 for one row and no-flag for another is one broken edge, not two verdicts")
    }

    /// The TTL half is a LOCAL decision — nothing the network said — so the belt never covers it:
    /// an expired row goes even in the pass where every gate answer is belted.
    @Test func anExpiredRowStillDeletesWhileTheGateVerdictsAreBelted() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let expired = OfflineItem(videoId: "vidTTLExpired", title: "Lecture", channelName: nil,
                                  thumbnailUrl: nil, qualityLabel: "360p", audioOnly: true,
                                  status: OfflineStatus.completed.rawValue,
                                  completedAt: rig.flags.now.addingTimeInterval(-40 * 86_400))
        try rig.store.insert(expired)
        for index in 0..<2 {
            let item = OfflineItem(videoId: "vidBelted\(index)", title: "Lecture \(index)",
                                   channelName: nil, thumbnailUrl: nil, qualityLabel: "360p",
                                   audioOnly: true, status: OfflineStatus.completed.rawValue,
                                   completedAt: rig.flags.now)
            try rig.store.insert(item)
        }
        rig.flags.gate = .notAllowed

        await rig.manager.sweep()

        #expect(rig.persisted(id: expired.id) == nil, "the TTL is local: no gate answer belts it")
        #expect(rig.rowCount() == 2)
    }

    /// The bound: ONE row really can leave the catalog, and it still goes.
    @Test func aSweepWhereTheOnlyCheckedRowIsGoneStillDeletesIt() async throws {
        let rig = makeRig(.hls); defer { rig.cleanUp() }
        let item = OfflineItem(videoId: "vidGoneOnly", title: "Lecture", channelName: nil,
                               thumbnailUrl: nil, qualityLabel: "360p", audioOnly: true,
                               status: OfflineStatus.completed.rawValue, completedAt: rig.flags.now)
        try rig.store.insert(item)
        rig.flags.gate = .gone

        await rig.manager.sweep()

        #expect(rig.rowCount() == 0)
    }

    // MARK: - Live smoke (plan Task 4 step 2; §15 "simulator download" evidence)

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
            // `.allowed`, not `.unreachable` (adversarial r1 P1-2): this rig asserts the save
            // COMPLETES, and a save that completes without an affirmative gate answer is the
            // compliance violation, not the evidence. The refusal half is a fakes test
            // (`aStartWhoseGateIsUnreachableParksTheRowInsteadOfWalking`), so it runs in every gate.
            gate: { _ in .allowed }, now: { Date() })

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
