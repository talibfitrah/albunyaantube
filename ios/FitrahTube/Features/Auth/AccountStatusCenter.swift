import Observation

/// The terminal account-lifecycle signals, 1:1 with Android's `AccountStatusEvent.kt`. `.signedOut`
/// is not a 403 — it is the user's own sign-out, posted so per-account state can be released
/// without every holder depending on the auth client.
nonisolated enum AccountStatusEvent: Sendable, Equatable { case blocked, deleted, signedOut }

extension AccountStatusEvent: Comparable {
    /// TERMINAL PRECEDENCE, `deleted > blocked > signedOut` (Stage 3 / I4). The three do NOT take
    /// the user to the same place: `.deleted` runs `handleDeletion()` — ruling C13's device wipe —
    /// while `.blocked` and `.signedOut` only drop the session. So a buffer that let the last
    /// writer win could lose the wipe for a server-deleted account, and the writers are unordered
    /// (each `post` is its own unstructured `Task`, and Swift's cooperative executor makes no
    /// enqueue-order promise once priority escalation is in play).
    private var severity: Int {
        switch self {
        case .signedOut: 0
        case .blocked: 1
        case .deleted: 2
        }
    }

    static func < (lhs: AccountStatusEvent, rhs: AccountStatusEvent) -> Bool {
        lhs.severity < rhs.severity
    }
}

/// Where `AuthorizedTransport`'s 403 envelopes land. The transport runs on whatever isolation the
/// request was made from and must never block on the UI, so `post` hops to the main actor and
/// returns immediately.
@MainActor @Observable final class AccountStatusCenter {

    /// Buffered depth ONE, resolved by TERMINAL PRECEDENCE rather than by arrival order. Depth one
    /// is not a simplification — every event here is terminal and the consumer routes once — but
    /// "last writer wins" was: `.deleted` is the only one that wipes the device, and it must not be
    /// losable to a `.blocked` or a `.signedOut` that happened to hop later.
    private(set) var pending: AccountStatusEvent?

    /// The merge happens INSIDE the `@MainActor` hop, so two concurrent posts are serialised by the
    /// actor and the survivor is the most terminal of them, whatever order they arrive in.
    nonisolated func post(_ event: AccountStatusEvent) {
        Task { @MainActor in self.pending = Swift.max(self.pending ?? event, event) }
    }

    /// Reading it clears it — the consumer routes once and a re-render must not route again.
    func consume() -> AccountStatusEvent? {
        defer { pending = nil }
        return pending
    }
}
