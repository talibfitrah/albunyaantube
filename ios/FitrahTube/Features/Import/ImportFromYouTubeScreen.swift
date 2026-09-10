import SwiftUI

/// `Route.importFromYouTube` — the Me kebab's Import row (`ImportFromYouTubeFragment.kt`), offered
/// to every signed-in user whose account actually has a Google grant to extend (RULING 28: the row
/// is ABSENT for an Apple or email/password account, never greyed).
///
/// **Nothing here links to YouTube.** The one external destination on the screen is Google's own
/// account-permissions page, on the revoke confirmation — ruling F9's "we forgot the token; the
/// grant is yours to revoke, here". The consent sheet the flow raises is Google's, presented by the
/// SDK inside `YouTubeAuthorizer.authorize()`.
struct ImportFromYouTubeScreen: View {

    /// GOOGLE's account-permissions page — never a YouTube URL. `static` so the pin lives on a
    /// value rather than on a string buried in a `Link`'s initialiser.
    static let permissionsURL = URL(string: "https://myaccount.google.com/permissions")!

    @Environment(\.container) private var container
    @Environment(\.widthClass) private var widthClass
    @Environment(\.locale) private var locale

    @State private var model: ImportViewModel?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.lg(widthClass)) {
                stateView(model?.state ?? .idle)
                revokeSection
            }
            .padding(Spacing.md(widthClass))
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Color.background.ignoresSafeArea())
        .navigationTitle(String(localized: "import_youtube_title"))
        .navigationBarTitleDisplayMode(.inline)
        // The Sharī'ah caution gate. Presented off the ViewModel's own flag, not a `@State` here,
        // so the Import button has no path to the import that does not pass through this dialog.
        .alert(String(localized: "import_caution_title"), isPresented: cautionBinding) {
            Button(String(localized: "cancel"), role: .cancel) { model?.dismissCaution() }
            Button(String(localized: "import_caution_continue")) { model?.acceptCaution() }
        } message: {
            Text(String(localized: "import_caution_message"))
        }
        .task {
            if model == nil {
                let model = ImportViewModel(authorizer: container.youtubeAuthorizer,
                                            source: container.youtubeImportSource,
                                            pipeline: container.importPipeline)
                self.model = model
                // The screen exists because the user chose Import; asking them to tap a second
                // button to begin would be a step Android does not have either (`:83-86`).
                model.start()
            }
        }
    }

    private var cautionBinding: Binding<Bool> {
        Binding(get: { model?.isCautionPresented ?? false },
                set: { if !$0 { model?.dismissCaution() } })
    }

    // MARK: - The five arms

    /// Internal, not private, and free of trailing modifiers: `MainShellRoutingTests` walks
    /// `destination(for:)` down to a leaf, and the same descent is what lets a test reach an arm
    /// rather than a `ModifiedContent` wrapper around it.
    @ViewBuilder
    func stateView(_ state: ImportUiState) -> some View {
        switch state {
        case .idle, .authorizing:
            spinner("import_youtube_loading_authorizing")
        case .fetching:
            spinner("import_youtube_loading_fetching")
        case .review(let candidates, let selected, let partialFailures):
            reviewList(candidates, selected, partialFailures)
        case .importing(let phase, let processed, let total):
            importing(phase, processed, total)
        case .done(let summary):
            done(summary)
        case .error(let messageKey, let retryable):
            errorArm(messageKey, retryable)
        }
    }

    private func spinner(_ captionKey: String) -> some View {
        VStack(spacing: Spacing.md(widthClass)) {
            ProgressView().tint(.brand)
            Text(String(localized: String.LocalizationValue(captionKey)))
                .font(TypeScale.body(widthClass))
                .foregroundStyle(Color.textSecondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, Spacing.lg(widthClass))
        .accessibilityElement(children: .combine)
    }

    // MARK: Review

    /// A COMPLETE local list, so CLAUDE.md's pagination rule does not apply — there is no
    /// `loadMore()` behind it and never will be (the chip rail's rationale, one screen over). The
    /// engine's own 40-page cap is what bounds it, at 2 000 rows per type.
    @ViewBuilder
    private func reviewList(_ candidates: [ImportCandidate], _ selected: Set<String>,
                            _ partialFailures: Set<CandidateType>) -> some View {
        LazyVStack(alignment: .leading, spacing: Spacing.lg(widthClass)) {
            if !partialFailures.isEmpty {
                partialFailureBanner(partialFailures)
            }
            ForEach(CandidateType.allCases, id: \.self) { type in
                let rows = candidates.filter { $0.type == type }
                if !rows.isEmpty {
                    group(type, rows, selected)
                }
            }
            importButton(selected)
        }
    }

    /// `buildPartialFailureText` (`ImportFromYouTubeFragment.kt:185-196`): names the types that did
    /// not answer, and blocks nothing — the types that did are reviewable.
    private func partialFailureBanner(_ types: Set<CandidateType>) -> some View {
        let names = CandidateType.allCases
            .filter(types.contains)
            .map { String(localized: String.LocalizationValue($0.shortTitleKey)) }
            .joined(separator: ", ")
        return Text(String(format: String(localized: "import_youtube_partial_failure"), names))
            .font(TypeScale.body(widthClass))
            .foregroundStyle(Color.textSecondary)
            .padding(Spacing.md(widthClass))
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.homeCard, in: RoundedRectangle(cornerRadius: Radius.card))
    }

    @ViewBuilder
    private func group(_ type: CandidateType, _ rows: [ImportCandidate],
                       _ selected: Set<String>) -> some View {
        let ids = Set(rows.map(\.youtubeId))
        let allOn = ids.isSubset(of: selected)
        VStack(alignment: .leading, spacing: Spacing.sm) {
            Button {
                model?.setGroupSelected(type, !allOn)
            } label: {
                HStack(spacing: Spacing.sm) {
                    Text(Format.localizedFormat(type.groupTitleKey, locale: locale, Int64(rows.count)))
                        .font(TypeScale.subtitle)
                        .foregroundStyle(Color.textPrimary)
                    Spacer(minLength: 0)
                    checkbox(allOn)
                }
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(String(localized: "import_youtube_select_all_content_description"))
            .accessibilityValue(Text(Format.localizedFormat(type.groupTitleKey, locale: locale,
                                                            Int64(rows.count))))
            .accessibilityAddTraits(allOn ? [.isSelected] : [])

            ForEach(rows) { candidate in
                row(candidate, isSelected: selected.contains(candidate.youtubeId))
            }
        }
    }

    private func row(_ candidate: ImportCandidate, isSelected: Bool) -> some View {
        Button {
            model?.toggle(candidate.youtubeId)
        } label: {
            HStack(spacing: Spacing.md(widthClass)) {
                RemoteImage(url: candidate.thumbnailUrl.flatMap(URL.init(string:)))
                    .frame(width: 96, height: 54)
                    .clipShape(RoundedRectangle(cornerRadius: Radius.homeThumbnail))
                Text(candidate.title)
                    .font(TypeScale.body(widthClass))
                    .foregroundStyle(Color.textPrimary)
                    .multilineTextAlignment(.leading)
                    .lineLimit(2)
                Spacer(minLength: 0)
                checkbox(isSelected)
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(candidate.title)
        .accessibilityAddTraits(isSelected ? [.isSelected] : [])
        .accessibilityIdentifier("import.row.\(candidate.youtubeId)")
    }

    private func checkbox(_ on: Bool) -> some View {
        Image(systemName: on ? "checkmark.circle.fill" : "circle")
            .font(.system(size: 22))
            .foregroundStyle(on ? Color.brand : Color.textSecondary)
            .accessibilityHidden(true)
    }

    /// Raises the gate. It starts nothing — `ImportViewModel.importTapped()` is the whole action.
    @ViewBuilder
    private func importButton(_ selected: Set<String>) -> some View {
        Button {
            model?.importTapped()
        } label: {
            Text(Format.localizedFormat("import_youtube_button_import", locale: locale,
                                        Int64(selected.count)))
                .foregroundStyle(Color.onBrand)
                .frame(maxWidth: .infinity, minHeight: Size.button(widthClass))
        }
        .buttonStyle(.borderedProminent)
        .tint(.brand)
        .disabled(selected.isEmpty)
        .accessibilityIdentifier("import.confirm")
    }

    // MARK: Importing

    private func importing(_ phase: ImportPhase, _ processed: Int, _ total: Int) -> some View {
        VStack(spacing: Spacing.md(widthClass)) {
            // Indeterminate until the pipeline has a denominator: the first emission is a fresh
            // zero-of-zero by design, and a determinate bar at 0/0 reads as a stalled one.
            if total > 0 {
                ProgressView(value: Double(processed), total: Double(total)).tint(.brand)
            } else {
                ProgressView().tint(.brand)
            }
            Text(String(localized: String.LocalizationValue(phase.captionKey)))
                .font(TypeScale.body(widthClass))
                .foregroundStyle(Color.textSecondary)
        }
        .padding(.vertical, Spacing.lg(widthClass))
        .accessibilityElement(children: .combine)
    }

    // MARK: Done

    private func done(_ summary: ImportSummary) -> some View {
        VStack(spacing: Spacing.md(widthClass)) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: Size.iconXL(widthClass)))
                .foregroundStyle(Color.brand)
                .accessibilityHidden(true)
            Text(Format.localizedFormat("import_youtube_done_summary", locale: locale,
                                        Int64(summary.added), Int64(summary.sentForReview),
                                        Int64(summary.skipped)))
                .font(TypeScale.body(widthClass))
                .foregroundStyle(Color.textPrimary)
                .multilineTextAlignment(.center)
            // Task 28 review I1: the partial signal comes off the SUMMARY, never off the transient
            // DONE progress emission this view has already dropped by the time it renders. The cap
            // line is the WHY when there is one; the count line is the WHAT either way.
            if summary.isPartial {
                Text(Format.localizedFormat("import_youtube_done_partial", locale: locale,
                                            Int64(summary.processed), Int64(summary.total)))
                    .font(TypeScale.body(widthClass))
                    .foregroundStyle(Color.textSecondary)
                    .multilineTextAlignment(.center)
                if summary.rateLimited {
                    Text(String(localized: "import_youtube_done_rate_limited"))
                        .font(TypeScale.body(widthClass))
                        .foregroundStyle(Color.textSecondary)
                        .multilineTextAlignment(.center)
                }
            }
            Button {
                model?.retry()
            } label: {
                Text(String(localized: "import_youtube_button_retry"))
                    .frame(minHeight: 44)
            }
            .buttonStyle(.bordered)
            .tint(.brand)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, Spacing.lg(widthClass))
    }

    // MARK: Error

    @ViewBuilder
    private func errorArm(_ messageKey: String, _ retryable: Bool) -> some View {
        let message = String(localized: String.LocalizationValue(messageKey))
        if retryable {
            ErrorStateView(message: message) { model?.retry() }
        } else {
            // No retry: an empty YouTube library is not a failure, and a button that re-runs three
            // empty paginators is an invitation to keep tapping it.
            EmptyStateView(systemImage: "tray", message: message)
        }
    }

    // MARK: - Ruling F9: revoke

    @ViewBuilder
    private var revokeSection: some View {
        Divider()
        if model?.didRevoke == true {
            VStack(alignment: .leading, spacing: Spacing.sm) {
                Text(String(localized: "import_revoke_done"))
                    .font(TypeScale.body(widthClass))
                    .foregroundStyle(Color.textPrimary)
                // GOOGLE's own page. Revoking the grant server-side is the user's to do, and this
                // is where Google lets them do it — never `disconnect()`, which would sign them out.
                Link(String(localized: "import_revoke_manage_link"), destination: Self.permissionsURL)
                    .font(TypeScale.body(widthClass))
                    .foregroundStyle(Color.brand)
                    .frame(minHeight: 44)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            Button(role: .destructive) {
                model?.revoke()
            } label: {
                HStack(spacing: Spacing.md(widthClass)) {
                    Image(systemName: "key.slash")
                    Text(String(localized: "import_revoke_action"))
                        .font(TypeScale.body(widthClass))
                    Spacer(minLength: 0)
                }
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .accessibilityIdentifier("import.revoke")
        }
    }
}

// MARK: - Copy per case, spelled once

extension CandidateType {
    /// `buildPartialFailureText`'s three-arm `when` (`ImportFromYouTubeFragment.kt:187-193`).
    var shortTitleKey: String {
        switch self {
        case .channel: "import_youtube_group_channels_short"
        case .playlist: "import_youtube_group_playlists_short"
        case .video: "import_youtube_group_videos_short"
        }
    }

    /// The section headers, which carry their own count (`Channels (%1$lld)`).
    var groupTitleKey: String {
        switch self {
        case .channel: "import_youtube_group_channels"
        case .playlist: "import_youtube_group_playlists"
        case .video: "import_youtube_group_videos"
        }
    }
}

extension ImportPhase {
    /// `renderProgress` (`:180-184`).
    var captionKey: String {
        switch self {
        case .resolving: "import_youtube_importing_resolving"
        case .writing: "import_youtube_importing_writing"
        case .done: "import_youtube_importing_done"
        }
    }
}

#if DEBUG
#Preview {
    NavigationStack { ImportFromYouTubeScreen() }
        .environment(\.container, .sharedFake)
}

#Preview("RTL") {
    NavigationStack { ImportFromYouTubeScreen() }
        .environment(\.container, .sharedFake)
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}
#endif
