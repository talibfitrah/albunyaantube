import InnerTubeKit
import SwiftUI

/// `Route.mySubmissions` — the Me kebab's My Submissions row (`MySubmissionsFragment.kt`), which
/// ruling C4 gates to moderators and admins: `MeKebabItem.items(isModerator:)` is what decides
/// whether the row exists at all, and nothing else in the app pushes this route.
///
/// The **+** (Android's FAB, `my_submissions_submit_cta`) lands in Task 27 with the sheet it opens.
/// It is what makes `my_submissions_empty` — "Tap + to suggest content for the library" — true;
/// until Task 27 there was no + to tap, which was RULING 28 honoured at the cost of the copy.
struct MySubmissionsScreen: View {
    @Environment(\.container) private var container
    @Environment(\.widthClass) private var widthClass
    @Environment(\.locale) private var locale
    @Environment(\.router) private var router

    @State private var model: MySubmissionsViewModel?
    @State private var banner: BannerMessage?
    @State private var editing: Submission?
    @State private var confirmingDelete: Submission?
    @State private var suggesting = false

    var body: some View {
        ScrollView {
            stateView(model?.state ?? .loading)
                .padding(Spacing.md(widthClass))
        }
        .refreshable {
            if let message = await model?.refresh() { banner = BannerMessage(text: message) }
        }
        .background(Color.background.ignoresSafeArea())
        .navigationTitle(String(localized: "my_submissions_title"))
        .navigationBarTitleDisplayMode(.inline)
        // Android's FAB (`fragment_my_submissions.xml`), as a toolbar +. This is the affordance
        // `my_submissions_empty` names, so it is present in every arm — including the empty one it
        // is being pointed at from.
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { suggesting = true } label: {
                    Image(systemName: "plus").frame(minWidth: 44, minHeight: 44)
                }
                .accessibilityLabel(String(localized: "my_submissions_submit_cta"))
            }
        }
        .transientBanner($banner)
        .sheet(isPresented: $suggesting) {
            SubmitContentSheet { message, _ in
                suggesting = false
                banner = BannerMessage(text: message)
                Task { _ = await model?.refresh() }
            }
        }
        // Fix round 1 / M7: a submit made from the Suggest screen — a sibling route with its own
        // ViewModel — re-reads this list if it is on the stack, which is what the **+** above
        // already does for its own sheet.
        .onChange(of: router.submissionsToken) { _, _ in
            Task { _ = await model?.refresh() }
        }
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
            if let message = await model.refresh() { banner = BannerMessage(text: message) }
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

    // No pagination: this list never pages (`MySubmissionsViewModel.pageSize`).
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
                // The ADMIN's note, on the one status that asks the user to act (fix round 1 / I3).
                // Italic and above the submitter's own note, as Android stacks them
                // (`item_my_submission.xml:160-173`); no label, and no new key — the text is the
                // admin's own words.
                if let reviewNote = submission.reviewNoteToShow {
                    Text(reviewNote)
                        .font(TypeScale.body(widthClass))
                        .italic()
                        .foregroundStyle(Color.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
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
/// Fix round 1 / M4: the rows, not the screen. `.sharedFake`'s canned `/me` body has no `data` key,
/// so a preview of the SCREEN paints `EmptyStateView` and never renders a `SubmissionRow` at all —
/// which is what the RTL preview was supposed to be showing.
private let previewRows = [
    Submission(id: "s1", type: .channels, title: "Lecture series", thumbnailUrl: nil,
               status: .requestChanges, submitterNote: "Please add this series",
               reviewNotes: "Please add Arabic subtitles before resubmitting.", submittedAt: .now),
    Submission(id: "s2", type: .videos, title: nil, thumbnailUrl: nil, status: .pending,
               submitterNote: nil, reviewNotes: nil, submittedAt: .now),
    // An adjudicated row: no kebab, and its stale review note must NOT show under the green pill.
    Submission(id: "s3", type: .playlists, title: "Tafsir playlist", thumbnailUrl: nil,
               status: .approved, submitterNote: nil, reviewNotes: "an earlier bounce", submittedAt: .now)
]

@ViewBuilder
private func previewRowStack(_ locale: Locale) -> some View {
    ScrollView {
        LazyVStack(spacing: Spacing.md(.compact)) {
            ForEach(previewRows) { row in
                SubmissionRow(submission: row, locale: locale, onEdit: {}, onDelete: {})
            }
        }
        .padding(Spacing.md(.compact))
    }
}

#Preview { previewRowStack(Locale(identifier: "en")) }

#Preview("RTL") {
    previewRowStack(Locale(identifier: "ar"))
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}
#endif
