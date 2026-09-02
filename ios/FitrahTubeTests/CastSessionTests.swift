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
        #expect(cast.claimCastSource("xc7keR2piUM"))
        #expect(cast.castingVideoId == "xc7keR2piUM")
        // A second mounted PlayerScreen reacting to the same session flag must NOT resolve or load.
        #expect(cast.claimCastSource("other-video") == false)
        #expect(cast.castingVideoId == "xc7keR2piUM")
        // Idempotent for the owner: a resumed session re-claims without losing the stamp.
        #expect(cast.claimCastSource("xc7keR2piUM"))
    }

    @Test func theStampSurvivesSessionEndSoTheHandBackFindsItsOwner() {
        let cast = CastController()
        _ = cast.claimCastSource("xc7keR2piUM")
        cast.recordLoad("xc7keR2piUM")
        cast.sessionWillEnd(position: 120)
        cast.sessionDidEnd()
        #expect(cast.isSessionActive == false)
        // Still stamped: the end reaction is what reads it, and `.onChange` runs after the callback.
        #expect(cast.castingVideoId == "xc7keR2piUM")
        #expect(cast.lastStreamPosition == 120)
        cast.finishClaim("xc7keR2piUM")
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
        _ = cast.claimCastSource("xc7keR2piUM")
        cast.recordLoad("other-video")            // another screen's video is what plays there
        cast.sessionWillEnd(position: 300)

        cast.finishClaim("xc7keR2piUM")
        #expect(cast.castingVideoId == nil, "our stamp goes")
        #expect(cast.loadedVideoId == "other-video", "the receiver's video is not ours to forget")
        #expect(cast.lastStreamPosition == 300, "nor is the position that belongs to it")

        // And a screen that holds neither takes nothing.
        cast.finishClaim("no-such-video")
        #expect(cast.loadedVideoId == "other-video")
        #expect(cast.lastStreamPosition == 300)
    }

    // MARK: - Important 2: a new session never carries the previous one's position

    @Test func aNewSessionClearsThePreviousSessionsPositionAndStamp() {
        let cast = CastController()
        _ = cast.claimCastSource("xc7keR2piUM")
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
        #expect(cast.claimForCast(videoId: "xc7keR2piUM", isOfflinePlayback: false))
        // Fix round 1's Important 1 still holds: a second mounted screen does not steal the session.
        #expect(cast.claimForCast(videoId: "other-video", isOfflinePlayback: false) == false)
        // The claimant goes away: its claim goes with it, and the next screen mounts and casts.
        cast.releaseClaim("xc7keR2piUM")
        #expect(cast.castingVideoId == nil)
        #expect(cast.claimForCast(videoId: "other-video", isOfflinePlayback: false))
    }

    /// m1 survives the new funnel: an offline player never starts, claims or loads a cast — and
    /// with no live session there is nothing to start at all.
    @Test func anOfflinePlayerNeverClaimsALiveSession() {
        let cast = CastController()
        cast.sessionDidBegin(deviceName: "Living Room TV")
        #expect(cast.claimForCast(videoId: "xc7keR2piUM", isOfflinePlayback: true) == false)
        #expect(cast.castingVideoId == nil, "an offline screen must not even take the stamp")

        cast.sessionDidEnd()
        #expect(cast.claimForCast(videoId: "xc7keR2piUM", isOfflinePlayback: false) == false)
    }

    /// The release half of R2-5: only the screen that OWNS the stamp may drop it, or a second
    /// mounted `PlayerScreen` disappearing would hand the session away from the real claimant.
    @Test func aScreenThatNeverClaimedCannotReleaseAnotherScreensSession() {
        let cast = CastController()
        cast.sessionDidBegin(deviceName: "Living Room TV")
        #expect(cast.claimForCast(videoId: "xc7keR2piUM", isOfflinePlayback: false))
        cast.releaseClaim("other-video")
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
        #expect(cast.claimForCast(videoId: "xc7keR2piUM", isOfflinePlayback: false))

        // The queue advanced: the screen plays another video now, the stamp still names the claim.
        cast.releaseClaim("other-video")
        #expect(cast.castingVideoId == "xc7keR2piUM",
                "an advanced-to id must never release a claim taken for another video")

        cast.releaseClaim("xc7keR2piUM")
        #expect(cast.castingVideoId == nil)
        #expect(cast.claimForCast(videoId: "other-video", isOfflinePlayback: false),
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
        #expect(cast.claimForCast(videoId: "xc7keR2piUM", isOfflinePlayback: false))
        cast.sessionWillEnd(position: 300)

        cast.releaseClaim("xc7keR2piUM")
        #expect(cast.castingVideoId == nil)
        #expect(cast.lastStreamPosition == 300, "the hand-back still needs the receiver's position")
    }

    // MARK: - R4-6: the ownership table (the old `returnAction` rows fold in here)

    private static let claimed = "xc7keR2piUM"
    private static let other = "other-video"

    /// Every input the decision reads, defaulted to "this screen claimed the live session, the
    /// receiver is playing its video, the phone is paused for it".
    private func ownership(claim: String? = "xc7keR2piUM", video: String = "xc7keR2piUM",
                           offline: Bool = false, paused: Bool = true, sessionActive: Bool = true,
                           stamp: String? = "xc7keR2piUM",
                           loaded: String? = "xc7keR2piUM") -> CastOwnershipState {
        CastOwnershipState(claimedVideoId: claim, videoId: video, isOfflinePlayback: offline,
                           pausedForCast: paused, sessionActive: sessionActive,
                           stampedVideoId: stamp, loadedVideoId: loaded)
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
        }
        for trigger in [CastTrigger.appear, .disappear, .loadFailed] {
            #expect(CastOwnership.decide(state: ownership(claim: nil, stamp: nil, loaded: nil),
                                         trigger: trigger) == .none)
        }
    }

    /// What a claimant coming back on screen does — the old `returnAction` table, plus the R4-5 row
    /// it got wrong. Re-claiming costs no re-resolve and no second `load()`; a session that ended
    /// while the screen was away still owes it the hand-back its `.onChange` arm missed (the stamp
    /// was gone); and a LIVE session the receiver is not playing our video on must be cast into,
    /// never quietly resumed on the phone.
    @Test func aClaimantReclaimsReCastsOrHandsBackButNeverJustResumes() {
        for trigger in [CastTrigger.appear, .sessionChanged, .videoStarted] {
            // Still casting OUR video and still paused for it, stamp free: take it back, no more.
            #expect(CastOwnership.decide(state: ownership(stamp: nil), trigger: trigger)
                    == .reclaim(videoId: Self.claimed))
            // R4-5: live session, free stamp — but the receiver is playing something else (another
            // screen cast and popped, or the session churned while we were away). Resuming locally
            // there leaves the phone playing A audibly while the TV plays B and the cast button
            // offers only disconnect. Cast this video instead.
            #expect(CastOwnership.decide(state: ownership(stamp: nil, loaded: Self.other), trigger: trigger)
                    == .startCast(videoId: Self.claimed))
            #expect(CastOwnership.decide(state: ownership(stamp: nil, loaded: nil), trigger: trigger)
                    == .startCast(videoId: Self.claimed))
            // Nothing is paused: a load the receiver rejected already resumed this player. A
            // reclaim would make a screen that is not casting the silent owner of the session, so
            // the answer is the real start path — which pauses and loads — or nothing.
            #expect(CastOwnership.decide(state: ownership(paused: false, stamp: nil), trigger: trigger)
                    == .startCast(videoId: Self.claimed))
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
        }
    }

    /// The two claimant triggers that are not a reconcile. The release is the STAMP only and is
    /// keyed on the CLAIMED id — an advanced-to id released nothing, so the stamp stuck for the
    /// rest of the session — and the banner belongs to the claimant that is still stamped, or
    /// several mounted screens each raise it and each write the device name back.
    @Test func offScreenReleasesTheStampAndOnlyTheStampedClaimantHearsAFailure() {
        #expect(CastOwnership.decide(state: ownership(video: Self.other), trigger: .disappear)
                == .release(videoId: Self.claimed))
        #expect(CastOwnership.decide(state: ownership(sessionActive: false), trigger: .disappear)
                == .release(videoId: Self.claimed))
        #expect(CastOwnership.decide(state: ownership(loaded: nil), trigger: .loadFailed)
                == .reportFailure(videoId: Self.claimed))
        #expect(CastOwnership.decide(state: ownership(stamp: Self.other), trigger: .loadFailed) == .none)
        #expect(CastOwnership.decide(state: ownership(stamp: nil), trigger: .loadFailed) == .none)
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
        #expect(cast.claimForCast(videoId: "xc7keR2piUM", isOfflinePlayback: false))
        #expect(cast.stillCasting("xc7keR2piUM"))

        cast.sessionWillEnd(position: 90)
        cast.sessionDidEnd()
        #expect(cast.stillCasting("xc7keR2piUM") == false, "a dead session must not be pause-and-loaded")

        // The other way the cast stops being ours inside that window: the queue advanced, so the
        // stamp names a video this screen no longer plays.
        let advanced = CastController()
        advanced.sessionDidBegin(deviceName: "Living Room TV")
        #expect(advanced.claimForCast(videoId: "xc7keR2piUM", isOfflinePlayback: false))
        #expect(advanced.stillCasting("other-video") == false)
    }

    /// Re-review Minor 2: a failure that landed with no claimant mounted is never consumed, and
    /// `.onChange` does not fire twice for the same device name — so without this the NEXT failure
    /// on that receiver would be silent.
    @Test func aNewSessionDropsAnUnreadLoadFailure() {
        let cast = CastController()
        cast.lastLoadFailureDevice = "Living Room TV"
        cast.sessionDidBegin(deviceName: "Living Room TV")
        #expect(cast.lastLoadFailureDevice == nil)
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
        #expect(cast.claimForCast(videoId: "xc7keR2piUM", isOfflinePlayback: false))
        cast.recordLoad("xc7keR2piUM")
        // `willEndSession` is the only thing that ever stamps a position, so this is how a test
        // gets one onto a session it is about to suspend.
        cast.sessionWillEnd(position: 240)
        cast.lastLoadFailureDevice = "Living Room TV"

        cast.sessionDidResume(deviceName: "Living Room TV")
        #expect(cast.isSessionActive)
        #expect(cast.connectedDeviceName == "Living Room TV")
        #expect(cast.castingVideoId == "xc7keR2piUM", "a resumed session is the same session")
        #expect(cast.loadedVideoId == "xc7keR2piUM", "the receiver kept playing across the suspension")
        #expect(cast.lastStreamPosition == 240)
        #expect(cast.lastLoadFailureDevice == "Living Room TV", "an unread failure is still owed")

        // A genuinely new session still inherits nothing.
        cast.sessionDidBegin(deviceName: "Kitchen TV")
        #expect(cast.castingVideoId == nil)
        #expect(cast.loadedVideoId == nil)
        #expect(cast.lastStreamPosition == nil)
        #expect(cast.lastLoadFailureDevice == nil)
    }

    /// A casts; Up Next pushes B, so A surrenders the stamp; B claims and loads on the receiver;
    /// the user pops B and A comes back to a free stamp. A used to reclaim a session that is
    /// playing B, and the disconnect then seeked A to B's position and played it. The stamp says
    /// who may act; only the LOADED id says what the receiver's position belongs to.
    @Test func aReclaimAndAHandBackNeedTheReceiverToStillPlayOurVideo() {
        let cast = CastController()
        cast.sessionDidBegin(deviceName: "Living Room TV")
        #expect(cast.claimForCast(videoId: "xc7keR2piUM", isOfflinePlayback: false))
        cast.recordLoad("xc7keR2piUM")
        cast.releaseClaim("xc7keR2piUM")
        #expect(cast.claimForCast(videoId: "other-video", isOfflinePlayback: false))
        cast.recordLoad("other-video")
        cast.releaseClaim("other-video")

        // A comes back: the session is live and free, but the receiver is playing B — so A must
        // re-cast, not take the session over and not resume on the phone under a TV playing B.
        #expect(CastOwnership.decide(
            state: CastOwnershipState(claimedVideoId: "xc7keR2piUM", videoId: "xc7keR2piUM",
                                      isOfflinePlayback: false, pausedForCast: true,
                                      sessionActive: cast.isSessionActive,
                                      stampedVideoId: cast.castingVideoId,
                                      loadedVideoId: cast.loadedVideoId),
            trigger: .appear) == .startCast(videoId: "xc7keR2piUM"))

        cast.sessionWillEnd(position: 512)
        cast.sessionDidEnd()
        #expect(cast.receiverPosition(for: "xc7keR2piUM") == nil,
                "B's receiver position is not A's hand-back")
        #expect(cast.receiverPosition(for: "other-video") == 512)

        // B's own hand-back spends both.
        cast.finishClaim("other-video")
        #expect(cast.loadedVideoId == nil)
        #expect(cast.receiverPosition(for: "other-video") == nil)
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
    /// strictly better than a banner for a stream that works.
    @Test func aRefusedCastResolveFallsBackToTheStreamAlreadyPlaying() async {
        let resolver = RecordingResolver(.hls)
        resolver.expiresIn = 120
        let vm = makeCastModel(resolver)
        await vm.open()
        resolver.outcome = .failure(.cooldown(until: Date().addingTimeInterval(30)))
        let media = await vm.castMedia()
        #expect(resolver.calls.count == 2)
        #expect(media != nil, "a refused refresh must not throw away a stream that still plays")
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
        cast.recordLoad("a")   // the receiver took A (`load()` itself needs a `GCKCastContext`)

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

        cast.recordLoad(Self.other)
        vm.reconcile(.appear)
        await settle()
        #expect(cast.castingVideoId == Self.claimed, "a live free session is this claimant's to use")
        #expect(vm.pausedForCast, "a hand-back would have spent the pause and resumed the phone")
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

        cast.lastLoadFailureDevice = "Living Room TV"
        vm.reconcile(.loadFailed)
        #expect(vm.banner?.text.contains("Living Room TV") == true)
        #expect(cast.lastLoadFailureDevice == nil,
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
        #expect(cast.claimForCast(videoId: "xc7keR2piUM", isOfflinePlayback: false))
        cast.recordLoad("xc7keR2piUM")
        cast.reportLoadFailure(videoId: "xc7keR2piUM")
        #expect(cast.loadedVideoId == nil)

        cast.sessionWillEnd(position: 77)
        #expect(cast.receiverPosition(for: "xc7keR2piUM") == nil)
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
        _ = cast.claimForCast(videoId: Self.claimed, isOfflinePlayback: false)
        cast.recordLoad(Self.claimed)
        cast.releaseClaim(Self.claimed)          // A goes off screen; the receiver keeps playing it

        cast.reportLoadFailure(videoId: Self.other)   // B has nothing castable

        #expect(cast.loadedVideoId == Self.claimed,
                "another screen's failure must not erase what the receiver is actually playing")
        cast.sessionWillEnd(position: 77)
        #expect(cast.receiverPosition(for: Self.claimed) == 77)
    }
}
