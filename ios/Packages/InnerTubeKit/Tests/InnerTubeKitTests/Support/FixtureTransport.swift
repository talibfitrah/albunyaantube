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

/// Test-only clock: starts at `.zero` (monotonic) / a fixed epoch (wall),
/// advances only when told to.
final class ManualClock: MonotonicClock, WallClock, @unchecked Sendable {
    // Sendable: all mutable state is guarded by `lock`.
    private let lock = NSLock()
    private var elapsed: Duration = .zero
    private var wall = Date(timeIntervalSinceReferenceDate: 0)

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

    var wallNow: Date {
        get {
            lock.lock()
            defer { lock.unlock() }
            return wall
        }
        set {
            lock.lock()
            defer { lock.unlock() }
            wall = newValue
        }
    }

    func advanceWall(by amount: Duration) {
        let seconds = Double(amount.components.seconds) + Double(amount.components.attoseconds) / 1e18
        lock.lock()
        defer { lock.unlock() }
        wall = wall.addingTimeInterval(seconds)
    }
}

/// Test-only in-memory KeyValueStore (the app uses a UserDefaults-backed one).
final class InMemoryKeyValueStore: KeyValueStore, @unchecked Sendable {
    // Sendable: all mutable state is guarded by `lock`.
    private let lock = NSLock()
    private var storage: [String: Data] = [:]

    init() {}

    func get(_ key: String) -> Data? {
        lock.lock()
        defer { lock.unlock() }
        return storage[key]
    }

    func set(_ key: String, _ value: Data) {
        lock.lock()
        defer { lock.unlock() }
        storage[key] = value
    }
}
