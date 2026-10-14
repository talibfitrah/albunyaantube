import Foundation
import Testing
@testable import FitrahTube

/// Task 8 (`docs/superpowers/plans/2026-08-24-ios-phase2b1-player-core.md`): pure logic behind the
/// player metadata panel and toolbar -- the description link allow-list, the optimistic
/// favorite-toggle/revert, and the views line's `Format` usage. No SwiftUI/SwiftData involved.
@Suite(.perTest)
struct PlayerToolbarTests {

    // MARK: - attributedDescription (PlayerDescriptions.kt allow-list, ported)

    @Test func nonYouTubeHttpAndHttpsLinksAreKeptAndYouTubeLinksAreInert() {
        let raw = "Visit http://a.example or https://b.example/x?y=1, not https://youtu.be/V2Brp_esIVI, for more."
        let result = attributedDescription(raw)
        #expect(String(result.characters) == raw)
        let links = result.runs.compactMap(\.link).map(\.absoluteString)
        #expect(links == ["http://a.example", "https://b.example/x?y=1"])
    }

    /// Owner directives 2026-08-27 / 2026-10-14: a description never hands the user to YouTube. The
    /// link stays visible as text; only `https://example.org` is tappable. The two bare ones have no
    /// scheme in the text: `NSDataDetector` adds `http://` itself.
    @Test(arguments: [
        "https://youtu.be/V2Brp_esIVI",
        "https://www.youtube.com/channel/UC_x5XG1OV2P6uZZ5FSM9Ttw",
        "https://m.youtube.com/watch?v=V2Brp_esIVI",
        "https://MUSIC.YOUTUBE.COM/watch?v=V2Brp_esIVI",
        "youtube.com/watch?v=V2Brp_esIVI",
        "www.youtu.be/V2Brp_esIVI",
    ])
    func aYouTubeLinkInADescriptionStaysInertText(_ link: String) {
        let raw = "Watch \(link) and read https://example.org"
        let result = attributedDescription(raw)
        #expect(String(result.characters) == raw, "\(link): the text must stay visible")
        #expect(result.runs.compactMap(\.link).map(\.absoluteString) == ["https://example.org"], "\(link) must not be tappable")
    }

    /// The rule also runs on the text as written, not only on the detector's normalised URL: the
    /// detector drops a soft hyphen, so `match.url` alone passes it. A bare domain is judged with the
    /// `http://` the detector adds, so `example.org/b` stays a link and `ｙｏｕｔｕｂｅ.com/x` does not.
    @Test func theTextAsWrittenMustPassTheLinkRuleToo() {
        let raw = "See https://ex\u{AD}ample.org/a and example.org/b and ｙｏｕｔｕｂｅ.com/x here."
        let result = attributedDescription(raw)
        #expect(String(result.characters) == raw)
        #expect(result.runs.compactMap(\.link).map(\.absoluteString) == ["http://example.org/b"])
    }

    // MARK: - isOpenableLink (the vectors shared with Android's PlayerDescriptionsTest)

