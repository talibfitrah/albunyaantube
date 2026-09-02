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
        #expect(cast.claimCastSource("5ZMMARhgvsw") == false)
        #expect(cast.castingVideoId == "xc7keR2piUM")
        // Idempotent for the owner: a resumed session re-claims without losing the stamp.
        #expect(cast.claimCastSource("xc7keR2piUM"))
    }

    @Test func theStampSurvivesSessionEndSoTheHandBackFindsItsOwner() {
        let cast = CastController()
        _ = cast.claimCastSource("xc7keR2piUM")
        cast.sessionWillEnd(position: 120)
        cast.sessionDidEnd()
        #expect(cast.isSessionActive == false)
        // Still stamped: the end reaction is what reads it, and `.onChange` runs after the callback.
        #expect(cast.castingVideoId == "xc7keR2piUM")
        #expect(cast.lastStreamPosition == 120)
        cast.finishCasting()
        #expect(cast.castingVideoId == nil)
        #expect(cast.lastStreamPosition == nil)
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

    /// Important 4: `startCasting` pauses the local player before the load, so a receiver that
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
        #expect(cast.claimForCast(videoId: "5ZMMARhgvsw", isOfflinePlayback: false) == false)
        // The claimant goes away: its claim goes with it, and the next screen mounts and casts.
        cast.releaseClaim("xc7keR2piUM")
        #expect(cast.castingVideoId == nil)
        #expect(cast.claimForCast(videoId: "5ZMMARhgvsw", isOfflinePlayback: false))
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
        cast.releaseClaim("5ZMMARhgvsw")
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
        cast.releaseClaim("5ZMMARhgvsw")
        #expect(cast.castingVideoId == "xc7keR2piUM",
                "an advanced-to id must never release a claim taken for another video")

        cast.releaseClaim("xc7keR2piUM")
        #expect(cast.castingVideoId == nil)
        #expect(cast.claimForCast(videoId: "5ZMMARhgvsw", isOfflinePlayback: false),
                "the next video opened during the same session must be able to claim it")
    }

    /// Important 2: `onDisappear` is a WENT-OFF-SCREEN seam, not a teardown one — a push-over and a
    /// compact-layout tab switch both fire it on a screen that is still alive and still paused for
    /// its cast. Surrendering the stamp is right (the next video must be able to claim), but
    /// discarding `lastStreamPosition` with it threw away the receiver's position the hand-back
    /// needs, leaving the phone paused at the pre-cast position. The release gives back the STAMP
    /// only; `finishCasting()` is what consumes both.
    @Test func anOffScreenReleaseGivesBackTheStampButKeepsTheReceiversPosition() {
        let cast = CastController()
        cast.sessionDidBegin(deviceName: "Living Room TV")
        #expect(cast.claimForCast(videoId: "xc7keR2piUM", isOfflinePlayback: false))
        cast.sessionWillEnd(position: 300)

        cast.releaseClaim("xc7keR2piUM")
        #expect(cast.castingVideoId == nil)
        #expect(cast.lastStreamPosition == 300, "the hand-back still needs the receiver's position")
    }

    /// Important 2, the return leg: what a claimant coming back on screen does. Re-claiming costs
    /// no re-resolve and no second `load()` — it only takes back the stamp it surrendered — and a
    /// session that ended while the screen was away still owes it the hand-back, which its
    /// `.onChange` arm missed precisely because the stamp was gone.
    @Test func aReturningClaimantReclaimsALiveSessionAndHandsBackAFinishedOne() {
        // Still casting, stamp free: take it back.
        #expect(CastController.returnAction(pausedForCast: true, sessionActive: true,
                                            stampIsFree: true) == .reclaim)
        // Still casting but another screen claimed while we were away: leave it alone (fix round
        // 1's Important 1 — one screen owns the session).
        #expect(CastController.returnAction(pausedForCast: true, sessionActive: true,
                                            stampIsFree: false) == .none)
        // The session ended while we were off screen: hand back now, or the phone stays paused at
        // the pre-cast position with the receiver's position thrown away. Whoever holds the stamp
        // by then is irrelevant — our own player is the one that owes a resume, and the release
        // that follows no-ops unless the stamp is still ours.
        for stampIsFree in [true, false] {
            #expect(CastController.returnAction(pausedForCast: true, sessionActive: false,
                                                stampIsFree: stampIsFree) == .handBack)
        }
        // Nothing was ever paused for a cast here: a plain re-appear does nothing.
        for stampIsFree in [true, false] {
            #expect(CastController.returnAction(pausedForCast: false, sessionActive: false,
                                                stampIsFree: stampIsFree) == .none)
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

    /// R2-8: `startCasting` awaits a FORCED resolve before it pauses the local player. If the
    /// session ended inside that window the end reaction already ran (`pausedForCast` was false,
    /// so nothing resumes), `finishCasting()` cleared the stamp, `load()` finds no session and
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
        #expect(advanced.stillCasting("5ZMMARhgvsw") == false)
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
}
