import Foundation

/// A canned answer from the per-video `offlineAllowed` gate (Task 5's `OfflineGateClient`
/// produces these from `GET /api/v1/videos/{id}`; the sweep and its tests consume them pure).
nonisolated enum GateAnswer: Sendable {
    /// 200 with `offlineAllowed == true`.
    case allowed
    /// 200 with the flag absent or false — the admin revoked (or never granted) the gate.
    case notAllowed
    /// 404/410 — the video left the catalog.
    case gone
    /// Transport error — no answer, not a "no".
    case unreachable
}

nonisolated enum SweepAction: Sendable {
    case keep, deleteExpired, deleteRemoved, deleteGateRevoked
}

/// Expiry + revalidation as ONE pure decision (reconciliation note 7). TTL and grace are
/// Android's `DownloadExpiryPolicy.kt:23-28` (30-day TTL, 1 h grace); the sweep cadence
/// (launch + `willEnterForeground`) is Task 7's glue.
nonisolated enum OfflineSweep {
    static let ttl: TimeInterval = 30 * 86_400
    static let grace: TimeInterval = 3_600

    /// The TTL check runs before any network: Task 7's sweep asks this first and only fetches
    /// the gate for items that survive it.
    static func isExpired(completedAt: Date, now: Date) -> Bool {
        now.timeIntervalSince(completedAt) > ttl + grace
    }

    static func decide(completedAt: Date, now: Date, gate: GateAnswer) -> SweepAction {
        if isExpired(completedAt: completedAt, now: now) { return .deleteExpired }
        switch gate {
        case .gone:
            // Catalog removal → the owner ruling's auto-delete.
            return .deleteRemoved
        case .notAllowed:
            // Fork C: an admin flipping `offlineAllowed` off is the same-day remedy path;
            // a lingering copy defeats it.
            return .deleteGateRevoked
        case .allowed, .unreachable:
            // Unreachable is fail-open: never mass-delete a library because the phone was
            // offline — the next successful sweep catches up (CF-D-3).
            return .keep
        }
    }
}
