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
    @Test func aLoadFailureReportsOnlyForTheCurrentRequestAndNeverForOurOwnCancel() {
        #expect(CastController.reportsLoadFailure(isCurrentRequest: true, wasCancelledByUs: false))
        #expect(CastController.reportsLoadFailure(isCurrentRequest: true, wasCancelledByUs: true) == false)
        #expect(CastController.reportsLoadFailure(isCurrentRequest: false, wasCancelledByUs: false) == false)
        #expect(CastController.reportsLoadFailure(isCurrentRequest: false, wasCancelledByUs: true) == false)
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
