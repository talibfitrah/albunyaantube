import AVFoundation
import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

@Suite(.perTest)
struct PlayerHostTests {
    private static func resolved(_ stream: ResolvedStream, userAgent: String = "TestUA") -> Resolved {
        Resolved(stream: stream, client: .visionos, userAgent: userAgent, resolvedAt: Date(), expiresAt: nil)
    }

    // MARK: - .ready builds a player carrying the resolved URL + User-Agent (plan constraint: the
    // resolved User-Agent MUST be set on the AVURLAsset)

    @Test func readyStateBuildsPlayerWithResolvedURL() throws {
        let url = URL(string: "https://example.com/stream.m3u8")!
        let resolved = Self.resolved(.hls(url: url, isLive: false, audioOnlyURL: nil, captionTracks: []), userAgent: "FitrahTube/1.0")

        let player = PlayerHostView.player(for: .ready(resolved), replacing: nil)

        let asset = try #require(player?.currentItem?.asset as? AVURLAsset)
        #expect(asset.url == url)
    }

    @Test func readyStateAssetOptionsCarryTheResolvedUserAgent() {
        // `AVURLAsset` doesn't expose its `options` dictionary back out once built, so this asserts
        // the options `player(for:replacing:)` actually passes to `AVURLAsset(url:options:)` --
        // the only place the User-Agent value is inspectable.
        let options = PlayerHostView.assetOptions(userAgent: "FitrahTube/1.0")
        #expect(options[AVURLAssetHTTPUserAgentKey] as? String == "FitrahTube/1.0")
    }

    @Test func rung2ProgressiveStateAlsoBuildsAPlayer() throws {
        let url = URL(string: "https://example.com/a.mp4")!
        let resolved = Self.resolved(.progressive(url: url, label: "360p"))

        let player = PlayerHostView.player(for: .rung2Progressive(resolved), replacing: nil)

        let asset = try #require(player?.currentItem?.asset as? AVURLAsset)
        #expect(asset.url == url)
    }

    // MARK: - Non-playable states never build a player
    // Five plain functions, not `@Test(arguments:)` (`PlayerViewModelTests`'s pattern for
    // `ExtractionError`) -- `StreamState` isn't `Sendable` (Task 2's type, not this task's to touch)
    // and Swift Testing's `arguments:` needs its element type to cross that boundary.

    @Test func idleBuildsNoPlayer() {
        #expect(PlayerHostView.player(for: .idle, replacing: nil) == nil)
    }

    @Test func loadingBuildsNoPlayer() {
        #expect(PlayerHostView.player(for: .loading, replacing: nil) == nil)
    }

    @Test func contentUnavailableBuildsNoPlayer() {
        #expect(PlayerHostView.player(for: .contentUnavailable, replacing: nil) == nil)
    }

    @Test func errorBuildsNoPlayer() {
        #expect(PlayerHostView.player(for: .error(messageKey: "player_error_message"), replacing: nil) == nil)
    }

    @Test func cooldownBuildsNoPlayer() {
        #expect(PlayerHostView.player(for: .cooldown(until: Date()), replacing: nil) == nil)
    }

    // MARK: - Ruling 32: session-only resume -- replacing an existing player for the SAME URL keeps it

    @Test func sameURLReusesTheExistingPlayerInstance() {
        let url = URL(string: "https://example.com/stream.m3u8")!
        let resolved = Self.resolved(.hls(url: url, isLive: false, audioOnlyURL: nil, captionTracks: []))
        let first = PlayerHostView.player(for: .ready(resolved), replacing: nil)

        let second = PlayerHostView.player(for: .ready(resolved), replacing: first)

        #expect(second === first)
    }

    // MARK: - A state update to a DIFFERENT URL replaces the item, carrying `currentTime` over

    @Test func differentURLReplacesTheItemOnTheSamePlayer() throws {
        let firstURL = URL(string: "https://example.com/a.m3u8")!
        let secondURL = URL(string: "https://example.com/b.m3u8")!
        let first = Self.resolved(.hls(url: firstURL, isLive: false, audioOnlyURL: nil, captionTracks: []))
        let second = Self.resolved(.hls(url: secondURL, isLive: false, audioOnlyURL: nil, captionTracks: []))
        let player = PlayerHostView.player(for: .ready(first), replacing: nil)

        let updated = PlayerHostView.player(for: .ready(second), replacing: player)

        #expect(updated === player) // same AVPlayer instance, new item swapped in
        let asset = try #require(updated?.currentItem?.asset as? AVURLAsset)
        #expect(asset.url == secondURL)
    }

    // MARK: - A non-playable state tears playback down instead of leaving a stale player

    @Test func transitioningToANonPlayableStatePausesTheExistingPlayer() {
        let url = URL(string: "https://example.com/a.m3u8")!
        let resolved = Self.resolved(.hls(url: url, isLive: false, audioOnlyURL: nil, captionTracks: []))
        let player = PlayerHostView.player(for: .ready(resolved), replacing: nil)

        let result = PlayerHostView.player(for: .contentUnavailable, replacing: player)

        #expect(result == nil)
        #expect(player?.rate == 0) // paused, not left running detached from any view
    }
}
