import AVFoundation
import Testing
@testable import FitrahTube

@MainActor struct AudioSessionPolicyTests {
    private func context(backgroundPlay: Bool = true, userAudioOnly: Bool = false,
                         audioOnlyAvailable: Bool = true, pip: Bool = false, wasPlaying: Bool = true,
                         autoSwapped: Bool = false) -> PlaybackPolicyContext {
        PlaybackPolicyContext(backgroundPlay: backgroundPlay, userAudioOnly: userAudioOnly,
                              audioOnlyAvailable: audioOnlyAvailable,
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

    /// The swap is gated at the SOURCE, not declined at the receiver: `handle()` sets
    /// `autoSwappedToAudioOnly` the moment `.swapToAudioOnly` is decided, so a swap the player
    /// cannot honour (rung 2, or a rung-1 stream YouTube gave no itag 140 for) would come back as a
    /// spurious `.restoreVideo` on the next foreground. The policy must never emit it.
    @Test func backgroundingDoesNotSwapWhenTheStreamHasNoAudioOnlyURL() {
        #expect(AudioSessionPolicy.decide(.enteredBackground, context(audioOnlyAvailable: false)) == .none)
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
