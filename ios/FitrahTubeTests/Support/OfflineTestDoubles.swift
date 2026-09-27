import Foundation
import Testing
@testable import FitrahTube

/// Shared offline fixtures: the row builder, the engine double, its event drain, the engine's
/// resume-token wire format, and the base every stub `URLProtocol` in the suite inherits.
/// File-level so `OfflineManagerTests` and `OfflinePlaybackTests` share one spelling of each.

/// One saved row, with the fields no test varies already filled in (no channel, no thumbnail,
/// 360p, audio-only). Every argument below is one some test actually changes.
func makeOfflineItem(_ videoId: String, title: String = "Lecture", status: OfflineStatus = .queued,
                     audioOnly: Bool = true, resumeData: Data? = nil,
                     createdAt: Date = Date(), completedAt: Date? = nil, userId: String = "") -> OfflineItem {
    OfflineItem(videoId: videoId, title: title, channelName: nil, thumbnailUrl: nil,
                qualityLabel: "360p", audioOnly: audioOnly, status: status.rawValue,
                resumeData: resumeData, createdAt: createdAt, completedAt: completedAt, userId: userId)
}

/// Records every engine call; a test drives completions by calling `manager.handle(_:)`
/// directly (deterministic) or by `emit(_:)` into the stream (proves the consumer loop).
/// Yields NOTHING on its own — the stream is silent until `emit`, which is what lets a test that
/// only cares whether the engine was asked to move bytes use it as a null engine.
nonisolated final class FakeOfflineEngine: OfflineEngine, @unchecked Sendable {
    struct Start: Equatable, Sendable { var id: String; var url: URL; var userAgent: String; var allowsCellular: Bool }
    private let lock = NSLock()
    private var _starts: [Start] = []
    private var _resumes: [(id: String, resumeData: Data)] = []
    private var _pauses: [String] = []
    private var _cancels: [String] = []
    private var _live: Set<String> = []
    /// What `pause` — and `start`, which persists it as the walk's resume point — hands back.
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
    /// The same shape for `resume`, and the ONE seam that parks a caller INSIDE the engine start:
    /// the hold sits before the walk is recorded, so a test can land a `pause()` in the window
    /// between `begin`'s last check and the engine hop.
    var resumeHeld: Set<String> {
        get { lock.withLock { _resumeHeld } }
        set { lock.withLock { _resumeHeld = newValue } }
    }
    var resumeEntered: Set<String> { lock.withLock { _resumeEntered } }
    private var _resumeHeld: Set<String> = []
    private var _resumeEntered: Set<String> = []
    /// Same shape for `cancel`: an interleaving that needs a cancel parked mid-flight, past its own
    /// row read but before it drops the manager's claim.
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
        lock.withLock { _ = _resumeEntered.insert(id) }
        await Self.hold(while: { self.lock.withLock { self._resumeHeld.contains(id) } }, what: "resume of \(id)")
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

    /// Bounded, like `RecordingResolver.hold`: a `#require` failing before the release line must
    /// not leave an unstructured task polling at 1 ms for the whole process. Reports rather than
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

/// The engine's resume-token wire format. The engine's own struct is private, and `start` cannot
/// hand one back without first deleting the partial these tests are about, so the tests spell it.
struct WireToken: Codable { var url: URL; var userAgent: String }

/// The three overrides every stub `URLProtocol` in the suite writes identically; a subclass adds
/// only its own `startLoading`.
nonisolated class StubURLProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}
}
