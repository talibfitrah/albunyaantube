import SwiftUI

/// The action row between the player and the metadata panel (spec §10, Android's `PlayerFragment`
/// action-row placement): Favorite, Share, Report, and — Phase 3 Task 5 — Save for offline.
/// The Save slot renders per `SaveAffordance.state` (gate × kill-switch × item status,
/// `OfflineGateTests`' table): nothing at all until the gate affirms (fail-closed; the
/// kill-switch OFF state hides it silently), the quality sheet when saveable, live progress
/// while an item runs, Open when it completed.
struct PlayerToolbar: View {
    let args: PlayerArgs
    /// The per-open `offlineAllowed` answer, fetched once by `PlayerScreen` (reconciliation
    /// note 3); nil = not landed yet = hidden. Defaults keep fixture/preview call sites on the
    /// pre-Phase-3 three-button row.
    var saveGate: GateAnswer? = nil
    /// The remote config's kill-switch (`RemoteConfig.isDownloadsEnabled`), read per open.
    var saveEnabled: Bool = true

    @Environment(\.container) private var container
    @Environment(\.widthClass) private var widthClass
    @Environment(\.locale) private var locale
    @State private var isFavorite = false
    @State private var bannerMessage: BannerMessage?
    @State private var showReport = false
    @State private var showSaveSheet = false

    var body: some View {
        HStack {
            favoriteButton
            Spacer()
            ShareLink(item: args.shareURL, subject: Text(args.title ?? args.videoId),
                      message: Text(args.shareMessage(locale: locale))) {
                toolbarLabel(systemImage: "square.and.arrow.up", title: String(localized: "player_action_share"))
            }
            .accessibilityIdentifier("player.shareButton")
            Spacer()
            reportButton
            if saveButtonState != .hidden {
                Spacer()
                saveSlot
            }
        }
        .padding(.horizontal, Spacing.md(widthClass))
        .padding(.vertical, Spacing.sm)
        .transientBanner($bannerMessage)
        .sheet(isPresented: $showReport) {
            ReportSheet(context: args.reportContext) {
                bannerMessage = BannerMessage(text: String(localized: "report_success"))
            }
        }
        .sheet(isPresented: $showSaveSheet) {
            SaveOfflineSheet(args: args)
        }
        // `.task(id:)`, not `.task` (Cubic #12): `PlayerViewModel.swapArgs` mutates `args` in place
        // on every advance / Up Next tap, and an id-less task runs once per view lifetime -- so the
        // heart kept the FIRST video's favorite state for the whole queue.
        .task(id: args.videoId) {
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
            showReport = true
        } label: {
            toolbarLabel(systemImage: "flag", title: String(localized: "player_action_report"))
        }
        .accessibilityIdentifier("player.reportButton")
    }

    // MARK: - Save for offline (Phase 3 Task 5)

    /// The row for this video, live from the `@Observable` store — progress re-renders as the
    /// manager persists `bytesWritten` (Task 4's throttle), no extra plumbing.
    private var offlineItem: OfflineItem? {
        container.offlineStore.items.first { $0.videoId == args.videoId }
    }

    private var saveButtonState: SaveButtonState {
        SaveAffordance.state(gate: saveGate, downloadsEnabled: saveEnabled,
                             itemStatus: offlineItem.flatMap { OfflineStatus(rawValue: $0.status) })
    }

    /// The `favoriteButton` a11y idiom: a constant role label (`offline_save`) plus a value that
    /// carries the current state, instead of the visible caption doubling as the label.
    @ViewBuilder private var saveSlot: some View {
        switch saveButtonState {
        case .save:
            Button {
                showSaveSheet = true
            } label: {
                toolbarLabel(systemImage: "arrow.down.circle", title: String(localized: "offline_save"))
            }
            .accessibilityIdentifier("player.saveButton")
            .accessibilityLabel(String(localized: "offline_save"))
        case .progress:
            VStack(spacing: 4) {
                progressRing
                Text(progressCaption).font(TypeScale.caption)
            }
            .foregroundStyle(Color.textPrimary)
            .accessibilityElement(children: .ignore)
            .accessibilityIdentifier("player.saveProgress")
            .accessibilityLabel(String(localized: "offline_save"))
            .accessibilityValue(progressCaption)
        case .open:
            // Task 6 wires Route.offline (the offline player) -- until then the completed state
            // renders its label/a11y with a no-op action rather than a route case this task
            // isn't allowed to add.
            Button {} label: {
                toolbarLabel(systemImage: "checkmark.circle", title: String(localized: "offline_action_open"))
            }
            .accessibilityIdentifier("player.saveButton")
            .accessibilityLabel(String(localized: "offline_save"))
            .accessibilityValue(String(localized: "offline_status_completed"))
        case .hidden:
            EmptyView()
        }
    }

    /// Determinate ring, icon-sized. Not `ProgressView(value:).progressViewStyle(.circular)` --
    /// on iOS that renders the indeterminate spinner regardless of the value.
    private var progressRing: some View {
        let fraction = offlineItem.flatMap { item in
            item.totalBytes.flatMap { $0 > 0 ? Double(item.bytesWritten) / Double($0) : nil }
        } ?? 0
        return ZStack {
            Circle().stroke(Color.textPrimary.opacity(0.2), lineWidth: 2)
            Circle().trim(from: 0, to: min(max(fraction, 0), 1))
                .stroke(Color.brand, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .frame(width: 20, height: 20)
    }

    private var progressCaption: String {
        switch offlineItem.flatMap({ OfflineStatus(rawValue: $0.status) }) {
        case .paused: String(localized: "offline_status_paused")
        case .running: String(localized: "offline_status_saving")
        default: String(localized: "offline_status_queued")
        }
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
    /// app's own via `ShareLinks` (owner directive 2026-08-27: never a youtube.com / youtu.be link).
    var shareURL: URL { ShareLinks.video(videoId) }

    /// The share body (title + "Watch in FitrahTube" + promo; the URL is its own activity item).
    func shareMessage(locale: Locale) -> String {
        ShareLinks.message(for: .video(videoId), title: title ?? videoId, locale: locale)
    }

    /// Plan C task 3: the player's report context. Parent precedence is Android's kebab path
    /// (`PlayerFragment.kt:1832-1838`): playlist over channel over none.
    var reportContext: ReportContext {
        ReportContext(targetType: .video, targetId: videoId,
                      parentType: playlistId != nil ? .playlist : (channelId != nil ? .channel : nil),
                      parentId: playlistId ?? channelId, contentSubType: isLive ? .livestream : nil)
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
