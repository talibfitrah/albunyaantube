import Foundation

/// What went wrong, as the recovery machine classifies it. One case per incident *class*: each class
/// gets its own single same-rung re-resolve allowance (`RecoveryBudget.sameRungUsed`), so a stall
/// after a 403 isn't refused just because the 403 already spent its own.
enum RecoveryEvent: Hashable, Sendable {
    /// `AVPlayerItem.status == .failed` while the stall watchdog is still unarmed, i.e. this rung
    /// never produced a first frame.
    case failedBeforeFirstFrame
    /// A failure after playback had started: `AVPlayerItemFailedToPlayToEndTime`, or a `.failed`
    /// item status once armed. This is the 403-class incident (an expired/revoked stream URL
    /// surfaces here, not as a distinguishable HTTP status -- AVFoundation gives no code we can
    /// branch on, and the recovery is identical either way).
    case playbackError
    /// The buffered position stopped advancing past the watchdog threshold (`shouldFireStall`).
    case stall
}

/// What the VM should do about one `RecoveryEvent`.
enum RecoveryAction: Hashable, Sendable {
    /// Re-resolve and keep playing whatever rung the resolver hands back (spec §10: "re-resolve
    /// same rung once, `replaceCurrentItem` + seek").
    case reResolveSameRung
    /// The same class already spent its same-rung allowance -- re-resolve and accept the demotion
    /// the resolver's own ladder produces. See `PlayerViewModel.handleRecoveryEvent` for why both
    /// actions issue the identical call and differ only in accounting.
    case stepDownRung
    /// Budget gone -> `.recoveryExhausted` and the manual Retry escape hatch.
    case exhausted
}

/// Spec §10: "budgets retries 3 / re-resolves 2" (Android `PlayerFragment.kt:1097-1101`
/// `maxRetries = 3` / `maxStreamRefreshes = 2`).
struct RecoveryBudget: Equatable, Sendable {
    static let maxRetries = 3
    static let maxReResolves = 2

    /// Every recovery attempt of either kind. The termination guarantee: once this hits
    /// `maxRetries` the machine is exhausted no matter which classes fired.
    private(set) var retries = 0
    /// The subset of `retries` that were same-rung re-resolves.
    private(set) var reResolves = 0
    /// Incident classes that already spent their one same-rung re-resolve.
    private(set) var sameRungUsed: Set<RecoveryEvent> = []
    private var hasPlayed = false

    mutating func apply(_ action: RecoveryAction, for event: RecoveryEvent) {
        switch action {
        case .exhausted:
            break // nothing was attempted
        case .reResolveSameRung:
            reResolves += 1
            sameRungUsed.insert(event)
            retries += 1
        case .stepDownRung:
            retries += 1
        }
    }

    /// Android parity (`PlayerFragment.kt:1154-1165`, and its own comment there): `streamRefreshCount`
    /// resets on EVERY successful resume -- so the re-resolve budget is per-failure-episode, not
    /// per-video-lifetime, and two recovered stalls on one long video don't leave the third
    /// unrecoverable. `retryCount` resets only on the FIRST successful playback (`hasAutoHidden`
    /// guards it), which keeps `retries` a genuine per-stream lifetime cap: a stream that flaps
    /// (plays a second, fails, plays a second, fails) still terminates at `maxRetries`.
    /// Called by `PlayerHostView`'s periodic sampler when the playback position actually advances.
    mutating func recordPlaybackProgress() {
        reResolves = 0
        sameRungUsed = []
        if !hasPlayed {
            hasPlayed = true
            retries = 0
        }
    }
}

/// The pure recovery deciders. No AVFoundation, no clock, no I/O -- `PlayerHostView.Coordinator`
/// measures the world and calls in; `PlayerViewModel.handleRecoveryEvent` acts on the answer.
enum PlaybackRecovery {
    /// player.md §3.2: VOD **6 s**, live **45 s**. (Spec §10's Recovery bullet says "stall > 8 s";
    /// player.md §3.2 is the measured Android constant and the brief names it as the authority for
    /// the watchdog, so 6/45 is what ships.)
    static let vodStallThreshold: TimeInterval = 6
    static let liveStallThreshold: TimeInterval = 45

    static func decide(event: RecoveryEvent, state: RecoveryBudget) -> RecoveryAction {
        guard state.retries < RecoveryBudget.maxRetries else { return .exhausted }
        // spec §10: a `.failed` status before the first frame goes straight to the next rung -- this
        // rung has proven it can't produce one, so re-resolving it is a wasted attempt.
        guard event != .failedBeforeFirstFrame else { return .stepDownRung }
        guard !state.sameRungUsed.contains(event), state.reResolves < RecoveryBudget.maxReResolves else {
            return .stepDownRung
        }
        return .reResolveSameRung
    }

    /// player.md §3.2: armed only after the first READY of the stream, and fires only if the buffered
    /// position has NOT advanced -- a slow-but-working network re-arms instead (the caller resets its
    /// progress mark on every advance, which is what keeps `elapsedSinceProgress` small).
    static func shouldFireStall(armed: Bool, elapsedSinceProgress: TimeInterval, isLive: Bool) -> Bool {
        armed && elapsedSinceProgress >= (isLive ? liveStallThreshold : vodStallThreshold)
    }
}
