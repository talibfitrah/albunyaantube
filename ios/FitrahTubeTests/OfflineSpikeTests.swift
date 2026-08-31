import AVFoundation
import CoreMedia
import Foundation
import InnerTubeKit
import Testing

/// Phase 3 Task 1 — the §17 spike: does `AVAssetDownloadTask` accept YouTube's HLS packaging
/// (the VISIONOS `hlsManifestUrl` the resolver ladder returns)? The recorded verdict lives in
/// `OfflineEngineSupport.current`; this suite is the re-measurement instrument (rerun on hardware
/// per CF-D-1). Live-gated off by default, the `LiveResolveTests` idiom. Run deliberately:
///
///     TEST_RUNNER_OFFLINE_LIVE=1 xcodebuild test -project FitrahTube.xcodeproj \
///         -scheme FitrahTube -destination "platform=iOS Simulator,name=iPhone 17" \
///         -only-testing:FitrahTubeTests/OfflineSpikeTests -derivedDataPath DerivedData
///
/// (`TEST_RUNNER_` is xcodebuild's env-forwarding prefix — the `screenshots.sh` mechanism.)
///
/// Everything observed is printed verbatim with a `[spike]` prefix; the only hard assertion is
/// the plan's round-trip one: if a `.movpkg` lands, it must load a non-zero duration.
@Suite struct OfflineSpikeTests {
    /// The repo's ONE shared video id (approved catalog lecture — owner directive 2026-08-27
    /// forbids music-video ids anywhere). Same id as `LiveResolveTests.knownGoodVideoId`.
    private static let lectureVideoId = "xc7keR2piUM"

    /// The plan's 120 s ceiling collides with FitrahTube.xctestplan's enforced 60 s per-test
    /// allowance, so the in-test ceiling is 50 s; a cancelled `AVAssetDownloadTask` still hands
    /// its partial `.movpkg` to the delegate, and partial-plus-duration answers the acceptance
    /// question just as well as a full download.
    private static let ceiling: Duration = .seconds(50)

    private struct AlwaysAvailable: AvailabilityGate {
        func verify(videoId: String, sourceChannelId: String?) async throws -> Bool { true }
    }

    private nonisolated final class MemoryKV: KeyValueStore, @unchecked Sendable {
        private let lock = NSLock()
        private var storage: [String: Data] = [:]
        func get(_ key: String) -> Data? { lock.withLock { storage[key] } }
        func set(_ key: String, _ value: Data) { lock.withLock { storage[key] = value } }
    }

