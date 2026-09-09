import InnerTubeKit
import SwiftUI

/// `Route.mySubmissions` — the Me kebab's My Submissions row (`MySubmissionsFragment.kt`), which
/// ruling C4 gates to moderators and admins: `MeKebabItem.items(isModerator:)` is what decides
/// whether the row exists at all, and nothing else in the app pushes this route.
///
/// No FAB. Android's opens `SubmitContentBottomSheet`, which is Task 27's screen — RULING 28 refuses
/// a button whose destination has not landed, so `my_submissions_submit_cta` stays unrendered until
/// then.
struct MySubmissionsScreen: View {
    @Environment(\.container) private var container
    @Environment(\.widthClass) private var widthClass
    @Environment(\.locale) private var locale

    @State private var model: MySubmissionsViewModel?
    @State private var banner: BannerMessage?
    @State private var editing: Submission?
    @State private var confirmingDelete: Submission?
    @State private var paginationGuard = PaginationGuard()
    @State private var isLoadingMore = false
    /// Geometry *state*, not an event (gate B1-C1), exactly as `ContentListView` keeps it.
    @State private var contentFits = false

    var body: some View {
        ScrollView {
            stateView(model?.state ?? .loading)
                .padding(Spacing.md(widthClass))
        }
        .refreshable {
            paginationGuard.reset()
            await model?.refresh()
        }
        .onContentFits { fits in
            contentFits = fits
            triggerAutoFill()
        }
        // The companion every autofill site pairs with `onContentFits`: re-arm after each completed
        // load rather than relying on the fit margin alone changing.
        .onChange(of: model?.state) { _, _ in triggerAutoFill() }
        .background(Color.background.ignoresSafeArea())
        .navigationTitle(String(localized: "my_submissions_title"))
        .navigationBarTitleDisplayMode(.inline)
        .transientBanner($banner)
        .sheet(item: $editing) { row in
            if let model {
                EditSubmissionSheet(submission: row) { note in
                    await model.updateNote(row, note: note)
                } onFinish: { message in
                    editing = nil
                    banner = BannerMessage(text: message)
                }
            }
        }
        .alert(String(localized: "my_submissions_delete_confirm_title"),
               isPresented: Binding(get: { confirmingDelete != nil },
                                    set: { if !$0 { confirmingDelete = nil } }),
               presenting: confirmingDelete) { row in
            Button(String(localized: "cancel"), role: .cancel) { confirmingDelete = nil }
            Button(String(localized: "my_submissions_delete_confirm_button"), role: .destructive) {
                guard let model else { return }
                Task { banner = BannerMessage(text: await model.delete(row)) }
            }
        } message: { _ in
            Text(String(localized: "my_submissions_delete_confirm_message"))
        }
        // Android refreshes in `onResume` (`:120-123`) as well as at construction. `.task` covers
        // both: it runs on first appearance and again each time the screen comes back.
        .task {
            let model = self.model ?? MySubmissionsViewModel(client: container.approvals)
            self.model = model
            paginationGuard.reset()
            await model.refresh()
        }
    }

    /// The four arms. Internal, not private: `MySubmissionsViewModelTests` walks them the way
    /// `MainShellRoutingTests` walks `MainShellView.destination(for:)`, which is what pins ruling
    /// C13 — the Error arm is a real screen with a real retry, not Android's `TODO`.
    ///
    /// Deliberately free of trailing modifiers, so the walk reaches the leaf view rather than a
    /// `ModifiedContent` wrapper around it.
    @ViewBuilder
    func stateView(_ state: MySubmissionsUiState) -> some View {
        switch state {
        case .loading:
            SkeletonListView()
        case .empty:
            EmptyStateView(systemImage: "tray", message: String(localized: "my_submissions_empty"))
        case .error:
            ErrorStateView(message: String(localized: "my_submissions_error")) {
                Task { await model?.refresh() }
            }
        case .loaded(let rows):
            LazyVStack(spacing: Spacing.md(widthClass)) {
                ForEach(rows) { row in
                    SubmissionRow(submission: row, locale: locale,
                                  onEdit: { editing = row },
                                  onDelete: { confirmingDelete = row })
                }
            }
        }
    }

    /// CLAUDE.md's pagination rule: a page whose rows already fit the viewport never scrolls, so the
    /// six `PaginationGuard` checks run on every layout delta instead of a scroll listener. Same
    /// commit discipline as `ContentListView.triggerAutoFill` — a rejection still writes the guard
    /// back (guards 2 and 6 renew the budget), only the attempt increment waits for a fetch to start.
    private func triggerAutoFill() {
        guard !isLoadingMore, let model, case .loaded(let rows) = model.state else { return }
        var attempt = paginationGuard
        guard attempt.shouldAutoLoad(widthClass: widthClass, hasMore: model.hasMore,
                                     paginationError: model.paginationError, contentFits: contentFits,
                                     itemCount: rows.count) else {
            paginationGuard = attempt
            return
        }
        isLoadingMore = true
        Task {
            let started = await model.loadMore()
            isLoadingMore = false
            if started, attempt.generation == paginationGuard.generation { paginationGuard = attempt }
        }
    }
}

