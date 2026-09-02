import AVFoundation
import Foundation
import InnerTubeKit
import SwiftData
import Testing
@testable import FitrahTube

/// Phase 3 Task 7: offline playback (reconciliation note 5's `OfflineResolver` stub — never a
/// player fork) plus the revalidation sweep's full note-7 table and the launch/foreground cadence.
@Suite(.perTest)
struct OfflinePlaybackTests {
    private static let lectureVideoId = "xc7keR2piUM"

    // MARK: - Fixtures

    private struct Rig {
        let store: OfflineStore
        let container: ModelContainer
        let base: URL
        var directory: URL { OfflineStorage.directoryURL(base: base) }
        func cleanUp() { try? FileManager.default.removeItem(at: base) }
    }

    private func makeRig() -> Rig {
        let container = AppContainer.makeModelContainer(inMemory: true)
        let base = FileManager.default.temporaryDirectory
            .appending(path: "OfflinePlaybackTests-\(UUID().uuidString)", directoryHint: .isDirectory)
        return Rig(store: OfflineStore(modelContainer: container), container: container, base: base)
    }

    /// A completed row whose `localPath` is `<id>.<ext>` (Task 5's catalog), with a real file on
    /// disk unless `writeFile: false` (`movpkg` is a bundle DIRECTORY, like AVAssetDownloadTask's).
    private func completed(_ rig: Rig, ext: String, audioOnly: Bool = false,
                           writeFile: Bool = true) throws -> OfflineItem {
        let item = OfflineItem(videoId: "vid-\(ext)-\(UUID().uuidString.prefix(6))", title: "Lecture",
                               channelName: "Channel", thumbnailUrl: nil, qualityLabel: "360p",
                               audioOnly: audioOnly, status: OfflineStatus.completed.rawValue,
                               completedAt: Date())
        item.localPath = "\(item.id).\(ext)"
        if writeFile {
            try FileManager.default.createDirectory(at: rig.directory, withIntermediateDirectories: true)
            let file = OfflineStorage.fileURL(relativePath: item.localPath!, base: rig.base)
            if ext == "movpkg" {
                try FileManager.default.createDirectory(at: file, withIntermediateDirectories: true)
            } else {
                try Data("x".utf8).write(to: file)
            }
        }
        try rig.store.insert(item)
        return item
    }

    private func makeSettings() -> UserDefaultsSettingsStore {
        UserDefaultsSettingsStore(defaults: UserDefaults(suiteName: "OfflinePlaybackTests.\(UUID().uuidString)")!)
    }

    // MARK: - OfflineResolver mapping (reconciliation note 5)

    @Test func aMovpkgResolvesToTheHlsShapeOnTheVisionosClient() async throws {
        let rig = makeRig(); defer { rig.cleanUp() }
        let item = try completed(rig, ext: "movpkg")
        let resolver = OfflineResolver(store: rig.store, itemId: item.id, base: rig.base)
        let resolved = try await resolver.resolve(item.videoId, purpose: .player, kind: .player,
                                                  sourceChannelId: nil, forceRefresh: false)
        let expected = OfflineStorage.fileURL(relativePath: item.localPath!, base: rig.base)
        guard case .hls(let url, let isLive, let audioOnlyURL, let captionTracks) = resolved.stream else {
            Issue.record("expected .hls, got \(resolved.stream)"); return
        }
        #expect(url == expected)
        #expect(isLive == false)
        #expect(audioOnlyURL == nil)
        #expect(captionTracks.isEmpty)
        #expect(resolved.client == .visionos)
        #expect(resolved.expiresAt == nil)   // the TTL-refresh path must never fire offline
    }

    @Test func anMp4ResolvesToProgressiveWithTheRowsQualityLabel() async throws {
        let rig = makeRig(); defer { rig.cleanUp() }
        let item = try completed(rig, ext: "mp4")
        let resolver = OfflineResolver(store: rig.store, itemId: item.id, base: rig.base)
        let resolved = try await resolver.resolve(item.videoId, purpose: .player, kind: .player,
                                                  sourceChannelId: nil, forceRefresh: false)
        guard case .progressive(let url, let label) = resolved.stream else {
            Issue.record("expected .progressive, got \(resolved.stream)"); return
        }
        #expect(url == OfflineStorage.fileURL(relativePath: item.localPath!, base: rig.base))
        #expect(label == "360p")
        #expect(resolved.expiresAt == nil)
    }

