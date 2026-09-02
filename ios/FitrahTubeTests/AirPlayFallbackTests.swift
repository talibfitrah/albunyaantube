import Foundation
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
}
