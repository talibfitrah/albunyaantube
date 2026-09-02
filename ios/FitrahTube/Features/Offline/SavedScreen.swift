import SwiftUI

/// Phase 3 Task 6: the Saved library (Android's `DownloadsFragment`). Rows render
/// `container.offlineStore.items` verbatim — the store already sorts alphabetically by title
/// (`DownloadsFragment` parity) and the `@Model` rows carry live `bytesWritten`/`totalBytes`, so
/// progress re-renders with no extra plumbing. Every mutation goes through
/// `container.offlineManager` (`OfflineSaving`) — never `FileManager` from here. No pagination:
/// a local SwiftData list, complete in memory (plan Global Constraints). No share/export
/// affordance exists by construction (owner ruling; re-pinned as a compliance test in Task 7).
struct SavedScreen: View {
    @Environment(\.container) private var container
    @Environment(\.locale) private var locale
    @Environment(\.widthClass) private var widthClass

    /// Free-space capacity, read once per appearance instead of once per `body`. It used to be
    /// computed inline in the footer, and `body` re-runs on every persisted progress tick while a
    /// save is running — a `volumeAvailableCapacityForImportantUsage` stat twice a second. Nil
    /// only until the first `.task` lands; the footer falls back to a single read so the first
    /// frame never renders "Zero KB available".
    @State private var availableBytes: Int64?

    var body: some View {
        Group {
            if container.offlineStore.items.isEmpty {
                EmptyStateView(systemImage: "arrow.down.circle",
                               message: String(localized: "offline_empty_state"))
            } else {
                // A plain scroll stack, not `List`: the library is complete in memory (no
                // pagination by plan constraint), needs no cells, and a pure-SwiftUI subtree is
                // what lets `SavedScreenTests` pin the layout through `ImageRenderer` (a
                // UICollectionView-backed `List` renders nothing there).
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(container.offlineStore.items, id: \.id) { item in
                            SavedRow(item: item)
                            Divider().padding(.leading, Spacing.md(widthClass))
                        }
                        footer
                    }
                }
            }
        }
        .background(Color.background.ignoresSafeArea())
        .navigationTitle(String(localized: "offline_saved_title"))
        .navigationBarTitleDisplayMode(.inline)
        .task(id: container.offlineStore.items.count) {
            availableBytes = OfflineStorage.availableBytes(base: container.offlineBase)
        }
    }

    /// "%lld saved • %@ used • %@ available" — digits localized (ar numerals) via `Format`,
    /// pinned by `OfflineEngineTests.theFooterRendersThroughFormatWithLocaleAwareNumerals`.
    private var footer: some View {
        let items = container.offlineStore.items
        return Text(OfflineStorage.footer(count: items.count,
                                          used: OfflineStorage.usedBytes(items: items),
                                          available: availableBytes
                                              ?? OfflineStorage.availableBytes(base: container.offlineBase),
                                          locale: locale))
            .font(TypeScale.caption)
            .foregroundStyle(Color.textSecondary)
            .frame(maxWidth: .infinity)
            .multilineTextAlignment(.center)
            .padding(.vertical, Spacing.sm)
    }
}

/// One saved item: thumbnail, title/channel, status caption, live progress, and the action
/// buttons `OfflineStateMachine.actions(for:)` grants its status.
private struct SavedRow: View {
    let item: OfflineItem

    @Environment(\.container) private var container
    @Environment(\.widthClass) private var widthClass
    @Environment(\.router) private var router

    private var status: OfflineStatus? { OfflineStatus(rawValue: item.status) }

    var body: some View {
        HStack(alignment: .center, spacing: Spacing.sm) {
            RemoteImage(url: item.thumbnailUrl.flatMap(URL.init))
                .frame(width: 104, height: 104 * 9 / 16)
                .clipShape(RoundedRectangle(cornerRadius: Radius.homeThumbnail))
            VStack(alignment: .leading, spacing: Spacing.xs) {
                Text(item.title)
                    .font(TypeScale.subtitle).fontWeight(.bold)
                    .foregroundStyle(Color.textPrimary).lineLimit(2)
                if let channelName = item.channelName {
                    Text(channelName)
                        .font(TypeScale.itemMeta).foregroundStyle(Color.textSecondary).lineLimit(1)
                }
                Text(caption)
                    .font(TypeScale.caption).foregroundStyle(Color.textSecondary).lineLimit(2)
                if let fraction = determinateFraction {
                    ProgressView(value: fraction).tint(.brand)
                }
            }
            Spacer(minLength: 0)
            if let status {
                // Task 3's ONE matrix, read directly — no second status switch in this file.
                ForEach(OfflineStateMachine.actions(for: status), id: \.self) { action in
                    actionButton(action)
                }
            }
        }
        .padding(.horizontal, Spacing.md(widthClass))
        .padding(.vertical, Spacing.sm)
    }