    @Test func anM4aResolvesToProgressiveToo() async throws {
        let rig = makeRig(); defer { rig.cleanUp() }
        let item = try completed(rig, ext: "m4a", audioOnly: true)
        let resolver = OfflineResolver(store: rig.store, itemId: item.id, base: rig.base)
        let resolved = try await resolver.resolve(item.videoId, purpose: .player, kind: .player,
                                                  sourceChannelId: nil, forceRefresh: false)
        guard case .progressive = resolved.stream else {
            Issue.record("expected .progressive, got \(resolved.stream)"); return
        }
    }

    /// Recovery re-resolves route back into the resolver (note 5): same row, same answer, no
    /// state consumed — `forceRefresh` changes nothing.
    @Test func theResolverIsIdempotentAcrossForcedReResolves() async throws {
        let rig = makeRig(); defer { rig.cleanUp() }
        let item = try completed(rig, ext: "m4a", audioOnly: true)
        let resolver = OfflineResolver(store: rig.store, itemId: item.id, base: rig.base)
        let first = try await resolver.resolve(item.videoId, purpose: .player, kind: .player,
                                               sourceChannelId: nil, forceRefresh: false)
        let second = try await resolver.resolve(item.videoId, purpose: .player, kind: .autoRecovery,
                                                sourceChannelId: nil, forceRefresh: true)
        guard case .progressive(let firstURL, _) = first.stream,
              case .progressive(let secondURL, _) = second.stream else {
            Issue.record("expected .progressive twice"); return
        }
        #expect(firstURL == secondURL)
    }

