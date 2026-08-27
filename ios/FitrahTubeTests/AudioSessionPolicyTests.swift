import AVFoundation
import Testing
@testable import FitrahTube

@MainActor struct AudioSessionPolicyTests {
    private func context(backgroundPlay: Bool = true, userAudioOnly: Bool = false,
                         pip: Bool = false, wasPlaying: Bool = true,
                         autoSwapped: Bool = false) -> PlaybackPolicyContext {
        PlaybackPolicyContext(backgroundPlay: backgroundPlay, userAudioOnly: userAudioOnly,
                              pictureInPictureActive: pip, wasPlayingBeforeInterruption: wasPlaying,
                              autoSwappedToAudioOnly: autoSwapped)
    }

    @Test func backgroundingSwapsToAudioOnlyWhenBackgroundPlayIsOn() {
        #expect(AudioSessionPolicy.decide(.enteredBackground, context()) == .swapToAudioOnly)
    }

    @Test func backgroundingDoesNothingExtraWhenBackgroundPlayIsOff() {
        // Ruling 34's pause is delivered by `backgroundPolicy`, not by this action.
        #expect(AudioSessionPolicy.decide(.enteredBackground, context(backgroundPlay: false)) == .none)
    }

    @Test func backgroundingNeverSwapsWhileInPictureInPicture() {
        #expect(AudioSessionPolicy.decide(.enteredBackground, context(pip: true)) == .none)
    }

    @Test func backgroundingDoesNotSwapWhenTheUserAlreadyChoseAudioOnly() {
        #expect(AudioSessionPolicy.decide(.enteredBackground, context(userAudioOnly: true)) == .none)
    }

    @Test func foregroundRestoresVideoOnlyAfterAnAutomaticSwap() {
        #expect(AudioSessionPolicy.decide(.willEnterForeground, context(autoSwapped: true)) == .restoreVideo)
        #expect(AudioSessionPolicy.decide(.willEnterForeground, context(autoSwapped: false)) == .none)
    }

    @Test func interruptionPausesAndResumesOnlyWhenItWasPlaying() {
        #expect(AudioSessionPolicy.decide(.interruptionBegan, context()) == .pause)
        #expect(AudioSessionPolicy.decide(.interruptionEnded(shouldResume: true), context(wasPlaying: true)) == .resume)
        #expect(AudioSessionPolicy.decide(.interruptionEnded(shouldResume: true), context(wasPlaying: false)) == .none)
        #expect(AudioSessionPolicy.decide(.interruptionEnded(shouldResume: false), context(wasPlaying: true)) == .none)
    }

    @Test func headphoneUnplugPauses() {
        #expect(AudioSessionPolicy.decide(.routeChanged(oldDeviceUnavailable: true), context()) == .pause)
        #expect(AudioSessionPolicy.decide(.routeChanged(oldDeviceUnavailable: false), context()) == .none)
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
