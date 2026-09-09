import Foundation

/// Per-row exponential backoff for sync push retries (`SyncBackoff.kt:18-35`): base 1 s doubling to
/// a 60 s cap, then EQUAL JITTER -- the returned wait is drawn from `[base/2, base]`.
///
/// The jitter is not decoration. A deterministic schedule meant a fleet-wide outage produced
/// synchronised reconnections: every client whose push failed at the same wall-clock waited the
/// same 1 s / 2 s / 4 s and re-hit the backend together, amplifying the outage into a thundering
/// herd. Equal jitter fans them out while keeping the exponential shape.
///
/// Single-writer by design -- the caller owns one instance, so this is a `mutating` value type
/// rather than anything shared.
nonisolated struct SyncBackoff: Sendable {
    private let initialMillis: Int
    private let capMillis: Int
    /// The jitter draw, injected so tests pin the window's edges instead of sampling a real RNG.
    /// The range is in SECONDS, matching the `Duration` returned.
    private let random: @Sendable (ClosedRange<Double>) -> Double
    private var currentMillis = 0

    init(initialMillis: Int = 1_000, capMillis: Int = 60_000,
         random: @escaping @Sendable (ClosedRange<Double>) -> Double = { Double.random(in: $0) }) {
        self.initialMillis = initialMillis
        self.capMillis = capMillis
        self.random = random
    }

    /// The wait for this attempt; the base doubles (up to the cap) for the next one.
    mutating func next() -> Duration {
        let base = currentMillis == 0 ? initialMillis : min(currentMillis * 2, capMillis)
        currentMillis = base
        let half = max(base / 2, 1)   // floored at 1 ms so a wait is never zero
        return .seconds(random(Double(half) / 1000 ... Double(base) / 1000))
    }

    mutating func reset() { currentMillis = 0 }
}
