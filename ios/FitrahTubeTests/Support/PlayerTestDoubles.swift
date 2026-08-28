import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// `MonotonicClock` whose `now` the test sets. `@unchecked Sendable`: test-only, single-threaded
/// use; the protocol requires `Sendable` but a settable stored property cannot prove it.
final class FixedMonotonicClock: MonotonicClock, @unchecked Sendable {
    nonisolated(unsafe) var now: Duration
    init(now: Duration) { self.now = now }
}

/// Records every resolve and can hold its answer so a test can observe state mid-flight.
/// `outcome` is settable so one instance can succeed on `open()` and then fail on the refresh.
final class RecordingResolver: StreamResolving, @unchecked Sendable {
    // B4: `purpose` was dropped on the floor. It is the lane CF-B2-2 reserves (.prefetch is B5's),
    // so a test that means "Shorts never resolve a neighbour" has to be able to see it.
    struct Call: Equatable { var videoId: String; var kind: RequestKind; var purpose: Purpose; var forceRefresh: Bool }

    enum Outcome { case hls, progressive, failure(ExtractionError) }

    private let holdsUntilReleased: Bool
    private let lock = NSLock()
    private var _outcome: Outcome
    private var _calls: [Call] = []
    private var _permits = 0

    var outcome: Outcome {
        get { lock.withLock { _outcome } }
        set { lock.withLock { _outcome = newValue } }
    }
    var calls: [Call] { lock.withLock { _calls } }

    init(_ outcome: Outcome, holdsUntilReleased: Bool = false) {
        self._outcome = outcome
        self.holdsUntilReleased = holdsUntilReleased
    }

    /// Lets one held resolve proceed (no-op when `holdsUntilReleased` is false).
    func release() { lock.withLock { _permits += 1 } }

    /// Suspends until `calls.count >= count`, or fails after ~2 s. M3 (fix round 1): an unbounded
    /// wait turns "the call never came" into a hang that only the per-test limit ends, with no
    /// indication of which expectation was never met.
    func waitUntilCalled(count: Int, sourceLocation: SourceLocation = #_sourceLocation) async {
        for _ in 0..<2000 {
            if calls.count >= count { return }
            try? await Task.sleep(for: .milliseconds(1))
        }
        #expect(calls.count >= count, "resolver was called \(calls.count) times, expected \(count)",
                sourceLocation: sourceLocation)
    }

    func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind,
                 sourceChannelId: String?, forceRefresh: Bool) async throws -> Resolved {
        // Captured HERE, not after the hold: a test that scripts a second answer while this call is
        // still held is describing the NEXT call's outcome, not retroactively this one's.
        let outcome = lock.withLock { () -> Outcome in
            _calls.append(Call(videoId: videoId, kind: kind, purpose: purpose, forceRefresh: forceRefresh))
            return _outcome
        }
        // ponytail: a 1 ms poll, same as `waitUntilCalled` above -- a continuation registry would
        // be more code than the whole helper for a test-only gate. Ceiling: the test must call
        // `release()` or it spins until the 60 s per-test limit kills it.
        if holdsUntilReleased {
            while lock.withLock({ () -> Bool in
                if _permits > 0 { _permits -= 1; return false }
                return true
            }) { try? await Task.sleep(for: .milliseconds(1)) }
        }
        let url = URL(string: "https://manifest.googlevideo.com/x.m3u8")!
        switch outcome {
        case .hls:
            return Resolved(stream: .hls(url: url, isLive: false, audioOnlyURL: URL(string: "https://r1/a140")!,
                                         captionTracks: []),
                            client: .visionos, userAgent: "UA", resolvedAt: Date(),
                            expiresAt: Date().addingTimeInterval(3600))
        case .progressive:
            return Resolved(stream: .progressive(url: url, label: "360p"), client: .android,
                            userAgent: "UA", resolvedAt: Date(), expiresAt: Date().addingTimeInterval(3600))
        case .failure(let error):
            throw error
        }
    }
}
