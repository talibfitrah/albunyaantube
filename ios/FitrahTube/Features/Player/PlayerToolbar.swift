import SwiftUI

/// The action row between the player and the metadata panel (spec §10, Android's `PlayerFragment`
/// action-row placement): Favorite, Share, Report. Download is Phase 3 (ruling 28, plan global
/// constraints) -- there is no `DownloadKit` reference in this app yet, so this renders no button
/// at all rather than a permanently-disabled one; a disabled placeholder would be its own kind of
/// lie ("this will work once you tap enough") for a feature with no wiring behind it.
struct PlayerToolbar: View {
    let args: PlayerArgs

    @Environment(\.container) private var container
    @Environment(\.widthClass) private var widthClass
    @State private var isFavorite = false
    @State private var bannerMessage: BannerMessage?

    var body: some View {
        HStack {
            favoriteButton
            Spacer()
            ShareLink(item: args.shareURL, subject: Text(args.title ?? args.videoId)) {
                toolbarLabel(systemImage: "square.and.arrow.up", title: String(localized: "player_action_share"))
            }
            .accessibilityIdentifier("player.shareButton")
            Spacer()
            reportButton
        }
        .padding(.horizontal, Spacing.md(widthClass))
        .padding(.vertical, Spacing.sm)
        .transientBanner($bannerMessage)
        .task {
            isFavorite = container.favorites.isFavorite(args.videoId)
        }
    }

    private var favoriteButton: some View {
        Button(action: toggleFavorite) {
            toolbarLabel(systemImage: isFavorite ? "heart.fill" : "heart",
                        title: String(localized: isFavorite ? "player_action_favorited" : "player_action_favorite"))
        }
        .accessibilityIdentifier("player.favoriteButton")
        // Task 10 (spec §6.11 "Favorite, Not favorited" example): a constant role label + a value
        // that carries the toggled state, plus `.isSelected` (same toggle idiom `NavigationRailView`/
        // `SettingsView`'s theme rows already use) -- rather than the visible caption's own
        // Favorite/Favorited swap doubling as the accessibility label.
        .accessibilityLabel(String(localized: "player_action_favorite"))
        .accessibilityValue(String(localized: isFavorite ? "player_action_favorited" : "player_action_not_favorited"))
        .accessibilityAddTraits(isFavorite ? [.isSelected] : [])
    }

    /// `SwiftDataFavoritesStore.isFavorite` reads live from the SwiftData context on every call --
    /// it is not a tracked `@Observable` stored property, so nothing re-renders this view when a
    /// favorite changes elsewhere. `isFavorite` above is therefore local `@State`, flipped
    /// optimistically here for instant feedback and reverted if the store throws -- the
    /// SwiftData-store side of that (a failed save rolling its own mutation back) already happens
    /// inside `SwiftDataFavoritesStore.toggle`; `FavoriteToggle.perform` only has to keep this
    /// view's own `@State` in sync with whichever outcome actually happened.
    private func toggleFavorite() {
        let result = FavoriteToggle.perform(item: args.contentItem, wasFavorite: isFavorite, store: container.favorites)
        isFavorite = result.isFavorite
        bannerMessage = result.banner
    }

    private var reportButton: some View {
        Button {
            // ponytail: Plan C replaces this banner with the real report flow (VIDEO, with parent
            // PLAYLIST/CHANNEL + subtype, per spec §10). B1 only needs the button to do something
            // honest -- a silent no-op is a VoiceOver dead-end -- so it shows the same
            // `transientBanner` mechanism the favorite toast above already uses.
            bannerMessage = BannerMessage(text: String(localized: "player_report_coming_soon"))
        } label: {
            toolbarLabel(systemImage: "flag", title: String(localized: "player_action_report"))
        }
        .accessibilityIdentifier("player.reportButton")
    }

    private func toolbarLabel(systemImage: String, title: String) -> some View {
        VStack(spacing: 4) {
            Image(systemName: systemImage)
            Text(title).font(TypeScale.caption)
        }
        .foregroundStyle(Color.textPrimary)
    }
}

extension PlayerArgs {
    /// B4 task 3: shared by `PlayerToolbar` and `ShortsOverlay`'s rail -- ONE share URL, always the
    /// app's own (owner directive 2026-08-27: never a youtube.com / youtu.be link).
    /// Spec §10 / plan global constraints: no "ad-free" in the shared text (OG publish is Phase 4
    /// -- CF). `!` is safe: videoId is always URL-path-safe (YouTube's fixed 11-char alphabet),
    /// same assumption `DeepLinkParser`/`Route` already make elsewhere.
    var shareURL: URL {
        URL(string: "https://app.fitrahtube.com/api/watch/\(videoId)")!
    }

    /// The favorites-store shape of this video, for `FavoriteToggle.perform`.
    var contentItem: ContentItem {
        ContentItem(id: videoId, type: .video, title: title ?? videoId, category: nil,
                    description: description, thumbnailURL: thumbnailURL,
                    durationSeconds: durationSeconds, uploadedDaysAgo: nil, viewCount: viewCount,
                    channelTitle: channelName, subscribers: nil, videoCount: nil, itemCount: nil)
    }
}

/// Pure optimistic-toggle logic (TDD'd in `PlayerToolbarTests` against a fake throwing
/// `FavoritesStore`, no SwiftUI/SwiftData involved): the banner text is chosen from the PRE-toggle
/// state (Phase-1 `FavoritesView` failure-banner pattern), so it reads correctly whether the
/// toggle actually happened or not. `isFavorite` on the success path is read back from the store
/// (not `!wasFavorite`) -- the store flips whatever it actually has persisted, independently of
/// the caller's belief, which can be stale (e.g. `PlayerToolbar`'s `.task` seed hasn't completed
/// yet when the user taps). Negating a stale belief reports the opposite of reality.
enum FavoriteToggle {
    struct Result {
        let isFavorite: Bool
        let banner: BannerMessage
    }

    static func perform(item: ContentItem, wasFavorite: Bool, store: any FavoritesStore) -> Result {
        do {
            try store.toggle(item)
            // T8-R1 fix: the banner text used to be keyed on `wasFavorite` (the caller's PRE-toggle
            // belief) while `isFavorite` above was already correctly read back from the store --
            // when that belief is stale (see `toggleReadsBackTheStoresActualStateWhenTheCallersBeliefIsStale`
            // below), the two disagreed and the banner announced the opposite of what happened.
            // Both are now keyed on the same post-toggle read.
            let now = store.isFavorite(item.id)
            return Result(isFavorite: now, banner: BannerMessage(
                text: String(localized: now ? "player_added_to_favorites" : "player_removed_from_favorites")))
        } catch {
            return Result(isFavorite: wasFavorite, banner: BannerMessage(text: String(localized: "player_favorite_toggle_error")))
        }
    }
}

#if DEBUG
#Preview {
    PlayerToolbar(args: PlayerArgs(videoId: "preview", title: "Preview video"))
        .environment(\.container, .sharedFake)
}
#endif
