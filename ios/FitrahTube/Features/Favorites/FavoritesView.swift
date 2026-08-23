import SwiftUI

/// Android's `FavoritesFragment` (`favorites-settings-about.md:1.3-1.4`). Rows reuse `VideoRow`
/// (the tabs' row component) rather than porting Android's bespoke `item_favorite_video.xml`,
/// passing the channel name as `VideoRow`'s `subtitle` to match Android's persistent one-line
/// channel-name caption (`:90`). Remove is a trailing swipe action instead of Android's
/// always-visible 48×48 button (native iOS idiom).
struct FavoritesView: View {
    @Environment(\.container) private var container
    @Environment(\.router) private var router
    @Environment(\.locale) private var locale

    @State private var viewModel: FavoritesViewModel?
    @State private var showClearAllConfirm = false
    @State private var bannerMessage: BannerMessage?

    var body: some View {
        Group {
            if let viewModel, !viewModel.items.isEmpty {
                List {
                    ForEach(viewModel.items, id: \.videoId) { item in
                        VideoRow(item: viewModel.contentItem(for: item), subtitle: item.channelName) {
                            router.push(.player(viewModel.playerArgs(for: item)))
                        }
                            .listRowInsets(EdgeInsets())
                            .swipeActions {
                                // favorites-settings-about.md:94 -- per-item label carries the title,
                                // not just the static row label.
                                Button(role: .destructive) { remove(item) } label: {
                                    Label(String(localized: "favorites_remove"), systemImage: "trash")
                                }
                                .accessibilityLabel(Format.localizedFormat("favorites_remove_description", locale: locale, item.title))
                            }
                    }
                }
                .listStyle(.plain)
            } else {
                EmptyStateView(systemImage: "heart", title: String(localized: "favorites_empty_title"),
                                message: String(localized: "favorites_empty_subtitle"))
            }
        }
        .background(Color.background.ignoresSafeArea())
        .navigationTitle(String(localized: "favorites_title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            // favorites-settings-about.md:1.3 -- Android hides the "Clear all" action when the list is empty.
            if let viewModel, !viewModel.items.isEmpty {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(String(localized: "favorites_clear_all")) { showClearAllConfirm = true }
                }
            }
        }
        // favorites-settings-about.md:1.4 -- destructive confirm styling (Android's own button
        // isn't destructive-styled, but the copy is; iOS uses `.destructive` to match the copy).
        .confirmationDialog(String(localized: "favorites_clear_all_title"), isPresented: $showClearAllConfirm, titleVisibility: .visible) {
            Button(String(localized: "favorites_clear_all_confirm"), role: .destructive) { clearAll() }
            Button(String(localized: "cancel"), role: .cancel) {}
        } message: {
            Text(String(localized: "favorites_clear_all_message"))
        }
        .transientBanner($bannerMessage)
        .task {
            if viewModel == nil {
                viewModel = FavoritesViewModel(store: container.favorites)
            }
            #if DEBUG
            // Acceptance-screenshot hook (task-12): the confirmation only otherwise appears after a
            // real tap on "Clear all", which `simctl launch` can't perform.
            if ProcessInfo.processInfo.arguments.contains("-fitrah-show-clear-all-confirm") {
                showClearAllConfirm = true
            }
            #endif
        }
    }

    private func remove(_ item: FavoriteVideo) {
        do {
            try viewModel?.remove(item)
        } catch {
            showFailureBanner()
        }
    }

    private func clearAll() {
        do {
            try viewModel?.clearAll()
        } catch {
            showFailureBanner()
        }
    }

    // RULINGS #31: failures surface via the transient banner. Android hardcodes English-only
    // "Failed to remove/clear favorites" text with no string resource to port (favorites-settings-about.md:1.2);
    // reusing the existing generic error copy avoids inventing new, untranslated-by-contract strings for
    // a failure mode a local SwiftData store hits only on genuine disk/persistence errors.
    private func showFailureBanner() {
        bannerMessage = BannerMessage(text: String(localized: "error_state_generic_headline"))
    }

}

#if DEBUG
#Preview {
    NavigationStack { FavoritesView() }
        .environment(\.container, .sharedFake)
}

#Preview("RTL") {
    NavigationStack { FavoritesView() }
        .environment(\.container, .sharedFake)
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}
#endif
