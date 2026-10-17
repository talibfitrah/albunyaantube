import Foundation

/// What went wrong, as the recovery machine classifies it. One case per incident *class*: each class
/// gets its own single same-rung re-resolve allowance (`RecoveryBudget.sameRungUsed`), so a stall
/// after a 403 isn't refused just because the 403 already spent its own.
enum RecoveryEvent: Hashable, Sendable {
    /// `AVPlayerItem.status == .failed` while the stall watchdog is still unarmed, i.e. this rung
    /// never produced a first frame.
    case failedBeforeFirstFrame
    /// A failure after playback had started: `AVPlayerItemFailedToPlayToEndTime`, or a `.failed`
    /// item status once armed. An expired or revoked URL lands here. The CDN's answer, when there is
    /// one, travels beside the event as `httpStatus`: AVFoundation logs it in the item's error-log
    /// comment ("… -16840 - HTTP 401: Unauthorized", spike 2026-10-14), not as a code to branch on
    /// (`PlaybackRecovery.httpStatus`).
    case playbackError
    /// The buffered position stopped advancing past the watchdog threshold (`shouldFireStall`).
    case stall
}

/// What the VM should do about one `RecoveryEvent`.
enum RecoveryAction: Hashable, Sendable {
    /// Re-resolve and keep playing whatever rung the resolver hands back (spec §10: "re-resolve
    /// same rung once, `replaceCurrentItem` + seek"). A fresh URL is the fix for an expired one.
    case reResolveSameRung
    /// The rung on screen has failed: `StreamResolving.demote` it for this video (sticky for the
    /// session), then re-resolve -- the walk resumes at the NEXT rung (owner directive 2026-10-14).
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
    /// Called by `PlayerHostView`'s wall-clock sampler when the playback position actually advances.
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
    /// The period of `PlayerHostView.Coordinator`'s wall-clock sampler, which feeds `StallWatchdog`
    /// and stamps the error-log floor.
    static let sampleInterval: TimeInterval = 1

    static func decide(event: RecoveryEvent, httpStatus: Int? = nil, state: RecoveryBudget) -> RecoveryAction {
        guard state.retries < RecoveryBudget.maxRetries else { return .exhausted }
        // 401: the CDN refuses this client's URLs outright, and a fresh URL from the same rung is
        // refused too (V2Brp_esIVI: 14 of 21 AVPlayer attempts over 50 min). 403/404/410 fall
        // through: an expired or revoked URL is exactly what one same-rung re-resolve fixes.
        if httpStatus == 401 { return .stepDownRung }
        // spec §10: a `.failed` status before the first frame goes straight to the next rung -- this
        // rung has proven it can't produce one, so re-resolving it is a wasted attempt. Not for a
        // refused URL (1.0.4 I7; Task 6 Minor 4, spec R4): a cached or prefetched manifest after a
        // network change answers 403 for the new IP and proves nothing about the rung, so it gets
        // the class's one fresh same-rung URL before the step-down that is sticky for the session.
        let refusedURL = httpStatus.map { [403, 404, 410].contains($0) } ?? false
        guard event != .failedBeforeFirstFrame || refusedURL else { return .stepDownRung }
        guard !state.sameRungUsed.contains(event), state.reResolves < RecoveryBudget.maxReResolves else {
            return .stepDownRung
        }
        return .reResolveSameRung
    }

    /// The HTTP status of a CDN refusal, from the failed item's last error-log event. AVFoundation
    /// reports it as `errorStatusCode` -16840 with the code only in the comment ("The operation
    /// couldn’t be completed. (CoreMediaErrorDomain error -16840 - HTTP 401: Unauthorized)", macOS
    /// 15.7, spike 2026-10-14); a plain 4xx/5xx `statusCode` is taken as-is in case a platform puts
    /// it there. Nil when neither says -- `decide` then keeps the class rule.
    static func httpStatus(comment: String?, statusCode: Int) -> Int? {
        if (400...599).contains(statusCode) { return statusCode }
        guard let comment, let range = comment.range(of: #"HTTP (\d{3})"#, options: .regularExpression) else {
            return nil
        }
        return Int(comment[range].dropFirst(5))
    }

