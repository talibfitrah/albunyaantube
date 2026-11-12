import AVFoundation
import Testing
@testable import FitrahTube

@MainActor struct AudioSessionPolicyTests {
    private func decide(_ event: PlaybackLifecycleEvent, wasPlaying: Bool = true) -> PlaybackPolicyAction {
        AudioSessionPolicy.decide(event, wasPlayingBeforeInterruption: wasPlaying)
    }

    @Test func interruptionPausesAndResumesOnlyWhenItWasPlaying() {
        #expect(decide(.interruptionBegan) == .pause)
        #expect(decide(.interruptionEnded(shouldResume: true), wasPlaying: true) == .resume)
        #expect(decide(.interruptionEnded(shouldResume: true), wasPlaying: false) == .none)
        #expect(decide(.interruptionEnded(shouldResume: false), wasPlaying: true) == .none)
    }

    @Test func headphoneUnplugPauses() {
        #expect(decide(.routeChanged(oldDeviceUnavailable: true)) == .pause)
        #expect(decide(.routeChanged(oldDeviceUnavailable: false)) == .none)
    }

    @Test func backgroundPolicyFollowsTheSettingUnlessPiPIsActive() {
        #expect(AudioSessionPolicy.backgroundPolicy(backgroundPlay: true, pictureInPictureActive: false) == .continues)
        #expect(AudioSessionPolicy.backgroundPolicy(backgroundPlay: false, pictureInPictureActive: false) == .pauses)
        #expect(AudioSessionPolicy.backgroundPolicy(backgroundPlay: false, pictureInPictureActive: true) == .continues)
    }

    @Test func audioSessionIsPlaybackWithoutMixing() {
        BackgroundPlaybackController.configureAudioSession()
        let session = AVAudioSession.sharedInstance()
        #expect(session.category == .playback)
        #expect(session.mode == .moviePlayback)
        // Ruling 44: no mixWithOthers.
        #expect(session.categoryOptions.contains(.mixWithOthers) == false)
    }
}
