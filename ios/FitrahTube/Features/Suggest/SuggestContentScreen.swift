import SwiftUI

/// `Route.suggestContent` — the Me kebab's Suggest Content row (`SuggestContentFragment.kt`), which
/// ruling C4 gates to moderators and admins alongside My Submissions: `MeKebabItem.items(
/// isModerator:)` is what decides whether the row exists at all, and nothing else pushes this route.
///
/// The search is a BACKEND call (`GET api/admin/youtube/search`, run server-side by NewPipe). No
/// row here links to YouTube, and the one YouTube URL in the flow is one the user pastes into the
/// submit sheet — parsed, never rendered back and never opened.
struct SuggestContentScreen: View {
    @Environment(\.container) private var container
    @Environment(\.widthClass) private var widthClass
    @Environment(\.locale) private var locale

    @State private var model: SuggestContentViewModel?
    @State private var banner: BannerMessage?
    @State private var submitting: SuggestItem?
    @State private var paginationGuard = PaginationGuard()
    /// Geometry *state*, not an event (gate B1-C1), exactly as `ContentListView` keeps it.
    @State private var contentFits = false

    var body: some View {
        VStack(spacing: 0) {
            SearchField(text: queryBinding, accessibilityLabel: String(localized: "cd_search_icon"),
                        placeholderKey: "suggest_search_hint")
            chips
            ScrollView {
                stateView(model?.state ?? .idle)
                    .padding(Spacing.md(widthClass))
            }
            .onContentFits { fits in
                contentFits = fits
                triggerAutoFill()
            }
            // The companion every autofill site pairs with `onContentFits`: re-arm after each
            // completed load rather than relying on the fit margin alone changing.
            .onChange(of: model?.state) { _, _ in triggerAutoFill() }
        }
        .background(Color.background.ignoresSafeArea())
        .navigationTitle(String(localized: "me_kebab_suggest_content"))
        .navigationBarTitleDisplayMode(.inline)
        .transientBanner($banner)
        .sheet(item: $submitting) { hit in
            SubmitContentSheet(hit: hit) { message in
                submitting = nil
                banner = BannerMessage(text: message)
            }
        }
        .task {
            if model == nil { model = SuggestContentViewModel(client: container.youtubeSearch) }
        }
    }

    private var queryBinding: Binding<String> {
        Binding(get: { model?.query ?? "" }, set: { model?.query = $0 })
    }

    // MARK: - Chips (`fragment_suggest_content.xml`'s type row — client-side, never a request)

    @ViewBuilder
    private var chips: some View {
        if let model, case .results = model.state {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: Spacing.sm) {
                    ForEach(SuggestType.allCases, id: \.self) { type in
                        SuggestTypeChip(type: type, isActive: model.activeFilter == type) {
                            model.onTypeChange(type)
                        }
                    }
                }
                .padding(.horizontal, Spacing.md(widthClass))
                .padding(.top, Spacing.sm)
            }
        }
    }

    // MARK: - The six arms

    /// Internal, not private: `SuggestContentViewModelTests` walks these the way
    /// `MainShellRoutingTests` walks `MainShellView.destination(for:)`, which is what pins ruling
    /// C13 — the error arm is a real screen with a real retry.
    ///
    /// Deliberately free of trailing modifiers, so the walk reaches the leaf view rather than a
    /// `ModifiedContent` wrapper around it.
    @ViewBuilder
    func stateView(_ state: SuggestUiState) -> some View {
        switch state {
        case .idle:
            EmptyStateView(systemImage: "magnifyingglass", message: String(localized: "suggest_search_hint"))
        case .loading:
            SkeletonListView()
        case .empty:
            EmptyStateView(systemImage: "magnifyingglass",
                           message: Format.localizedFormat("suggest_empty_results", locale: locale,
                                                           model?.lastQuery ?? ""))
        case .rateLimited:
            // No Retry. A retry button on a rate limit is an invitation to hammer the thing that
            // is rate-limiting you, so this arm states the situation and stops there — which is
            // why it is `EmptyStateView` (no action) and not `ErrorStateView`.
            EmptyStateView(systemImage: "clock", iconColor: .accentRed,
                           message: String(localized: "suggest_rate_limited"))
        case .error(let messageKey):
            ErrorStateView(message: String(localized: String.LocalizationValue(messageKey))) {
                Task { await model?.retry() }
            }
        case .results(let hits):
            LazyVStack(spacing: Spacing.md(widthClass)) {
                ForEach(Array(hits.enumerated()), id: \.element.id) { index, hit in
                    SuggestResultRow(hit: hit) { submitting = hit }
                        // The scroll-driven half of CLAUDE.md's pagination rule: `PaginationGuard`'s
                        // guard 1 refuses to autofill on a compact width, so without this a phone
                        // could not reach page two. The once-per-page guard lives in `loadMore`, so
                        // a frame that appears all five of the tail rows costs one page.
                        .onAppear { Task { await model?.rowAppeared(at: index) } }
                }
                if model?.isLoadingMore == true {
                    ProgressView()
                        .tint(.brand)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, Spacing.md(widthClass))
                        .accessibilityLabel(String(localized: "suggest_loading_more"))
                }
            }
        }
    }

    /// CLAUDE.md's pagination rule, large-screen half. Same commit discipline as
    /// `ContentListView.triggerAutoFill` — a rejection still writes the guard back (guards 2 and 6
    /// renew the budget), only the attempt increment waits for a fetch to start.
    private func triggerAutoFill() {
        guard let model, !model.isLoadingMore, case .results(let hits) = model.state else { return }
        var attempt = paginationGuard
        guard attempt.shouldAutoLoad(widthClass: widthClass, hasMore: model.hasMore,
                                     paginationError: model.paginationError, contentFits: contentFits,
                                     itemCount: hits.count) else {
            paginationGuard = attempt
            return
        }
        Task {
            let started = await model.loadMore()
            if started, attempt.generation == paginationGuard.generation { paginationGuard = attempt }
        }
    }
}

