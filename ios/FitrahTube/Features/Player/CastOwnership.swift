import Foundation

// MARK: - Cast ownership (spec §10)

/// What just happened to a `PlayerScreen` that the cast session might have to answer for. One case
/// per reaction site, and not one of them decides anything itself.
nonisolated enum CastTrigger: Sendable, Equatable, CaseIterable {
    /// `onAppear`: back on screen after a push-over or a compact-layout tab switch.
    case appear
    /// `onDisappear`. A WENT-OFF-SCREEN seam, not a teardown one -- the screen is usually still
    /// alive, still paused for its cast, and coming back.
    case disappear
    /// This screen started playing a video: the `.task` mount arm, and `swapArgs` on every
    /// auto-advance, Up Next tap and auto-skip.
    case videoStarted
    /// `.onChange(of: isSessionActive)` -- a receiver connected or disconnected.
    case sessionChanged
    /// `.onChange(of: lastLoadFailure)` -- the receiver refused what it was handed.
    case loadFailed
}

/// WHICH screen claimed WHAT. `MainShellView` keeps every visited tab's stack mounted, so two
/// `PlayerScreen`s can be up on the SAME video -- a Home stack and a Search stack both on X -- and
/// a stamp keyed on the videoId alone reads as theirs to BOTH: both pause for the cast, both load
/// the receiver, and both resume on hand-back (double audio). `owner` is a `UUID` each
/// `PlayerViewModel` mints at init, so one screen's claim can never be mistaken for another's.
nonisolated struct CastClaim: Sendable, Equatable {
    var videoId: String
    var owner: UUID
}

/// One rejected load (`CastController.lastLoadFailure`). `PlayerScreen` watches it with
/// `.onChange`, so the device's NAME alone will not do: a second failure on the same receiver
/// writes an EQUAL value, `.onChange` never fires, and the claimant stays paused with the stamp
/// held for the rest of the session (every other screen locked out with it). `id` is what makes
/// every failure a distinct value; `claim` is WHOSE load failed, without which an unstamped
/// claimant cannot tell its own failure from another screen's.
nonisolated struct CastLoadFailure: Sendable, Equatable {
    /// The receiver's name for `cast_error_format`; nil when there is no session left to name one.
    /// A nameless failure still has to be published -- it is what releases the claim and the pause.
    var device: String?
    /// The load's own claim, or nil for a report whose claim was already cleared (a session that
    /// began while the load was in flight).
    var claim: CastClaim?
    let id = UUID()
}

/// Everything the cast decision reads, from all three of its owners: this screen's claim and pause
/// (`PlayerViewModel`), what it plays now (`args`), and the controller's session/stamp/loaded claim.
nonisolated struct CastOwnershipState: Sendable, Equatable {
    /// The video THIS screen claimed the session for; nil when it holds no claim.
    var claimedVideoId: String?
    /// The video it plays NOW. `swapArgs` moves this; a claim never follows on its own.
    var videoId: String
    var isOfflinePlayback: Bool
    var sessionActive: Bool
    /// THIS screen's identity (`PlayerViewModel.castOwner`), which is what makes the two claims
    /// below answerable at all: "is this ours?" is a question about a screen, not about a video.
    var owner: UUID
    /// `CastController.castingClaim`: the ONE screen this session belongs to.
    var stamp: CastClaim?
    /// `CastController.loadedClaim`: what the receiver was asked to play, and who put it there.
    var loaded: CastClaim?
    /// `CastController.lastLoadFailure`'s claim: whose load the receiver (or the start path) most
    /// recently refused. A failure is announced app-wide, so this is what stops a screen acting on
    /// one that was never its own.
    var failure: CastClaim?
    /// Set by a `.dropClaim` that fired while THIS screen was paused for a cast that never
    /// reached the receiver AND was not on screen -- there is no route back from a hidden tab
    /// (playing there is the audio bug `.dropClaim` itself exists to avoid), so nothing resumes it
    /// until the screen is actually looked at. Consumed by the next `.appear` (`.resume`, below);
    /// recomputed fresh by every `.dropClaim`, so a drop that was never paused (nothing castable,
    /// before `pauseForCast()` ever ran) correctly leaves it false.
    var droppedWhilePaused: Bool = false
    /// Whether this screen is the one on screen: `.appear` sets it, `.disappear` clears it. Its own
    /// field rather than an inference from WHICH trigger fired, because the trigger that discovers a
    /// drop is not always `.appear` -- a still-visible screen's `.sessionChanged`, or its own
    /// `.appear` arriving before its `.onChange(isSessionActive)` did, both decide one while the
    /// user is looking at it, and a screen that can be resumed now must not wait for a further
    /// visibility cycle to notice. False until the first `.appear`: the safe direction is "stays
    /// paused", never "plays on a tab nobody can see".
    var isVisible: Bool = false

    /// Ours, meaning THIS screen's claim on `videoId` -- never merely "the same video", which is
    /// what let a second screen on the same video act on the first screen's session.
    func isOurs(_ claim: CastClaim?, _ videoId: String) -> Bool {
        claim == CastClaim(videoId: videoId, owner: owner)
    }
}

