import SwiftUI

extension SubmissionType {
    /// `SubmitContentBottomSheet.kt:62-67` — the "Detected: …" line under the URL field.
    var detectedKey: String {
        switch self {
        case .channels: "submit_content_detected_channel"
        case .playlists: "submit_content_detected_playlist"
        case .videos: "submit_content_detected_video"
        }
    }
}

/// `SubmitContentBottomSheet.kt`'s decisions, hoisted off the view so they are pinned by tests
/// rather than by a `#Preview`: what the input resolves to, whether Submit is alive, and what each
/// answer from the server says.
///
/// Two entry points, one model. From a SEARCH RESULT the target is already known (`hit`), so there
/// is no URL to parse and none to render — nothing in this app puts a YouTube URL on screen (owner
/// directive 2026-08-27). From the My Submissions **+** the user pastes one, and `YouTubeURLParser`
/// turns it into the same target.
@MainActor @Observable final class SubmitContentModel {

    private let client: ApprovalsClient
    /// nil = paste-a-URL mode.
    let hit: SuggestItem?
    private let locale: Locale

    /// Paste mode only. Re-parsed on every keystroke (`:53-56`).
    var url = ""
    /// REQUIRED, as Android requires it (`:100-101`). An approved row with no `categoryIds` is
    /// invisible to every public category filter, and the submitter is the one person who knows
    /// which category the content belongs in.
    var categoryId: String?
    var note = ""
    private(set) var submitting = false

    init(client: ApprovalsClient, hit: SuggestItem? = nil, locale: Locale) {
        self.client = client
        self.hit = hit
        self.locale = locale
    }

    /// The registry collection + id this sheet would POST. nil for a handle, a plain query or an
    /// empty field — see `YouTubeURLParser.Parsed.submitTarget`.
    var target: SubmitTarget? {
        if let hit { return hit.submitTarget }
        return YouTubeURLParser.parse(url).submitTarget
    }

    /// The line under the field: the detected type, `submit_content_invalid_url`, or nothing at all
    /// while the field is still empty (`:57-60` hides it rather than accusing an empty field).
    var detectionKey: String? {
        guard hit != nil || !url.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return target?.type.detectedKey ?? "submit_content_invalid_url"
    }

    var canSubmit: Bool { target != nil && categoryId != nil && !submitting }

    /// Returns the message the caller banners. `POST api/admin/registry/{type}` — 201 new, 200 a
    /// re-submit of an admin-bounced row, 409 already in the registry, 429 the daily cap.
    func submit() async -> String {
        guard let target, let categoryId, !submitting else {
            return String(localized: "submit_content_error_generic")
        }
        submitting = true
        defer { submitting = false }
        do {
            try await client.submit(type: target.type, youtubeId: target.youtubeId,
                                    note: note.isEmpty ? nil : note, categoryIds: [categoryId])
            return String(localized: "submit_content_success")
        } catch AccountError.conflict {
            return String(localized: "submit_content_conflict")
        } catch AccountError.rateLimited(let seconds) {
            return Format.localizedFormat("submit_content_rate_limited", locale: locale,
                                          Self.wait(seconds, locale: locale))
        } catch {
            return String(localized: "submit_content_error_generic")
        }
    }

    /// Whole hours, rounded UP and floored at one (`:120`: `(retryAfterSeconds + 3599) / 3600`) —
    /// telling someone to wait "0 hours" for a limit that is still in force is worse than rounding.
    /// Formatted as a MEASUREMENT so the unit is the reader's, not English's.
    private static func wait(_ seconds: Int, locale: Locale) -> String {
        let hours = max(1, (max(0, seconds) + 3599) / 3600)
        return Measurement(value: Double(hours), unit: UnitDuration.hours)
            .formatted(.measurement(width: .abbreviated).locale(locale))
    }
}

/// The sheet itself. `EditSheetScaffold` + `LabelledField` + `EditSheetAction`, the three components
/// Task 17 already shares with `EditSubmissionSheet`.
struct SubmitContentSheet: View {
    /// nil = the pasted-URL entry (the My Submissions **+**).
    var hit: SuggestItem?
    let onFinish: (String) -> Void

    @Environment(\.container) private var container
    @Environment(\.widthClass) private var widthClass
    @Environment(\.locale) private var locale

