import Foundation
@testable import InnerTubeKit

/// Maps a request matcher to a recorded response, in matcher order.
struct FixtureTransport: HTTPTransport {
    struct Route: Sendable {
        var match: @Sendable (HTTPRequest) -> Bool
        var response: HTTPResponse

        init(match: @escaping @Sendable (HTTPRequest) -> Bool, response: HTTPResponse) {
            self.match = match
            self.response = response
        }
    }

    struct NoRouteMatched: Error, Sendable {
        let request: HTTPRequest
    }

    private let routes: [Route]

    init(routes: [Route]) {
        self.routes = routes
    }

    func send(_ request: HTTPRequest) async throws -> HTTPResponse {
        guard let route = routes.first(where: { $0.match(request) }) else {
            throw NoRouteMatched(request: request)
        }
        return route.response
    }
}

/// Test-only clock: starts at `.zero`, advances only when told to.
final class ManualClock: MonotonicClock, @unchecked Sendable {
    // Sendable: all mutable state is guarded by `lock`.
    private let lock = NSLock()
    private var elapsed: Duration = .zero

    init() {}

    var now: Duration {
        lock.lock()
        defer { lock.unlock() }
        return elapsed
    }

    func advance(by amount: Duration) {
        lock.lock()
        defer { lock.unlock() }
        elapsed += amount
    }
}
