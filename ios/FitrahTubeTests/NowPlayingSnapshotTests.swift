import AVFoundation
import AVKit
import Foundation
import InnerTubeKit
import MediaPlayer
import UIKit
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
        // B5 Task 4: a finished playlist must not leave the last video's entry on the lock screen.
        #expect(NowPlayingSnapshot.make(args: PlayerArgs(videoId: "abc", title: "Last"), state: .queueEnded,
                                        elapsed: 30, duration: 60, rate: 0) == nil)
    }

    /// CF-G-16: `.recoveryExhausted` means playback has STOPPED for good until a manual retry --
    /// advertising it as playable was masked only because that state dismantles the host, which
    /// clears the dictionary anyway. The snapshot must not lie: nothing playable, nothing made.
    @Test func recoveryExhaustedPublishesNothing() {
        let resolved = Resolved(stream: .hls(url: URL(string: "https://x/y.m3u8")!, isLive: false,
                                             audioOnlyURL: nil, captionTracks: []),
                                client: .visionos, userAgent: "UA", resolvedAt: Date(), expiresAt: nil)
        #expect(NowPlayingSnapshot.make(args: PlayerArgs(videoId: "abc", title: "T"),
                                        state: .recoveryExhausted(resolved),
                                        elapsed: 10, duration: 60, rate: 0) == nil)
    }

    @Test func remoteCommandsExposePlayPauseAndSeek() {
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
        controller.detach()
    }

    /// 2026-11-11 owner ask: next/previous on the lock screen while a playlist plays. Enabled only for a lecture that
    /// exists (ruling 28: a dead button is worse than an absent one), and a detached controller leaves them off.
    @Test func trackCommandsFollowTheQueueNeighbours() {
        let player = AVPlayer()   // the controller holds it weakly
        let controller = BackgroundPlaybackController(backgroundPlay: true)
        controller.attach(player: player)
        let center = MPRemoteCommandCenter.shared()
        #expect(center.nextTrackCommand.isEnabled == false)       // a single video: no neighbours
        #expect(center.previousTrackCommand.isEnabled == false)

        controller.onNextTrack = {}
        #expect(center.nextTrackCommand.isEnabled)
        #expect(center.previousTrackCommand.isEnabled == false)    // the playlist's first lecture
        controller.onPreviousTrack = {}
        #expect(center.previousTrackCommand.isEnabled)
        controller.onNextTrack = nil                               // its last
        #expect(center.nextTrackCommand.isEnabled == false)

        controller.detach()
        #expect(center.previousTrackCommand.isEnabled == false)
        controller.onNextTrack = {}                                // a pass after detach owns nothing
        #expect(center.nextTrackCommand.isEnabled == false)
    }

    /// Codex adversarial P2: the rail shell keeps a covered tab's player attached. Its update pass (a single video, so
    /// no neighbours) must not switch off the playlist's next/previous that the newer player installed.
    @Test func aCoveredControllerLeavesTheOwnersTrackCommandsAlone() {
        let covered = AVPlayer(), playing = AVPlayer()
        let single = BackgroundPlaybackController(backgroundPlay: true)
        let playlist = BackgroundPlaybackController(backgroundPlay: true)
        single.attach(player: covered)
        playlist.attach(player: playing)
        playlist.onNextTrack = {}
        single.onNextTrack = nil
        #expect(MPRemoteCommandCenter.shared().nextTrackCommand.isEnabled)
        single.detach()                                    // the covered tab closes first
        #expect(MPRemoteCommandCenter.shared().nextTrackCommand.isEnabled)
        #expect(MPRemoteCommandCenter.shared().playCommand.isEnabled)
        playlist.detach()
        #expect(MPRemoteCommandCenter.shared().playCommand.isEnabled == false)
    }

    /// Cold review P1: the lock screen is the PLAYING lecture's. A covered pass must not retitle it, and a covered player
    /// that starts playing takes it back -- else next on the lock screen runs the newer screen's handler and starts a
    /// second lecture under the one playing.
    @Test func aCoveredPlayerThatStartsPlayingTakesTheLockScreenBack() throws {
        let url = try #require(Bundle.main.url(forResource: "player-fixture", withExtension: "mp4"))
        let covered = AVPlayer(url: url), newer = AVPlayer()
        let single = BackgroundPlaybackController(backgroundPlay: true)
        let playlist = BackgroundPlaybackController(backgroundPlay: true)
        defer { covered.pause(); playlist.detach(); single.detach() }
        single.attach(player: covered)
        playlist.attach(player: newer)
        playlist.onNextTrack = {}
        playlist.update(args: PlayerArgs(videoId: "b", title: "B"), state: ready())
        single.update(args: PlayerArgs(videoId: "a", title: "A"), state: ready())   // the covered tab's pass
        let title = { MPNowPlayingInfoCenter.default().nowPlayingInfo?[MPMediaItemPropertyTitle] as? String }
        #expect(title() == "B")

        covered.play()
        single.refreshNowPlaying()                         // its periodic observer's tick
        #expect(title() == "A")
        #expect(MPRemoteCommandCenter.shared().nextTrackCommand.isEnabled == false)   // a single video
        #expect(MPRemoteCommandCenter.shared().playCommand.isEnabled)
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

    /// Sign-in wall P3: during PiP the dismantle defers `detach()`, so the metadata of the video
    /// being stopped would outlive the sign-out on the lock screen.
    @Test func stoppingAllPlaybackClearsTheLockScreenMetadata() {
        MPNowPlayingInfoCenter.default().nowPlayingInfo = [MPMediaItemPropertyTitle: "T"]
        PlayerHostView.stopAllPlayback(NSHashTable<AVPlayer>.weakObjects())
        #expect(MPNowPlayingInfoCenter.default().nowPlayingInfo == nil)
    }

    /// The item-replacement refresh (audio-only toggle, a recovery `replaceCurrentItem`, a quality
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
