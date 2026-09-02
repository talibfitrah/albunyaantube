import AVFoundation
import Foundation
import Testing
@testable import FitrahTube

/// Task 8 fix round 1: the cast session is app-wide but the player it drives is not.
/// `CastController` publishes one `isSessionActive`, and `MainShellView` keeps every visited tab's
/// stack mounted — so without a cast-source stamp, a hand-back seeks and force-plays whichever
/// `PlayerScreen` happens to still be alive (review Important 1), including an offline one whose
/// cast slot is hidden by design.
///
/// The three `sessionDid*` seams below are what the `GCKSessionManagerListener` callbacks call:
/// `GCKSessionManager` has an unavailable `init` and `GCKSession` is abstract, so the callbacks
/// themselves cannot be driven from a test — the seams carry every decision they make.
@Suite(.perTest)
struct CastSessionTests {

    // MARK: - Important 1: one screen owns the session

    @Test func onlyTheFirstScreenToClaimOwnsTheCastSession() {
        let cast = CastController()
        #expect(cast.claimCastSource(videoId: "xc7keR2piUM", owner: Self.owner))
        #expect(cast.castingVideoId == "xc7keR2piUM")
        // A second mounted PlayerScreen reacting to the same session flag must NOT resolve or load.
        #expect(cast.claimCastSource(videoId: "other-video", owner: Self.otherOwner) == false)
        #expect(cast.castingVideoId == "xc7keR2piUM")
        // Idempotent for the owner: a resumed session re-claims without losing the stamp.
        #expect(cast.claimCastSource(videoId: "xc7keR2piUM", owner: Self.owner))
    }