    @Test func aMissingFileThrowsUnavailable() async throws {
        let rig = makeRig(); defer { rig.cleanUp() }
        let item = try completed(rig, ext: "m4a", audioOnly: true, writeFile: false)
        let resolver = OfflineResolver(store: rig.store, itemId: item.id, base: rig.base)
        await #expect(throws: ExtractionError.unavailable(videoId: item.videoId)) {
            _ = try await resolver.resolve(item.videoId, purpose: .player, kind: .player,
                                           sourceChannelId: nil, forceRefresh: false)
        }
    }

    /// The player's existing `.contentUnavailable` path handles the missing file — the SAME
    /// `PlayerViewModel.map` every online unavailable already rides; no new state, no new surface.
    @Test func aMissingFileLandsTheContentUnavailableState() async throws {
        let rig = makeRig(); defer { rig.cleanUp() }
        let item = try completed(rig, ext: "mp4", writeFile: false)
        var args = PlayerArgs(videoId: item.videoId)
        args.offlineItemId = item.id
        let vm = PlayerViewModel(resolver: OfflineResolver(store: rig.store, itemId: item.id, base: rig.base),
                                 settings: makeSettings(), args: args)
        await vm.open()
        #expect(vm.state == .contentUnavailable)
    }

    @Test func aDeletedRowThrowsUnavailable() async throws {
        let rig = makeRig(); defer { rig.cleanUp() }
        let resolver = OfflineResolver(store: rig.store, itemId: "no-such-row", base: rig.base)
        await #expect(throws: ExtractionError.unavailable(videoId: "vid")) {
            _ = try await resolver.resolve("vid", purpose: .player, kind: .player,
                                           sourceChannelId: nil, forceRefresh: false)
        }
    }

    // MARK: - Offline presentation (quality/cast/save hidden; Task 8's cast consumes the same flag)

    @Test func theOfflinePresentationFlagFollowsTheArgs() {
        var offline = PlayerArgs(videoId: "vid")
        offline.offlineItemId = "item-1"
        let settings = makeSettings()
        let vm = PlayerViewModel(resolver: RecordingResolver(.progressive), settings: settings, args: offline)
        #expect(vm.isOfflinePlayback)
        let online = PlayerViewModel(resolver: RecordingResolver(.progressive), settings: settings,
                                     args: PlayerArgs(videoId: "vid"))
        #expect(!online.isOfflinePlayback)
    }

    /// CF-C-9's all-optionals-nil Hashable property must survive the additive field: a deep link
    /// builds `PlayerArgs(videoId:)` alone and routes by equality.
    @Test func theAdditiveOfflineItemIdKeepsArgsEqualityForDeepLinks() {
        #expect(PlayerArgs(videoId: "abc123") == PlayerArgs(videoId: "abc123"))
        var offline = PlayerArgs(videoId: "abc123")
        offline.offlineItemId = "item-1"
        #expect(offline != PlayerArgs(videoId: "abc123"))
    }

    // MARK: - The Task 4 trap: precise duration on local files

    /// A saved fMP4 m4a reports ~2× duration without `AVURLAssetPreferPreciseDurationAndTimingKey`
    /// (measured, Task 4). Local files get the key at the ONE asset-construction seam; remote
    /// streams keep the cheap estimate (precise timing forces a full parse over the network).
    @Test func theAssetSeamPrefersPreciseTimingForFileURLsOnly() {
        let local = PlayerHostView.assetOptions(userAgent: "UA", url: URL(filePath: "/tmp/x.m4a"))
        #expect(local[AVURLAssetPreferPreciseDurationAndTimingKey] as? Bool == true)
        #expect(local[AVURLAssetHTTPUserAgentKey] as? String == "UA")
        let remote = PlayerHostView.assetOptions(userAgent: "UA", url: URL(string: "https://example.invalid/v.m3u8")!)
        #expect(remote[AVURLAssetPreferPreciseDurationAndTimingKey] == nil)
        #expect(remote[AVURLAssetHTTPUserAgentKey] as? String == "UA")
    }

    // MARK: - Sweep integration (reconciliation note 7's ONE table)

    /// Records every id the sweep asks the gate about, and answers with one canned value.
    private final class GateLog: @unchecked Sendable {
        nonisolated(unsafe) var asked: [String] = []
        nonisolated(unsafe) var answer: GateAnswer = .allowed
    }

    /// The sweep never moves bytes; the kill-switch tests only need to know whether the engine
    /// was ever asked to.
    private nonisolated final class NullEngine: OfflineEngine, @unchecked Sendable {
        private let lock = NSLock()
        private var _starts: [String] = []
        private var _resumes: [String] = []
        var starts: [String] { lock.withLock { _starts } }
        var resumes: [String] { lock.withLock { _resumes } }
        let events: AsyncStream<OfflineDownloadEvent> = AsyncStream { _ in }
        func start(id: String, url: URL, userAgent: String, allowsCellular: Bool) async -> Data? {
            lock.withLock { _starts.append(id) }
            return nil
        }
        func resume(id: String, resumeData: Data, allowsCellular: Bool) async {
            lock.withLock { _resumes.append(id) }
        }
        func pause(id: String) async -> Data? { nil }
        func cancel(id: String) async {}
        func liveIds() async -> Set<String> { [] }
    }

    /// The remote kill-switch as the manager reads it — live, so a test can flip it mid-run
    /// (the `GateLog` idiom).
    private final class KillSwitch: @unchecked Sendable {
        nonisolated(unsafe) var enabled: Bool
        init(_ enabled: Bool) { self.enabled = enabled }
    }

    private func makeManager(_ rig: Rig, gate: GateLog, engine: NullEngine = NullEngine(),
                             downloadsEnabled: @escaping @Sendable () async -> Bool = { true },
                             now: Date = Date()) -> OfflineManager {
        OfflineManager(store: rig.store, engine: engine, resolver: RecordingResolver(.hls),
                       limiterCheck: { _ in .allowed }, wifiOnly: { false }, isOnCellular: { false },
                       baseDirectory: rig.base,
                       gate: { id in gate.asked.append(id); return gate.answer },
                       now: { now },
                       downloadsEnabled: downloadsEnabled)
    }

    private func fileExists(_ rig: Rig, _ item: OfflineItem) -> Bool {
        FileManager.default.fileExists(atPath: OfflineStorage.fileURL(relativePath: item.localPath!,
                                                                      base: rig.base).path())
    }

    @Test func theSweepDeletesARemovedRowFileAndRowTogether() async throws {
        let rig = makeRig(); defer { rig.cleanUp() }
        let item = try completed(rig, ext: "m4a", audioOnly: true)
        let gate = GateLog(); gate.answer = .gone
        // `downloadsEnabled: false` on purpose (fork D): the kill-switch governs SAVING, never
        // the sweep — revalidation must keep deleting revoked copies while saving is off.
        await makeManager(rig, gate: gate, downloadsEnabled: { false }).sweep()
        #expect(rig.store.item(id: item.id) == nil)
        #expect(!fileExists(rig, item))
    }

    /// Fork C: an admin flipping `offlineAllowed` off is the same-day remedy path.
    @Test func theSweepDeletesAGateRevokedRow() async throws {
        let rig = makeRig(); defer { rig.cleanUp() }
        let item = try completed(rig, ext: "m4a", audioOnly: true)
        let gate = GateLog(); gate.answer = .notAllowed
        await makeManager(rig, gate: gate).sweep()
        #expect(rig.store.item(id: item.id) == nil)
        #expect(!fileExists(rig, item))
    }

    @Test func theSweepKeepsAnAllowedRow() async throws {
        let rig = makeRig(); defer { rig.cleanUp() }
        let item = try completed(rig, ext: "m4a", audioOnly: true)
        let gate = GateLog(); gate.answer = .allowed
        await makeManager(rig, gate: gate).sweep()
        #expect(rig.store.item(id: item.id) != nil)
        #expect(fileExists(rig, item))
    }

    /// Fail-open: never mass-delete a library because the phone was offline (CF-D-3).
    @Test func theSweepKeepsAnUnreachableRow() async throws {
        let rig = makeRig(); defer { rig.cleanUp() }
        let item = try completed(rig, ext: "m4a", audioOnly: true)
        let gate = GateLog(); gate.answer = .unreachable
        await makeManager(rig, gate: gate).sweep()
        #expect(rig.store.item(id: item.id) != nil)
        #expect(fileExists(rig, item))
    }

    /// TTL first, before any network: an expired row is deleted WITHOUT a gate call.
    @Test func anExpiredRowIsDeletedWithoutAskingTheGate() async throws {
        let rig = makeRig(); defer { rig.cleanUp() }
        let item = try completed(rig, ext: "m4a", audioOnly: true)
        item.completedAt = Date().addingTimeInterval(-40 * 86_400)
        try rig.store.save()
        let gate = GateLog(); gate.answer = .allowed
        await makeManager(rig, gate: gate).sweep()
        #expect(rig.store.item(id: item.id) == nil)
        #expect(!fileExists(rig, item))
        #expect(gate.asked.isEmpty)
    }

    // MARK: - Fragmented-MP4 duration normalization (the REAL Task 4 trap fix)

    /// Big-endian box builder for a synthetic MP4 head.
    private func box(_ type: String, _ payload: [UInt8]) -> [UInt8] {
        let size = UInt32(8 + payload.count)
        return [UInt8(size >> 24), UInt8((size >> 16) & 0xFF), UInt8((size >> 8) & 0xFF), UInt8(size & 0xFF)]
            + Array(type.utf8) + payload
    }

    /// mvhd/mdhd version-0 payload: ver/flags + creation + modification + timescale + duration.
    private func headerPayload(timescale: UInt32, duration: UInt32) -> [UInt8] {
        func be(_ v: UInt32) -> [UInt8] {
            [UInt8(v >> 24), UInt8((v >> 16) & 0xFF), UInt8((v >> 8) & 0xFF), UInt8(v & 0xFF)]
        }
        return be(0) + be(0) + be(0) + be(timescale) + be(duration)
    }

    private func syntheticHead(fragmented: Bool) -> Data {
        let mvhd = box("mvhd", headerPayload(timescale: 44_100, duration: 372_911_104))
        let mdhd = box("mdhd", headerPayload(timescale: 44_100, duration: 372_911_104))
        let mdia = box("mdia", mdhd)
        let trak = box("trak", mdia)
        let mvex = box("mvex", box("trex", [UInt8](repeating: 0, count: 24)))
        let moov = box("moov", mvhd + (fragmented ? mvex : []) + trak)
        return Data(box("ftyp", Array("mp42".utf8)) + moov)
    }

    /// YouTube's itag-140 fMP4 declares the FULL duration in mvhd/mdhd while also carrying every
    /// fragment, so AVFoundation reports ~2× (probed live 2026-09-01: afinfo 8455.99 s,
    /// `AVURLAsset` 16912.02 s, and `AVURLAssetPreferPreciseDurationAndTimingKey` changes
    /// NOTHING). Zeroing the two fields — the shape the fMP4 spec itself prescribes for
    /// fragmented files — makes AVFoundation derive the exact duration from the fragments.
    @Test func normalizeZeroesTheMvhdAndMdhdDurationsOfAFragmentedFile() throws {
        let url = FileManager.default.temporaryDirectory.appending(path: "frag-\(UUID().uuidString).m4a")
        defer { try? FileManager.default.removeItem(at: url) }
        try syntheticHead(fragmented: true).write(to: url)
        FragmentedMP4Durations.normalize(at: url)
        let data = try Data(contentsOf: url)
        let ranges = FragmentedMP4Durations.durationFieldRanges(in: data)
        #expect(ranges.count == 2)
        for range in ranges {
            #expect(data[range].allSatisfy { $0 == 0 })
        }
        // The timescales (4 bytes before each duration) survive untouched.
        for range in ranges {
            let timescale = data[(range.lowerBound - 4)..<range.lowerBound]
            #expect(Array(timescale) == [0x00, 0x00, 0xAC, 0x44])   // 44_100
        }
    }

    /// A plain (non-fragmented) mp4's mvhd is authoritative — itag 18 saves must NOT be touched.
    @Test func normalizeLeavesANonFragmentedFileUntouched() throws {
        let url = FileManager.default.temporaryDirectory.appending(path: "plain-\(UUID().uuidString).mp4")
        defer { try? FileManager.default.removeItem(at: url) }
        let original = syntheticHead(fragmented: false)
        try original.write(to: url)
        FragmentedMP4Durations.normalize(at: url)
        #expect(try Data(contentsOf: url) == original)
        #expect(FragmentedMP4Durations.durationFieldRanges(in: original).isEmpty)
    }

    /// Security r1 P3-1: the `trak`/`mdia` recursion had no depth cap, so a head that nests `trak`
    /// inside `trak` recurses once per 8 bytes of header — up to ~500 k frames within the 4 MB head
    /// limit, far past the 512 KB stack of the cooperative thread the actor runs `normalize` on.
    /// A hard crash at save completion, repeatable on every retry of the same URL. Real MP4 nesting
    /// is `moov/trak/mdia` deep, so anything past 8 is not a movie header: the walk stops and the
    /// file is left alone (the fail-safe no-op the rest of this walk already takes).
    @Test func aPathologicallyNestedHeadIsWalkedNoDeeperThanRealMP4Nesting() {
        let mvex = box("mvex", box("trex", [UInt8](repeating: 0, count: 24)))
        var nested = box("mdhd", headerPayload(timescale: 44_100, duration: 372_911_104))
        for _ in 0..<2_000 { nested = box("trak", nested) }
        let head = Data(box("ftyp", Array("mp42".utf8)) + box("moov", mvex + nested))

        #expect(FragmentedMP4Durations.durationFieldRanges(in: head).isEmpty,
                "a duration field 2000 boxes deep is not a movie header, and reaching it is a crash")
    }

    // MARK: - Kill-switch (Task 6 review fold-in)

    /// `downloadsEnabled == false` must refuse to START new work everywhere, not just hide the
    /// Save button (`SaveAffordance` was the ONLY config consult — Saved-screen Retry/Resume
    /// bypassed the switch entirely). delete/cancel/pause/sweep/playback stay ungated (fork D).
    @Test func theKillSwitchRefusesARetryAndLeavesTheRowFailed() async throws {
        let rig = makeRig(); defer { rig.cleanUp() }
        let item = OfflineItem(videoId: "vid-failed", title: "Lecture", channelName: nil,
                               thumbnailUrl: nil, qualityLabel: "360p", audioOnly: true,
                               status: OfflineStatus.failed.rawValue, errorCode: "NETWORK")
        try rig.store.insert(item)
        let engine = NullEngine()
        let manager = makeManager(rig, gate: GateLog(), engine: engine, downloadsEnabled: { false })
        await manager.retry(item.id)
        #expect(rig.store.item(id: item.id)?.status == OfflineStatus.failed.rawValue)
        #expect(engine.starts.isEmpty && engine.resumes.isEmpty)
    }

    @Test func theKillSwitchRefusesAResumeAndLeavesTheRowPaused() async throws {
        let rig = makeRig(); defer { rig.cleanUp() }
        let item = OfflineItem(videoId: "vid-paused", title: "Lecture", channelName: nil,
                               thumbnailUrl: nil, qualityLabel: "360p", audioOnly: true,
                               status: OfflineStatus.paused.rawValue, resumeData: Data("RD".utf8))
        try rig.store.insert(item)
        let engine = NullEngine()
        let manager = makeManager(rig, gate: GateLog(), engine: engine, downloadsEnabled: { false })
        await manager.resume(item.id)
        #expect(rig.store.item(id: item.id)?.status == OfflineStatus.paused.rawValue)
        #expect(engine.starts.isEmpty && engine.resumes.isEmpty)
    }

    @Test func aRetryUnderAnEnabledSwitchStartsTheEngine() async throws {
        let rig = makeRig(); defer { rig.cleanUp() }
        let item = OfflineItem(videoId: "vid-failed", title: "Lecture", channelName: nil,
                               thumbnailUrl: nil, qualityLabel: "360p", audioOnly: true,
                               status: OfflineStatus.failed.rawValue, errorCode: "NETWORK")
        try rig.store.insert(item)
        let engine = NullEngine()
        let manager = makeManager(rig, gate: GateLog(), engine: engine, downloadsEnabled: { true })
        await manager.retry(item.id)
        for _ in 0..<2000 {
            if !engine.starts.isEmpty { break }
            try? await Task.sleep(for: .milliseconds(1))
        }
        #expect(engine.starts == [item.id])
    }

    /// Cubic P3-1: a row saved during an off-window stays queued, and NOTHING observed the switch
    /// flipping back on — `observeOfflineGate` watches only Wi-Fi/cellular, so the row sat at
    /// "Waiting" until the next launch's `reattach()`. The remote-config refresh path now kicks
    /// `schedule()` after every refresh; this pins the manager end of that kick.
    @Test func aRowQueuedWhileTheKillSwitchWasOffStartsWhenTheSchedulerIsKickedAfterItFlipsOn() async throws {
        let rig = makeRig(); defer { rig.cleanUp() }
        let item = OfflineItem(videoId: "vid-queued", title: "Lecture", channelName: nil,
                               thumbnailUrl: nil, qualityLabel: "360p", audioOnly: true,
                               status: OfflineStatus.queued.rawValue)
        try rig.store.insert(item)
        let engine = NullEngine()
        let killSwitch = KillSwitch(false)
        let manager = makeManager(rig, gate: GateLog(), engine: engine,
                                  downloadsEnabled: { killSwitch.enabled })
        await manager.schedule()
        #expect(engine.starts.isEmpty, "the switch is off — nothing may start")

        killSwitch.enabled = true
        await manager.schedule()
        for _ in 0..<2000 {
            if !engine.starts.isEmpty { break }
            try? await Task.sleep(for: .milliseconds(1))
        }
        #expect(engine.starts == [item.id])
    }

    // MARK: - Sweep cadence

    /// The sweep shares the remote-config refresh hook's ONE due-decision (note 7's
    /// `DownloadExpiryPolicy.kt:23-28` cadence: launch + `willEnterForeground`, spaced so a
    /// scene-phase flicker never re-fires either effect).
    @Test func theSweepCadenceSharesTheForegroundRefreshDecision() {
        let now = Date()
        #expect(FitrahTubeApp.isRemoteConfigRefreshDue(now: now, last: nil, spacing: 900))
        #expect(!FitrahTubeApp.isRemoteConfigRefreshDue(now: now, last: now.addingTimeInterval(-100), spacing: 900))
        #expect(FitrahTubeApp.isRemoteConfigRefreshDue(now: now, last: now.addingTimeInterval(-901), spacing: 900))
    }

    // MARK: - Live leg (never in the gate; §15 "offline play" evidence)

    /// Saves the approved lecture audio-only for REAL, then opens it through `OfflineResolver`.
    /// The plan's "airplane-mode the Mac" step is replaced by proof by construction:
    /// `OfflineResolver`'s only inputs are the store row and `FileManager` — it holds no
    /// transport, no URLSession, no client; nothing it can reach performs a network call, so the
    /// open is offline-equivalent regardless of the Mac's network state.
    @Test(.enabled(if: ProcessInfo.processInfo.environment["OFFLINE_LIVE"] == "1"))
    func liveOfflineOpenPlaysTheSavedFileWithZeroNetwork() async throws {
        let innerTube = InnerTube(
            keyValueStore: MemoryKV(), availabilityGate: AlwaysAvailable(),
            locale: InnerTubeLocale(hl: "en", gl: "US"),
            remoteConfigURL: URL(string: "https://example.invalid/remote-config.json")!)
        let container = AppContainer.makeModelContainer(inMemory: true)
        let store = OfflineStore(modelContainer: container)
        let base = FileManager.default.temporaryDirectory
            .appending(path: "OfflinePlaybackLive-\(UUID().uuidString)", directoryHint: .isDirectory)
        defer { try? FileManager.default.removeItem(at: base) }
        let engine = ProgressiveEngine(directory: OfflineStorage.directoryURL(base: base),
                                       configuration: .default)
        let manager = OfflineManager(
            store: store, engine: engine, resolver: LiveStreamResolver(resolver: innerTube.resolver),
            limiterCheck: { await innerTube.rateLimiter.check($0, kind: .prefetch, now: innerTube.clock.now) },
            wifiOnly: { false }, isOnCellular: { false }, baseDirectory: base,
            gate: { _ in .unreachable }, now: { Date() })

        await manager.save(videoId: Self.lectureVideoId, quality: "audio",
                           audioOnly: true, metadata: OfflineMetadata(title: "Lecture", channelName: nil, thumbnailUrl: nil))
        let id = try #require(store.item(videoId: Self.lectureVideoId)?.id)
        let deadline = ContinuousClock.now + .seconds(50)
        while ContinuousClock.now < deadline {
            let status = store.item(id: id)?.status
            if status == OfflineStatus.completed.rawValue || status == OfflineStatus.failed.rawValue { break }
            try await Task.sleep(for: .milliseconds(200))
        }
        let row = try #require(store.item(id: id))
        print("[offline-live] row status=\(row.status) error=\(row.errorCode ?? "nil") bytes=\(row.bytesWritten) localPath=\(row.localPath ?? "nil")")
        #expect(row.status == OfflineStatus.completed.rawValue)

        // (a) Zero network by construction — see the doc comment above.
        let resolver = OfflineResolver(store: store, itemId: id, base: base)
        let resolved = try await resolver.resolve(Self.lectureVideoId, purpose: .player, kind: .player,
                                                  sourceChannelId: nil, forceRefresh: false)
        // (c) The Resolved shape rides `.progressive` with `expiresAt == nil`.
        guard case .progressive(let localURL, _) = resolved.stream else {
            Issue.record("expected .progressive, got \(resolved.stream)"); return
        }
        #expect(resolved.expiresAt == nil)
        #expect(localURL.isFileURL)

        // (b) Playable, and the duration matches afinfo's 8456 s (±5) — the Task 4 trap: an
        // UN-normalized fMP4 reports ~2× here (16912.02 s, probed), so this is the end-to-end
        // proof that `.finished` ran `FragmentedMP4Durations.normalize`.
        let asset = PlayerHostView.asset(url: localURL, userAgent: resolved.userAgent)
        let (isPlayable, duration) = try await asset.load(.isPlayable, .duration)
        print("[offline-live] file=\(localURL.lastPathComponent) isPlayable=\(isPlayable) duration=\(duration.seconds)s")
        #expect(isPlayable)
        #expect(abs(duration.seconds - 8456) <= 5)
    }
}