    private func actionButton(_ action: OfflineAction) -> some View {
        let title = String(localized: String.LocalizationValue(SavedRowAction.labelKey(action)))
        return Button {
            let manager = container.offlineManager
            let id = item.id
            Task { await SavedRowAction.perform(action, id: id, manager: manager, open: openCompleted) }
        } label: {
            Image(systemName: SavedRowAction.symbolName(action))
                .font(.system(size: 22))
                .foregroundStyle(action == .delete || action == .remove ? Color.red : Color.brand)
                .frame(width: 44, height: 44)   // spec §14 target floor
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
        // The row title disambiguates identical buttons on adjacent rows for VoiceOver users
        // (the FavoritesView swipe-action idiom).
        .accessibilityValue(item.title)
    }

    /// Task 7: Open plays the saved file through the player's existing `.ready`/
    /// `.rung2Progressive` states — a `.player` push whose `offlineItemId` makes `PlayerScreen`
    /// build its VM over `OfflineResolver` (no network, reduced chrome).
    private func openCompleted() {
        var args = PlayerArgs(videoId: item.videoId, title: item.title,
                              channelName: item.channelName,
                              thumbnailURL: item.thumbnailUrl.flatMap(URL.init))
        args.offlineItemId = item.id
        router.push(.player(args))
    }

    /// The row's bar is determinate only while bytes are actually moving toward a known total —
    /// the model's `progressFraction` plus this status guard. Deliberately NOT named
    /// `progressFraction`: two same-named properties one line apart read as a recursion hazard.
    private var determinateFraction: Double? {
        guard let status, status == .running || status == .paused || status == .queued else { return nil }
        return item.progressFraction
    }

    private var caption: String {
        String(localized: String.LocalizationValue(
            SavedRowText.captionKey(status: status, errorCode: item.errorCode)))
    }
}

/// The row/status → catalog-key mappings, pure (`SavedScreenTests`).
enum SavedRowText {
    /// The row's caption: error copy when the row carries an error code, its status caption
    /// otherwise (WHAT, never why).
    ///
    /// Cubic R5-3: this was `.failed`-only, so a refused Retry or Resume — which leaves the network
    /// code on a row that stays cancelled or paused (`OfflineManager.note`) — was still invisible
    /// and the button still read as broken. A code present outranks the status caption; a failed
    /// row with no code keeps its generic copy, exactly as before.
    static func captionKey(status: OfflineStatus?, errorCode: String?) -> String {
        guard let status else { return "offline_error_unknown" }
        return status == .failed || errorCode != nil ? errorKey(errorCode) : statusKey(status)
    }

    /// `running` reads as "Saving…" (owner ruling: "Save for offline" language, never "Download").
    static func statusKey(_ status: OfflineStatus) -> String {
        switch status {
        case .queued: "offline_status_queued"
        case .running: "offline_status_saving"
        case .paused: "offline_status_paused"
        case .completed: "offline_status_completed"
        case .failed: "offline_status_failed"
        case .cancelled: "offline_status_cancelled"
        }
    }

    /// `OfflineManager.ErrorCode` raw → key; NOT_SAVEABLE gets the refusal copy. An unknown or
    /// missing raw (a future version's code) falls back to the generic key, never a raw code.
    static func errorKey(_ raw: String?) -> String {
        switch raw.flatMap(OfflineManager.ErrorCode.init(rawValue:)) {
        case .http403: "offline_error_403"
        case .http429: "offline_error_429"
        case .network: "offline_error_network"
        case .noStream: "offline_error_no_stream"
        case .invalidInput: "offline_error_invalid"
        case .notSaveable: "offline_not_saveable"
        case .unknown, nil: "offline_error_unknown"
        }
    }
}

/// Action → manager dispatch, one place (`SavedScreenTests` drives it with a spy `OfflineSaving`).
/// `remove` and `delete` are the same manager call — the matrix splits them only for their copy
/// (Remove clears a failed/cancelled row, Delete a completed file+row; `OfflineManager.delete`
/// tears down whatever exists either way).
enum SavedRowAction {
    static func perform(_ action: OfflineAction, id: String, manager: any OfflineSaving,
                        open: () -> Void) async {
        switch action {
        case .pause: await manager.pause(id)
        case .resume: await manager.resume(id)
        case .cancel: await manager.cancel(id)
        case .retry: await manager.retry(id)
        case .remove, .delete: await manager.delete(id)
        case .open: open()
        }
    }

    /// cancel/retry reuse the Android generics already in the catalog (Task 5's authoring note).
    static func labelKey(_ action: OfflineAction) -> String {
        switch action {
        case .pause: "offline_action_pause"
        case .resume: "offline_action_resume"
        case .cancel: "cancel"
        case .retry: "retry"
        case .remove: "offline_action_remove"
        case .open: "offline_action_open"
        case .delete: "offline_action_delete"
        }
    }

    static func symbolName(_ action: OfflineAction) -> String {
        switch action {
        case .pause: "pause.circle"
        case .resume: "play.circle"
        case .cancel: "xmark.circle"
        case .retry: "arrow.clockwise.circle"
        case .remove: "trash.circle"
        case .open: "play.circle"
        case .delete: "trash.circle"
        }
    }
}

#if DEBUG
#Preview {
    NavigationStack { SavedScreen() }
        .environment(\.container, .sharedFake)
}

#Preview("RTL") {
    NavigationStack { SavedScreen() }
        .environment(\.container, .sharedFake)
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}
#endif
