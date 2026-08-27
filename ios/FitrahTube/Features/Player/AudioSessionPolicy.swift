import Foundation

enum PlaybackLifecycleEvent: Equatable, Sendable {
    case enteredBackground
    case willEnterForeground
    case interruptionBegan
    case interruptionEnded(shouldResume: Bool)
    case routeChanged(oldDeviceUnavailable: Bool)
}

enum PlaybackPolicyAction: Equatable, Sendable { case none, pause, resume, swapToAudioOnly, restoreVideo }

/// Whether AVFoundation should keep the player running when the app backgrounds
/// (`AVPlayer.audiovisualBackgroundPlaybackPolicy`, mapped by the controller).
enum BackgroundPolicy: Equatable, Sendable { case continues, pauses }

struct PlaybackPolicyContext: Equatable, Sendable {
    var backgroundPlay: Bool
    var userAudioOnly: Bool
    var pictureInPictureActive: Bool
    var wasPlayingBeforeInterruption: Bool
    var autoSwappedToAudioOnly: Bool
}

/// The single decision table for "what should playback do when the world changes around it"
/// (rulings 34 and 44). Pure and AVFoundation-free so the whole contract is a truth table in
/// `AudioSessionPolicyTests`; `BackgroundPlaybackController` is the only thing that turns these
/// actions into calls on a real `AVPlayer`.
enum AudioSessionPolicy {
    static func decide(_ event: PlaybackLifecycleEvent, _ context: PlaybackPolicyContext) -> PlaybackPolicyAction {
        switch event {
        case .enteredBackground:
            // Ruling 34's "background-play OFF pauses" is delivered by `backgroundPolicy` below --
            // AVFoundation's own policy fires before app suspension and already exempts PiP and
            // AirPlay, which a hand-rolled `player.pause()` racing suspension would not.
            guard context.backgroundPlay, !context.pictureInPictureActive, !context.userAudioOnly else { return .none }
            return .swapToAudioOnly
        case .willEnterForeground:
            // Never `.resume`: a pause the user made before backgrounding must survive
            // (player.md §6.1, Android's preserved `playWhenReady`).
            return context.autoSwappedToAudioOnly ? .restoreVideo : .none
        case .interruptionBegan:
            return .pause
        case .interruptionEnded(let shouldResume):
            return shouldResume && context.wasPlayingBeforeInterruption ? .resume : .none
        case .routeChanged(let oldDeviceUnavailable):
            return oldDeviceUnavailable ? .pause : .none
        }
    }

    static func backgroundPolicy(backgroundPlay: Bool, pictureInPictureActive: Bool) -> BackgroundPolicy {
        backgroundPlay || pictureInPictureActive ? .continues : .pauses
    }
}