    /// Task 7 fix round 3 (N2): the status of the item's NEWEST error-log event, but only when it is
    /// dated after the cutoff. `floor` is the last time the player was sampled actually playing; an
    /// event older than that is taken as one AVFoundation already recovered from (probe 2026-10-14: a
    /// variant's segment 401 logged, failover to the other variant, then normal playback), and
    /// reading it would turn a later, unrelated stall into a sticky step-down. Fix round 4 (Minor 1):
    /// the cutoff runs one `sampleInterval` behind `floor`, because AVFoundation logs on its own
    /// thread and a tick can still read `.playing` just after a fresh 401. `floor == nil`: never seen
    /// playing, so every dated event is this incident's. An undated event cannot be placed, so it is
    /// not trusted.
    static func httpStatus(date: Date?, comment: String?, statusCode: Int, after floor: Date?) -> Int? {
        guard let date, date > floor.map({ $0 - sampleInterval }) ?? .distantPast else { return nil }
        return httpStatus(comment: comment, statusCode: statusCode)
    }

    /// How recently the player must have wanted to play for a failure to count as "died under the
    /// user" rather than "the user had paused" (I5).
    static let resumeWindow: TimeInterval = 3

    /// Whether the coordinator itself must play recovery's replacement item. A stall: no. It never
    /// pauses the player (the watchdog only counts `.waitingToPlayAtSpecifiedRate`), so the live
    /// player state decides at swap time (AVPlayer keeps its rate across `replaceCurrentItem`, and the
    /// builder plays a player that was not paused), and a pause made during the re-resolve holds (fix
    /// round 3, Minor A). A failure leaves AVPlayer paused (spike 2026-10-14: `timeControlStatus ==
    /// .paused` after `FailedToPlayToEndTime`), so intent comes from when it was last seen wanting to
    /// play; `nil` = never sampled, a fresh player the builder had just started.
    static func shouldResume(after event: RecoveryEvent, lastPlayingAt: Date?, now: Date) -> Bool {
        guard event != .stall else { return false }
        guard let lastPlayingAt else { return true }
        return now.timeIntervalSince(lastPlayingAt) <= resumeWindow
    }

    /// The threshold primitive: how long a stall has to last before it counts. `StallWatchdog` below
    /// is what decides *whether* the player is stalled at all.
    static func shouldFireStall(armed: Bool, elapsedSinceProgress: TimeInterval, isLive: Bool) -> Bool {
        armed && elapsedSinceProgress >= (isLive ? liveStallThreshold : vodStallThreshold)
    }
}

/// Spec §10's AirPlay paragraph: "if the external device fails the item with a 403 (the plan's
/// IP-binding risk), set `allowsExternalPlayback = false` and retry so video mirrors from the
/// phone -- AirPlay always works, at worst as mirroring."
///
/// Deliberately NOT a fourth `RecoveryAction`: it is a PRE-CHECK consulted in
/// `PlayerViewModel.handleRecoveryEvent` before `PlaybackRecovery.decide` runs, so the mirroring
/// retry spends none of the retry/re-resolve budgets. Those budgets exist for genuinely broken
/// streams; a stream that is fine on the phone and only 403s because the AirPlay receiver fetches
/// it from a different public IP must not consume them, or a later real failure on the same video
/// finds the machine already exhausted.
nonisolated enum AirPlayFallback {
    static func shouldMirror(event: RecoveryEvent, externalPlaybackActive: Bool, alreadyFellBack: Bool) -> Bool {
        // Once per stream: a second failure after mirroring is not the route's fault (there is no
        // route left to blame), so it belongs to the ordinary ladder and its budgets.
        guard externalPlaybackActive, !alreadyFellBack else { return false }
        switch event {
        case .playbackError, .failedBeforeFirstFrame:
            // Any item failure while an external route plays it points at the route (the plan's
            // IP-binding risk), whatever status the CDN gave, so the status is not consulted here.
            return true
        case .stall:
            // A stall is not an item failure: the stream is still valid, the route is not the
            // suspect, and dropping the user off their TV for a network hiccup is worse than
            // waiting for the buffer.
            return false
        }
    }
}

