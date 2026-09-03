import Observation

/// The terminal account-lifecycle signals, 1:1 with Android's `AccountStatusEvent.kt`. `.signedOut`
/// is not a 403 — it is the user's own sign-out, posted so per-account state can be released
/// without every holder depending on the auth client.
nonisolated enum AccountStatusEvent: Sendable, Equatable { case blocked, deleted, signedOut }

/// Where `AuthorizedTransport`'s 403 envelopes land. The transport runs on whatever isolation the
/// request was made from and must never block on the UI, so `post` hops to the main actor and
/// returns immediately.
@MainActor @Observable final class AccountStatusCenter {

    /// Buffered, drop-oldest, depth ONE. Depth one is not a simplification: every event here is
    /// terminal ("you are blocked", "this account is gone", "you signed out") and takes the user to
    /// the same place, so a queue would only replay the same destination twice.
    private(set) var pending: AccountStatusEvent?

    nonisolated func post(_ event: AccountStatusEvent) {
        Task { @MainActor in self.pending = event }
    }

    /// Reading it clears it — the consumer routes once and a re-render must not route again.
    func consume() -> AccountStatusEvent? {
        defer { pending = nil }
        return pending
    }
}
