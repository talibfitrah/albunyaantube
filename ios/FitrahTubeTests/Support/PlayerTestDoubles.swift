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
    struct Call: Equatable {
        var videoId: String; var kind: RequestKind; var purpose: Purpose; var forceRefresh: Bool
        /// Defaulted so the many pre-save-walk call assertions stay as written (they all mean false).
        var requiresMuxed: Bool = false
        var requiresHLS: Bool = false
    }

    /// `liveHLS` is `hls` with `isLive` set -- the only thing that separates a live rung 1 from a
    /// VOD one, and what the cast hand-back's seek has to refuse (R7-18).
    enum Outcome { case hls, liveHLS, progressive, failure(ExtractionError) }

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
    /// Review F1: per-id holds, independent of the global `holdsUntilReleased` gate -- a race test
    /// needs to hold TWO specific resolves open (the advance's and the tap's) and release them in
    /// a scripted order, which one global permit pool cannot express.
    private var _heldIds: Set<String> = []
    private var _idPermits: [String: Int] = [:]
    /// How far out the answered stream's `expiresAt` sits. Settable because the cast path re-uses
    /// the stream the player already holds UNLESS it is near expiry, and that margin is the only
    /// thing separating "cast what we have" from "walk the network again".
    private var _expiresIn: TimeInterval = 3600
    /// Per-video `captionTracks` for the `.hls` answer (the caption carry-over test); default none.
    private var _captionTracks: [String: [CaptionTrack]] = [:]
    /// `demote` calls in order, as "videoId:family" (playback hardening, spec §4.1).
    private var _demotions: [String] = []
    var demotions: [String] { lock.withLock { _demotions } }

    /// Holds every `demote` open until released (a race test's window); nil = return at once.
    private var _demoteGate: Gate?
    var demoteGate: Gate? {
        get { lock.withLock { _demoteGate } }
        set { lock.withLock { _demoteGate = newValue } }
    }

    func demote(_ videoId: String, failed: ClientFamily) async {
        lock.withLock { _demotions.append("\(videoId):\(failed)") }
        await demoteGate?.block()
    }

    var outcome: Outcome {
        get { lock.withLock { _outcome } }
        set { lock.withLock { _outcome = newValue } }
    }
    /// What a `requiresMuxed` walk answers, when set (the cast path's itag-18 walk); nil = `outcome`.
    private var _muxedOutcome: Outcome?
    var muxedOutcome: Outcome? {
        get { lock.withLock { _muxedOutcome } }
        set { lock.withLock { _muxedOutcome = newValue } }
    }
    /// What a `requiresHLS` walk answers, when set (the audio-only save's fallback walk); nil = `outcome`.
    private var _hlsOutcome: Outcome?
    var hlsOutcome: Outcome? {
        get { lock.withLock { _hlsOutcome } }
        set { lock.withLock { _hlsOutcome = newValue } }
    }
    var expiresIn: TimeInterval {
        get { lock.withLock { _expiresIn } }
        set { lock.withLock { _expiresIn = newValue } }
    }
    var captionTracks: [String: [CaptionTrack]] {
        get { lock.withLock { _captionTracks } }
        set { lock.withLock { _captionTracks = newValue } }
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

    /// Every later resolve of `id` suspends until a matching `release(id:)`.
    func hold(_ id: String) { lock.withLock { _ = _heldIds.insert(id) } }

    /// Lets one held resolve of `id` proceed.
    func release(id: String) { lock.withLock { _idPermits[id, default: 0] += 1 } }

    /// Suspends until `calls.count >= count`, or fails after a 10 s deadline (2000 polls, ~2 s, ran
    /// out under load). M3 (fix round 1): an unbounded wait turns "the call never came" into a hang
    /// that only the per-test limit ends, with no indication of which expectation was never met.
    /// A cancelled task's `Task.sleep` throws at once, so the loop exits on it instead of spinning
    /// to the deadline (fix r4 N-3).
    func waitUntilCalled(count: Int, sourceLocation: SourceLocation = #_sourceLocation) async {
        let deadline = ContinuousClock.now + .seconds(10)
        while calls.count < count, ContinuousClock.now < deadline {
            do { try await Task.sleep(for: .milliseconds(1)) } catch { break }
        }
        #expect(calls.count >= count, "resolver was called \(calls.count) times, expected \(count)",
                sourceLocation: sourceLocation)
    }

    func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind,
                 sourceChannelId: String?, forceRefresh: Bool,
                 requiresMuxed: Bool, requiresHLS: Bool) async throws -> Resolved {
        // Captured HERE, not after the hold: a test that scripts a second answer while this call is
        // still held is describing the NEXT call's outcome, not retroactively this one's.
        let outcome = lock.withLock { () -> Outcome in
            _calls.append(Call(videoId: videoId, kind: kind, purpose: purpose,
                               forceRefresh: forceRefresh, requiresMuxed: requiresMuxed, requiresHLS: requiresHLS))
            if kind == .prefetch, _prefetchRefusals.contains(videoId) {
                return .failure(.cooldown(until: Date().addingTimeInterval(30)))
            }
            if requiresMuxed, let _muxedOutcome { return _muxedOutcome }
            if requiresHLS, let _hlsOutcome { return _hlsOutcome }
            return _outcomes[videoId] ?? _outcome
        }
        // ponytail: a 1 ms poll, same as `waitUntilCalled` above -- a continuation registry would
        // be more code than the whole helper for a test-only gate. 1.0.4 I11: bounded and
        // cancellation-safe like the per-id hold below -- the `try?` swallowed a cancelled task's
        // immediate throw and spun hot until a release its cancelled caller never sends.
        if holdsUntilReleased {
            var released = false
            let deadline = ContinuousClock.now + .seconds(10)
            while ContinuousClock.now < deadline {
                if lock.withLock({ () -> Bool in
                    if _permits > 0 { _permits -= 1; return true }
                    return false
                }) { released = true; break }
                do { try await Task.sleep(for: .milliseconds(1)) } catch { break }
            }
            #expect(released || Task.isCancelled, "held resolve of \(videoId) was never released")
        }
        // CF-G-15: bounded like `waitUntilCalled` (a 10 s deadline; fix r4: 2000 polls, ~2 s, ran
        // out under load while the offline tests' own waits held the release back), and
        // cancellation-safe -- a cancelled task's `Task.sleep` throws immediately, so the old
        // unbounded `try?` loop hot-spun. On cancellation we bail out (the resolver's caller
        // discards a cancelled result anyway).
        if lock.withLock({ _heldIds.contains(videoId) }) {
            var released = false
            let deadline = ContinuousClock.now + .seconds(10)
            while ContinuousClock.now < deadline {
                if lock.withLock({ () -> Bool in
                    if _idPermits[videoId, default: 0] > 0 { _idPermits[videoId]! -= 1; return true }
                    return false
                }) { released = true; break }
                do { try await Task.sleep(for: .milliseconds(1)) } catch { break }
            }
            #expect(released || Task.isCancelled, "held resolve of \(videoId) was never released")
        }
        // m6: every outcome's played URL answers one nothing can resolve. These are what
        // `CastSessionTests.phonePlayer(_:)` and the offline rigs build real `AVPlayer`s and
        // `AVURLAsset`s from, and a real googlevideo host there started DNS from un-gated unit tests
        // (the class R7-22 fixed in `OfflineComplianceTests`). An address literal on the discard
        // port needs no lookup at all, and no assertion in the suite reads anything but the string.
        let nonResolvingURL = URL(string: "https://127.0.0.1:9/x.m3u8")!
        let expiresAt = Date().addingTimeInterval(expiresIn)
        switch outcome {
        case .hls:
            return Resolved(stream: .hls(url: nonResolvingURL, isLive: false,
                                         audioOnlyURL: URL(string: "https://r1/a140")!,
                                         captionTracks: captionTracks[videoId] ?? []),
                            client: .visionos, userAgent: "UA", resolvedAt: Date(),
                            expiresAt: expiresAt)
        case .liveHLS:
            return Resolved(stream: .hls(url: nonResolvingURL, isLive: true, audioOnlyURL: nil,
                                         captionTracks: []),
                            client: .visionos, userAgent: "UA", resolvedAt: Date(),
                            expiresAt: expiresAt)
        case .progressive:
            return Resolved(stream: .progressive(url: nonResolvingURL, label: "360p"), client: .android,
                            userAgent: "UA", resolvedAt: Date(), expiresAt: expiresAt)
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

    /// Suspends until `pageCalls >= count`, or fails after a 10 s deadline, left at once by a
    /// cancelled task -- the shape of `RecordingResolver.waitUntilCalled` (1.0.4 I11: this one was
    /// still 2000 polls, ~2 s, which runs out under load).
    func waitUntilPageCalled(count: Int, sourceLocation: SourceLocation = #_sourceLocation) async {
        let deadline = ContinuousClock.now + .seconds(10)
        while pageCalls < count, ContinuousClock.now < deadline {
            do { try await Task.sleep(for: .milliseconds(1)) } catch { break }
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
            // Bounded and cancellation-safe, like `RecordingResolver`'s holds (1.0.4 I11).
            var released = false
            let deadline = ContinuousClock.now + .seconds(10)
            while ContinuousClock.now < deadline {
                if lock.withLock({ () -> Bool in
                    if _permits > 0 { _permits -= 1; return true }
                    return false
                }) { released = true; break }
                do { try await Task.sleep(for: .milliseconds(1)) } catch { break }
            }
            #expect(released || Task.isCancelled, "gated page \(index) was never released")
        }
        if let failFrom, index >= failFrom { throw ExtractionError.transport("fake page failure") }
        guard index < pages.count else { return ([], nil) }
        let page = pages[index]
        return (page.ids.map { ContentItem(video: VideoItem(id: $0, title: "T-\($0)", channelName: "Ch",
                                                             durationSeconds: 120, thumbnailURL: nil)) },
                page.next)
    }
}
