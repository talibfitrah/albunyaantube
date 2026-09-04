#if DEBUG
import Foundation
import InnerTubeKit
import Synchronization

/// The ONE canned `HTTPTransport` every hand-written-client test uses (Phase 4 Tasks 7, 9, 10, 11,
/// 12, 16, 17, 22, 25, 26, 28). `FixedStatusTransport` (`AppContainer.swift`, DEBUG) answers one
/// status forever and cannot express a 401-then-200; `RecordingTransport`
/// (`FitrahAPI/Tests/.../RecordingTransport.swift`) is an OpenAPI `ClientTransport` over `HTTPTypes`
/// and is the wrong protocol entirely. **Do not write a tenth copy of this.**
///
/// It lives in the APP target, not `FitrahTubeTests`, for the same reason `FakeAuthClient` and
/// `ParkedOfflineEngine` do: `AppContainer.fake()` hands one to the fixture container and cannot see
/// the test bundle. Every consuming test still names it directly — `FitrahTubeTests` declares
/// `- target: FitrahTube` (`project.yml:109-117`), so the bundle links the Debug app and
/// `@testable import FitrahTube` resolves `#if DEBUG` symbols, exactly as `AppContainerTests`
/// already does for `FixedStatusTransport`. The Release stage compiles it out.
nonisolated final class ScriptedTransport: HTTPTransport {

    /// The queue running dry is a TEST BUG and must look like one — never a silent repeat of the
    /// last response, which would let an unexpected extra request pass unnoticed.
    enum Failure: Error, Equatable { case exhausted(method: String, url: URL) }

    private struct State {
        var queue: [HTTPResponse]
        var sent: [HTTPRequest] = []
        var inFlight = 0
        var peak = 0
    }

    /// `Mutex` rather than `@unchecked Sendable` + bare vars: `HTTPTransport` refines `Sendable` and
    /// a canned transport is driven from whatever isolation a test happens to use — including, in
    /// Task 16's case, several tasks at once.
    private let state: Mutex<State>

    /// Responses are consumed in order.
    init(_ responses: [HTTPResponse]) { state = Mutex(State(queue: responses)) }

    /// Every request, in order, for assertions.
    var sent: [HTTPRequest] { state.withLock { $0.sent } }
    /// Peak simultaneous in-flight `send`s. Task 16 asserts the Me feed's `TaskGroup` respects
    /// `MeFeedRefreshGate.maxConcurrent`; every other caller ignores it. Kept here rather than in a
    /// subclass because this type is `final` on purpose — one canned transport, no variants.
    var peakConcurrency: Int { state.withLock { $0.peak } }

    // The two builders live on `HTTPResponse` (below) so leading-dot syntax works inside the
    // `[HTTPResponse]` the initializer takes — `ScriptedTransport([.json(200, "{}")])`, which is
    // what all ~90 call sites use.

    func send(_ request: HTTPRequest) async throws -> HTTPResponse {
        let next: HTTPResponse? = state.withLock {
            $0.sent.append(request)
            $0.inFlight += 1
            $0.peak = max($0.peak, $0.inFlight)
            return $0.queue.isEmpty ? nil : $0.queue.removeFirst()
        }
        // ONE suspension point so simultaneous callers actually overlap and `peakConcurrency` can
        // exceed 1. `Task.yield()`, never a sleep — the gate is hermetic and clock-free.
        await Task.yield()
        state.withLock { $0.inFlight -= 1 }

        guard let next else { throw Failure.exhausted(method: request.method, url: request.url) }
        if let key = next.headers[Self.errorKey],
           let error = Self.errors.withLock({ $0.removeValue(forKey: key) }) {
            throw error
        }
        return next
    }

    fileprivate static let errorKey = "X-ScriptedTransport-Error"
    fileprivate static let errors = Mutex<[String: any Error & Sendable]>([:])
}

nonisolated extension HTTPResponse {
    static func json(_ status: Int, _ body: String, headers: [String: String] = [:]) -> HTTPResponse {
        HTTPResponse(status: status,
                     headers: headers.merging(["Content-Type": "application/json"]) { caller, _ in caller },
                     body: Data(body.utf8))
    }

    /// The transport-error leg: `ScriptedTransport.send` THROWS this instead of answering. The error
    /// travels in a side table keyed from the response's headers, because `HTTPResponse` has nowhere
    /// to put a value that is not bytes — and carrying the error ITSELF rather than a description is
    /// what lets a caller pin `URLError.notConnectedToInternet` and not merely "something threw".
    ///
    /// `some Error & Sendable` rather than the plan's bare `Error`: the table crosses isolation, and
    /// every error a test scripts (`URLError`, `CancellationError`, `ExtractionError`) conforms.
    // ponytail: a `failing` response that is never consumed leaks one dictionary entry. DEBUG-only,
    // one entry per scripted failure per test — make the table per-instance if that ever matters.
    static func failing(_ error: some Error & Sendable) -> HTTPResponse {
        let key = UUID().uuidString
        ScriptedTransport.errors.withLock { $0[key] = error }
        return HTTPResponse(status: 0, headers: [ScriptedTransport.errorKey: key], body: Data())
    }
}
#endif