    @Test(arguments: [
        "https:youtube.com/watch?v=x",
        "HTTPS:YOUTU.BE/x",
        "intent://x#Intent;end",
        "albunyaantube://video/x",
        "javascript:alert(1)",
        "mailto:a@b.c",
        "https://youtu.be/abc",
        "https://www.youtube.com/channel/x",
        "https://m.youtube.com/watch?v=x",
        "https://MUSIC.YOUTUBE.COM/x",
        "https://www.youtube.de/x",
        "https://www.youtube.co.uk/x",
        "https://youtubekids.com/x",
        "https://youtube.googleapis.com/x",
        "https://x@youtube.com/a",
        "https://%79outube.com/a",
        "https://YOUTUBE.COM./a",
        "//youtube.com/a",
        "https://youtube.com\\@example.org/a",
        "https://\\youtube.com/a",
        "https://\u{FF59}\u{FF4F}\u{FF55}\u{FF54}\u{FF55}\u{FF42}\u{FF45}.com/a",   // fullwidth
        "https://youtube\u{3002}com/a",
        "https://you\u{AD}tube.com/a",
        "https://you\ttube.com/",
        "https://you\ntube.com/",
        "https://yt.be/x",
        "https://www.yt.be/x",
        "https://youtubego.com/",
        "https://withyoutube.com/",
        "https://youtubegaming.com/",
        "http://youtubemusic.com/",
        "http://youtubetv.com/",
        "https://youtubeshorts.com/",
        "https://y\u{1D52}utube.com/a",
        "https://\u{1F148}\u{1F13E}\u{1F144}\u{1F143}\u{1F144}\u{1F131}\u{1F134}.com/a",
        "https://you\u{1BCA0}tube.com/a",
        " https://example.org/a",
        "\u{A0}https://example.org/a",
        "https://example.org/a ",
    ])
    func theLinkRuleKeepsThisAsText(_ link: String) {
        #expect(!isOpenableLink(link), "\(link.debugDescription) must stay text")
    }

    /// `ভবিষ্যৎ.com`: Android lists it as NOT openable, but only as its accepted cost
    /// (`java.net.IDN` is Unicode 3.2 and refuses U+09CE). Foundation's IDNA knows the letter and
    /// converts the host to `xn--d6bbh5a2b7ei.com`, so iOS opens the real site. It is not YouTube
    /// on either platform.
    @Test(arguments: [
        "https://example.org/a",
        "http://Example.org",
        "https://youtube.com@example.org/a",
        "https://\u{645}\u{62B}\u{627}\u{644}.\u{645}\u{635}\u{631}/",   // مثال.مصر
        "https://bücher.de/",
        "https://notyoutube.com/x",
        "https://myyoutube.org/x",
        "https://notyt.be/x",
        "https://\u{9AD}\u{9AC}\u{9BF}\u{9B7}\u{9CD}\u{9AF}\u{9CE}.com/",   // ভবিষ্যৎ.com
    ])
    func theLinkRuleOpensThis(_ link: String) {
        #expect(isOpenableLink(link), "\(link.debugDescription) must open")
    }

    /// The rule's IDNA step is Foundation's: `URL(string:)` converts a non-ASCII host to ASCII
    /// (UTS 46, as a browser does) and refuses an unassigned code point. Pinned because the rule
    /// relies on it; were it ever to stop converting, non-ASCII hosts would fail closed. Read with
    /// `percentEncoded: false`: `host()` gives `b%C3%BCcher.de` on iOS 26.3 but `xn--…` on 18.6.
    @Test func foundationConvertsHostsToASCIIAndRefusesUnassignedCodePoints() {
        #expect(URL(string: "https://bücher.de/")?.host(percentEncoded: false) == "xn--bcher-kva.de")
        #expect(URL(string: "https://\u{FF59}\u{FF4F}\u{FF55}\u{FF54}\u{FF55}\u{FF42}\u{FF45}.com/")?.host(percentEncoded: false) == "youtube.com")
        #expect(URL(string: "https://you\u{0378}tube.com/") == nil)
    }

    /// The tap opens a lower-case scheme. Foundation keeps the case as written
    /// (`NSDataDetector` hands back `Https://…`), so `attributedDescription` lowers it.
    @Test func aLinkOpensWithItsSchemeLowercased() {
        let result = attributedDescription("Read Https://example.org/a or HTTP://Example.org today.")
        #expect(result.runs.compactMap(\.link).map(\.absoluteString) == ["https://example.org/a", "http://Example.org"])
    }

    @Test func nonHttpSchemesAreStrippedButTextIsKept() {
        // NSDataDetector never matches javascript:/data: at all (verified: no match produced), so
        // both are already inert text with no work needed; a custom app scheme (myapp://) IS
        // matched -- that's the case the scheme filter actually has to reject.
        let raw = "javascript:alert(1) then data:text/html;base64,AAA then myapp://open/1 stay inert."
        let result = attributedDescription(raw)
        #expect(String(result.characters) == raw)
        #expect(result.runs.allSatisfy { $0.link == nil })
    }