/// One submission (`item_my_submission.xml` + `MySubmissionAdapter.bind`). The kebab is present only
/// while the row is still the submitter's to change; an adjudicated row carries no menu at all
/// rather than a greyed one that would 409 (RULING 28).
struct SubmissionRow: View {
    let submission: Submission
    let locale: Locale
    let onEdit: () -> Void
    let onDelete: () -> Void

    @Environment(\.widthClass) private var widthClass

    var body: some View {
        HStack(alignment: .top, spacing: Spacing.md(widthClass)) {
            thumbnail
            VStack(alignment: .leading, spacing: Spacing.xs) {
                // A row can land before the backend has enriched its title (`MySubmissionAdapter`
                // :52-54), so the placeholder is copy, not an empty line.
                Text(submission.title?.isEmpty == false
                     ? submission.title! : String(localized: "my_submissions_title_placeholder"))
                    .font(TypeScale.subtitle)
                    .foregroundStyle(Color.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                HStack(spacing: Spacing.sm) {
                    statusPill
                    if let submittedAt = submission.submittedAt,
                       let relative = AtomFeedFetcher.humanizePublished(from: submittedAt, locale: locale) {
                        Text(relative)
                            .font(TypeScale.caption)
                            .foregroundStyle(Color.textSecondary)
                    }
                }
                if let note = submission.submitterNote, !note.isEmpty {
                    Text(String(localized: "my_submissions_submitter_note_label"))
                        .font(TypeScale.caption)
                        .foregroundStyle(Color.textSecondary)
                    Text(note)
                        .font(TypeScale.body(widthClass))
                        .foregroundStyle(Color.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            if submission.status.isManageable { kebab }
        }
        .padding(Spacing.md(widthClass))
        .frame(minHeight: 44)
        .background(Color.homeCard, in: RoundedRectangle(cornerRadius: Radius.card))
        .accessibilityIdentifier("mySubmissions.row.\(submission.id)")
    }

    /// A channel reads as a circle, a video/playlist as a rounded thumbnail — the same distinction
    /// `MySubmissionAdapter` makes with a `ShapeAppearanceModel`.
    private var thumbnail: some View {
        RemoteImage(url: submission.thumbnailUrl.flatMap(URL.init(string:)))
            .frame(width: 56, height: 56)
            .clipShape(RoundedRectangle(cornerRadius: submission.type == .channels ? 28 : Radius.thumbnail))
            .accessibilityHidden(true)
    }

    /// Label AND value: the pill is the row's status, so VoiceOver reads both what it is and what
    /// it says (spec §14).
    private var statusPill: some View {
        let label = String(localized: String.LocalizationValue(submission.status.labelKey))
        return HStack(spacing: Spacing.xs) {
            Image(systemName: submission.status.symbolName)
            Text(label)
        }
        .font(TypeScale.caption)
        .foregroundStyle(Color.onBrand)
        .padding(.horizontal, Spacing.sm)
        .padding(.vertical, Spacing.xs)
        .background(statusColor, in: Capsule())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String(localized: "my_submissions_title"))
        .accessibilityValue(label)
    }

    /// The four `my_submission_status_*` colours, already in the palette
    /// (`Tokens.swift:74-77`) — Task 13 ported them with the rest of the Me tokens.
    private var statusColor: Color {
        switch submission.status {
        case .pending: .submissionPending
        case .approved: .submissionApproved
        case .rejected: .submissionRejected
        case .requestChanges: .submissionChanges
        }
    }

    private var kebab: some View {
        Menu {
            Button(String(localized: "my_submissions_action_edit_note"), systemImage: "square.and.pencil",
                   action: onEdit)
            Button(String(localized: "my_submissions_action_delete"), systemImage: "trash",
                   role: .destructive, action: onDelete)
        } label: {
            Image(systemName: "ellipsis")
                .frame(minWidth: 44, minHeight: 44)
        }
        .accessibilityLabel(String(localized: "my_submissions_actions_content_description"))
    }
}

#if DEBUG
#Preview {
    NavigationStack { MySubmissionsScreen() }
        .environment(\.container, .sharedFake)
}

#Preview("RTL") {
    NavigationStack { MySubmissionsScreen() }
        .environment(\.container, .sharedFake)
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}
#endif
