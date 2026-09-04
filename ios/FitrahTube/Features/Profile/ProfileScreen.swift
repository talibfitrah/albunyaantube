import SwiftUI

/// `Route.profile` — the Me kebab's Profile row (`ProfileFragment.kt`). Inline name and date of
/// birth with a Save that sends **only what changed**; a read-only email row, a phone row and a
/// password row, each opening its own sheet.
///
/// No "Delete account" row yet: RULING 28 refuses an affordance with nowhere to go, and Task 18 is
/// what lands the delete flow. It arrives with its wiring, exactly as `.profile` itself did.
struct ProfileScreen: View {
    @Environment(\.container) private var container
    @Environment(\.widthClass) private var widthClass
    @Environment(\.locale) private var locale
    @Environment(\.dismiss) private var dismiss

    @State private var model: ProfileViewModel?
    @State private var isPickingDate = false
    @State private var banner: BannerMessage?
    @State private var sheet: EditSheet?

    /// `.sheet(item:)` rather than three booleans: one presentation slot means two sheets can never
    /// be asked for at once.
    private enum EditSheet: String, Identifiable {
        case email, password, phone
        var id: String { rawValue }
    }

    var body: some View {
        ScrollView {
            content.padding(Spacing.md(widthClass))
        }
        .background(Color.background.ignoresSafeArea())
        .navigationTitle(String(localized: "profile_title"))
        .navigationBarTitleDisplayMode(.inline)
        .transientBanner($banner)
        .sheet(item: $sheet) { which in
            switch which {
            case .email: EditEmailSheet(onSent: finish)
            case .password: EditPasswordSheet(onUpdated: finish)
            case .phone: EditPhoneSheet(current: model?.draft?.phoneNumber, onUpdated: finish)
            }
        }
        .alert(String(localized: "profile_error_age_dialog_title"), isPresented: showsAgeDialog) {
            // No cancel: the account is no longer eligible either way, and a dismissable dialog
            // would leave the user staring at a form whose Save can never succeed.
            Button(String(localized: "ok")) { model?.confirmAgeIneligibleSignOut() }
        } message: {
            Text(String(localized: "profile_error_age_dialog_message"))
        }
        .task {
            if model == nil {
                model = ProfileViewModel(account: container.account, auth: container.auth,
                                         session: container.session)
            }
            await model?.sync()
        }
        // Android's `accountState.collect`: a `/me` that lands after this screen opened still
        // promotes it out of `.loading`, and every later change reconciles phone and email into a
        // draft the user may be typing in.
        .onChange(of: container.session.state) { _, _ in Task { await model?.sync() } }
        .onChange(of: model?.saveSucceeded) { _, succeeded in
            guard succeeded == true else { return }
            banner = BannerMessage(text: String(localized: "profile_save_success"))
            model?.consumeSaveSuccess()
        }
        // The age dialog's confirm dropped the session; there is nothing left on this screen to
        // edit, so it pops rather than rendering a form over a signed-out account.
        .onChange(of: model?.state) { _, state in
            if state == .signedOut { dismiss() }
        }
    }

    /// One completion path for all three sheets: banner the confirmation, close the sheet.
    private func finish(_ message: String) {
        banner = BannerMessage(text: message)
        sheet = nil
    }

    private var showsAgeDialog: Binding<Bool> {
        Binding(get: { model?.error == .ageIneligible },
                // The alert's only button already calls `confirmAgeIneligibleSignOut()`; a
                // system-driven dismissal must not silently clear an error nothing acted on.
                set: { _ in })
    }