    /// Collects every delegate signal; `waitForCompletion()` parks until `didCompleteWithError`
    /// (which fires for success, failure, AND cancellation — no continuation leak).
    private nonisolated final class SpikeDelegate: NSObject, AVAssetDownloadDelegate, @unchecked Sendable {
        struct Observation: Sendable {
            var location: URL?
            var loadedSeconds: Double = 0
            var expectedSeconds: Double = 0
        }

        private let lock = NSLock()
        private var observation = Observation()
        private var finished: Error??
        private var continuation: CheckedContinuation<Error?, Never>?

        func snapshot() -> Observation { lock.withLock { observation } }

        func waitForCompletion() async -> Error? {
            await withCheckedContinuation { c in
                lock.withLock {
                    if let finished { c.resume(returning: finished) } else { continuation = c }
                }
            }
        }

        func urlSession(_ session: URLSession, assetDownloadTask: AVAssetDownloadTask,
                        willDownloadTo location: URL) {
            lock.withLock { observation.location = location }
            print("[spike] willDownloadTo \(location.path)")
        }

        func urlSession(_ session: URLSession, assetDownloadTask: AVAssetDownloadTask,
                        didFinishDownloadingTo location: URL) {
            lock.withLock { observation.location = location }
            print("[spike] didFinishDownloadingTo \(location.path)")
        }

        func urlSession(_ session: URLSession, assetDownloadTask: AVAssetDownloadTask,
                        didLoad timeRange: CMTimeRange, totalTimeRangesLoaded: [NSValue],
                        timeRangeExpectedToLoad: CMTimeRange) {
            let loaded = totalTimeRangesLoaded.reduce(0.0) { $0 + $1.timeRangeValue.duration.seconds }
            lock.withLock {
                observation.loadedSeconds = loaded
                observation.expectedSeconds = timeRangeExpectedToLoad.duration.seconds
            }
        }

        func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
            print("[spike] didCompleteWithError \(error.map { String(describing: $0 as NSError) } ?? "nil")")
            let c: CheckedContinuation<Error?, Never>? = lock.withLock {
                finished = .some(error)
                let c = continuation
                continuation = nil
                return c
            }
            c?.resume(returning: error)
        }
    }

    /// Runs one AVAssetDownloadTask over `url` and returns what the delegate saw. Shared by the
    /// YouTube leg and the Apple-compliant control leg (the B-vs-C discriminator: if Apple's own
    /// stream also fails, the simulator refuses the API for ANY HLS and the YouTube question is
    /// unanswered, not answered "no").
    private func runDownload(url: URL, userAgent: String?, sessionId: String) async throws
        -> (observation: SpikeDelegate.Observation, error: Error?)
    {
        let delegate = SpikeDelegate()
        let configuration = URLSessionConfiguration.background(withIdentifier: sessionId)
        let session = AVAssetDownloadURLSession(
            configuration: configuration, assetDownloadDelegate: delegate, delegateQueue: OperationQueue())
        defer { session.invalidateAndCancel() }
        var options: [String: any Sendable] = [:]
        if let userAgent { options["AVURLAssetHTTPHeaderFieldsKey"] = ["User-Agent": userAgent] }
        let asset = AVURLAsset(url: url, options: options)
        let downloadConfiguration = AVAssetDownloadConfiguration(asset: asset, title: "spike \(sessionId)")
        // ≈ the plan's minimumRequiredMediaBitRate 500_000, expressed through the non-deprecated
        // iOS 15+ API: cap the variant choice so the spike stays small.
        downloadConfiguration.primaryContentConfiguration.variantQualifiers =
            [AVAssetVariantQualifier(predicate: NSPredicate(format: "peakBitRate < %d", 1_000_000))]
        let task = session.makeAssetDownloadTask(downloadConfiguration: downloadConfiguration)
        task.resume()
        print("[spike] \(sessionId): task resumed, state=\(task.state.rawValue)")

        // Ceiling: cancel rather than fail — a cancelled task still completes with its partial
        // .movpkg location, which is all the round trip needs.
        let ceiling = Task {
            try await Task.sleep(for: Self.ceiling)
            print("[spike] \(sessionId): ceiling reached — cancelling (state=\(task.state.rawValue))")
            task.cancel()
        }
        let error = await delegate.waitForCompletion()
        ceiling.cancel()
        let observed = delegate.snapshot()
        print("[spike] \(sessionId): terminal error=\(error.map { String(describing: $0 as NSError) } ?? "nil") "
            + "loaded=\(observed.loadedSeconds)s of \(observed.expectedSeconds)s "
            + "location=\(observed.location?.path ?? "nil")")
        return (observed, error)
    }

    /// If a .movpkg landed, it must load a non-zero duration (the plan's round-trip assertion).
    private func assertPlayableAndClean(_ observation: SpikeDelegate.Observation, label: String) async throws {
        guard let location = observation.location else {
            print("[spike] \(label): no .movpkg location was ever reported")
            return
        }
        print("[spike] \(label): movpkg bytes=\(Self.directorySize(location))")
        let downloaded = AVURLAsset(url: location)
        let duration = try await downloaded.load(.duration)
        print("[spike] \(label): playback duration=\(duration.seconds)s")
        #expect(duration.seconds > 0, "a landed .movpkg must load a non-zero duration")
        try? FileManager.default.removeItem(at: location)
    }

    /// Control leg: Apple's own HLS example stream (unquestionably compliant packaging). Not a
    /// YouTube URL and not a music video — a test pattern. If THIS fails the same way YouTube's
    /// manifest does, the failure is the simulator/API, not YouTube's packaging.
    @Test(.enabled(if: ProcessInfo.processInfo.environment["OFFLINE_LIVE"] == "1"))
    func appleCompliantHLSControl() async throws {
        let bipbop = URL(string: "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_hevc/master.m3u8")!
        let (observation, _) = try await runDownload(url: bipbop, userAgent: nil, sessionId: "spike.offline.control")
        try await assertPlayableAndClean(observation, label: "control")
    }

    @Test(.enabled(if: ProcessInfo.processInfo.environment["OFFLINE_LIVE"] == "1"))
    func youtubeHLSThroughAVAssetDownloadTask() async throws {
        // Step 1 of the plan: a REAL resolve through the real ladder (the `LiveResolveTests`
        // construction — the hosted app runs `-fitrah-fake-container`, so the container's own
        // resolver is not reachable here; `InnerTube` built directly IS the same real resolver).
        let innerTube = InnerTube(
            keyValueStore: MemoryKV(),
            availabilityGate: AlwaysAvailable(),
            locale: InnerTubeLocale(hl: "en", gl: "US"),
            remoteConfigURL: URL(string: "https://example.invalid/remote-config.json")!
        )
        let resolved = try await innerTube.resolver.resolve(
            Self.lectureVideoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
        guard case let .hls(manifestURL, isLive, _, _) = resolved.stream else {
            Issue.record("expected .hls from the ladder, got \(resolved.stream) — spike unanswerable without a manifest")
            return
        }
        print("[spike] resolved client=\(resolved.client) isLive=\(isLive) ua=\(resolved.userAgent)")
        print("[spike] manifest=\(manifestURL.absoluteString)")

        let (observation, error) = try await runDownload(
            url: manifestURL, userAgent: resolved.userAgent, sessionId: "spike.offline.hls")

        // Discriminator: on failure, prove (or disprove) that the manifest URL itself is
        // fetchable with a plain GET + the same UA — separates "the download API refused" from
        // "the network/URL is bad".
        if error != nil {
            var request = URLRequest(url: manifestURL)
            request.setValue(resolved.userAgent, forHTTPHeaderField: "User-Agent")
            let (body, response) = try await URLSession.shared.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? -1
            let firstLine = String(decoding: body.prefix(80), as: UTF8.self)
                .split(separator: "\n").first.map(String.init) ?? ""
            print("[spike] plain GET of the manifest: status=\(status) bytes=\(body.count) firstLine=\(firstLine)")
        }

        // Step 1.3: the round trip — if a .movpkg landed, it must load a non-zero duration.
        try await assertPlayableAndClean(observation, label: "youtube")
    }

    private nonisolated static func directorySize(_ url: URL) -> Int {
        let fm = FileManager.default
        guard let enumerator = fm.enumerator(at: url, includingPropertiesForKeys: [.totalFileAllocatedSizeKey]) else {
            return (try? url.resourceValues(forKeys: [.totalFileAllocatedSizeKey]).totalFileAllocatedSize) ?? 0
        }
        var total = 0
        for case let file as URL in enumerator {
            total += (try? file.resourceValues(forKeys: [.totalFileAllocatedSizeKey]).totalFileAllocatedSize) ?? 0
        }
        return total
    }
}