    @Test func plainTextWithNoLinksIsUntouched() {
        let raw = "No links in this description at all."
        let result = attributedDescription(raw)
        #expect(String(result.characters) == raw)
        #expect(result.runs.allSatisfy { $0.link == nil })
    }

    @Test func mixedTextKeepsHttpsAndDropsCustomScheme() {
        let raw = "Real link https://example.com/watch and fake albunyaantube://video/123 both stay in the text."
        let result = attributedDescription(raw)
        #expect(String(result.characters) == raw)
        let links = result.runs.compactMap(\.link).map(\.absoluteString)
        #expect(links == ["https://example.com/watch"])
    }

    // MARK: - FavoriteToggle (optimistic toggle + revert-on-failure)

    private func makeItem(id: String = "v1") -> ContentItem {
        ContentItem(id: id, type: .video, title: "Video", category: nil, description: nil, thumbnailURL: nil,
                    durationSeconds: 120, uploadedDaysAgo: nil, viewCount: nil, channelTitle: "Channel",
                    subscribers: nil, videoCount: nil, itemCount: nil)
    }

    @Test func togglingWhenNotFavoritedShowsAddedBannerAndFlipsToTrue() {
        let store = FakeFavoritesStore()
        let result = FavoriteToggle.perform(item: makeItem(), wasFavorite: false, store: store)
        #expect(result.isFavorite == true)
        #expect(result.banner.text == String(localized: "player_added_to_favorites"))
        #expect(store.toggledItems.map(\.id) == ["v1"])
    }

    @Test func togglingWhenFavoritedShowsRemovedBannerAndFlipsToFalse() {
        let store = FakeFavoritesStore()
        store.favoritedIds = ["v1"] // persisted state agrees with wasFavorite here (non-stale case)
        let result = FavoriteToggle.perform(item: makeItem(), wasFavorite: true, store: store)
        #expect(result.isFavorite == false)
        #expect(result.banner.text == String(localized: "player_removed_from_favorites"))
    }

    /// Pins the read-back fix: `perform` must report whatever the store actually persisted, not
    /// `!wasFavorite`. Here the caller's belief (`wasFavorite: false`, e.g. from the `.task` seed
    /// gap in `PlayerToolbar`) is stale -- the store already has it favorited -- so `toggle` flips
    /// it to *not* favorited. The negation of the stale belief would wrongly claim `true`.
    @Test func toggleReadsBackTheStoresActualStateWhenTheCallersBeliefIsStale() {
        let store = FakeFavoritesStore()
        store.favoritedIds = ["v1"] // store disagrees with the caller's `wasFavorite` below
        let result = FavoriteToggle.perform(item: makeItem(), wasFavorite: false, store: store)
        #expect(result.isFavorite == false) // store's true state after toggling out of "v1"
        // T8-R1: the banner text must key on the same post-toggle read as `isFavorite` above, not
        // on the caller's stale `wasFavorite` -- negating the stale belief would wrongly announce
        // "added" for what was actually a removal.
        #expect(result.banner.text == String(localized: "player_removed_from_favorites"))
    }

    @Test func aThrowingStoreRevertsToThePreToggleStateAndShowsAnErrorBanner() {
        let store = FakeFavoritesStore()
        store.errorToThrow = FakeFavoritesStore.Failure.saveFailed

        let result = FavoriteToggle.perform(item: makeItem(), wasFavorite: false, store: store)

        #expect(result.isFavorite == false) // reverted -- store never actually toggled
        #expect(result.banner.text == String(localized: "player_favorite_toggle_error"))
    }

