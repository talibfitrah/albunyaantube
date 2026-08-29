import InnerTubeKit
import SwiftUI

/// Android's `PlaylistDetailFragment` (`fragment_playlist_detail.xml`): hero, three-cell action bar
/// (rulings 28/56 delete Download), in-header search, the positioned list with `ListFooter`, the
/// kebab. Pagination is Phase 1's `PaginationGuard`, wired exactly as `ContentListView` wires it
/// (ruling 55; reconciliation note 2 -- NOT `ChannelTabAutofill`).
struct PlaylistDetailScreen: View {
    let id: String
    let title: String?
    let category: String?
    let count: Int?

    @Environment(\.container) private var container
    @Environment(\.router) private var router
    @Environment(\.widthClass) private var widthClass
    @Environment(\.locale) private var locale
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    @State private var viewModel: PlaylistDetailViewModel?
    @State private var isLoadingMore = false
    @State private var paginationGuard = PaginationGuard()
    @State private var contentFits = false
    @State private var banner: BannerMessage?

    var body: some View {
        DetailHeader(title: displayTitle, heroHeight: widthClass.pick(200, 280, 320)) { inset in
            hero(topInset: inset)
        } content: {
            VStack(spacing: 0) {
                titleBlock
                actionBar
                SearchField(text: queryBinding, accessibilityLabel: String(localized: "cd_search_icon"))
                listBody
            }
        }
        .onContentFits { fits in
            contentFits = fits
            triggerAutoFill()
        }
        .background(Color.background.ignoresSafeArea())
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                DetailKebab(share: .playlist(id), title: displayTitle,
                            report: ReportContext(targetType: .playlist, targetId: id, parentType: nil, parentId: nil, contentSubType: nil),
                            banner: $banner)
            }
        }
        .transientBanner($banner)
        .onChange(of: viewModel?.items) { _, _ in triggerAutoFill() }
        .onChange(of: queryBinding.wrappedValue) { _, _ in
            paginationGuard.reset()
            triggerAutoFill()
        }
        .task {
            if viewModel == nil {
                viewModel = PlaylistDetailViewModel(playlistId: id, title: title, category: category, count: count,
                                                    browse: container.browse, saved: container.savedPlaylists,
                                                    fetchHeader: container.playlistHeader)
            }
            if viewModel?.items == .idle { await viewModel?.load() }
        }
    }

    /// One fallback chain for the bar, the title block and the kebab: fetched title, route title, id.
    private var displayTitle: String { viewModel?.header.title ?? title ?? id }

    // MARK: - Hero (fragment_playlist_detail.xml:53-129)

    private func hero(topInset: CGFloat) -> some View {
        let url = viewModel?.header.thumbnailURL
        return ZStack {
            RemoteImage(url: url).blur(radius: 20).opacity(0.6)
            Color.heroOverlay
            RemoteImage(url: url, contentMode: .fit)
                .aspectRatio(16 / 9, contentMode: .fit)
                .frame(maxWidth: Size.playerMaxWidth(widthClass) ?? .infinity)
                .padding(.top, topInset)
                .padding(Spacing.md(widthClass))
        }
        .accessibilityHidden(true)
    }

    private var titleBlock: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Text(displayTitle)
                .font(TypeScale.headline(widthClass)).foregroundStyle(Color.textPrimary)
                .accessibilityIdentifier("playlist.title")
            if let meta = viewModel?.metadataLine(locale: locale) {
                Text(meta).font(TypeScale.itemMeta).foregroundStyle(Color.textSecondary)
                    .accessibilityIdentifier("playlist.metadata")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, Spacing.md(widthClass))
        .padding(.top, Spacing.md(widthClass))
    }

    // MARK: - Action bar: Play all / Shuffle / Save (rulings 28/56)

    private var actionBar: some View {
        let meta = viewModel?.metadataLine(locale: locale) ?? ""
        let hasFirst = viewModel?.firstKnownItem != nil
        // Spec §14: single column at `.accessibility1+` -- three side-by-side cells truncate there.
        let layout = dynamicTypeSize.isAccessibilitySize ? AnyLayout(VStackLayout(spacing: Spacing.sm)) : AnyLayout(HStackLayout(spacing: Spacing.sm))
        return layout {
            actionCell("play.fill", "playlist_play_all", value: meta, id: "playlist.playAll", enabled: hasFirst) { launch(shuffled: false) }
            actionCell("shuffle", "playlist_shuffle", value: meta, id: "playlist.shuffle", enabled: hasFirst) { launch(shuffled: true) }
            actionCell(viewModel?.isSaved == true ? "bookmark.fill" : "bookmark",
                       viewModel?.isSaved == true ? "playlist_unsave" : "playlist_save",
                       value: String(localized: viewModel?.isSaved == true ? "playlist_unsave" : "playlist_save"),
                       id: "playlist.save", enabled: true, action: toggleSaved)
                .accessibilityAddTraits(viewModel?.isSaved == true ? [.isSelected] : [])
        }
        .padding(.horizontal, Spacing.md(widthClass))
        .padding(.vertical, Spacing.sm)
    }

    private func actionCell(_ symbol: String, _ key: String, value: String, id: String, enabled: Bool,
                            action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: Spacing.xs) {
                Image(systemName: symbol)
                Text(String(localized: String.LocalizationValue(key))).font(TypeScale.caption)
                    .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
            }
            .frame(maxWidth: .infinity, minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.bordered)
        .tint(.brand)
        .disabled(!enabled)
        .accessibilityLabel(String(localized: String.LocalizationValue(key)))
        .accessibilityValue(value)
        .accessibilityIdentifier(id)
    }

    /// CF-B5-1: emission does not wait for the list; the player resolves the playlist itself.
    private func launch(shuffled: Bool) {
        guard let args = viewModel?.playAllArgs(shuffled: shuffled) else { return }
        router.push(.player(args))
    }

    private func toggleSaved() {
        guard let viewModel else { return }
        do {
            try viewModel.toggleSaved()
        } catch {
            banner = BannerMessage(text: String(localized: "error_state_generic_headline"))
        }
    }

    // MARK: - List (item_playlist_video.xml) + states

    @ViewBuilder
    private var listBody: some View {
        if let viewModel {
            let state = viewModel.visible
            switch state {
            case .idle, .loadingInitial:
                SkeletonListView()
            case .empty(let key):
                EmptyStateView(systemImage: key == "search_no_results" ? "magnifyingglass" : "list.bullet.rectangle",
                               message: String(localized: String.LocalizationValue(key)))
                    .padding(.top, Spacing.lg(widthClass))
            case .errorInitial(let key):
                if viewModel.canRetry {
                    ErrorStateView(message: String(localized: String.LocalizationValue(key))) {
                        Task { await viewModel.load() }
                    }
                    .padding(.top, Spacing.lg(widthClass))
                } else {
                    // RULING 14/15: terminal, no Retry.
                    EmptyStateView(systemImage: "exclamationmark.triangle.fill", iconColor: .accentRed,
                                   title: String(localized: "content_unavailable_title"),
                                   message: String(localized: String.LocalizationValue(key)))
                        .padding(.top, Spacing.lg(widthClass))
                        .accessibilityIdentifier("playlist.unavailable")
                }
            case .loaded, .errorAppend:
                let rows = viewModel.rows
                LazyVStack(spacing: 0) {
                    ForEach(Array(rows.enumerated()), id: \.element.id) { offset, row in
                        rowView(row, viewModel: viewModel)
                            .onAppear {
                                // `>=`, not `==` (gate B1-I2): a failed load-more must stay recoverable.
                                guard offset >= max(0, rows.count - 5) else { return }
                                triggerScrollLoadMore()
                            }
                    }
                }
                ListFooter(state: state, loadMore: triggerScrollLoadMore, retry: triggerScrollLoadMore)
            }
        }
    }

    private func rowView(_ row: PlaylistDetailViewModel.Row, viewModel: PlaylistDetailViewModel) -> some View {
        let item = row.item
        let contentItem = ContentItem(id: item.id, type: .video, title: item.title, category: nil, description: nil,
                                      thumbnailURL: item.thumbnailURL, durationSeconds: item.durationSeconds,
                                      uploadedDaysAgo: nil, viewCount: nil, channelTitle: item.channelName,
                                      subscribers: nil, videoCount: nil, itemCount: nil)
        let position = Int64(row.position)
        return HStack(spacing: 0) {
            // Bare numeral in the 32 pt column (`item_playlist_video.xml:14-25`); the spoken
            // "Position N" lives in the row's `a11y_playlist_video` label below.
            Text(position.formatted(.number.locale(locale)))
                .font(TypeScale.itemMeta).foregroundStyle(Color.textSecondary)
                .frame(width: 32).lineLimit(1).minimumScaleFactor(0.5)
                .padding(.leading, Spacing.sm)
            VideoRow(item: contentItem, subtitle: PlaylistDetailViewModel.rowSubtitle(item) ?? item.channelName ?? "") {
                router.push(.player(viewModel.playerArgs(for: row)))
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(rowAccessibilityLabel(item, position: position))
        .accessibilityIdentifier("playlist.row.\(row.position)")
    }

    /// A degraded/Atom row has no duration; "Duration: ," is not a sentence, so the segment is
    /// omitted rather than spoken empty (both keys already exist in the catalog).
    private func rowAccessibilityLabel(_ item: VideoItem, position: Int64) -> String {
        if let seconds = item.durationSeconds {
            return Format.localizedFormat("a11y_playlist_video", locale: locale, position, item.title,
                                          Format.duration(seconds), item.channelName ?? "")
        }
        return [Format.localizedFormat("playlist_video_position", locale: locale, position), item.title, item.channelName]
            .compactMap { $0 }.joined(separator: ", ")
    }

    // MARK: - Pagination (ContentListView.swift:570-618, verbatim wiring)

    private var queryBinding: Binding<String> {
        Binding(get: { viewModel?.query ?? "" }, set: { viewModel?.query = $0 })
    }

    private func triggerScrollLoadMore() {
        guard let viewModel, viewModel.visible.continuation != nil, !isLoadingMore else { return }
        isLoadingMore = true
        Task { await runLoadMore() }
    }

    private func triggerAutoFill() {
        guard !isLoadingMore, let viewModel else { return }
        let state = viewModel.visible
        var paginationError = false
        if case .errorAppend = state { paginationError = true }
        var attempt = paginationGuard
        guard attempt.shouldAutoLoad(widthClass: widthClass, hasMore: state.continuation != nil, paginationError: paginationError,
                                      contentFits: contentFits, itemCount: state.items.count) else {
            paginationGuard = attempt
            return
        }
        isLoadingMore = true
        Task {
            if await runLoadMore(), attempt.generation == paginationGuard.generation {
                paginationGuard = attempt
            }
        }
    }

    @discardableResult
    private func runLoadMore() async -> Bool {
        let started = await viewModel?.loadMore() ?? false
        isLoadingMore = false
        return started
    }
}

#if DEBUG
#Preview {
    NavigationStack {
        PlaylistDetailScreen(id: "PL1", title: "Fixture Playlist", category: "Aqeedah", count: 24)
    }
    .environment(\.container, .sharedFake)
}
#endif
