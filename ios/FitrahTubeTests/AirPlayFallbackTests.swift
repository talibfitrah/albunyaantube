import AVFoundation
import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// Phase 3 Task 8 (spec §10 AirPlay): "if the external device fails the item with a 403 ... set
/// `allowsExternalPlayback = false` and retry so video mirrors". The decision is a PRE-CHECK
/// consulted in `handleRecoveryEvent` BEFORE `PlaybackRecovery.decide` — never a fourth
/// `RecoveryAction` — because a failure caused by the external route's IP mismatch must not spend
/// the retry/re-resolve budgets that exist for genuinely broken streams.
@Suite(.perTest)
struct AirPlayFallbackTests {
    /// The full (failure, externalActive, alreadyFellBack) table for the two item-failure classes.
    @Test(arguments: [RecoveryEvent.playbackError, .failedBeforeFirstFrame])
    func anItemFailureOnAnExternalRouteMirrorsExactlyOnce(event: RecoveryEvent) {
        #expect(AirPlayFallback.shouldMirror(event: event, externalPlaybackActive: true, alreadyFellBack: false))
        // Once only: a second failure after mirroring is a real stream failure, so it belongs to
        // the ordinary recovery ladder and its budgets.
        #expect(!AirPlayFallback.shouldMirror(event: event, externalPlaybackActive: true, alreadyFellBack: true))
        // No external route == nothing to fall back from.
        #expect(!AirPlayFallback.shouldMirror(event: event, externalPlaybackActive: false, alreadyFellBack: false))
        #expect(!AirPlayFallback.shouldMirror(event: event, externalPlaybackActive: false, alreadyFellBack: true))
    }

    /// A stall is not an item failure: the stream is still valid and the route is not the suspect,
    /// so mirroring would drop the user off their TV for an ordinary network hiccup.
    @Test func aStallNeverTriggersMirroring() {
        #expect(!AirPlayFallback.shouldMirror(event: .stall, externalPlaybackActive: true, alreadyFellBack: false))
        #expect(!AirPlayFallback.shouldMirror(event: .stall, externalPlaybackActive: true, alreadyFellBack: true))
    }

    /// AC-P3-3's other direction, and the reason the reuse branch's new write is ONE-DIRECTIONAL:
    /// the fallback's own `allowsExternalPlayback = false` lives on the `AVPlayer`, and the
    /// re-resolve it fires comes straight back through `PlayerHostView.player(for:replacing:)`'s
    /// reuse branch. An unconditional write there would undo the fallback in the same turn it was
    /// made, so that branch only ever turns the route OFF (for a `file://` item) and never back on.
    /// `swapArgs` is what re-enables it, per stream.
    @Test func aReResolveNeverUndoesTheMirroringFallbackOnTheReusedPlayer() {
        let player = AVPlayer(playerItem: AVPlayerItem(url: URL(string: "https://example.invalid/old.m3u8")!))
        player.allowsExternalPlayback = false          // what the fallback wrote for THIS stream
        let streamed = Resolved(stream: .hls(url: URL(string: "https://example.invalid/new.m3u8")!,
                                             isLive: false, audioOnlyURL: nil, captionTracks: []),
                                client: .visionos, userAgent: "UA", resolvedAt: Date(), expiresAt: nil)
        #expect(PlayerHostView.player(for: .ready(streamed), replacing: player)?
            .allowsExternalPlayback == false,
                "the mirroring fallback must survive its own re-resolve")
    }
}