    @Test func aThrowingStoreOnRemoveAlsoRevertsToFavorited() {
        let store = FakeFavoritesStore()
        store.errorToThrow = FakeFavoritesStore.Failure.saveFailed

        let result = FavoriteToggle.perform(item: makeItem(), wasFavorite: true, store: store)

        #expect(result.isFavorite == true)
        #expect(result.banner.text == String(localized: "player_favorite_toggle_error"))
    }

    // MARK: - Views line (ruling 37: ONE formatter, Phase-1 `Format`)

    @Test func viewsLineUsesFormatCompactCount() {
        let locale = Locale(identifier: "en_US")
        let text = PlayerMetadataView.viewsText(viewCount: 12_700_000, locale: locale)
        #expect(text.contains(Format.compactCount(12_700_000, locale: locale)))
        #expect(text == Format.localizedFormat("video_views", locale: locale, "12.7M", Int64(1_000_000)))
    }

    @Test func viewsLineFallsBackToNoViewsWhenNil() {
        let text = PlayerMetadataView.viewsText(viewCount: nil, locale: Locale(identifier: "en_US"))
        #expect(text == String(localized: "player_no_views"))
    }

    // MARK: - Cast slot (Phase 3 Task 8)

    /// Spec §10's "Cast SDK is not loaded at all when `GCKCastContext` cannot be created": a
    /// controller whose `setUp()` never ran (or ran and failed) leaves `castAvailable == false`,
    /// and the toolbar then has no cast slot at all -- not a disabled one.
    ///
    /// Note (review Minor 8): the test HOST is the app, whose `AppDelegate.didFinishLaunching`
    /// has already created a real shared `GCKCastContext` in this process. That is why the
    /// assertion below is about a fresh container's controller and not about the process:
    /// `castAvailable` is per-instance and only `setUp()` writes it, so a fixture container that
    /// never called it reports false regardless. `PlayerToolbarLayoutTests` pins the render.
    @Test func theCastSlotIsHiddenUntilTheCastContextExists() {
        #expect(AppContainer.fake().castController.castAvailable == false)
        #expect(CastAffordance.isVisible(castAvailable: false, isOfflinePlayback: false) == false)
        #expect(CastAffordance.isVisible(castAvailable: true, isOfflinePlayback: false))
    }

    /// Task 7's `isOfflinePlayback` gates the cast slot exactly as it gates Save: a saved file
    /// lives in the app sandbox and no receiver can fetch it, so casting it is impossible -- and
    /// the media file must never leave the sandbox in any case.
    @Test func theOfflinePlayerHidesTheCastSlotEvenWhenCastIsAvailable() {
        #expect(CastAffordance.isVisible(castAvailable: true, isOfflinePlayback: true) == false)
    }
}

/// Minimal `FavoritesStore` fake -- `items`/`clearAll` are unused by `FavoriteToggle`, present
/// only to satisfy the protocol.
@MainActor private final class FakeFavoritesStore: FavoritesStore {
        /// Task 30: no fake here imports anything, so the awaiting queue is empty by
        /// construction — never a stored `var` a test could set and then forget to clear.
        var awaitingItems: [FavoriteVideo] { [] }
        func reload() {}
    enum Failure: Error { case saveFailed }

    /// Task 9: `UserScoped` is now a requirement of the protocol; this fake is never re-scoped.
    var currentUserId: String = ""

    private(set) var items: [FavoriteVideo] = []
    private(set) var toggledItems: [ContentItem] = []
    var errorToThrow: Error?
    /// Persisted state, settable by tests -- mirrors `SwiftDataFavoritesStore.toggle` flipping the
    /// record it actually holds, independently of any caller's `wasFavorite` belief.
    var favoritedIds: Set<String> = []

    func isFavorite(_ videoId: String) -> Bool { favoritedIds.contains(videoId) }

    func toggle(_ item: ContentItem) throws {
        if let errorToThrow { throw errorToThrow }
        toggledItems.append(item)
        if favoritedIds.contains(item.id) {
            favoritedIds.remove(item.id)
        } else {
            favoritedIds.insert(item.id)
        }
    }