/// The one thing a reaction can be owed. Each carries the id it acts on, because acting on the
/// CURRENT id where the CLAIMED one was meant (or the reverse) is the shape of every ownership bug
/// this feature has had: a release keyed on `args.videoId` freed nothing after an advance, and a
/// hand-back keyed on it seeked the wrong video to the wrong position.
nonisolated enum CastAction: Sendable, Equatable {
    case none
    /// Claim, get the stream, pause the phone, load the receiver.
    case startCast(videoId: String)
    /// The receiver is ALREADY playing this video: take the stamp (with OUR owner), pause the
    /// phone, load nothing. The predecessor's owner UUID dies with its view model, so "is it ours?"
    /// cannot be the question here; "is it this video?" is.
    case adopt(videoId: String)
    /// Let the claim and the pause go: nothing of ours is on the receiver (or ever reached it), so
    /// there is no position to hand back. `resume` says whether the phone may keep playing —  true
    /// only when the screen deciding this is the one on screen, because playing on a mounted tab
    /// the user cannot see is the audio bug this arm exists to avoid. A `false` here is what arms
    /// `droppedWhilePaused` for the next `.appear` instead.
    case dropClaim(videoId: String, resume: Bool)
    /// Give the stamp back so the next video opened during this session can claim it, keeping the
    /// claim (and the receiver's position) for the return leg.
    case release(videoId: String)
    /// Play the local player a `.dropClaim` left paused with no route back -- no seek (nothing
    /// of ours ever reached the receiver), no claim work (the `.dropClaim` that set the flag this
    /// answers already spent it). No videoId: unlike every other case this acts on no claim at all,
    /// only on whatever `currentPlayer` this screen currently holds.
    case resume
    /// Seek local to the receiver's position if the receiver played our video, resume if we paused,
    /// and drop the claim. `resume` is `.dropClaim`'s rule, on the same terms and for the same
    /// reason (R9-7): the SEEK is unconditional -- a position is state, not audio -- but PLAYING on
    /// a mounted tab the user cannot see is the audio bug both arms exist to avoid, so a hidden
    /// claimant arms `droppedWhilePaused` for its next `.appear` instead.
    case handBack(videoId: String, resume: Bool)
    /// Banner, resume the phone, drop the spent claim. `resume` is the same rule as the two arms
    /// above (fix round 1, I1): a claimless hidden screen still reacts to a receiver RECONNECT
    /// (`.sessionChanged` fires on an opacity-0 rail tab), so it can claim, pause and be refused
    /// without ever being on screen -- and this was the last arm that played the phone anyway.
    case reportFailure(videoId: String, resume: Bool)
}