/// The stall watchdog as a pure value (fix round 1, C1): the host feeds it one sample per second of
/// wall clock and it answers what changed. Previously this logic lived inline in
/// `PlayerHostView.Coordinator.sample` and measured only `loadedTimeRanges` growth, which false-fired
/// on healthy playback three ways -- a fully-buffered item (rung-2 MP4s finish downloading in
/// seconds) stopped "progressing" and stalled 6 s later; a backward seek froze the mark against the
/// lifetime max; a pause longer than the threshold fired the instant it resumed.
///
/// The fix mirrors Android (`PlayerFragment.kt:2237-2285`): the clock only accumulates while the
/// player is ACTUALLY stalled -- `timeControlStatus == .waitingToPlayAtSpecifiedRate`, i.e. it wants
/// to play and can't -- which is false while paused, while playing, and while fully buffered. Any
/// playback-position change (forwards, backwards, a seek) resets the mark, and a buffered-position
/// advance *during* a stall re-arms rather than fires (player.md §3.2: slow-but-working networks).
struct StallWatchdog: Equatable {
    /// player.md §3.2: armed only after the item's first READY. Never cleared again for the item --
    /// a fired episode resets `stalledSince` instead, so the next event needs a fresh full threshold.
    var armed = false

    private var lastPlaybackTime: TimeInterval
    private var lastBufferedEnd: TimeInterval = 0
    private var stalledSince: Date?

    /// - Parameter playbackTime: seed from the live player's `currentTime()`. A replacement item is
    ///   seeked back to the outgoing item's position, so seeding from 0 would read that offset as
    ///   real playback progress on the first tick and refund the recovery budget for free.
    init(playbackTime: TimeInterval = 0) {
        lastPlaybackTime = playbackTime.isFinite ? playbackTime : 0
    }

    struct Tick: Equatable {
        /// The playback position moved: the stream is genuinely playing (budget refund).
        var progressed = false
        /// A stall lasted past the threshold: raise `RecoveryEvent.stall`.
        var fire = false
    }

    /// `isOnline` (final review I2): with no usable network a stall is not ours to fix -- the forced
    /// re-resolve would fail at once and `.error` would replace the player -- so offline seconds never
    /// count, and back online AVPlayer gets a full threshold to resume by itself first.
    mutating func tick(playbackTime: TimeInterval, bufferedEnd: TimeInterval, isStalled: Bool,
                       isLive: Bool, isOnline: Bool = true, now: Date) -> Tick {
        var result = Tick()
        if abs(playbackTime - lastPlaybackTime) > 0.1 {
            lastPlaybackTime = playbackTime
            stalledSince = nil
            result.progressed = true
        }
        guard armed, isStalled, isOnline else {
            stalledSince = nil // paused, playing, not yet ready, or offline: nothing accumulates
            lastBufferedEnd = bufferedEnd
            return result
        }
        if bufferedEnd > lastBufferedEnd + 0.1 {
            lastBufferedEnd = bufferedEnd
            stalledSince = nil // still downloading -- re-arm instead of firing
        } else if bufferedEnd < lastBufferedEnd - 0.1 {
            // Cubic #11: a buffer-flushing seek regresses bufferedEnd far below the old high-water
            // mark, and a legitimate post-seek rebuffer (growing, but still below it) could then
            // never re-arm -- false-firing `.stall` at 6 s while visibly downloading. A regression
            // is a flush, not a stall signal: re-base the mark so growth re-arms again.
            lastBufferedEnd = bufferedEnd
            stalledSince = nil
        }
        let since = stalledSince ?? now
        stalledSince = since
        if PlaybackRecovery.shouldFireStall(armed: armed, elapsedSinceProgress: now.timeIntervalSince(since),
                                            isLive: isLive) {
            stalledSince = nil // one event per stall episode
            result.fire = true
        }
        return result
    }
}