    func clearAll() throws {}

    /// Task 28: this fake keeps no tombstones, so "any state" is the same set.
    func containsAny(_ videoId: String) -> Bool { favoritedIds.contains(videoId) }

    func importVideo(id: String, title: String, channelName: String, thumbnailUrl: String?,
                     durationSeconds: Int, approvalStatus: String, at: Date) throws {
        if let errorToThrow { throw errorToThrow }
        favoritedIds.insert(id)
    }
}

// MARK: - Task 5 UI preservation: the 4-button row at accessibility sizes

import GoogleCast
import SwiftUI
import UIKit

/// The Save slot must join the row without breaking the toolbar's one-row shape — at
/// accessibility Dynamic Type, in RTL. `ImageRenderer`, not a hosted window: it renders
/// synchronously (no `.task` races, deterministic pixels) and its ideal size is a real
/// discriminator — a fourth button widens the row's ideal width and, if it broke the
/// horizontal layout, would grow its height.
@MainActor
@Suite(.perTest)
struct PlayerToolbarLayoutTests {
    private func idealSize(gate: GateAnswer?, width: CGFloat?) -> CGSize {
        let toolbar = PlayerToolbar(args: PlayerArgs(videoId: "layout", title: "Layout"),
                                    saveGate: gate, saveEnabled: true)
            .environment(\.container, .sharedFake)
            .environment(\.layoutDirection, .rightToLeft)
            .environment(\.locale, Locale(identifier: "ar"))
            .dynamicTypeSize(.accessibility3)
        let renderer = ImageRenderer(content: toolbar)
        renderer.proposedSize = ProposedViewSize(width: width, height: nil)
        return renderer.uiImage?.size ?? .zero
    }