    @ViewBuilder
    private var content: some View {
        if let model, let draft = model.draft {
            @Bindable var bindable = model
            VStack(alignment: .leading, spacing: Spacing.md(widthClass)) {
                Text(String(localized: "profile_personal_info"))
                    .font(TypeScale.headline(widthClass))
                    .foregroundStyle(Color.textPrimary)

                LabelledField(key: "profile_display_name") {
                    TextField(String(localized: "profile_display_name"), text: $bindable.displayName)
                        .textContentType(.name)
                        .autocorrectionDisabled()
                        .fieldChrome(widthClass)
                        .accessibilityLabel(String(localized: "profile_display_name"))
                    if let message = ProfileViewModel.fieldMessage(for: model.error, field: "displayName") {
                        InlineError(text: message)
                    }
                }

                dobField(model, draft: draft)
                saveButton(model)

                emailRow(draft)
                phoneRow(draft)
                if draft.hasPasswordProvider { passwordRow() }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            // A failed save is announced once, as a banner with a real dismiss control, rather
            // than as a line of body text no assistive technology reported.
            .onChange(of: model.error) { _, error in
                guard let error,
                      let message = ProfileViewModel.bannerMessage(for: error, locale: locale)
                else { return }
                banner = BannerMessage(text: message)
            }
        } else {
            ProgressView()
                .tint(.brand)
                .frame(maxWidth: .infinity)
                .padding(.vertical, Spacing.lg(widthClass))
                .accessibilityLabel(String(localized: "loading"))
        }
    }

    // MARK: - Date of birth

    /// The same shape the bootstrap form uses: a row that reads as unset until it is tapped, and
    /// only then a picker. `in: ...Date()` and nothing tighter — the under-13 rule lives in
    /// `BootstrapValidator`, and a second copy of it as a picker bound is a second thing to keep
    /// correct.
    @ViewBuilder
    private func dobField(_ model: ProfileViewModel, draft: ProfileFields) -> some View {
        LabelledField(key: "profile_date_of_birth") {
            Button { isPickingDate.toggle() } label: {
                HStack {
                    if let dob = draft.dateOfBirth {
                        Text(dob, format: .dateTime.year().month().day())
                            .foregroundStyle(Color.textPrimary)
                    } else {
                        Text(String(localized: "profile_dob_pick"))
                            .foregroundStyle(Color.textSecondary)
                    }
                    Spacer()
                    Image(systemName: "calendar").foregroundStyle(Color.textSecondary)
                }
                .font(TypeScale.body(widthClass))
                .padding(Spacing.md(widthClass))
                .frame(minHeight: 44)
                .background(Color.homeCard, in: RoundedRectangle(cornerRadius: Radius.card))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(String(localized: "profile_date_of_birth"))
            .accessibilityValue(draft.dateOfBirth.map {
                $0.formatted(.dateTime.year().month().day())
            } ?? String(localized: "profile_dob_pick"))
            .accessibilityIdentifier("profile.dateOfBirth")

            if isPickingDate {
                DatePicker("", selection: dobBinding(model), in: ...Date(), displayedComponents: .date)
                    .datePickerStyle(.graphical)
                    .labelsHidden()
            }
            if let message = ProfileViewModel.fieldMessage(for: model.error, field: "dateOfBirth") {
                InlineError(text: message)
            }
        }
    }

    private func dobBinding(_ model: ProfileViewModel) -> Binding<Date> {
        Binding(get: { model.dateOfBirth ?? Self.defaultDOB() }, set: { model.dateOfBirth = $0 })
    }

    /// Opening on 18 years ago keeps the wheel near a plausible birth year rather than one that is
    /// guaranteed to fail the gate.
    private static func defaultDOB() -> Date {
        Calendar.current.date(byAdding: .year, value: -18, to: Date()) ?? Date()
    }

    // MARK: - Save

    private func saveButton(_ model: ProfileViewModel) -> some View {
        let title = String(localized: "profile_save")
        let saving = model.isSaving
        return Button {
            Task { await model.save() }
        } label: {
            ZStack {
                Text(title).opacity(saving ? 0 : 1)
                if saving { ProgressView().tint(Color.onBrand) }
            }
            .font(TypeScale.body(widthClass))
            .foregroundStyle(Color.onBrand)
            .frame(maxWidth: .infinity, minHeight: Size.button(widthClass))
        }
        .buttonStyle(.borderedProminent)
        .tint(.brand)
        // No dead affordance: an unchanged form makes Save visibly unavailable rather than
        // tappable-and-ignored.
        .disabled(!model.canSave)
        .accessibilityLabel(title)
        .accessibilityValue(saving ? String(localized: "loading") : "")
        .accessibilityIdentifier("profile.save")
    }

    // MARK: - Rows

    /// The address is Firebase's, so it is read-only here. An account with no password provider
    /// (Google/Apple only) cannot re-authenticate with one either, so it gets the explanation
    /// instead of an Edit it could not complete.
    @ViewBuilder
    private func emailRow(_ draft: ProfileFields) -> some View {
        ProfileRow(label: String(localized: "profile_email_label"),
                   value: draft.emailReadOnly ?? "",
                   action: draft.hasPasswordProvider
                       ? (title: String(localized: "profile_edit"), run: { sheet = .email }) : nil)
        if !draft.hasPasswordProvider {
            Text(String(localized: "profile_email_locked"))
                .font(TypeScale.caption)
                .foregroundStyle(Color.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// The stored value renders as the raw E.164 the server holds (`+31612345678`): iOS carries no
    /// libphonenumber, and inventing a grouping for a number whose country we never asked for
    /// would be a guess shown as a fact.
    private func phoneRow(_ draft: ProfileFields) -> some View {
        let hasPhone = !(draft.phoneNumber ?? "").isEmpty
        return ProfileRow(
            label: String(localized: "profile_phone"),
            value: hasPhone ? (draft.phoneNumber ?? "") : String(localized: "profile_phone_unset"),
            action: (title: String(localized: hasPhone ? "profile_edit" : "profile_add"),
                     run: { sheet = .phone }))
    }

    private func passwordRow() -> some View {
        ProfileRow(label: String(localized: "profile_password"),
                   value: String(localized: "profile_password_dots"),
                   action: (title: String(localized: "profile_edit"), run: { sheet = .password }))
    }
}

// MARK: - Shared chrome

/// Label above the control, leading-aligned — RTL comes out of the alignment, never a literal.
/// The same shape `ProfileBootstrapScreen.labelled` has; shared here because four fields across
/// four files would otherwise be four copies of it (the wave-2 W9 lesson).
struct LabelledField<Content: View>: View {
    let key: String
    let content: Content

    init(key: String, @ViewBuilder content: () -> Content) {
        self.key = key
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Text(String(localized: String.LocalizationValue(key)))
                .font(TypeScale.caption)
                .foregroundStyle(Color.textSecondary)
            content
        }
    }
}

struct InlineError: View {
    let text: String

    init(text: String) { self.text = text }
    init(key: String) { self.text = String(localized: String.LocalizationValue(key)) }

    var body: some View {
        Text(text)
            .font(TypeScale.caption)
            .foregroundStyle(Color.errorText)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// One row: a label, its current value, and an optional trailing action. The whole row is 44 pt
/// tall and the action carries label AND value, so VoiceOver reads what it changes.
struct ProfileRow: View {
    let label: String
    let value: String
    var action: (title: String, run: () -> Void)?

    @Environment(\.widthClass) private var widthClass

    var body: some View {
        HStack(spacing: Spacing.md(widthClass)) {
            VStack(alignment: .leading, spacing: Spacing.xs) {
                Text(label)
                    .font(TypeScale.caption)
                    .foregroundStyle(Color.textSecondary)
                Text(value)
                    .font(TypeScale.body(widthClass))
                    .foregroundStyle(Color.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            if let action {
                Button(action.title, action: action.run)
                    .font(TypeScale.body(widthClass))
                    .foregroundStyle(Color.brand)
                    .frame(minWidth: 44, minHeight: 44)
                    .accessibilityLabel(action.title)
                    .accessibilityValue(label)
            }
        }
        .padding(Spacing.md(widthClass))
        .frame(minHeight: 44)
        .background(Color.homeCard, in: RoundedRectangle(cornerRadius: Radius.card))
    }
}

/// The chrome every edit sheet shares: the native grabber, the title, and a scrolling body that
/// survives Dynamic Type at `.accessibility5`.
struct EditSheetScaffold<Content: View>: View {
    let title: String
    let content: Content

    @Environment(\.widthClass) private var widthClass

    init(title: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.md(widthClass)) {
                Text(title)
                    .font(TypeScale.headline(widthClass))
                    .foregroundStyle(Color.textPrimary)
                    .accessibilityAddTraits(.isHeader)
                content
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(Spacing.md(widthClass))
        }
        .background(Color.background.ignoresSafeArea())
        .presentationDragIndicator(.visible)
        .presentationDetents([.medium, .large])
    }
}

/// The sheets' one primary action: full width, `Size.button` tall, and dead while its own work is
/// in flight so a double tap cannot send twice.
struct EditSheetAction: View {
    let title: String
    let isLoading: Bool
    let run: () async -> Void

    @Environment(\.widthClass) private var widthClass

    var body: some View {
        Button {
            Task { await run() }
        } label: {
            ZStack {
                Text(title).opacity(isLoading ? 0 : 1)
                if isLoading { ProgressView().tint(Color.onBrand) }
            }
            .font(TypeScale.body(widthClass))
            .foregroundStyle(Color.onBrand)
            .frame(maxWidth: .infinity, minHeight: Size.button(widthClass))
        }
        .buttonStyle(.borderedProminent)
        .tint(.brand)
        .disabled(isLoading)
        .accessibilityLabel(title)
        .accessibilityValue(isLoading ? String(localized: "loading") : "")
    }
}

#if DEBUG
#Preview {
    NavigationStack { ProfileScreen() }
        .environment(\.container, .sharedFake)
}

#Preview("RTL") {
    NavigationStack { ProfileScreen() }
        .environment(\.container, .sharedFake)
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}
#endif
