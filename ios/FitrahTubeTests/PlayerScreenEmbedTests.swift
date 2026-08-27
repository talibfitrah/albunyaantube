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

    /// Ruling 19: an unsupported (or regional) tag is clamped to a catalog language before it
    /// reaches the page. `EmbedPage.html` also defaults `hl` to en on its own (a3302261), so this is
    /// belt and braces -- but the CHOICE of catalog language is the app's, not the page's.
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

    /// C1 (Task 4 review): `webView(_:decidePolicyFor:decisionHandler:)` is an OPTIONAL protocol
    /// requirement, so a signature that only NEARLY matches the SDK's compiles with a warning,
    /// gets no `@objc` thunk, and is never called -- WebKit then allows every navigation and the
    /// rung's containment (plan §6.4 row 3, §6.10) is silently gone. `EmbedNavigationPolicyTests`
    /// pins what the lock DECIDES; this pins that it is installed at all, which is the half that
    /// went missing. Selectors, not signatures, because the ObjC runtime is the thing that broke.
    @Test func theNavigationLockIsInstalledOnTheCoordinator() {
        let settings = UserDefaultsSettingsStore(
            defaults: UserDefaults(suiteName: "PlayerScreenEmbedTests.\(UUID().uuidString)")!)
        let model = PlayerViewModel(resolver: RecordingResolver(.hls), settings: settings,
                                    args: PlayerArgs(videoId: "dQw4w9WgXcQ", channelId: "ch1"))
        let coordinator = EmbedWebView.Coordinator(videoId: "dQw4w9WgXcQ", resolved: Self.embed(),
                                                   model: model, locale: "en")
        #expect(coordinator.responds(to: Selector("webView:decidePolicyForNavigationAction:decisionHandler:")))
        // The other half of the lock: without this `window.open` / `target="_blank"` escapes it.
        #expect(coordinator.responds(
            to: Selector("webView:createWebViewWithConfiguration:forNavigationAction:windowFeatures:")))
    }

    /// I2 (Task 4 review): the offline gate above swaps `EmbedRungView` for the offline card, so the
    /// "Playing in YouTube's player" announcement would describe a screen that is not on the screen.
    /// The visible and audible halves of a transition have to agree.
    @Test func theEmbedTransitionIsAnnouncedOnlyWhenTheEmbedActuallyMounts() {
        #expect(PlayerScreen.transitionAnnouncement(for: .embed(Self.embed()), isOnline: true)
                == String(localized: "player_embed_caption"))
        #expect(PlayerScreen.transitionAnnouncement(for: .embed(Self.embed()), isOnline: false) == nil)
        // Rung 2's announcement is not connectivity-gated: it plays from an already-resolved URL.
        #expect(PlayerScreen.transitionAnnouncement(for: .rung2Progressive(Self.embed()), isOnline: false)
                == String(localized: "player_announce_standard_quality"))
        #expect(PlayerScreen.transitionAnnouncement(for: .loading, isOnline: true) == nil)
        #expect(PlayerScreen.transitionAnnouncement(for: nil, isOnline: true) == nil)
    }
}