    @State private var model: SubmitContentModel?

    var body: some View {
        EditSheetScaffold(title: String(localized: "submit_content_sheet_title")) {
            if let model {
                if model.hit == nil {
                    LabelledField(key: "submit_content_url_hint") {
                        TextField(String(localized: "submit_content_url_hint"),
                                  text: Binding(get: { model.url }, set: { model.url = $0 }))
                            .textFieldStyle(.plain)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .keyboardType(.URL)
                            .padding(Spacing.md(widthClass))
                            .frame(minHeight: 44)
                            .background(Color.homeCard, in: RoundedRectangle(cornerRadius: Radius.card))
                            .accessibilityLabel(String(localized: "submit_content_url_hint"))
                    }
                } else if let title = model.hit?.title, !title.isEmpty {
                    // The hit's own title, never its URL.
                    Text(title)
                        .font(TypeScale.subtitle)
                        .foregroundStyle(Color.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                detection(model)
                categoryPicker(model)
                LabelledField(key: "submit_content_note_hint") {
                    TextField(String(localized: "submit_content_note_hint"),
                              text: Binding(get: { model.note }, set: { model.note = $0 }), axis: .vertical)
                        .lineLimit(2...5)
                        .padding(Spacing.md(widthClass))
                        .frame(minHeight: 44)
                        .background(Color.homeCard, in: RoundedRectangle(cornerRadius: Radius.card))
                        .accessibilityLabel(String(localized: "submit_content_note_hint"))
                    Text(String(localized: "submit_content_note_helper"))
                        .font(TypeScale.caption)
                        .foregroundStyle(Color.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                EditSheetAction(title: String(localized: "submit_content_submit_button"),
                                isLoading: model.submitting) {
                    guard model.canSubmit else { return }
                    onFinish(await model.submit())
                }
                .disabled(!model.canSubmit)
            }
        }
        .task {
            if model == nil {
                model = SubmitContentModel(client: container.approvals, hit: hit, locale: locale)
            }
            await container.categories.loadIfNeeded()
        }
    }

    @ViewBuilder
    private func detection(_ model: SubmitContentModel) -> some View {
        if let key = model.detectionKey {
            let text = String(localized: String.LocalizationValue(key))
            Text(text)
                .font(TypeScale.caption)
                .foregroundStyle(model.target == nil ? Color.errorText : Color.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityLabel(text)
        }
    }

    /// The top-level categories, from the cache the whole app already shares — a `Menu`, not a
    /// wheel: ≥44 pt, Dynamic Type, and it reads its own value to VoiceOver.
    @ViewBuilder
    private func categoryPicker(_ model: SubmitContentModel) -> some View {
        let categories = container.categories.topLevel()
        let selected = model.categoryId.flatMap { id in categories.first { $0.id == id } }
        let label = selected.map { Format.categoryDisplayName($0, locale: locale) }
            ?? String(localized: "submit_content_pick_category")
        LabelledField(key: "submit_content_category_hint") {
            Menu {
                ForEach(categories) { category in
                    Button(Format.categoryDisplayName(category, locale: locale)) {
                        model.categoryId = category.id
                    }
                }
            } label: {
                HStack {
                    Text(label)
                        .foregroundStyle(selected == nil ? Color.textSecondary : Color.textPrimary)
                    Spacer(minLength: Spacing.sm)
                    Image(systemName: "chevron.down").foregroundStyle(Color.textSecondary)
                }
                .font(TypeScale.body(widthClass))
                .padding(Spacing.md(widthClass))
                .frame(minHeight: 44)
                .background(Color.homeCard, in: RoundedRectangle(cornerRadius: Radius.card))
            }
            .accessibilityLabel(String(localized: "submit_content_category_hint"))
            .accessibilityValue(label)
        }
    }
}

#if DEBUG
#Preview {
    SubmitContentSheet(onFinish: { _ in })
        .environment(\.container, .sharedFake)
}

#Preview("RTL") {
    SubmitContentSheet(hit: SuggestItem(youtubeId: "UCmMcOjsVehVlEOteyrhjI2Q", type: .channels,
                                        title: "مشاري راشد العفاسي", thumbnailUrl: nil,
                                        channelTitle: nil, registryState: nil),
                       onFinish: { _ in })
        .environment(\.container, .sharedFake)
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}
#endif
