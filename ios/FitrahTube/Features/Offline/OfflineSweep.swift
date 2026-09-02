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

    /// The GATE half of the table, and only that half: `OfflineManager.sweep()` answers the TTL
    /// itself with `isExpired` above — before it spends the gate GET — and calls this for the rows
    /// that survive, so a `completedAt`/`now` arm here was reachable from tests alone and gave the
    /// TTL-first rule two spellings to keep in step (R9-13). One rule, one place.
    static func decide(gate: GateAnswer) -> SweepAction {
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
