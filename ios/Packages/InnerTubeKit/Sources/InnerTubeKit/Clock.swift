import Foundation

public protocol MonotonicClock: Sendable {
    var now: Duration { get }
}

public protocol WallClock: Sendable {
    var wallNow: Date { get }
}

/// Real clock: monotonic time is measured against a `ContinuousClock` baseline
/// captured at init so `now` reflects elapsed time, not wall time; wall time
/// comes straight from `Date()`.
public struct SystemClock: MonotonicClock, WallClock, Sendable {
    private let clock = ContinuousClock()
    private let baseline: ContinuousClock.Instant

    public init() {
        baseline = clock.now
    }

    public var now: Duration {
        clock.now - baseline
    }

    public var wallNow: Date {
        Date()
    }
}
