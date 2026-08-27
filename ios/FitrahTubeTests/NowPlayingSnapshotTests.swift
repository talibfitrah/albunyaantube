import AVFoundation
import AVKit
import Foundation
import InnerTubeKit
import MediaPlayer
import Testing
@testable import FitrahTube

/// `.serialized`: every test below writes the process-wide `MPNowPlayingInfoCenter.default()` /
/// `MPRemoteCommandCenter.shared()` singletons, so two of them running concurrently would read each
/// other's dictionary.
@MainActor @Suite(.serialized) struct NowPlayingSnapshotTests {
    private func ready(isLive: Bool = false) -> StreamState {
        .ready(Resolved(stream: .hls(url: URL(string: "https://x/y.m3u8")!, isLive: isLive,
                                     audioOnlyURL: nil, captionTracks: []),
                        client: .visionos, userAgent: "UA", resolvedAt: Date(), expiresAt: nil))
    }

    @Test func mapsTitleChannelDurationAndElapsed() throws {
        let args = PlayerArgs(videoId: "abc", title: "Understanding Tawakkul",
                              channelName: "Sample Channel", durationSeconds: 754)
        let snapshot = try #require(NowPlayingSnapshot.make(args: args, state: ready(),
                                                           elapsed: 42, duration: 754, rate: 1))
        #expect(snapshot.info[MPMediaItemPropertyTitle] as? String == "Understanding Tawakkul")
        #expect(snapshot.info[MPMediaItemPropertyArtist] as? String == "Sample Channel")
        #expect(snapshot.info[MPMediaItemPropertyPlaybackDuration] as? TimeInterval == 754)
        #expect(snapshot.info[MPNowPlayingInfoPropertyElapsedPlaybackTime] as? TimeInterval == 42)
        #expect(snapshot.info[MPNowPlayingInfoPropertyPlaybackRate] as? Float == 1)
    }

    /// CF-B6: `Resolved` carries no duration, so `args.durationSeconds` is the seed and the item's
    /// own duration wins the moment it is finite.
    @Test func theItemsOwnDurationOverridesTheArgumentSeed() throws {
        let args = PlayerArgs(videoId: "abc", title: "T", durationSeconds: 754)
        let seeded = try #require(NowPlayingSnapshot.make(args: args, state: ready(), elapsed: 0,
                                                         duration: nil, rate: 0))
        #expect(seeded.duration == 754)
        let measured = try #require(NowPlayingSnapshot.make(args: args, state: ready(), elapsed: 0,
                                                           duration: 761.5, rate: 0))
        #expect(measured.duration == 761.5)
    }

    @Test func liveStreamsPublishNoDuration() throws {
        let args = PlayerArgs(videoId: "abc", title: "Live khutbah", channelName: "Sample Channel")
        let snapshot = try #require(NowPlayingSnapshot.make(args: args, state: ready(isLive: true),
                                                           elapsed: 10, duration: nil, rate: 1))
        #expect(snapshot.isLive)
        #expect(snapshot.info[MPNowPlayingInfoPropertyIsLiveStream] as? Bool == true)
        #expect(snapshot.info[MPMediaItemPropertyPlaybackDuration] == nil)
    }

    @Test func fallsBackToTheDefaultTitleAndOmitsAMissingChannel() throws {
        let snapshot = try #require(NowPlayingSnapshot.make(args: PlayerArgs(videoId: "abc"),
                                                           state: ready(), elapsed: 0,
                                                           duration: nil, rate: 0))
        #expect(snapshot.info[MPMediaItemPropertyTitle] as? String == String(localized: "player_default_title"))
        #expect(snapshot.info[MPMediaItemPropertyArtist] == nil)
    }

    @Test func nonPlayableStatesPublishNothing() {
        #expect(NowPlayingSnapshot.make(args: PlayerArgs(videoId: "abc"), state: .loading,
                                        elapsed: 0, duration: nil, rate: 0) == nil)
        #expect(NowPlayingSnapshot.make(args: PlayerArgs(videoId: "abc"), state: .contentUnavailable,
                                        elapsed: 0, duration: nil, rate: 0) == nil)
    }

    /// CF-B2-9: `make` already returns nil for the embed rung; what this pins is that it STAYS nil,
    /// so leaving rung 1 for the embed cannot leave the previous video's metadata on the lock
    /// screen. The clearing itself is `PlayerHostView.dismantleUIViewController` -> `detach()` ->
    /// `removeRemoteCommands()`, which runs because the embed branch never mounts the host --
    /// nothing in `EmbedRungView` publishes a Now Playing entry of its own (YouTube API Services
    /// policy III.I.9: no background player for embed content).
    @Test func theEmbedRungAdvertisesNoNowPlayingEntry() {
        let resolved = Resolved(stream: .embed(videoId: "xc7keR2piUM"), client: .web, userAgent: "",
                                resolvedAt: Date(), expiresAt: nil)
        #expect(NowPlayingSnapshot.make(args: PlayerArgs(videoId: "xc7keR2piUM"),
                                        state: .embed(resolved),
                                        elapsed: 0, duration: 120, rate: 1) == nil)
    }

    @Test func remoteCommandsExposePlayPauseAndSeekButNotNextOrPrevious() {
        let player = AVPlayer()
        let controller = BackgroundPlaybackController(backgroundPlay: true)
        controller.attach(player: player)
        let center = MPRemoteCommandCenter.shared()
        #expect(center.playCommand.isEnabled)
        #expect(center.pauseCommand.isEnabled)
        #expect(center.togglePlayPauseCommand.isEnabled)
        #expect(center.changePlaybackPositionCommand.isEnabled)
        #expect(center.skipForwardCommand.isEnabled)
        #expect(center.skipBackwardCommand.isEnabled)
        // B5 owns the queue; a dead next/prev on the lock screen is worse than none (ruling 28's
        // "dead buttons are worse than absent ones", applied to transport).
        #expect(center.nextTrackCommand.isEnabled == false)
        #expect(center.previousTrackCommand.isEnabled == false)
        controller.detach()
    }

    /// The one thing the simulator CAN prove about the lock screen: the dictionary is published
    /// while the controller owns a player, and a dismantled player owns no transport surface.
    @Test func nowPlayingInfoIsPublishedOnUpdateAndClearedOnDetach() {
        let player = AVPlayer()
        let controller = BackgroundPlaybackController(backgroundPlay: true)
        controller.attach(player: player)
        controller.update(args: PlayerArgs(videoId: "abc", title: "Understanding Tawakkul",
                                           channelName: "Sample Channel", durationSeconds: 754),
                          state: ready())
        let info = MPNowPlayingInfoCenter.default().nowPlayingInfo
        #expect(info?[MPMediaItemPropertyTitle] as? String == "Understanding Tawakkul")
        #expect(info?[MPMediaItemPropertyArtist] as? String == "Sample Channel")
        #expect(info?[MPMediaItemPropertyPlaybackDuration] as? TimeInterval == 754)

        controller.detach()
        #expect(MPNowPlayingInfoCenter.default().nowPlayingInfo == nil)
        #expect(MPRemoteCommandCenter.shared().playCommand.isEnabled == false)
    }

    /// The item-replacement refresh (audio-only swap, a recovery `replaceCurrentItem`, a quality
    /// change): a non-playable state must retract the lock-screen surface, not leave the old
    /// video's metadata standing.
    @Test func aNonPlayableStateRetractsTheLockScreenSurface() {
        let player = AVPlayer()
        let controller = BackgroundPlaybackController(backgroundPlay: true)
        controller.attach(player: player)
        controller.update(args: PlayerArgs(videoId: "abc", title: "T"), state: ready())
        #expect(MPNowPlayingInfoCenter.default().nowPlayingInfo != nil)
        controller.update(args: PlayerArgs(videoId: "abc", title: "T"), state: .contentUnavailable)
        #expect(MPNowPlayingInfoCenter.default().nowPlayingInfo == nil)
        controller.detach()
    }

    /// I2 (Task 5 review): a back-navigation OUT of the player while PiP is live deallocates the
    /// `Coordinator` before AVKit's `…DidStopPictureInPicture` can run the deferred teardown, so
    /// the audio session, the lock-screen dictionary and the remote-command handlers would stay
    /// owned by a dead object for the rest of the process's life. `deinit` is the backstop.
    @Test func aCoordinatorDeallocatedWhileOwingATeardownStillHandsBackTheLockScreen() {
        let player = AVPlayer()
        let controller = AVPlayerViewController()
        controller.player = player
        do {
            let coordinator = PlayerHostView.Coordinator(backgroundPlay: true)
            coordinator.background.attach(player: player)
            coordinator.background.update(args: PlayerArgs(videoId: "abc", title: "T"), state: ready())
            coordinator.playerViewControllerWillStartPictureInPicture(controller)
            PlayerHostView.dismantleUIViewController(controller, coordinator: coordinator)
            // Still OWED, not yet done: a live PiP window needs the session and the lock screen.
            #expect(MPNowPlayingInfoCenter.default().nowPlayingInfo != nil)
            #expect(MPRemoteCommandCenter.shared().playCommand.isEnabled)
        }
        // Nothing holds the coordinator now and the delegate callback will never arrive.
        #expect(MPNowPlayingInfoCenter.default().nowPlayingInfo == nil)
        #expect(MPRemoteCommandCenter.shared().playCommand.isEnabled == false)
    }
}
