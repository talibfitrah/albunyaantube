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

    @Test func aScreenThatNeverPausedForCastIsNotSeekedOrPlayed() {
        let model = makeViewModel()
        model.currentPlayer = AVPlayer()
        model.currentTime = 7
        model.resumeAfterCast(at: 120)
        #expect(model.currentTime == 7, "a screen that never cast was seeked to the receiver's position")
    }

    @Test func theScreenThatPausedForCastResumesAtTheReceiversPosition() {
        let model = makeViewModel()
        model.currentPlayer = AVPlayer()
        model.pauseForCast()
        model.resumeAfterCast(at: 120)
        #expect(model.currentTime == 120)
        // One hand-back per pause: a second end transition must not re-seek a player the user may
        // have moved on with.
        model.currentTime = 5
        model.resumeAfterCast(at: 120)
        #expect(model.currentTime == 5)
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
        // …and the pause is spent, so the later session-end transition is a no-op.
        model.currentTime = 43
        model.resumeAfterCast(at: 120)
        #expect(model.currentTime == 43)
    }
}
