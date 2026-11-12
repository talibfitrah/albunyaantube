import Foundation

/// 2026-11-11 (owner report: silence with the screen locked): no lifecycle event here. Backgrounding used to swap the
/// item to the stream's itag 140 URL, which AVPlayer never loads -- a DASH fMP4 whose open-ended range googlevideo
/// throttles (`.unknown` for 35 s, foreground or background, and in a bare macOS AVPlayer). The playing item plays on;
/// `backgroundPolicy` below is the whole background rule.
enum PlaybackLifecycleEvent: Equatable, Sendable {
    case interruptionBegan
    case interruptionEnded(shouldResume: Bool)
    case routeChanged(oldDeviceUnavailable: Bool)
}

enum PlaybackPolicyAction: Equatable, Sendable {
    case none, pause, resume
}

/// Whether AVFoundation should keep the player running when the app backgrounds
/// (`AVPlayer.audiovisualBackgroundPlaybackPolicy`, mapped by the controller).
enum BackgroundPolicy: Equatable, Sendable { case continues, pauses }

/// The single decision table for "what should playback do when the world changes around it"
/// (rulings 34 and 44). Pure and AVFoundation-free so the whole contract is a truth table in
/// `AudioSessionPolicyTests`; `BackgroundPlaybackController` is the only thing that turns these
/// actions into calls on a real `AVPlayer`.
enum AudioSessionPolicy {
    static func decide(_ event: PlaybackLifecycleEvent, wasPlayingBeforeInterruption: Bool) -> PlaybackPolicyAction {
        switch event {
        case .interruptionBegan:
            return .pause
        case .interruptionEnded(let shouldResume):
            return shouldResume && wasPlayingBeforeInterruption ? .resume : .none
        case .routeChanged(let oldDeviceUnavailable):
            return oldDeviceUnavailable ? .pause : .none
        }
    }

    /// Ruling 34's "background-play OFF pauses": AVFoundation's own policy fires before app suspension and already
    /// exempts PiP and AirPlay, which a hand-rolled `player.pause()` racing suspension would not.
    static func backgroundPolicy(backgroundPlay: Bool, pictureInPictureActive: Bool) -> BackgroundPolicy {
        backgroundPlay || pictureInPictureActive ? .continues : .pauses
    }
}
