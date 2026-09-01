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

    /// The row action set consults Task 3's ONE matrix — pinned by
    /// `SavedScreenTests.rowActionsComeFromTheOneMatrix`; no second status switch in this file.
    static func rowActions(for status: OfflineStatus) -> [OfflineAction] {
        OfflineStateMachine.actions(for: status)
    }

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
    }

    /// "%lld saved • %@ used • %@ available" — digits localized (ar numerals) via `Format`,
    /// pinned by `OfflineEngineTests.theFooterRendersThroughFormatWithLocaleAwareNumerals`.
    private var footer: some View {
        let items = container.offlineStore.items
        return Text(OfflineStorage.footer(count: items.count,
                                          used: OfflineStorage.usedBytes(items: items),
                                          available: OfflineStorage.availableBytes(),
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
    @Environment(\.locale) private var locale

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
                if let fraction = progressFraction {
                    ProgressView(value: fraction).tint(.brand)
                }
            }
            Spacer(minLength: 0)
            if let status {
                ForEach(Self.rowActions(for: status), id: \.self) { action in
                    actionButton(action)
                }
            }
        }
        .padding(.horizontal, Spacing.md(widthClass))
        .padding(.vertical, Spacing.sm)
    }

    /// Same delegation the type-level pin covers — a private forward, not a second switch.
    private static func rowActions(for status: OfflineStatus) -> [OfflineAction] {
        SavedScreen.rowActions(for: status)
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

    /// Task 7 wires offline playback (the completed row plays its `localPath` through the
    /// player's `.ready`/`.rung2Progressive` states); until then Open renders per the matrix
    /// with no navigation of its own — the user is already on the Saved screen.
    private func openCompleted() {}

    /// Determinate only while bytes are actually moving toward a known total.
    private var progressFraction: Double? {
        guard let status, status == .running || status == .paused || status == .queued,
              let total = item.totalBytes, total > 0 else { return nil }
        return min(max(Double(item.bytesWritten) / Double(total), 0), 1)
    }

    /// Failed rows surface their error copy (WHAT, never why); every other status shows its
    /// status caption.
    private var caption: String {
        guard let status else { return String(localized: "offline_error_unknown") }
        let key = status == .failed ? SavedRowText.errorKey(item.errorCode) : SavedRowText.statusKey(status)
        return String(localized: String.LocalizationValue(key))
    }
}

/// The row/status → catalog-key mappings, pure (`SavedScreenTests`).
enum SavedRowText {
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

/// Settings' Clear row (CF-B3-11 confirm fires this): every row through the manager, once each —
/// never `FileManager` from UI.
enum OfflineClearAll {
    static func run(ids: [String], manager: any OfflineSaving) async {
        for id in ids { await manager.delete(id) }
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