/// Who owns the cast session, as a pure table. It lives outside the view model on purpose: spread
/// across a view, a model and a controller as five `if`s, the same decision is only ever as right
/// as those three agree. Pure means the whole table is pinned without driving SwiftUI's appearance
/// callbacks or an SDK session.
nonisolated enum CastOwnership {
    /// R7-P1 #1: what a change of the RAIL layout's `\\.tabIsSelected` means for this screen.
    ///
    /// `MainShellView.railStacks` applies the key to the whole `NavigationStack`, not to its
    /// visible top, so it also reaches a screen buried under a push (`PlayerToolbar` pushes another
    /// player; `ShortsOverlay` pushes a channel) -- while `.onAppear`, which drives the very same
    /// reconcile, does not fire there until the push is popped. Left unreconciled, a tab return
    /// re-armed an INVISIBLE claimant: it re-adopted the receiver stamp it had handed back on the
    /// way under, so the video opened from the covering screen could no longer claim the session,
    /// and a session end in that state took `.dropClaim(resume: true)` -- audio from a screen the
    /// user cannot see, the exact bug T0-1 landed to fix.
    ///
    /// So the key says "your tab is selected", never "you are visible", and only a screen that is
    /// also its stack's visible content may act on it. `nil` means there is nothing to reconcile.
    static func railTrigger(tabSelected: Bool, onScreen: Bool) -> CastTrigger? {
        guard onScreen else { return nil }
        return tabSelected ? .appear : .disappear
    }

    static func decide(state: CastOwnershipState, trigger: CastTrigger) -> CastAction {
        guard let claimed = state.claimedVideoId else {
            switch trigger {
            case .videoStarted, .sessionChanged:
                return start(state)
            case .appear:
                // A screen with no claim owns nothing to CAST, but it may still owe itself a
                // RESUME -- a `.dropClaim` that fired while it was paused and off screen left no
                // other route back. `.appear` is the one trigger that means the screen is actually
                // visible, so it is the one that may spend the flag; otherwise unchanged, a player
                // left mounted on another tab must not start one, or switching tabs would take the
                // TV away from the screen the user is actually watching.
                return state.droppedWhilePaused ? .resume : .none
            case .disappear, .loadFailed:
                // Neither means the screen is the one on screen right now, so neither may act on a
                // dropped-while-paused flag -- it stays exactly as `.dropClaim` left it.
                return .none
            }
        }
        switch trigger {
        case .disappear:
            return .release(videoId: claimed)
        case .loadFailed:
            // WHOSE load was refused, first: a failure is announced app-wide, and R9-2's shape is a
            // cast of A still in flight when an Up Next tap moves this screen to B -- A's rejection
            // then landed on B's claimant, which raised a false banner, spent B's claim and left B
            // never loaded. Being stamped says the session is ours; it says nothing about the
            // failure. Whatever the stale claim behind a foreign failure still needs is its own
            // claimant's business.
            guard state.isOurs(state.failure, claimed) else { return .none }
            // The banner belongs to the claimant that is still stamped, or several mounted screens
            // each raise it and each write the device name back.
            if state.isOurs(state.stamp, claimed) {
                return .reportFailure(videoId: claimed, resume: state.isVisible)
            }
            // OUR load was refused while we were off screen (the stamp went back on the way out),
            // so there is no banner to raise on a screen nobody is looking at -- but nothing of
            // ours reached the receiver either, and holding the spent claim and the pause would
            // leave the phone silent and lock every other screen out of the session.
            return .dropClaim(videoId: claimed, resume: state.isVisible)
        case .appear, .sessionChanged, .videoStarted:
            // The session is over: resume whatever we paused and let the claim go. A claim that
            // outlives its session is what stops this screen ever casting this video again.
            // The SEEK half is owed only when the receiver actually played OUR video -- a hidden
            // claimant whose video another screen replaced would otherwise jump to a stranger's
            // timestamp. The RESUME half is the same rule on both arms (R9-7): visible plays now,
            // hidden arms the flag, because starting under another screen's own resume is two
            // videos audible at once.
            // WHO decides the drop no longer decides whether it may resume -- the screen's own
            // tracked visibility does. A session that ended just before this screen's
            // `.onChange(isSessionActive)` ran is discovered by its own `.appear`, and inferring
            // "visible" from the trigger alone left that phone paused until a further
            // appear/disappear cycle.
            guard state.sessionActive else {
                return state.isOurs(state.loaded, claimed)
                    ? .handBack(videoId: claimed, resume: state.isVisible)
                    : .dropClaim(videoId: claimed, resume: state.isVisible)
            }
            // Another screen owns the live session now: not ours to reconcile, and its own claimant
            // pays its own hand-back. Another SCREEN, so a second screen mounted on the same video
            // is a non-owner here exactly like one on any other video.
            guard state.stamp == nil || state.isOurs(state.stamp, claimed) else { return .none }
            return start(state)
        }
    }

    /// Take the live session for the video on screen -- by the cheapest route the receiver allows.
    ///
    /// "Already on the receiver" is a question about the VIDEO, never about the owner: popping a
    /// claimant destroys its view model and its owner UUID with it, so an owner-keyed test makes a
    /// fresh screen re-opened on the video the TV is playing read `loadedClaim` as a stranger's and
    /// reload it at its own 0:00 (and a returning claimant that is no longer paused do the same at
    /// the phone's stale clock). Adopting takes the stamp with our owner, pauses the phone and
    /// loads nothing, so the receiver keeps playing and the hand-back gets its real position.
    private static func start(_ state: CastOwnershipState) -> CastAction {
        guard canStart(state) else { return .none }
        return state.loaded?.videoId == state.videoId
            ? .adopt(videoId: state.videoId) : .startCast(videoId: state.videoId)
    }

    /// Every precondition for taking (or keeping) the session: a live session, a stream a receiver
    /// could actually fetch (a saved sandbox file never is -- it is also why the offline player
    /// hides its cast slot), and a stamp that is free or already this video's.
    private static func canStart(_ state: CastOwnershipState) -> Bool {
        guard state.sessionActive, !state.isOfflinePlayback else { return false }
        return state.stamp == nil || state.isOurs(state.stamp, state.videoId)
    }
}
