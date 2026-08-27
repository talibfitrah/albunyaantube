import AVFoundation
import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// B3 task 4 (`docs/superpowers/plans/2026-08-27-ios-phase2b3-embed-safemode.md`): the embed rung is
/// a DIFFERENT playback surface with no `AVPlayer` at all. Everything decidable about it outside a
/// live `WKWebView` is pinned here -- the native host must refuse it, Now Playing must stay empty
/// for it, the end cover must key off exactly one IFrame state, and an offline entry must land on
/// the offline card instead of loading a web view that cannot reach YouTube.
@MainActor
@Suite(.perTest)
struct PlayerScreenEmbedTests {
    private static func embed(_ videoId: String = "dQw4w9WgXcQ") -> Resolved {
        Resolved(stream: .embed(videoId: videoId), client: .web, userAgent: "",
                 resolvedAt: Date(), expiresAt: nil)
    }

    @Test func theEmbedRungHasNoAVPlayerAndPausesTheOldOne() {
        // CF-B2-4: `onPolicyAction` reads `model.state` live, so a background policy action that
        // fires during a native->embed transition sees `.embed`. `player(for:)` must then pause and
        // release the outgoing player rather than swap its URL.
        let existing = AVPlayer(playerItem: AVPlayerItem(url: URL(string: "https://x/y.m3u8")!))
        existing.play()
        #expect(PlayerHostView.player(for: .embed(Self.embed()), replacing: existing) == nil)
        #expect(existing.rate == 0)
        #expect(PlayerHostView.streamURL(.embed(videoId: "dQw4w9WgXcQ")) == nil)
    }

    @Test func endedStateShowsTheCoverAndNothingElseDoes() {
        // The IFrame API's ENDED is 0; -1 unstarted, 1 playing, 2 paused, 3 buffering, 5 cued.
        #expect(EmbedRungView.showsEndCover(for: .state(0)))
        for other in [-1, 1, 2, 3, 5] {
            #expect(EmbedRungView.showsEndCover(for: .state(other)) == false)
        }
        #expect(EmbedRungView.showsEndCover(for: .ready) == false)
        #expect(EmbedRungView.showsEndCover(for: .error(150)) == false)
    }

    /// Ruling 19 + `EmbedPage.html`'s `supportedLocales` guard: an unsupported (or regional) tag
    /// must be clamped to a catalog language BEFORE it reaches the page, or `html()` refuses and the
    /// rung fails on a locale the app itself chose.
    @Test func theEmbedLocaleIsClampedToTheThreeCatalogLanguages() {
        #expect(EmbedRungView.embedLocale(Locale(identifier: "ar")) == "ar")
        #expect(EmbedRungView.embedLocale(Locale(identifier: "nl-BE")) == "nl")
        #expect(EmbedRungView.embedLocale(Locale(identifier: "en-US")) == "en")
        #expect(EmbedRungView.embedLocale(Locale(identifier: "fr-FR")) == "en")
        #expect(EmbedPage.html(videoId: "dQw4w9WgXcQ",
                               locale: EmbedRungView.embedLocale(Locale(identifier: "fr-FR")),
                               captionsPreferred: false) != nil)
    }

    /// Controller carry-in: the `.embed` ENTRY is offline-gated. A `WKWebView` pointed at
    /// `youtube-nocookie.com` with no network renders a black frame under a caption that claims
    /// something is playing -- the same wrong surface the offline gate already fixed for
    /// `.idle`/`.loading`/`.error` (I2, B1 final review). One offline card, three states plus this.
    @Test func anOfflineEmbedEntryFallsBackToTheOfflineCard() {
        let copy = PlayerStateCopy.map(.embed(Self.embed()), isOnline: false)
        #expect(copy.message == String(localized: "connectivity_offline_banner"))
        #expect(copy.showsRetry)
        #expect(copy.announces == false)
    }
}
