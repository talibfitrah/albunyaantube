import Foundation

enum PlaybackLifecycleEvent: Equatable, Sendable {
    case enteredBackground
    case willEnterForeground
    case interruptionBegan
    case interruptionEnded(shouldResume: Bool)
    case routeChanged(oldDeviceUnavailable: Bool)
}

/// `restoreVideo` vs `restoreVideoNow` (fix round 2): both undo an automatic audio-only swap, and
/// they differ only in WHO performs the item replacement. `.restoreVideo` is the ordinary
/// foreground transition -- it leaves the replace to the host's next SwiftUI update pass, so a TTL
/// re-resolve still in flight cannot be beaten to the punch by a stale one (IMP-1). `.restoreVideoNow`
/// is the auto-PiP undo, which runs inside the home-swipe transition where that update pass may
/// never run before suspension; it must put the video url back on the live player itself or AVKit
/// opens its window over an audio-only item. `decide` never returns it -- only `undoAutoSwapIfAny()`.
enum PlaybackPolicyAction: Equatable, Sendable {
    case none, pause, resume, swapToAudioOnly, restoreVideo, restoreVideoNow
}

/// Whether AVFoundation should keep the player running when the app backgrounds
/// (`AVPlayer.audiovisualBackgroundPlaybackPolicy`, mapped by the controller).
enum BackgroundPolicy: Equatable, Sendable { case continues, pauses }

struct PlaybackPolicyContext: Equatable, Sendable {
    var backgroundPlay: Bool
    var userAudioOnly: Bool
    /// Whether the live stream actually HAS a separate audio rendition (`Resolved.hls.audioOnlyURL`,
    /// i.e. rung 1 only). The automatic swap is gated here, at the source, rather than declined by
    /// the controller: `handle()` flips `autoSwappedToAudioOnly` the moment `.swapToAudioOnly` is
    /// decided, so an action the player cannot honour would come back as a spurious `.restoreVideo`
    /// on the next foreground.
    var audioOnlyAvailable: Bool
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
            guard context.backgroundPlay, context.audioOnlyAvailable,
                  !context.pictureInPictureActive, !context.userAudioOnly else { return .none }
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
