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

    enum Outcome { case hls, progressive, embed, failure(ExtractionError) }

    private let holdsUntilReleased: Bool
    private let lock = NSLock()
    private var _outcome: Outcome
    private var _calls: [Call] = []
    private var _permits = 0
    /// B5: per-video overrides of `outcome` (the auto-skip tests script dead items by id).
    private var _outcomes: [String: Outcome] = [:]
    /// B5: ids the `.prefetch` lane refuses with `.cooldown` while the `.player` lane still serves
    /// them -- CF-B2-2 rule (a), "a refusal is skipped silently and never blocks the advance".
    private var _prefetchRefusals: Set<String> = []

    var outcome: Outcome {
        get { lock.withLock { _outcome } }
        set { lock.withLock { _outcome = newValue } }
    }
    var calls: [Call] { lock.withLock { _calls } }
    var outcomes: [String: Outcome] {
        get { lock.withLock { _outcomes } }
        set { lock.withLock { _outcomes = newValue } }
    }
    var prefetchRefusals: Set<String> {
        get { lock.withLock { _prefetchRefusals } }
        set { lock.withLock { _prefetchRefusals = newValue } }
    }

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
            if kind == .prefetch, _prefetchRefusals.contains(videoId) {
                return .failure(.cooldown(until: Date().addingTimeInterval(30)))
            }
            return _outcomes[videoId] ?? _outcome
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
        case .embed:
            return Resolved(stream: .embed(videoId: videoId), client: .web,
                            userAgent: "", resolvedAt: Date(), expiresAt: nil)
        case .failure(let error):
            throw error
        }
    }
}

/// B5: scripted playlist pages, served in call order. `failFrom` (0-based call index) makes that
/// call and every later one throw, so the paging latch can be exercised.
final class FakeQueueSource: PlaylistQueueSource, @unchecked Sendable {
    private let lock = NSLock()
    private let pages: [(ids: [String], next: String?)]
    private let failFrom: Int?
    /// Calls with a 0-based index >= this suspend until `release()` -- lets a test observe the
    /// view model mid-page (same 1 ms-poll gate as `RecordingResolver.holdsUntilReleased`).
    private let gateFrom: Int?
    private var _pageCalls = 0
    private var _permits = 0

    var pageCalls: Int { lock.withLock { _pageCalls } }

    init(pages: [(ids: [String], next: String?)], failFrom: Int? = nil, gateFrom: Int? = nil) {
        self.pages = pages
        self.failFrom = failFrom
        self.gateFrom = gateFrom
    }

    /// Lets one gated page call proceed (no-op when `gateFrom` is nil).
    func release() { lock.withLock { _permits += 1 } }

    /// Suspends until `pageCalls >= count`, or fails after ~2 s (same shape as
    /// `RecordingResolver.waitUntilCalled`).
    func waitUntilPageCalled(count: Int, sourceLocation: SourceLocation = #_sourceLocation) async {
        for _ in 0..<2000 {
            if pageCalls >= count { return }
            try? await Task.sleep(for: .milliseconds(1))
        }
        #expect(pageCalls >= count, "page was called \(pageCalls) times, expected \(count)",
                sourceLocation: sourceLocation)
    }

    func page(playlistId: String, continuation: String?) async throws
        -> (items: [ContentItem], continuation: String?) {
        let index = lock.withLock { () -> Int in
            defer { _pageCalls += 1 }
            return _pageCalls
        }
        if let gateFrom, index >= gateFrom {
            while lock.withLock({ () -> Bool in
                if _permits > 0 { _permits -= 1; return false }
                return true
            }) { try? await Task.sleep(for: .milliseconds(1)) }
        }
        if let failFrom, index >= failFrom { throw ExtractionError.transport("fake page failure") }
        guard index < pages.count else { return ([], nil) }
        let page = pages[index]
        return (page.ids.map { ContentItem(video: VideoItem(id: $0, title: "T-\($0)", channelName: "Ch",
                                                             durationSeconds: 120, thumbnailURL: nil)) },
                page.next)
    }
}
