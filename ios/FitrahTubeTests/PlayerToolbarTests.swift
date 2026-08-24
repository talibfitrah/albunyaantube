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
        let result = FavoriteToggle.perform(item: makeItem(), wasFavorite: true, store: store)
        #expect(result.isFavorite == false)
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
}

/// Minimal `FavoritesStore` fake -- `items`/`clearAll` are unused by `FavoriteToggle`, present
/// only to satisfy the protocol.
@MainActor private final class FakeFavoritesStore: FavoritesStore {
    enum Failure: Error { case saveFailed }

    private(set) var items: [FavoriteVideo] = []
    private(set) var toggledItems: [ContentItem] = []
    var errorToThrow: Error?

    func isFavorite(_ videoId: String) -> Bool { false }

    func toggle(_ item: ContentItem) throws {
        if let errorToThrow { throw errorToThrow }
        toggledItems.append(item)
    }

    func clearAll() throws {}
}
