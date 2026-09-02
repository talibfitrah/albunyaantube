import Foundation
import Testing
@testable import FitrahTube

/// Task 8 (`docs/superpowers/plans/2026-08-24-ios-phase2b1-player-core.md`): pure logic behind the
/// player metadata panel and toolbar -- the description link allow-list, the optimistic
/// favorite-toggle/revert, and the views line's `Format` usage. No SwiftUI/SwiftData involved.
@Suite(.perTest)
struct PlayerToolbarTests {

    // MARK: - attributedDescription (PlayerDescriptions.kt allow-list, ported)

    @Test func httpAndHttpsLinksAreKept() {
        let raw = "Visit http://a.example or https://b.example/x?y=1 for more."
        let result = attributedDescription(raw)
        #expect(String(result.characters) == raw)
        let links = result.runs.compactMap(\.link).map(\.absoluteString)
        #expect(links == ["http://a.example", "https://b.example/x?y=1"])
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
    /// and the toolbar then has no cast slot at all -- not a disabled one. Fixture container, so
    /// nothing here creates a Cast context.
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
    enum Failure: Error { case saveFailed }

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
}

// MARK: - Task 5 UI preservation: the 4-button row at accessibility sizes

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
}
