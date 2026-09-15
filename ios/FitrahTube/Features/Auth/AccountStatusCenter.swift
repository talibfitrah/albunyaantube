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

    /// Buffered depth ONE, resolved by TERMINAL PRECEDENCE rather than by arrival order. Depth one
    /// is not a simplification — every event here is terminal and the consumer routes once — but
    /// "last writer wins" was: `.deleted` is the only one that wipes the device, and it must not be
    /// losable to a `.blocked` or a `.signedOut` that happened to hop later.
    private(set) var pending: AccountStatusSignal?

    /// The merge happens INSIDE the `@MainActor` hop, so two concurrent posts are serialised by the
    /// actor and the survivor is the most terminal of them.
    ///
    /// Strictly more severe always wins — that is the original rule and the whole point of the
    /// buffer. The TIE is where attribution changed things (Task 33, review I1). It used to be
    /// last-writer-wins, which `Swift.max` gives you, and that stayed harmless while every signal
    /// was equally actionable. It is not any more: an UNATTRIBUTED signal is honoured
    /// unconditionally while an attributed one can be refused, so a tie that evicts the
    /// unattributed one can turn a verdict that would have acted into one that does nothing — the
    /// user's own account deletion posting a bare `.deleted` and losing the slot to a stale
    /// attributed `.deleted` for an account that has since been replaced. So: a tie keeps an
    /// unattributed signal already held, and is otherwise the old last-writer rule.
    nonisolated func post(_ event: AccountStatusEvent, for uid: String? = nil) {
        Task { @MainActor in
            let incoming = AccountStatusSignal(event: event, uid: uid)
            guard let held = self.pending else { self.pending = incoming; return }
            if incoming.event > held.event || (incoming.event == held.event && held.uid != nil) {
                self.pending = incoming
            }
        }
    }

    /// Reading it clears it — the consumer routes once and a re-render must not route again.
    func consume() -> AccountStatusSignal? {
        defer { pending = nil }
        return pending
    }
}