/// One type filter. A chip, not a segmented control: the segmented control's 32 pt height is under
/// the ≥44 pt floor and it cannot grow with Dynamic Type.
struct SuggestTypeChip: View {
    let type: SuggestType
    let isActive: Bool
    let onTap: () -> Void

    var body: some View {
        let label = String(localized: String.LocalizationValue(type.labelKey))
        Button(action: onTap) {
            Text(label)
                .font(TypeScale.caption)
                .foregroundStyle(isActive ? Color.onBrand : Color.brand)
                .padding(.horizontal, Spacing.md(.compact))
                .frame(minHeight: 44)
                .background(isActive ? Color.brand : Color.surfaceVariant,
                            in: RoundedRectangle(cornerRadius: Radius.chip))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityAddTraits(isActive ? [.isButton, .isSelected] : .isButton)
    }
}

/// One search hit (`item_suggest_result.xml` + `SuggestResultsAdapter.bind`). A row the registry
/// already knows carries its state as a badge INSTEAD of a Submit button — RULING 28: an
/// affordance that would 409 is not offered.
struct SuggestResultRow: View {
    let hit: SuggestItem
    let onSubmit: () -> Void

    @Environment(\.widthClass) private var widthClass

    var body: some View {
        HStack(alignment: .top, spacing: Spacing.md(widthClass)) {
            RemoteImage(url: hit.thumbnailUrl.flatMap(URL.init(string:)))
                .frame(width: 56, height: 56)
                .clipShape(RoundedRectangle(cornerRadius: hit.type == .channels ? 28 : Radius.thumbnail))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: Spacing.xs) {
                // Task 26 deviation 3: a hit whose `name` was null decodes to "", and there is no
                // `suggest_*` placeholder key — the id is the only honest stand-in, and it is what
                // the moderator would search for anyway.
                Text(hit.title.isEmpty ? hit.youtubeId : hit.title)
                    .font(TypeScale.subtitle)
                    .foregroundStyle(Color.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                if let secondary = hit.channelTitle, !secondary.isEmpty {
                    Text(secondary)
                        .font(TypeScale.caption)
                        .foregroundStyle(Color.textSecondary)
                }
                if let badge = Self.badgeKey(hit.registryState) {
                    CategoryChip(text: String(localized: String.LocalizationValue(badge)))
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            if Self.badgeKey(hit.registryState) == nil { submit }
        }
        .padding(Spacing.md(widthClass))
        .frame(minHeight: 44)
        .background(Color.homeCard, in: RoundedRectangle(cornerRadius: Radius.card))
        .accessibilityIdentifier("suggest.row.\(hit.youtubeId)")
    }

    private var submit: some View {
        Button(action: onSubmit) {
            Image(systemName: "plus.circle")
                .foregroundStyle(Color.brand)
                .frame(minWidth: 44, minHeight: 44)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(String(localized: "submit_content_submit_button"))
    }

    /// `SuggestResultsAdapter.kt:30-35`, over Task 25's `SubmissionStatus` rather than a fifth
    /// string table (Task 26 concern 4). nil means "not in the registry" — submittable — which is
    /// exactly the wire's `knownStatus == null`, so the optional is the whole rule and
    /// `alreadyKnown` never had to be decoded. `REQUEST_CHANGES` reads as pending: the row is back
    /// in the queue awaiting its submitter, which is the same "already there" answer to somebody
    /// else. An unrecognised value reads as pending too (`SubmissionStatus.fromWire`) — a row this
    /// build cannot name is still known to the registry, so the safe answer is the badge, never a
    /// Submit button that would 409.
    static func badgeKey(_ registryState: String?) -> String? {
        guard let registryState else { return nil }
        return switch SubmissionStatus.fromWire(registryState) {
        case .approved: "suggest_already_in_registry"
        case .rejected: "suggest_already_rejected"
        case .pending, .requestChanges: "suggest_already_pending"
        }
    }
}

#if DEBUG
private let previewHits = [
    SuggestItem(youtubeId: "UCmMcOjsVehVlEOteyrhjI2Q", type: .channels, title: "Mishary Alafasy",
                thumbnailUrl: nil, channelTitle: "1.2M", registryState: nil),
    SuggestItem(youtubeId: "xc7keR2piUM", type: .videos, title: "Tafsir lesson", thumbnailUrl: nil,
                channelTitle: "Mishary Alafasy", registryState: "PENDING"),
    SuggestItem(youtubeId: "PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc", type: .playlists, title: "",
                thumbnailUrl: nil, channelTitle: nil, registryState: "APPROVED")
]

@ViewBuilder
private func previewRowStack() -> some View {
    ScrollView {
        LazyVStack(spacing: Spacing.md(.compact)) {
            ForEach(previewHits) { hit in SuggestResultRow(hit: hit, onSubmit: {}) }
        }
        .padding(Spacing.md(.compact))
    }
}

#Preview { previewRowStack() }

#Preview("RTL") {
    previewRowStack()
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}
#endif
