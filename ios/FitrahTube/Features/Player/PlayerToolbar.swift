import SwiftUI

/// The save sheet's presentation identity (`.sheet(item:)`, Task 5 fold-in 2): the videoId, so the
/// sheet stays pinned to the video whose Save was tapped even if the toolbar's `args` advance.
///
/// A wrapper carrying the args, NOT `PlayerArgs: Identifiable` (Cubic P3-2): that conformance was
/// app-wide though only this one sheet needed it, and any future `ForEach`/`sheet` over
/// `PlayerArgs` would then silently treat two different args for the same video (online vs the
/// `offlineItemId` variant) as one identity. Nothing outside this file constructs it.
nonisolated struct SaveSheetArgs: Identifiable {
    let args: PlayerArgs
    var id: String { args.videoId }
}

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
    /// Task 7 (`PlayerViewModel.isOfflinePlayback`): while playing a saved file the whole Save
    /// slot is hidden — there is nothing to save and Open would open the screen it's on.
    /// favorite/share/report stay (link sharing is allowed; only media files never leave).
    var isOfflinePlayback: Bool = false

    @Environment(\.container) private var container
    @Environment(\.router) private var router
    @Environment(\.widthClass) private var widthClass
    @Environment(\.locale) private var locale
    @State private var isFavorite = false
    @State private var bannerMessage: BannerMessage?
    @State private var showReport = false
    /// Task 5 review fold-in 2: the args CAPTURED at tap time, not a `Bool` re-reading `self.args`
    /// — `PlayerViewModel.swapArgs` replaces `args` in place on a queue auto-advance, and an
    /// `isPresented` sheet re-evaluating its content closure mid-selection would silently re-aim
    /// the quality picker (and its Save) at the advanced-to video. `.sheet(item:)` keeps the
    /// tapped video; its identity is `SaveSheetArgs.id`
    /// (`SavedScreenTests.theSaveSheetIdentityIs...`).
    @State private var saveSheetArgs: SaveSheetArgs?

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
            if !isOfflinePlayback, saveButtonState != .hidden {
                Spacer()
                saveSlot
            }
            if CastAffordance.isVisible(castAvailable: container.castController.castAvailable,
                                        isOfflinePlayback: isOfflinePlayback) {
                Spacer()
                castSlot
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
        .sheet(item: $saveSheetArgs) { presented in
            SaveOfflineSheet(args: presented.args)
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

    // MARK: - Cast (Phase 3 Task 8, spec §10)

    /// The SDK's own `GCKUICastButton` (spec §10 names it), captioned like every other slot so the
    /// five-button row stays one shape. The button owns its icon states and presents the device
    /// chooser itself; the first tap is also what starts discovery, so nothing here does. The
    /// ≥44 pt target comes from the 24 pt glyph plus the caption below it, same as the other
    /// slots' `toolbarLabel`.
    ///
    /// Fix round 1 (review Important 3): the accessibility element is the `GCKUICastButton` ITSELF
    /// — a real `UIButton`, so it keeps the button trait and its own activation — with the caption
    /// hidden beside it. The previous `.accessibilityElement(children: .combine)` on the `VStack`
    /// produced a non-button with no state, which is why the UI test had to look it up in
    /// `otherElements`. The `favoriteButton` idiom in full this time: a constant role label plus a
    /// VALUE carrying the live state (the connected receiver's name).
    // ponytail: the SDK button hides its own glyph in one state (`startDiscoveryAfterFirstTap...`
    // docs: after the first tap, with no Wi-Fi connection), which would leave this slot's caption
    // standing over an invisible icon. Reading `castState` to hide the whole slot needs a second
    // SDK observer in `CastController`; add it if that state is ever seen in practice.
    private var castSlot: some View {
        VStack(spacing: 4) {
            CastButton()
                .frame(width: 24, height: 24)
                // Re-review Minor 3: moving the accessibility element onto the button shrank it to
                // the 24 pt glyph; this restores the ≥44 pt floor for it. The negative vertical
                // padding is what keeps the finding's OTHER half ("without changing the row's
                // look"): `.frame(minHeight: 44)` alone makes the slot 20 pt taller than the other
                // four and drops its caption out of line with them — measured, screenshotted.
                // -10 top and bottom hands the parent back the original 24 pt of layout while the
                // view itself still measures 44. The SDK button's own TOUCH area is 24 pt and
                // always was; enlarging that means resizing the `UIButton`, which reintroduces the
                // same misalignment.
                .frame(minWidth: 44, minHeight: 44)
                .padding(.vertical, -10)
                .accessibilityIdentifier("player.castButton")
                .accessibilityLabel(String(localized: "player_action_cast"))
                .accessibilityValue(container.castController.connectedDeviceName ?? "")
            Text(String(localized: "player_action_cast"))
                .font(TypeScale.caption)
                .accessibilityHidden(true)
        }
        .foregroundStyle(Color.textPrimary)
        .frame(minHeight: 44)
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
                saveSheetArgs = SaveSheetArgs(args: args)
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
            // Task 7: Open plays the saved file directly — the same `.player` route with
            // `offlineItemId` set that the Saved screen's own Open pushes.
            Button {
                guard let itemId = offlineItem?.id else { return }
                var offlineArgs = args
                offlineArgs.offlineItemId = itemId
                router.push(.player(offlineArgs))
            } label: {
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
        let fraction = offlineItem?.progressFraction ?? 0
        return ZStack {
            Circle().stroke(Color.textPrimary.opacity(0.2), lineWidth: 2)
            Circle().trim(from: 0, to: fraction)
                .stroke(Color.brand, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .frame(width: 20, height: 20)
    }

    /// The Saved row's own status→key mapping (`SavedRowText`), not a third copy of it.
    private var progressCaption: String {
        let status = offlineItem.flatMap { OfflineStatus(rawValue: $0.status) } ?? .queued
        return String(localized: String.LocalizationValue(SavedRowText.statusKey(status)))
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
