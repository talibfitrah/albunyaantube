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

/// A verdict PLUS the account it was minted for (Task 33 / CF-A-44). The event alone was not
/// enough: `AccountSession.handleDeletion()` stamps its marker from whoever is signed in at
/// DELIVERY time and wipes that account's scope, so a verdict recorded for A and delivered after B
/// had signed in wiped B's library and wrote B's uid into A's marker. The record end had been
/// hardened twice for exactly this (Cubic round 6 / P2b, round 4 / NB-A); the delivery end never
/// was, because the identity stopped at the transport.
///
/// `uid` is nil for an UNATTRIBUTED post — the session's own `.signedOut`/`.deleted` announcements,
/// which run after `dropSession()` has already cleared `user`, and the splash's launch-time
/// advisory. Those are honoured exactly as before; only a MISMATCH is dropped. Nil is a
/// "no worse than it was" arm, NOT a proof of safety: a 403 envelope answered to an unsigned
/// request also arrives unattributed and is honoured (CF-A-47).
nonisolated struct AccountStatusSignal: Sendable, Equatable {
    let event: AccountStatusEvent
    let uid: String?
}

/// Where `AuthorizedTransport`'s 403 envelopes land. The transport runs on whatever isolation the
/// request was made from and must never block on the UI, so `post` hops to the main actor and
/// returns immediately.
@MainActor @Observable final class AccountStatusCenter {

    /// At most ONE signal per uid (nil = unattributed), its most terminal event: `.deleted` is the
    /// only one that wipes and must not lose to a later `.blocked`/`.signedOut` for the same
    /// account. Other uids' signals are never evicted (CF-A-55 (b)) — each is accepted or refused
    /// on its own by `AccountSession.handle`. Kept least terminal first, unattributed first on a
    /// tie, so the drain (`RootView.routeAll`) acts on the most terminal one last.
    private var held: [AccountStatusSignal] = []

    /// The next signal `consume()` hands over, nil when none is held.
    var pending: AccountStatusSignal? { held.first }

    /// Merged INSIDE the `@MainActor` hop, so concurrent posts are serialised by the actor.
    nonisolated func post(_ event: AccountStatusEvent, for uid: String? = nil) {
        Task { @MainActor in
            if let index = self.held.firstIndex(where: { $0.uid == uid }) {
                guard event > self.held[index].event else { return }
                self.held.remove(at: index)
            }
            self.held.append(AccountStatusSignal(event: event, uid: uid))
            self.held.sort { $0.event != $1.event ? $0.event < $1.event : ($0.uid == nil && $1.uid != nil) }
        }
    }

    /// Hands over the next held signal and drops it — the consumer routes each once, and a
    /// re-render must not route it again.
    func consume() -> AccountStatusSignal? {
        held.isEmpty ? nil : held.removeFirst()
    }
}