    @Test func theSaveSlotRendersAndKeepsTheRowHeightAtAccessibilitySizesInRTL() {
        // Ideal (unconstrained) size: the fourth button must WIDEN the row — proof it renders —
        // while the row height stays that of the tallest existing caption.
        let three = idealSize(gate: nil, width: nil)
        let four = idealSize(gate: .allowed, width: nil)
        #expect(four.width > three.width, "gate .allowed added no width — the save slot is missing")
        #expect(abs(four.height - three.height) < 1, "the save slot reflowed the row: \(three.height) -> \(four.height)")

        // Constrained to the narrowest phone width at accessibility3: captions may wrap inside
        // their (now narrower) columns, but the fourth button must not stack the row vertically.
        // The reference is the 3-button toolbar at the SAME width — wrapping grows both alike; a
        // second stacked row would add a whole button's height (icon + padding + caption ≥160pt),
        // far beyond the ≤3 extra caption lines (~40pt each at accessibility3) allowed here.
        // Measured 2026-09-01: iPhone 17 narrow3≈narrow4≈165pt; iPad narrow3=149, narrow4=241
        // (the ar save caption wraps to 3 lines in its 80pt column — taller column, still one row).
        let narrow3 = idealSize(gate: nil, width: 320)
        let narrow4 = idealSize(gate: .allowed, width: 320)
        #expect(narrow4.height <= narrow3.height + 130,
                "four-button toolbar no longer fits one row at 320pt: \(narrow3.height) -> \(narrow4.height)")
    }

    /// Action row 1.0.3: the captions wrap inside their equal columns, they are never truncated (no
    /// `lineLimit` on the slot caption; accessibility Dynamic Type must stay readable). The
    /// unconstrained ideal size is the SINGLE-line row; at 320 pt the equal columns are narrower than
    /// the accessibility3 captions, so the row must come out taller by at least a caption line. A
    /// `lineLimit(1)` or a fixed-height caption would leave the two equal and fail this. An XCUITest
    /// cannot assert it: the captions are not separate elements inside the slot buttons.
    @Test func captionsWrapInsteadOfTruncatingAtAccessibilitySizes() {
        let singleLine = idealSize(gate: .allowed, width: nil)
        let narrow = idealSize(gate: .allowed, width: 320)
        #expect(narrow.height > singleLine.height + 20,
                "captions did not wrap in the 320 pt columns (single-line row \(singleLine.height), narrow row \(narrow.height)) — truncated?")
    }

    /// Task 8 fix round 1 (review Minor 7): the requirement is "the TOOLBAR hides the button", and
    /// asserting `CastAffordance.isVisible`'s truth table alone stays green if someone drops the
    /// guard from the view. Same `ImageRenderer` width discriminator the Save slot uses above; the
    /// `setUp()` in the middle picks up the shared `GCKCastContext` the app host's own launch
    /// already created, which is all `castAvailable` needs.
    @Test func theToolbarItselfShowsTheCastSlotOnlyWhenAvailableAndNotOffline() {
        let container = AppContainer.fake(
            defaults: UserDefaults(suiteName: "PlayerToolbarLayoutTests.\(UUID().uuidString)")!)
        func width(offline: Bool) -> CGFloat {
            let toolbar = PlayerToolbar(args: PlayerArgs(videoId: "layout", title: "Layout"),
                                        saveGate: nil, saveEnabled: true, isOfflinePlayback: offline)
                .environment(\.container, container)
            return ImageRenderer(content: toolbar).uiImage?.size.width ?? 0
        }
        let hidden = width(offline: false)
        #expect(container.castController.castAvailable == false)

        container.castController.setUp()
        #expect(container.castController.castAvailable, "the app host's launch never created a context")
        #expect(width(offline: false) > hidden, "castAvailable added no width — the toolbar ignores it")
        // Task 7's flag still wins: an offline player never offers cast (a sandboxed file no
        // receiver can fetch), even with a live context.
        #expect(width(offline: true) == hidden, "the offline player still rendered a cast slot")
    }

    /// Cubic R2-6 + re-review m3: the cast slot must reach the ≥44 pt touch target the row's other
    /// four slots get from their `Button` — WITHOUT growing the row. Measured on iPhone 17,
    /// 2026-09-02: the shipped slot costs 3.0 pt (its `.frame(minHeight: 44)` against ~41 pt
    /// siblings — that IS the accessibility floor, so it stays), while sizing the
    /// `GCKUICastButton` itself to 44×44 costs 22.0 pt and drops the cast caption out of line with
    /// the other four. Hence the tap-replay shape, and hence this bound: it passes at 3 and fails
    /// at 22. The `phase3-player-save-cast` screenshot is the other half of the evidence.
    @Test func theCastSlotWidensTheRowWithoutGrowingIt() {
        let container = AppContainer.fake(
            defaults: UserDefaults(suiteName: "PlayerToolbarLayoutTests.\(UUID().uuidString)")!)
        func size() -> CGSize {
            let toolbar = PlayerToolbar(args: PlayerArgs(videoId: "layout", title: "Layout"),
                                        saveGate: nil, saveEnabled: true)
                .environment(\.container, container)
            return ImageRenderer(content: toolbar).uiImage?.size ?? .zero
        }
        let without = size()
        container.castController.setUp()
        let with = size()
        #expect(with.width > without.width, "castAvailable added no width — the cast slot is missing")
        #expect(with.height - without.height < 4,
                "the cast slot grew the row: \(without.height) -> \(with.height)")
    }

    /// The slot's own `Button` replays the tap into the SDK button with
    /// `sendActions(for: .touchUpInside)` — the SDK still owns the glyph, its cast states and the
    /// device chooser. That replay only works while `GCKUICastButton` handles the event through
    /// target/action, so pin it: without this the whole slot would be silently inert.
    @Test func theCastButtonAnswersAReplayedTouchUpInside() {
        let button = GCKUICastButton(frame: CGRect(x: 0, y: 0, width: 24, height: 24))
        #expect(button.allControlEvents.contains(.touchUpInside),
                "GCKUICastButton no longer handles .touchUpInside — the cast slot's tap replay is inert")
    }
}
