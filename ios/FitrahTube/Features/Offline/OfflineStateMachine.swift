import Foundation

/// What happened to a save — a user action (Saved-screen row / cancel) or an engine callback
/// (Task 4's `OfflineManager` is the only caller that executes these decisions).
nonisolated enum OfflineEvent: Sendable {
    case start, pause, resume, complete, fail, cancel, retry
}

/// A Saved-screen row's affordances (`DownloadsAdapter.kt:96-125` visibility matrix).
nonisolated enum OfflineAction: Sendable {
    case pause, resume, cancel, retry, remove, open, delete
}

/// Spec §16's "state machine transitions and action matrix" plus the cellular gate — pure
/// decisions, no side effects.
nonisolated enum OfflineStateMachine {
    /// nil = illegal transition; callers assert rather than limp on in a corrupt state.
    static func transition(from status: OfflineStatus, on event: OfflineEvent) -> OfflineStatus? {
        switch (status, event) {
        case (.queued, .start): .running
        // A queued row's button reads Resume (DownloadsAdapter parity); resuming one starts it.
        case (.queued, .resume): .running
        // Resolve can fail before the engine ever starts (embed outcome, bad input).
        case (.queued, .fail): .failed
        case (.running, .pause): .paused
        case (.running, .complete): .completed
        case (.running, .fail): .failed
        case (.paused, .resume): .running
        // A resolve can fail for a PAUSED row too: the cellular gate can pause one between its
        // `.running` transition and `engine.start`. Without this arm `fail()` wrote neither status
        // nor error code and the row sat at "Paused" as if nothing had happened.
        case (.paused, .fail): .failed
        case (.failed, .retry), (.cancelled, .retry): .queued
        // Anything cancels except completed (completed is terminal; Delete is not a transition).
        case (.queued, .cancel), (.running, .cancel), (.paused, .cancel), (.failed, .cancel): .cancelled
        default: nil
        }
    }

    /// The row action matrix. `completed` yields Open + Delete and NOTHING else — the owner
    /// ruling's no-share/no-export invariant is enforced by construction here (re-pinned as a
    /// compliance test in Task 7).
    static func actions(for status: OfflineStatus) -> [OfflineAction] {
        switch status {
        case .running: [.pause, .cancel]
        case .paused, .queued: [.resume, .cancel]
        case .failed, .cancelled: [.retry, .remove]
        case .completed: [.open, .delete]
        }
    }

    /// The cellular gate (reconciliation note 6): consulted when a task is created or resumed,
    /// never "updated live" — a background `URLSessionConfiguration` is immutable after creation.
    static func allowedToRun(wifiOnly: Bool, isOnCellular: Bool) -> Bool {
        !(wifiOnly && isOnCellular)
    }
}