    /// AC-P2-1: the stamp names a SCREEN, not a video. `MainShellView` keeps every visited tab's
    /// stack mounted, so a Home stack and a Search stack can both sit on video X — and a
    /// videoId-only stamp read as theirs to both, so both paused for the cast, both loaded the
    /// receiver and both played on hand-back (double audio). A same-video screen with a different
    /// owner is a non-owner like any other, and the owner's own re-claim still succeeds.
    @Test func aSecondScreenOnTheSameVideoIsStillANonOwner() {
        let cast = CastController()
        // A LIVE session, or `stillCasting` short-circuits on `isSessionActive` and the owner
        // comparison below it never runs.
        cast.sessionDidBegin(deviceName: "Living Room TV")
        #expect(cast.claimCastSource(videoId: Self.claimed, owner: Self.owner))
        #expect(cast.claimCastSource(videoId: Self.claimed, owner: Self.otherOwner) == false,
                "the same video from another screen is another screen")
        #expect(cast.stillCasting(Self.claimed, owner: Self.owner), "the session is live and ours")
        #expect(cast.stillCasting(Self.claimed, owner: Self.otherOwner) == false)
        // Nor may it release, finish or spend anything of the owner's.
        cast.recordLoad(Self.claimed, owner: Self.owner)
        cast.sessionWillEnd(position: 300)
        #expect(cast.receiverPosition(for: Self.claimed, owner: Self.otherOwner) == nil,
                "only the screen whose load the receiver played may have its position")
        cast.releaseClaim(Self.claimed, owner: Self.otherOwner)
        #expect(cast.castingVideoId == Self.claimed)
        cast.finishClaim(Self.claimed, owner: Self.otherOwner)
        #expect(cast.castingVideoId == Self.claimed)
        #expect(cast.loadedVideoId == Self.claimed)
        #expect(cast.receiverPosition(for: Self.claimed, owner: Self.owner) == 300)
        // The owner's own off-screen release and re-appear re-claim still work.
        cast.releaseClaim(Self.claimed, owner: Self.owner)
        #expect(cast.castingVideoId == nil)
        #expect(cast.claimCastSource(videoId: Self.claimed, owner: Self.owner))
    }

    @Test func theStampSurvivesSessionEndSoTheHandBackFindsItsOwner() {
        let cast = CastController()
        _ = cast.claimCastSource(videoId: "xc7keR2piUM", owner: Self.owner)
        cast.recordLoad("xc7keR2piUM", owner: Self.owner)
        cast.sessionWillEnd(position: 120)
        cast.sessionDidEnd()
        #expect(cast.isSessionActive == false)
        // Still stamped: the end reaction is what reads it, and `.onChange` runs after the callback.
        #expect(cast.castingVideoId == "xc7keR2piUM")
        #expect(cast.lastStreamPosition == 120)
        cast.finishClaim("xc7keR2piUM", owner: Self.owner)
        #expect(cast.castingVideoId == nil)
        #expect(cast.lastStreamPosition == nil)
        #expect(cast.loadedVideoId == nil)
    }

    /// `finishClaim` gives back only what is this screen's to give. A claimant handing back while
    /// ANOTHER screen's video is on the receiver used to have two shapes — a stamp-only release on
    /// the return leg (which left the spent position behind) and a clear-everything call on the
    /// session-end arm (which cleared a position that was not its to clear).
    @Test func aHandBackSpendsOnlyTheFieldsThatBelongToTheHandingBackScreen() {
        let cast = CastController()
        cast.sessionDidBegin(deviceName: "Living Room TV")
        _ = cast.claimCastSource(videoId: "xc7keR2piUM", owner: Self.owner)
        // another screen's video is what plays there
        cast.recordLoad("other-video", owner: Self.otherOwner)
        cast.sessionWillEnd(position: 300)

        cast.finishClaim("xc7keR2piUM", owner: Self.owner)
        #expect(cast.castingVideoId == nil, "our stamp goes")
        #expect(cast.loadedVideoId == "other-video", "the receiver's video is not ours to forget")
        #expect(cast.lastStreamPosition == 300, "nor is the position that belongs to it")

        // And a screen that holds neither takes nothing.
        cast.finishClaim("no-such-video", owner: Self.owner)
        #expect(cast.loadedVideoId == "other-video")
        #expect(cast.lastStreamPosition == 300)
    }

    // MARK: - Important 2: a new session never carries the previous one's position

    @Test func aNewSessionClearsThePreviousSessionsPositionAndStamp() {
        let cast = CastController()
        _ = cast.claimCastSource(videoId: "xc7keR2piUM", owner: Self.owner)
        cast.sessionWillEnd(position: 120)
        cast.sessionDidEnd()
        #expect(cast.lastStreamPosition == 120)

        cast.sessionDidBegin(deviceName: "Living Room TV")
        #expect(cast.lastStreamPosition == nil, "a new session must not inherit the old position")
        #expect(cast.castingVideoId == nil, "a new session must not inherit the old cast source")
        #expect(cast.isSessionActive)
        #expect(cast.connectedDeviceName == "Living Room TV")

        cast.sessionDidEnd()
        #expect(cast.connectedDeviceName == nil)
    }

    // MARK: - Important 1 + 4: only the screen that paused hands back / resumes

    private func makeViewModel() -> PlayerViewModel {
        let settings = UserDefaultsSettingsStore(
            defaults: UserDefaults(suiteName: "CastSessionTests.\(UUID().uuidString)")!)
        return PlayerViewModel(resolver: RecordingResolver(.hls), settings: settings,
                               args: PlayerArgs(videoId: "xc7keR2piUM"))
    }

    /// RULING (re-review Minor 6): the SEEK is unconditional for the claimant — spec §10's
    /// hand-back is seek + resume, and a screen reopened on the same video mid-cast must still
    /// land where the TV got to even though its fresh view model never paused anything. Only the
    /// RESUME stays gated on having paused, and `pausedForCast` (unspent here) is that gate.
    @Test func aScreenThatNeverPausedForCastIsSeekedButNotResumed() {
        let model = makeViewModel()
        model.currentPlayer = AVPlayer()
        model.currentTime = 7
        model.resumeAfterCast(at: 120)
        #expect(model.currentTime == 120, "the claimant's hand-back must apply the receiver's position")
        #expect(model.pausedForCast == false, "nothing was paused, so nothing may be resumed")
    }

    @Test func theScreenThatPausedForCastResumesAtTheReceiversPosition() {
        let model = makeViewModel()
        model.currentPlayer = AVPlayer()
        model.pauseForCast()
        #expect(model.pausedForCast)
        model.resumeAfterCast(at: 120)
        #expect(model.currentTime == 120)
        // The pause is spent: the resume happened exactly once.
        #expect(model.pausedForCast == false)
    }

    /// `startCast` pauses the local player before the load, so a receiver that
    /// REJECTS the load must leave the phone playing — the code's own comment promises it.
    @Test func aRejectedLoadResumesTheLocalPlayerItPaused() {
        let model = makeViewModel()
        model.currentPlayer = AVPlayer()
        model.currentTime = 42
        model.pauseForCast()
        // The failure path hands back with no receiver position: keep playing where we were.
        model.resumeAfterCast(at: nil)
        #expect(model.currentTime == 42, "the failure hand-back must not move the position")
        #expect(model.pausedForCast == false, "the pause must be spent by the failure hand-back")
    }

    // MARK: - Fix round 2

    /// Re-review Important 1: `GCKRequest.cancel()` delivers `.cancelled` back through
    /// `request(_:didAbortWith:)`, so `cancelLoadRequest()` fed its own abort into the failure
    /// handler — every second load raised "Couldn't play on {TV}" for a request the app cancelled
    /// and (via the Important-4 resume) restarted the phone mid-load. Both guards are needed: a
    /// synchronous abort still looks like the current request, an asynchronous one lands after the
    /// new request is stored.
    ///
    /// Re-review Minor 1: the IDENTITY guard used to sit in `finishLoad` ahead of the pure helper,
    /// so no test could ever see it answer false — deleting it left `loadRequest = nil` running for
    /// a stale callback (nil-ing the NEW request's only strong reference, since `GCKRequest.delegate`
    /// is weak) with every test still green. One pure decision over the ids covers both guards.
    @Test func aLoadCallbackReportsOnlyForTheCurrentRequestAndNeverForOurOwnCancel() {
        // The current request: a genuine failure is the user's business, our own cancel is not,
        // and a success just retires it.
        #expect(CastController.loadCallbackOutcome(callbackID: 7, currentID: 7, reportFailure: true,
                                                   cancelledByUs: false) == .clearAndReport)
        #expect(CastController.loadCallbackOutcome(callbackID: 7, currentID: 7, reportFailure: true,
                                                   cancelledByUs: true) == .clear)
        #expect(CastController.loadCallbackOutcome(callbackID: 7, currentID: 7, reportFailure: false,
                                                   cancelledByUs: false) == .clear)
        // A callback about an OLDER request must touch nothing at all — not the banner, and above
        // all not the stored request, which by now belongs to the newer load.
        for (report, cancelled) in [(true, false), (true, true), (false, false)] {
            #expect(CastController.loadCallbackOutcome(callbackID: 6, currentID: 7, reportFailure: report,
                                                       cancelledByUs: cancelled) == .ignore)
        }
        #expect(CastController.loadCallbackOutcome(callbackID: 6, currentID: nil, reportFailure: true,
                                                   cancelledByUs: false) == .ignore)
    }

    // MARK: - Cubic round 2

    /// R2-5: casting was driven solely by `.onChange(of: isSessionActive)`, which fires only on
    /// TRANSITIONS — a screen that mounts with the session already up never cast (phone plays
    /// locally, the TV keeps the old video), and the popped claimant's stale stamp meant it could
    /// not even claim. `claimForCast` is the one start decision both the transition and the mount
    /// run; `releaseClaim` is what the leaving screen gives back.
    @Test func aVideoOpenedDuringALiveSessionClaimsItOnceTheOldClaimantIsGone() {
        let cast = CastController()
        cast.sessionDidBegin(deviceName: "Living Room TV")
        #expect(cast.claimForCast(videoId: "xc7keR2piUM", owner: Self.owner, isOfflinePlayback: false))
        // Fix round 1's Important 1 still holds: a second mounted screen does not steal the session.
        #expect(cast.claimForCast(videoId: "other-video", owner: Self.otherOwner,
                                  isOfflinePlayback: false) == false)
        // The claimant goes away: its claim goes with it, and the next screen mounts and casts.
        cast.releaseClaim("xc7keR2piUM", owner: Self.owner)
        #expect(cast.castingVideoId == nil)
        #expect(cast.claimForCast(videoId: "other-video", owner: Self.otherOwner, isOfflinePlayback: false))
    }

    /// m1 survives the new funnel: an offline player never starts, claims or loads a cast — and
    /// with no live session there is nothing to start at all.
    @Test func anOfflinePlayerNeverClaimsALiveSession() {
        let cast = CastController()
        cast.sessionDidBegin(deviceName: "Living Room TV")
        #expect(cast.claimForCast(videoId: "xc7keR2piUM", owner: Self.owner,
                                  isOfflinePlayback: true) == false)
        #expect(cast.castingVideoId == nil, "an offline screen must not even take the stamp")

        cast.sessionDidEnd()
        #expect(cast.claimForCast(videoId: "xc7keR2piUM", owner: Self.owner,
                                  isOfflinePlayback: false) == false)
    }

    /// The release half of R2-5: only the screen that OWNS the stamp may drop it, or a second
    /// mounted `PlayerScreen` disappearing would hand the session away from the real claimant.
    @Test func aScreenThatNeverClaimedCannotReleaseAnotherScreensSession() {
        let cast = CastController()
        cast.sessionDidBegin(deviceName: "Living Room TV")
        #expect(cast.claimForCast(videoId: "xc7keR2piUM", owner: Self.owner, isOfflinePlayback: false))
        cast.releaseClaim("other-video", owner: Self.otherOwner)
        #expect(cast.castingVideoId == "xc7keR2piUM")
    }

    // MARK: - Part B fix round 1

    /// Important 1(a): the release was keyed on `model.args.videoId`, which `swapArgs` replaces on
    /// every auto-advance / Up Next tap / auto-skip, while the stamp keeps the id that was actually
    /// claimed. So after ONE advance during a session the leaving screen released nothing, the
    /// session-end arm missed for the same reason, and `sessionDidEnd()` deliberately preserves the
    /// stamp — every later video opened during that session then failed to claim, which is R2-5's
    /// exact symptom. The screen holds the claimed id and releases exactly that; this pins the
    /// contract it has to honour.
    @Test func onlyTheClaimedIdReleasesTheClaimNotTheVideoTheScreenAdvancedTo() {
        let cast = CastController()
        cast.sessionDidBegin(deviceName: "Living Room TV")
        #expect(cast.claimForCast(videoId: "xc7keR2piUM", owner: Self.owner, isOfflinePlayback: false))

        // The queue advanced: the screen plays another video now, the stamp still names the claim.
        cast.releaseClaim("other-video", owner: Self.owner)
        #expect(cast.castingVideoId == "xc7keR2piUM",
                "an advanced-to id must never release a claim taken for another video")

        cast.releaseClaim("xc7keR2piUM", owner: Self.owner)
        #expect(cast.castingVideoId == nil)
        #expect(cast.claimForCast(videoId: "other-video", owner: Self.owner, isOfflinePlayback: false),
                "the next video opened during the same session must be able to claim it")
    }

    /// Important 2: `onDisappear` is a WENT-OFF-SCREEN seam, not a teardown one — a push-over and a
    /// compact-layout tab switch both fire it on a screen that is still alive and still paused for
    /// its cast. Surrendering the stamp is right (the next video must be able to claim), but
    /// discarding `lastStreamPosition` with it threw away the receiver's position the hand-back
    /// needs, leaving the phone paused at the pre-cast position. The release gives back the STAMP
    /// only; `finishClaim(_:)` is what consumes both.
    @Test func anOffScreenReleaseGivesBackTheStampButKeepsTheReceiversPosition() {
        let cast = CastController()
        cast.sessionDidBegin(deviceName: "Living Room TV")
        #expect(cast.claimForCast(videoId: "xc7keR2piUM", owner: Self.owner, isOfflinePlayback: false))
        cast.sessionWillEnd(position: 300)

        cast.releaseClaim("xc7keR2piUM", owner: Self.owner)
        #expect(cast.castingVideoId == nil)
        #expect(cast.lastStreamPosition == 300, "the hand-back still needs the receiver's position")
    }

    // MARK: - R4-6: the ownership table (the old `returnAction` rows fold in here)

    private static let claimed = "xc7keR2piUM"
    private static let other = "other-video"
    /// AC-P2-1: this screen, and a SECOND `PlayerScreen` — which `MainShellView`'s mounted stacks
    /// make possible on the same video, and which the videoId-only stamp could not tell apart.
    private static let owner = UUID()
    private static let otherOwner = UUID()

    /// Every input the decision reads, defaulted to "this screen claimed the live session, the
    /// receiver is playing its video, the phone is paused for it". `failure` defaults to NO
    /// outstanding load failure, which is the state every row that is not about one is in.
    private func ownership(claim: String? = "xc7keR2piUM", video: String = "xc7keR2piUM",
                           offline: Bool = false, paused: Bool = true, sessionActive: Bool = true,
                           stamp: String? = "xc7keR2piUM", stampOwner: UUID = CastSessionTests.owner,
                           loaded: String? = "xc7keR2piUM",
                           loadedOwner: UUID = CastSessionTests.owner,
                           failure: String? = nil,
                           failureOwner: UUID = CastSessionTests.owner) -> CastOwnershipState {
        CastOwnershipState(claimedVideoId: claim, videoId: video, isOfflinePlayback: offline,
                           pausedForCast: paused, sessionActive: sessionActive, owner: Self.owner,
                           stamp: stamp.map { CastClaim(videoId: $0, owner: stampOwner) },
                           loaded: loaded.map { CastClaim(videoId: $0, owner: loadedOwner) },
                           failure: failure.map { CastClaim(videoId: $0, owner: failureOwner) })
    }

    /// A screen with no claim owns nothing, so only the two triggers that MEAN "there is something
    /// new to cast" can start one. `.appear` deliberately cannot: a player left mounted on another
    /// tab would otherwise take the TV away from the screen the user is actually watching, the
    /// moment they switch tabs.
    @Test func aScreenWithNoClaimOnlyEverStartsAndNeverOnAMereAppearance() {
        for trigger in [CastTrigger.videoStarted, .sessionChanged] {
            #expect(CastOwnership.decide(state: ownership(claim: nil, stamp: nil, loaded: nil),
                                         trigger: trigger) == .startCast(videoId: Self.claimed))
            // Every precondition, one at a time: no session, an offline player, someone else's stamp.
            #expect(CastOwnership.decide(state: ownership(claim: nil, sessionActive: false,
                                                          stamp: nil, loaded: nil),
                                         trigger: trigger) == .none)
            #expect(CastOwnership.decide(state: ownership(claim: nil, offline: true, stamp: nil, loaded: nil),
                                         trigger: trigger) == .none)
            #expect(CastOwnership.decide(state: ownership(claim: nil, stamp: Self.other, loaded: Self.other),
                                         trigger: trigger) == .none)
            // R7-9: unless the receiver is ALREADY playing this video — whoever put it there. The
            // popped claimant's owner UUID died with its view model, so "is it ours?" cannot be the
            // question here; a reload would restart the TV at this fresh screen's 0:00.
            #expect(CastOwnership.decide(state: ownership(claim: nil, stamp: nil,
                                                          loadedOwner: Self.otherOwner),
                                         trigger: trigger) == .adopt(videoId: Self.claimed))
        }
        for trigger in [CastTrigger.appear, .disappear, .loadFailed] {
            #expect(CastOwnership.decide(state: ownership(claim: nil, stamp: nil, loaded: nil),
                                         trigger: trigger) == .none)
        }
    }

    /// What a claimant coming back on screen does — the old `returnAction` table, plus the R4-5 row
    /// it got wrong. Adopting costs no re-resolve and no second `load()`; a session that ended
    /// while the screen was away still owes it the hand-back its `.onChange` arm missed (the stamp
    /// was gone); and a LIVE session the receiver is not playing our video on must be cast into,
    /// never quietly resumed on the phone.
    @Test func aClaimantAdoptsReCastsOrHandsBackButNeverJustResumes() {
        for trigger in [CastTrigger.appear, .sessionChanged, .videoStarted] {
            // Still casting OUR video and still paused for it, stamp free: take it back, no more.
            #expect(CastOwnership.decide(state: ownership(stamp: nil), trigger: trigger)
                    == .adopt(videoId: Self.claimed))
            // R4-5: live session, free stamp — but the receiver is playing something else (another
            // screen cast and popped, or the session churned while we were away). Resuming locally
            // there leaves the phone playing A audibly while the TV plays B and the cast button
            // offers only disconnect. Cast this video instead.
            #expect(CastOwnership.decide(state: ownership(stamp: nil, loaded: Self.other), trigger: trigger)
                    == .startCast(videoId: Self.claimed))
            #expect(CastOwnership.decide(state: ownership(stamp: nil, loaded: nil), trigger: trigger)
                    == .startCast(videoId: Self.claimed))
            // R7-15: nothing is paused, but the receiver IS still playing our video. Re-casting it
            // hands `load(at: currentTime)` the phone's own stale clock and jumps the TV back to
            // wherever the phone stopped — the receiver's mid-session position is never sampled.
            // Adopting pauses the phone under a TV that is already playing the right thing.
            #expect(CastOwnership.decide(state: ownership(paused: false, stamp: nil), trigger: trigger)
                    == .adopt(videoId: Self.claimed))
            // Another screen claimed the live session while we were away: not ours to reconcile,
            // and its own claimant is the one that pays its hand-back (one screen owns a session).
            for paused in [true, false] {
                for loaded in [Self.claimed, Self.other, nil] {
                    #expect(CastOwnership.decide(state: ownership(paused: paused, stamp: Self.other,
                                                                  loaded: loaded),
                                                 trigger: trigger) == .none)
                }
            }
            // The session ended while we were off screen: hand back now, or the phone stays paused
            // at the pre-cast position with the receiver's position thrown away. Whoever holds the
            // stamp by then is irrelevant — our player is the one that owes a resume. Never paused
            // and the session gone is the same arm: nothing to resume, but the stale claim still
            // has to go, or this screen can never cast this video again. WHICH position it resumes
            // at is `receiverPosition(for:)`'s decision, not this one's.
            for paused in [true, false] {
                for stamp in [Self.claimed, Self.other, nil] {
                    #expect(CastOwnership.decide(state: ownership(paused: paused, sessionActive: false,
                                                                  stamp: stamp),
                                                 trigger: trigger) == .handBack(videoId: Self.claimed))
                }
            }
            // R7-8: but ONLY when the receiver was playing OUR video. A casts X and tab-switches, B
            // casts Y, the receiver disconnects — A's hand-back gets a nil position and would still
            // `play()` on a tab nobody is looking at, under B's own resume. Let the claim and the
            // pause go, silently.
            for loaded in [Self.other, nil] {
                #expect(CastOwnership.decide(state: ownership(sessionActive: false, stamp: nil,
                                                              loaded: loaded),
                                             trigger: trigger) == .dropClaim(videoId: Self.claimed))
            }
            #expect(CastOwnership.decide(state: ownership(sessionActive: false, stamp: nil,
                                                          loadedOwner: Self.otherOwner),
                                         trigger: trigger) == .dropClaim(videoId: Self.claimed),
                    "the same video from another screen is another screen's load")
        }
    }

    /// The two claimant triggers that are not a reconcile. The release is the STAMP only and is
    /// keyed on the CLAIMED id — an advanced-to id released nothing, so the stamp stuck for the
    /// rest of the session — and the banner belongs to the claimant that is still stamped, or
    /// several mounted screens each raise it and each write the device name back.
    ///
    /// R7-3(b): an unstamped claimant is not silent about its OWN failure. It has no banner to
    /// raise (nobody is looking at it) but it must not keep the claim or the pause: that is what
    /// left the phone silent and every other screen locked out for the rest of the session.
    @Test func offScreenReleasesTheStampAndAFailureIsActedOnByTheClaimItBelongsTo() {
        #expect(CastOwnership.decide(state: ownership(video: Self.other), trigger: .disappear)
                == .release(videoId: Self.claimed))
        #expect(CastOwnership.decide(state: ownership(sessionActive: false), trigger: .disappear)
                == .release(videoId: Self.claimed))
        #expect(CastOwnership.decide(state: ownership(loaded: nil, failure: Self.claimed),
                                     trigger: .loadFailed) == .reportFailure(videoId: Self.claimed))
        #expect(CastOwnership.decide(state: ownership(stamp: Self.other, failure: Self.claimed),
                                     trigger: .loadFailed) == .dropClaim(videoId: Self.claimed))
        #expect(CastOwnership.decide(state: ownership(stamp: nil, failure: Self.claimed),
                                     trigger: .loadFailed) == .dropClaim(videoId: Self.claimed))
        // Someone else's failure, and a failure with no claim behind it, are nothing of ours.
        #expect(CastOwnership.decide(state: ownership(stamp: nil, failure: Self.other),
                                     trigger: .loadFailed) == .none)
        #expect(CastOwnership.decide(state: ownership(stamp: nil, failure: Self.claimed,
                                                      failureOwner: Self.otherOwner),
                                     trigger: .loadFailed) == .none)
        #expect(CastOwnership.decide(state: ownership(stamp: nil), trigger: .loadFailed) == .none)
        // R7-3(c): an unconsumed failure — anyone's — gates no OTHER screen's start.
        #expect(CastOwnership.decide(state: ownership(claim: nil, stamp: nil, loaded: nil,
                                                      failure: Self.other, failureOwner: Self.otherOwner),
                                     trigger: .videoStarted) == .startCast(videoId: Self.claimed))
    }

    /// AC-P2-1 at the table: a second `PlayerScreen` on the SAME video is a non-owner on every
    /// trigger. Keyed on the videoId alone this screen read the other's stamp as its own — with no
    /// claim it started its own cast on `.videoStarted`/`.sessionChanged` (two players paused, two
    /// loads, double audio on hand-back), and with one it reclaimed and banner'd for a session that
    /// was never its.
    @Test func aSecondScreenOnTheSameVideoDecidesNothingOnAnyTrigger() {
        for trigger in CastTrigger.allCases {
            #expect(CastOwnership.decide(state: ownership(claim: nil, stampOwner: Self.otherOwner,
                                                          loadedOwner: Self.otherOwner),
                                         trigger: trigger) == .none,
                    "\(trigger) let a same-video screen act on another screen's session")
        }
        // The same screen once it holds a claim of its own — the superseded owner, whose session
        // ended and whose successor claimed the new one. `.disappear` is excluded: releasing an id
        // it does not own is already a no-op at the controller.
        for trigger in [CastTrigger.appear, .sessionChanged, .videoStarted, .loadFailed] {
            #expect(CastOwnership.decide(state: ownership(stampOwner: Self.otherOwner,
                                                          loadedOwner: Self.otherOwner),
                                         trigger: trigger) == .none,
                    "\(trigger) let a superseded owner reclaim the same video")
        }
    }

    /// An offline screen is refused at the one gate every start goes through, on every trigger —
    /// a sandbox file is never castable and its cast slot is hidden for the same reason.
    @Test func anOfflineScreenIsNeverStartedByAnyTrigger() {
        for trigger in CastTrigger.allCases {
            let action = CastOwnership.decide(state: ownership(claim: nil, offline: true,
                                                               stamp: nil, loaded: nil),
                                              trigger: trigger)
            #expect(action == .none, "\(trigger) started a cast for an offline player")
        }
    }

    /// Minor 1: with one mini controller per representable (R2-9), a tab switch has two alive at
    /// once and their create/teardown order is exactly what R2-9 calls undefined — so a
    /// `shouldAppear: false` from the OUTGOING controller would land on the flag the incoming one
    /// is being shown under, relocating the blank-strip symptom from view parenting to the
    /// delegate. Only the controller we last handed out is trusted.
    @Test func aStaleMiniControlsCallbackCannotClearTheLiveStripsFlag() {
        let cast = CastController()
        cast.miniMediaControlsViewControllerDidChangeActive(true)
        #expect(cast.miniControlsActive)

        cast.miniMediaControlsViewControllerDidChangeActive(false, from: ObjectIdentifier(NSObject()))
        #expect(cast.miniControlsActive,
                "an outgoing tab's controller must not clear the incoming one's flag")
    }

    /// R2-8: `startCast` can await a network walk before it pauses the local player. If the
    /// session ended inside that window the end reaction already ran (`pausedForCast` was false,
    /// so nothing resumes) and the hand-back cleared the stamp, `load()` finds no session and
    /// `reportLoadFailure()` has no device to name — leaving the phone paused with no banner and
    /// nothing to undo it. The pause must not happen at all.
    @Test func aSessionEndingDuringTheResolveStopsTheCastBeforeThePause() {
        let cast = CastController()
        cast.sessionDidBegin(deviceName: "Living Room TV")
        #expect(cast.claimForCast(videoId: "xc7keR2piUM", owner: Self.owner, isOfflinePlayback: false))
        #expect(cast.stillCasting("xc7keR2piUM", owner: Self.owner))

        cast.sessionWillEnd(position: 90)
        cast.sessionDidEnd()
        #expect(cast.stillCasting("xc7keR2piUM", owner: Self.owner) == false,
                "a dead session must not be pause-and-loaded")

        // The other way the cast stops being ours inside that window: the queue advanced, so the
        // stamp names a video this screen no longer plays.
        let advanced = CastController()
        advanced.sessionDidBegin(deviceName: "Living Room TV")
        #expect(advanced.claimForCast(videoId: "xc7keR2piUM", owner: Self.owner, isOfflinePlayback: false))
        #expect(advanced.stillCasting("other-video", owner: Self.owner) == false)
    }

    /// Re-review Minor 2: a failure that landed with no claimant mounted is never consumed, and
    /// `.onChange` does not fire twice for the same device name — so without this the NEXT failure
    /// on that receiver would be silent.
    @Test func aNewSessionDropsAnUnreadLoadFailure() {
        let cast = CastController()
        cast.lastLoadFailure = CastLoadFailure(device: "Living Room TV", claim: nil)
        cast.sessionDidBegin(deviceName: "Living Room TV")
        #expect(cast.lastLoadFailure == nil)
    }

    /// R7-3(a): `PlayerScreen` watches this with `.onChange`, which compares VALUES — so a second
    /// refusal by the same receiver wrote the same device name, never fired, and left its claimant
    /// paused with the stamp held (and every other screen locked out) until the session ended.
    @Test func everyLoadFailureIsADistinctValueSoASecondRefusalStillFires() {
        let cast = CastController()
        cast.sessionDidBegin(deviceName: "Living Room TV")
        let claim = CastClaim(videoId: Self.claimed, owner: Self.owner)
        cast.reportLoadFailure(claim: claim)
        let first = cast.lastLoadFailure
        #expect(first?.claim == claim, "a failure has to say whose load it was")
        cast.reportLoadFailure(claim: claim)
        #expect(cast.lastLoadFailure != nil)
        #expect(cast.lastLoadFailure != first,
                "an equal value is a failure `.onChange` never delivers")
    }

    /// R7-17: `GCKDevice.friendlyName` is nullable and empty on some receivers, so a rejected load
    /// on an unnamed device set nothing at all — no banner, and (before R7-3) no release either.
    /// "Chromecast" is the product name, not translatable copy: no new string key.
    @Test func anUnnamedReceiverStillHasSomethingToCallItself() {
        #expect(CastController.deviceLabel(friendlyName: "Living Room TV",
                                           modelName: "Chromecast Ultra") == "Living Room TV")
        #expect(CastController.deviceLabel(friendlyName: "", modelName: "Chromecast Ultra")
                == "Chromecast Ultra")
        #expect(CastController.deviceLabel(friendlyName: nil, modelName: "Chromecast Ultra")
                == "Chromecast Ultra")
        #expect(CastController.deviceLabel(friendlyName: "", modelName: "") == "Chromecast")
        #expect(CastController.deviceLabel(friendlyName: nil, modelName: nil) == "Chromecast")
    }

    /// Re-review Important 2, the stuck-visible direction: nothing else clears `miniControlsActive`,
    /// so a strip left above the tab bar after the session ends would be as SDK-dependent as one
    /// that never appears.
    @Test func sessionEndClearsTheMiniControlsFlag() {
        let cast = CastController()
        cast.miniMediaControlsViewControllerDidChangeActive(true)
        #expect(cast.miniControlsActive)
        cast.sessionDidEnd()
        #expect(cast.miniControlsActive == false)
    }

    // MARK: - Backgrounding, and the video the receiver actually plays

    /// `GCKCastOptions.suspendSessionsWhenBackgrounded` defaults to YES (`GCKCastOptions.h:92-100`),
    /// so every Home-press and return during a cast SUSPENDS and RESUMES the same session. Routing
    /// the resume through the session-START seam nilled the claim, the receiver's position and an
    /// unread load failure on a session that never went anywhere — so the later disconnect found no
    /// stamp and the phone stayed paused with no seek. A resumed session inherits everything; only
    /// a genuinely new one starts clean.
    @Test func aResumedSessionKeepsTheClaimThePositionAndAnUnreadFailure() {
        let cast = CastController()
        cast.sessionDidBegin(deviceName: "Living Room TV")
        #expect(cast.claimForCast(videoId: "xc7keR2piUM", owner: Self.owner, isOfflinePlayback: false))
        cast.recordLoad("xc7keR2piUM", owner: Self.owner)
        // `willEndSession` is the only thing that ever stamps a position, so this is how a test
        // gets one onto a session it is about to suspend.
        cast.sessionWillEnd(position: 240)
        cast.lastLoadFailure = CastLoadFailure(device: "Living Room TV", claim: nil)

        cast.sessionDidResume(deviceName: "Living Room TV")
        #expect(cast.isSessionActive)
        #expect(cast.connectedDeviceName == "Living Room TV")
        #expect(cast.castingVideoId == "xc7keR2piUM", "a resumed session is the same session")
        #expect(cast.loadedVideoId == "xc7keR2piUM", "the receiver kept playing across the suspension")
        #expect(cast.lastStreamPosition == 240)
        #expect(cast.lastLoadFailure?.device == "Living Room TV", "an unread failure is still owed")

        // A genuinely new session still inherits nothing.
        cast.sessionDidBegin(deviceName: "Kitchen TV")
        #expect(cast.castingVideoId == nil)
        #expect(cast.loadedVideoId == nil)
        #expect(cast.lastStreamPosition == nil)
        #expect(cast.lastLoadFailure == nil)
    }

    /// A casts; Up Next pushes B, so A surrenders the stamp; B claims and loads on the receiver;
    /// the user pops B and A comes back to a free stamp. A used to reclaim a session that is
    /// playing B, and the disconnect then seeked A to B's position and played it. The stamp says
    /// who may act; only the LOADED id says what the receiver's position belongs to.
    @Test func aReclaimAndAHandBackNeedTheReceiverToStillPlayOurVideo() {
        let cast = CastController()
        cast.sessionDidBegin(deviceName: "Living Room TV")
        #expect(cast.claimForCast(videoId: "xc7keR2piUM", owner: Self.owner, isOfflinePlayback: false))
        cast.recordLoad("xc7keR2piUM", owner: Self.owner)
        cast.releaseClaim("xc7keR2piUM", owner: Self.owner)
        #expect(cast.claimForCast(videoId: "other-video", owner: Self.owner, isOfflinePlayback: false))
        cast.recordLoad("other-video", owner: Self.owner)
        cast.releaseClaim("other-video", owner: Self.owner)

        // A comes back: the session is live and free, but the receiver is playing B — so A must
        // re-cast, not take the session over and not resume on the phone under a TV playing B.
        #expect(CastOwnership.decide(
            state: CastOwnershipState(claimedVideoId: "xc7keR2piUM", videoId: "xc7keR2piUM",
                                      isOfflinePlayback: false, pausedForCast: true,
                                      sessionActive: cast.isSessionActive, owner: Self.owner,
                                      stamp: cast.castingClaim, loaded: cast.loadedClaim),
            trigger: .appear) == .startCast(videoId: "xc7keR2piUM"))

        cast.sessionWillEnd(position: 512)
        cast.sessionDidEnd()
        #expect(cast.receiverPosition(for: "xc7keR2piUM", owner: Self.owner) == nil,
                "B's receiver position is not A's hand-back")
        #expect(cast.receiverPosition(for: "other-video", owner: Self.owner) == 512)

        // B's own hand-back spends both.
        cast.finishClaim("other-video", owner: Self.owner)
        #expect(cast.loadedVideoId == nil)
        #expect(cast.receiverPosition(for: "other-video", owner: Self.owner) == nil)
    }

    // MARK: - R4-1: the cast resolve is not a second forced walk

    /// `castMedia()` forced a `.player`-lane resolve on every cast, and that lane's 30 s minimum
    /// interval turns any second attempt inside the window into `.cooldown` — a false "Couldn't
    /// play on {TV}" for a URL the phone is playing right now. The receiver fetches from its own
    /// IP either way, so a fresh resolve buys nothing there: cast what is already resolved, and
    /// walk the network only when there is nothing usable or it is about to expire.
    @Test func castReResolvesOnlyWhenTheCurrentStreamIsMissingOrNearExpiry() async {
        for (expiresIn, expectedCalls) in [(3600.0, 1), (120.0, 2)] {
            let resolver = RecordingResolver(.hls)
            resolver.expiresIn = expiresIn
            let vm = makeCastModel(resolver)
            await vm.open()
            #expect(resolver.calls.count == 1)
            let media = await vm.castMedia()
            #expect(media != nil)
            #expect(resolver.calls.count == expectedCalls,
                    "expiring in \(expiresIn)s: expected \(expectedCalls) resolve(s)")
        }
    }

    /// A limiter refusal is not a dead stream. When the re-resolve a near-expiry URL asked for is
    /// turned away, the URL the phone is playing is still the best thing we have — casting it is
    /// strictly better than a banner for a stream that works, AS LONG AS it can still reach the end
    /// of the video (AC-P2-3): 120 s of lifetime left over a 30 s video clears the bound.
    @Test func aRefusedCastResolveFallsBackToTheStreamAlreadyPlaying() async {
        let resolver = RecordingResolver(.hls)
        resolver.expiresIn = 120
        let vm = makeCastModel(resolver, args: PlayerArgs(videoId: Self.claimed, durationSeconds: 30))
        await vm.open()
        resolver.outcome = .failure(.cooldown(until: Date().addingTimeInterval(30)))
        let media = await vm.castMedia()
        #expect(resolver.calls.count == 2)
        #expect(media != nil, "a refused refresh must not throw away a stream that still plays")
    }

    /// AC-P2-3: the fallback is BOUNDED. Nothing re-resolves for the receiver — `CastController`
    /// publishes, it does not drive — so a near-expiry URL handed to a TV dies mid-playback with no
    /// recovery and no banner. A stream that cannot outlive what is left to play is not a fallback,
    /// it is a doomed load: refuse it and take the honest failure path instead.
    @Test func aRefusedCastResolveRefusesAStreamThatWouldDieMidPlayback() async {
        let resolver = RecordingResolver(.hls)
        resolver.expiresIn = 120
        let vm = makeCastModel(resolver, args: PlayerArgs(videoId: Self.claimed, durationSeconds: 600))
        await vm.open()
        resolver.outcome = .failure(.cooldown(until: Date().addingTimeInterval(30)))
        #expect(await vm.castMedia() == nil,
                "120 s of URL left cannot carry 600 s of video on a receiver that cannot re-resolve")
    }

    /// The unknown-duration row: with no duration there is nothing to compare the remaining
    /// lifetime against, and guessing in the receiver's favour is guessing the failure the bound
    /// exists to prevent. Unknown counts as NOT covered.
    @Test func aRefusedCastResolveWithAnUnknownDurationIsNeverCovered() async {
        let resolver = RecordingResolver(.hls)
        resolver.expiresIn = 120
        let vm = makeCastModel(resolver, args: PlayerArgs(videoId: Self.claimed))
        await vm.open()
        resolver.outcome = .failure(.cooldown(until: Date().addingTimeInterval(30)))
        #expect(await vm.castMedia() == nil, "an unknown duration cannot clear the bound")
    }

    /// The bound itself, at its edges: 30 s into a 600 s video the receiver still has 570 s to
    /// fetch, so it needs that plus the 60 s floor and one second less is refused.
    @Test func theNearExpiryBoundIsWhatIsLeftToPlayPlusAMinute() {
        let now = Date()
        func covers(_ lifetime: TimeInterval, duration: Int?, position: TimeInterval = 30) -> Bool {
            PlayerViewModel.castStreamCoversPlayback(expiresAt: now.addingTimeInterval(lifetime),
                                                     now: now, durationSeconds: duration,
                                                     position: position)
        }
        #expect(covers(630.001, duration: 600))
        #expect(!covers(629.999, duration: 600))
        #expect(!covers(3600, duration: nil), "an unknown duration is never covered")
        // Position past the end (a stale clock, a live edge): there is nothing left to cover but
        // the floor — never a NEGATIVE requirement that would wave an expired URL through.
        #expect(covers(60.001, duration: 600, position: 900))
        #expect(!covers(59.999, duration: 600, position: 900))
        // A stream with no expiry at all has nothing to outlive.
        #expect(PlayerViewModel.castStreamCoversPlayback(expiresAt: nil, now: now,
                                                         durationSeconds: nil, position: 0))
    }

    /// The bound, end to end through the one start path: `startCast` pauses the phone only once it
    /// has something to load, so a refused near-expiry cast must leave the phone playing — and then
    /// take the `reportLoadFailure` arm, exactly as a rejected load does: the existing
    /// `cast_error_format` banner, and the spent claim dropped so this screen can cast again.
    @Test func aDoomedNearExpiryCastKeepsThePhonePlayingAndTakesTheBannerPath() async {
        let cast = CastController()
        let resolver = RecordingResolver(.hls)
        resolver.expiresIn = 120
        let vm = makeCastModel(resolver, cast: cast,
                               args: PlayerArgs(videoId: Self.claimed, durationSeconds: 600))
        vm.currentPlayer = AVPlayer()
        cast.sessionDidBegin(deviceName: "Living Room TV")
        await vm.open()
        resolver.outcome = .failure(.cooldown(until: Date().addingTimeInterval(30)))

        vm.reconcile(.videoStarted)
        await resolver.waitUntilCalled(count: 2)
        await settle()
        #expect(vm.pausedForCast == false, "the phone must keep playing under a cast that cannot work")

        // The failure path proper — the screen's `.onChange(of: lastLoadFailure)` arm.
        // `reportLoadFailure` cannot name a device on a controller with no `GCKCastContext`, so the
        // test supplies the one the receiver would have carried.
        cast.lastLoadFailure = CastLoadFailure(device: "Living Room TV",
                                               claim: CastClaim(videoId: Self.claimed,
                                                                owner: vm.castOwner))
        vm.reconcile(.loadFailed)
        #expect(vm.banner?.text.contains("Living Room TV") == true, "the existing cast_error_format")
        #expect(vm.claimedVideoId == nil, "a spent claim would block this screen's own next cast")
        #expect(cast.castingVideoId == nil)
    }

    /// The other direction: a refusal with nothing castable behind it still surfaces. The embed
    /// rung has no stream URL at all (no YouTube hand-off, ever), so there is nothing to fall back
    /// to and the banner is the honest answer.
    @Test func aRefusedCastResolveWithNothingCastableStillFails() async {
        let resolver = RecordingResolver(.embed)
        let vm = makeCastModel(resolver)
        await vm.open()
        resolver.outcome = .failure(.cooldown(until: Date().addingTimeInterval(30)))
        #expect(await vm.castMedia() == nil)
    }

    // MARK: - R4-6: the table, executed

    /// `reconcile` starts the cast in a `Task`, so the test has to let it run. `castMedia` does not
    /// suspend when it re-uses the stream the player already holds (R4-1), so one hop is enough —
    /// the loop is slack, not a poll.
    private func settle() async {
        for _ in 0..<10 { await Task.yield() }
    }

    private func makeCastModel(_ resolver: RecordingResolver, cast: CastController? = nil,
                               args: PlayerArgs = PlayerArgs(videoId: "xc7keR2piUM"),
                               queue: [String] = []) -> PlayerViewModel {
        let settings = UserDefaultsSettingsStore(
            defaults: UserDefaults(suiteName: "CastSessionTests.\(UUID().uuidString)")!)
        return PlayerViewModel(resolver: resolver, settings: settings, args: args,
                               queueSource: queue.isEmpty
                                   ? nil : FakeQueueSource(pages: [(ids: queue, next: nil)]),
                               cast: cast)
    }

    /// R4-2 end to end. `swapArgs` reset `currentTime` and the per-stream flags but neither the
    /// claim nor the pause, so after an Up Next tap mid-cast the phone played B while
    /// `claimedVideoId == castingVideoId == loadedVideoId == A` — the session's end then seeked B
    /// to A's receiver position and played it, and nothing ever cast B, leaving phone and TV on
    /// different videos for the rest of the session.
    @Test func anAdvanceDuringACastMovesTheClaimToTheNewVideoAndReCastsIt() async {
        let cast = CastController()
        let vm = makeCastModel(RecordingResolver(.hls), cast: cast,
                               args: PlayerArgs(videoId: "a", playlistId: "PL"), queue: ["a", "b"])
        vm.currentPlayer = AVPlayer()
        cast.sessionDidBegin(deviceName: "Living Room TV")
        await vm.open()
        vm.reconcile(.videoStarted)
        await settle()
        #expect(vm.claimedVideoId == "a")
        #expect(vm.pausedForCast)
        #expect(cast.castingVideoId == "a")
        // the receiver took A (`load()` itself needs a `GCKCastContext`)
        cast.recordLoad("a", owner: vm.castOwner)

        await vm.play(at: 1)   // the Up Next tap
        await settle()
        #expect(vm.args.videoId == "b")
        #expect(vm.claimedVideoId == "b", "the claim follows the video the screen actually plays")
        #expect(cast.castingVideoId == "b", "A's stamp was given back and B took it")
        #expect(vm.pausedForCast, "B is the video being cast now, so B's player is the paused one")

        // The session ends carrying A's receiver position. B must not inherit it.
        cast.sessionWillEnd(position: 512)
        cast.sessionDidEnd()
        vm.reconcile(.sessionChanged)
        #expect(vm.currentTime == 0, "B must never be seeked to A's receiver position")
        #expect(vm.claimedVideoId == nil, "the hand-back drops the claim")
        #expect(cast.castingVideoId == nil)
    }

    /// R4-5 end to end: another screen cast its own video and popped, so the session is live, the
    /// stamp is free and the receiver is playing something else. Resuming the phone there leaves
    /// video A audible on the phone while the TV plays B, with the SDK cast button offering only
    /// disconnect. The pause surviving is what says this re-cast, not a local resume.
    @Test func aReturningClaimantReCastsWhenTheReceiverIsPlayingSomethingElse() async {
        let cast = CastController()
        let vm = makeCastModel(RecordingResolver(.hls), cast: cast)
        vm.currentPlayer = AVPlayer()
        cast.sessionDidBegin(deviceName: "Living Room TV")
        await vm.open()
        vm.reconcile(.videoStarted)
        await settle()
        #expect(vm.claimedVideoId == Self.claimed)

        // Off screen: the stamp goes back so the next video can claim it, the claim stays.
        vm.reconcile(.disappear)
        #expect(cast.castingVideoId == nil)
        #expect(vm.claimedVideoId == Self.claimed)

        cast.recordLoad(Self.other, owner: Self.otherOwner)
        vm.reconcile(.appear)
        await settle()
        #expect(cast.castingVideoId == Self.claimed, "a live free session is this claimant's to use")
        #expect(vm.pausedForCast, "a hand-back would have spent the pause and resumed the phone")
    }

    /// AC-P2-1 end to end, the double-audio shape: `MainShellView` keeps every visited tab's stack
    /// mounted, so a Home stack and a Search stack can both be up on video X, both reading the ONE
    /// app-wide `isSessionActive`. Keyed on the videoId alone both read the stamp as theirs — both
    /// paused for the cast, both loaded the receiver, and both seeked and played on hand-back.
    @Test func twoMountedScreensOnOneVideoNeverBothCastAndNeverBothResume() async {
        let cast = CastController()
        let first = makeCastModel(RecordingResolver(.hls), cast: cast)
        let second = makeCastModel(RecordingResolver(.hls), cast: cast)
        first.currentPlayer = AVPlayer()
        second.currentPlayer = AVPlayer()
        cast.sessionDidBegin(deviceName: "Living Room TV")
        await first.open()
        await second.open()

        first.reconcile(.videoStarted)
        await settle()
        #expect(first.claimedVideoId == Self.claimed)
        #expect(first.pausedForCast)
        cast.recordLoad(Self.claimed, owner: first.castOwner)

        // The second screen reacts to everything it can: same video, same session flag.
        for trigger in CastTrigger.allCases { second.reconcile(trigger) }
        await settle()
        #expect(second.claimedVideoId == nil, "a same-video screen must claim nothing")
        #expect(second.pausedForCast == false, "two paused players is two resumes: double audio")
        #expect(cast.castingVideoId == Self.claimed)
        #expect(first.pausedForCast, "the owner's cast survives the other screen's reactions")
        #expect(cast.receiverPosition(for: Self.claimed, owner: second.castOwner) == nil,
                "the receiver's position belongs to the screen whose load it played")

        // Hand-back: only the screen that paused seeks and resumes.
        cast.sessionWillEnd(position: 512)
        cast.sessionDidEnd()
        first.reconcile(.sessionChanged)
        second.reconcile(.sessionChanged)
        await settle()
        #expect(first.currentTime == 512)
        #expect(first.pausedForCast == false)
        #expect(second.currentTime == 0, "the non-owner was never paused and is never seeked")
    }

    /// The load-failure arm: banner, phone keeps playing, claim spent. Nothing of ours reached the
    /// receiver, so holding the claim would both block this screen's own next cast and keep every
    /// other screen out of a session it is not using.
    @Test func aRejectedLoadBannersResumesThePhoneAndDropsTheSpentClaim() async {
        let cast = CastController()
        let vm = makeCastModel(RecordingResolver(.hls), cast: cast)
        vm.currentPlayer = AVPlayer()
        cast.sessionDidBegin(deviceName: "Living Room TV")
        await vm.open()
        vm.reconcile(.videoStarted)
        await settle()
        #expect(vm.pausedForCast)

        cast.lastLoadFailure = CastLoadFailure(device: "Living Room TV",
                                               claim: CastClaim(videoId: Self.claimed,
                                                                owner: vm.castOwner))
        vm.reconcile(.loadFailed)
        #expect(vm.banner?.text.contains("Living Room TV") == true)
        #expect(cast.lastLoadFailure == nil,
                "consumed, so a second failure on the same device still announces itself")
        #expect(vm.pausedForCast == false, "the phone keeps playing under the banner")
        #expect(vm.claimedVideoId == nil)
        #expect(cast.castingVideoId == nil)
    }

    /// The other half of the same identity rule: a load the receiver REJECTED means nothing of ours
    /// is playing there, so the position sampled at the next disconnect belongs to whatever the
    /// receiver kept playing — and the screen whose load failed must not reclaim on the strength of
    /// a load that never landed.
    @Test func aRejectedLoadLeavesNothingOfOursOnTheReceiver() {
        let cast = CastController()
        cast.sessionDidBegin(deviceName: "Living Room TV")
        #expect(cast.claimForCast(videoId: "xc7keR2piUM", owner: Self.owner, isOfflinePlayback: false))
        cast.recordLoad("xc7keR2piUM", owner: Self.owner)
        cast.reportLoadFailure(claim: CastClaim(videoId: "xc7keR2piUM", owner: Self.owner))
        #expect(cast.loadedVideoId == nil)

        cast.sessionWillEnd(position: 77)
        #expect(cast.receiverPosition(for: "xc7keR2piUM", owner: Self.owner) == nil)
    }

    // MARK: - Cubic round 7

    /// A player with a REAL item, so "did it resume?" has an answer: `AVPlayer.rate` is what
    /// `player.play()` moves, and a bare `AVPlayer()` with no item has nothing to move. Same
    /// builder the host uses, and the same URL the resolver double already answers with.
    private func phonePlayer(_ vm: PlayerViewModel) -> AVPlayer? {
        PlayerHostView.player(for: vm.state, replacing: nil)
    }

    /// R7-2: with a session already live, the `.task` arm reconciles BEFORE `PlayerHostView` builds
    /// the `AVPlayer` — so `pauseForCast` finds `currentPlayer == nil` and can only record the
    /// intent, and the host then started the phone playing alongside the TV with nothing left to
    /// re-pause it. Every other case in this suite sets `currentPlayer` first, so this ordering is
    /// the one that was never covered.
    @Test func aCastClaimedBeforeTheHostBuildsThePlayerLeavesItPaused() async {
        let cast = CastController()
        let vm = makeCastModel(RecordingResolver(.hls), cast: cast)
        cast.sessionDidBegin(deviceName: "Living Room TV")
        await vm.open()
        #expect(vm.currentPlayer == nil, "the host has not run yet — that is the whole case")

        vm.reconcile(.videoStarted)
        await settle()
        #expect(vm.pausedForCast, "with no player to pause, the intent is all there is to record")

        let player = PlayerHostView.player(for: vm.state, replacing: nil,
                                           pausedForCast: vm.pausedForCast)
        #expect(player?.rate == 0, "the host must not autoplay the phone under a live cast")
        // And the advance shape: a DIFFERENT video always starts (B5 Task 4) — but not this one.
        let advanced = PlayerHostView.player(for: vm.state, replacing: player,
                                             continuesCurrentVideo: false,
                                             pausedForCast: vm.pausedForCast)
        #expect(advanced?.rate == 0)
    }

    /// R7-3(b)+(c): the claimant is off screen (stamp surrendered) when the receiver rejects the
    /// load. There is no banner to raise on a screen nobody is looking at, but holding the spent
    /// claim and the pause left the phone silent for the rest of the session — and the return leg's
    /// re-cast then failed with an EQUAL device name, so `.onChange` never fired again either.
    @Test func anOffScreenClaimantsOwnFailureReleasesItWithoutPlayingAnything() async throws {
        let cast = CastController()
        let vm = makeCastModel(RecordingResolver(.hls), cast: cast)
        cast.sessionDidBegin(deviceName: "Living Room TV")
        await vm.open()
        let player = try #require(phonePlayer(vm))
        vm.currentPlayer = player
        vm.reconcile(.videoStarted)
        await settle()
        #expect(vm.pausedForCast)

        vm.reconcile(.disappear)                        // a tab switch: the stamp goes back
        #expect(cast.castingVideoId == nil)
        #expect(vm.claimedVideoId == Self.claimed)

        cast.reportLoadFailure(claim: CastClaim(videoId: Self.claimed, owner: vm.castOwner))
        vm.reconcile(.loadFailed)
        #expect(vm.pausedForCast == false, "an unconsumed failure must not hold the phone paused")
        #expect(vm.claimedVideoId == nil, "nor keep a spent claim")
        #expect(player.rate == 0, "and must not start a tab the user cannot see")
        #expect(vm.banner == nil, "the banner belongs to a screen that is on it")
        // (c) the session is usable again — by this screen and by every other one.
        #expect(cast.claimForCast(videoId: Self.other, owner: Self.otherOwner, isOfflinePlayback: false))
    }

    /// R7-8: A casts X and tab-switches (stamp released, claim kept), B casts Y, the receiver
    /// disconnects. A's hand-back gets a nil position — the receiver's belongs to Y — but the pause
    /// was still spent by a `play()`, so the hidden tab played under B's own resume: double audio.
    @Test func aHiddenClaimantWhoseVideoLeftTheReceiverNeverPlaysOnHandBack() async throws {
        let cast = CastController()
        let a = makeCastModel(RecordingResolver(.hls), cast: cast)
        let b = makeCastModel(RecordingResolver(.hls), cast: cast,
                              args: PlayerArgs(videoId: Self.other))
        cast.sessionDidBegin(deviceName: "Living Room TV")
        await a.open()
        await b.open()
        let aPlayer = try #require(phonePlayer(a))
        a.currentPlayer = aPlayer
        b.currentPlayer = phonePlayer(b)

        a.reconcile(.videoStarted)
        await settle()
        cast.recordLoad(Self.claimed, owner: a.castOwner)
        a.reconcile(.disappear)
        b.reconcile(.videoStarted)
        await settle()
        cast.recordLoad(Self.other, owner: b.castOwner)
        #expect(b.pausedForCast)

        cast.sessionWillEnd(position: 512)
        cast.sessionDidEnd()
        a.reconcile(.sessionChanged)
        #expect(a.claimedVideoId == nil, "the stale claim still has to go")
        #expect(a.pausedForCast == false, "and so does the pause")
        #expect(a.currentTime == 0, "the receiver's position was never A's")
        #expect(aPlayer.rate == 0, "A's video is not on the receiver: nothing to hand back")
        b.reconcile(.sessionChanged)
        #expect(b.currentTime == 512, "B, whose video the receiver played, still gets its hand-back")
    }

    /// R7-9: popping the claimant destroys its view model and its owner `UUID` with it, so a fresh
    /// screen re-opened on the video the receiver is STILL playing read `loadedClaim` as a
    /// stranger's, cast it again and restarted the TV at its own 0:00. The check is about the
    /// VIDEO; the new screen adopts what is already there — and its hand-back gets the receiver's
    /// real position, which a reload would have thrown away.
    @Test func aFreshScreenOnTheVideoTheReceiverIsPlayingAdoptsItInsteadOfReloading() async throws {
        let cast = CastController()
        cast.sessionDidBegin(deviceName: "Living Room TV")
        cast.recordLoad(Self.claimed, owner: Self.otherOwner)   // the popped screen's load

        let resolver = RecordingResolver(.hls)
        let vm = makeCastModel(resolver, cast: cast)
        await vm.open()
        let player = try #require(phonePlayer(vm))
        vm.currentPlayer = player
        #expect(resolver.calls.count == 1)

        vm.reconcile(.videoStarted)
        await settle()
        #expect(vm.claimedVideoId == Self.claimed)
        #expect(cast.castingClaim == CastClaim(videoId: Self.claimed, owner: vm.castOwner),
                "the stamp is taken with OUR owner")
        #expect(cast.loadedClaim == CastClaim(videoId: Self.claimed, owner: vm.castOwner),
                "the receiver is playing this screen's video now, so the position is this screen's")
        #expect(vm.pausedForCast, "the receiver owns playback: the phone stops")
        #expect(player.rate == 0)
        #expect(resolver.calls.count == 1, "adopting resolves nothing and loads nothing")

        cast.sessionWillEnd(position: 512)
        cast.sessionDidEnd()
        vm.reconcile(.sessionChanged)
        #expect(vm.currentTime == 512, "the hand-back lands where the TV got to, not at 0:00")
    }

    /// R7-16: `startCast` awaits `castMedia()`, and going off screen and back inside that window
    /// opens a second walk for the SAME video and the SAME owner. Keyed on the videoId the return
    /// leg bailed, and the original then failed its own `stillCasting` check on the claim it no
    /// longer held — claim set, stamp free, receiver idle until some later trigger.
    @Test func aReleaseAndReClaimDuringTheCastResolveStillReachesTheReceiver() async {
        let cast = CastController()
        let resolver = RecordingResolver(.hls)
        resolver.expiresIn = 120        // near expiry, so the cast walks and the start suspends
        let vm = makeCastModel(resolver, cast: cast)
        cast.sessionDidBegin(deviceName: "Living Room TV")
        await vm.open()
        vm.currentPlayer = phonePlayer(vm)

        resolver.hold(Self.claimed)
        vm.reconcile(.videoStarted)                     // walk 1 suspends inside `castMedia()`
        await resolver.waitUntilCalled(count: 2)
        vm.reconcile(.disappear)                        // off screen: the stamp goes back
        #expect(cast.castingVideoId == nil)

        vm.reconcile(.appear)                           // the return leg
        await resolver.waitUntilCalled(count: 3)
        resolver.release(id: Self.claimed)
        resolver.release(id: Self.claimed)
        await settle()
        #expect(cast.castingVideoId == Self.claimed, "the return leg has to actually cast")
        #expect(vm.claimedVideoId == Self.claimed)
        #expect(vm.pausedForCast, "and pause the phone it cast from")
    }

    /// R7-18: `CastController.load` leaves `startTime` at the live edge for live media, so the
    /// receiver's sampled `approximateStreamPosition` is a number off its own timeline. Seeking the
    /// local live player to it lands at an unrelated point or the DVR edge.
    @Test func aLiveHandBackResumesAtTheLiveEdgeAndNeverAtTheReceiversPosition() async {
        let vm = makeCastModel(RecordingResolver(.liveHLS))
        await vm.open()
        vm.currentPlayer = phonePlayer(vm)
        #expect(vm.state.isLive)

        vm.pauseForCast()
        vm.resumeAfterCast(at: 512)
        #expect(vm.currentTime == 0, "a receiver's absolute position means nothing on a live timeline")
        #expect(vm.pausedForCast == false, "the resume itself still happens")
    }

    /// Cubic R6-4: the failure belongs to ONE video, and only that video's stamp is its to clear.
    /// Screen A casts and goes off-screen (stamp released, receiver still playing A); screen B
    /// mounts on an embed rung, `startCast` finds nothing castable and reports the failure.
    /// Clearing `loadedVideoId` unconditionally erased A's presence on the receiver, so A returning
    /// re-resolved and reloaded it at the phone's stale position — and the hand-back then handed
    /// back a position the receiver never had.
    @Test func aLoadFailureLeavesAnotherScreensVideoOnTheReceiver() {
        let cast = CastController()
        cast.sessionDidBegin(deviceName: "Living Room TV")
        _ = cast.claimForCast(videoId: Self.claimed, owner: Self.owner, isOfflinePlayback: false)
        cast.recordLoad(Self.claimed, owner: Self.owner)
        // A goes off screen; the receiver keeps playing it
        cast.releaseClaim(Self.claimed, owner: Self.owner)

        // B has nothing castable
        cast.reportLoadFailure(claim: CastClaim(videoId: Self.other, owner: Self.otherOwner))

        #expect(cast.loadedVideoId == Self.claimed,
                "another screen's failure must not erase what the receiver is actually playing")
        cast.sessionWillEnd(position: 77)
        #expect(cast.receiverPosition(for: Self.claimed, owner: Self.owner) == 77)
    }
}
