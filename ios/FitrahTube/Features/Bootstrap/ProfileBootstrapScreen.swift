import SwiftUI

/// Spec §13's `.profileBootstrap` landing: an account the backend reports as `pending_profile`
/// completes the mandatory profile here (`ProfileBootstrapFragment.kt`). Rendered as a ROOT
/// destination by `RootView` — there is no way past it and no tab bar under it — and, for the same
/// account reaching it from inside the shell, as `Route.profileBootstrap`.
///
/// The phone field is ONE free-text field with a fixed leading "+" and the E.164 shape as its
/// placeholder. No country picker (ruling C1/F5): Android's picker fed libphonenumber, which iOS does
/// not carry, and the server's own regex is the whole rule.
struct ProfileBootstrapScreen: View {
    @Environment(\.container) private var container
    @Environment(\.widthClass) private var widthClass

    @State private var viewModel: ProfileBootstrapViewModel?
    @State private var isPickingDate = false

    var body: some View {
        Group {
            if viewModel?.nav == .ageIneligible {
                // In place, not pushed: this screen is a root destination, and the terminal screen
                // must not be something a back gesture can leave.
                AgeIneligibleScreen()
            } else {
                form
            }
        }
        .task {
            if viewModel == nil {
                viewModel = ProfileBootstrapViewModel(account: container.account, auth: container.auth,
                                                      session: container.session)
            }
            await viewModel?.load()
        }
    }

    private var form: some View {
        ScrollView {
            content.padding(Spacing.md(widthClass))
        }
        .background(Color.background.ignoresSafeArea())
        .navigationBarBackButtonHidden()
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private var content: some View {
        if let viewModel {
            VStack(alignment: .leading, spacing: Spacing.md(widthClass)) {
                Text(String(localized: "bootstrap_title"))
                    .font(TypeScale.headline(widthClass))
                    .foregroundStyle(Color.textPrimary)

                nameField(viewModel)
                dobField(viewModel)
                phoneField(viewModel)
                if viewModel.state.passwordRequired { passwordFields(viewModel) }

                if let error = viewModel.state.error {
                    Text(String(localized: String.LocalizationValue(error.messageKey)))
                        .font(TypeScale.body(widthClass))
                        .foregroundStyle(Color.errorText)
                        .fixedSize(horizontal: false, vertical: true)
                }

                submitButton(viewModel)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    // MARK: - Fields

    @ViewBuilder
    private func nameField(_ model: ProfileBootstrapViewModel) -> some View {
        @Bindable var bindable = model
        // The 40-character cap lives in the view model's setter (`android:maxLength="40"`), so a
        // paste is capped exactly as typing is.
        labelled("bootstrap_display_name_label") {
            TextField(String(localized: "bootstrap_display_name_hint"), text: $bindable.displayName)
                .textContentType(.name)
                .autocorrectionDisabled()
                .fieldChrome(widthClass)
                .accessibilityLabel(String(localized: "bootstrap_display_name_label"))
        }
    }

    @ViewBuilder
    private func dobField(_ model: ProfileBootstrapViewModel) -> some View {
        // A `DatePicker` has no empty state, and defaulting one would show a date the user never
        // chose — on the one field whose mistyped value the server punishes permanently. So the row
        // reads as unset until it is tapped, and only then does a picker appear.
        labelled("bootstrap_dob_label") {
            Button {
                isPickingDate.toggle()
            } label: {
                HStack {
                    if let dob = model.state.dateOfBirth {
                        Text(dob, format: .dateTime.year().month().day())
                            .foregroundStyle(Color.textPrimary)
                    } else {
                        Text(String(localized: "bootstrap_dob_hint"))
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
            .accessibilityLabel(String(localized: "bootstrap_dob_label"))
            .accessibilityValue(model.state.dateOfBirth.map {
                $0.formatted(.dateTime.year().month().day())
            } ?? String(localized: "bootstrap_dob_hint"))
            .accessibilityIdentifier("bootstrap.dateOfBirth")

            if isPickingDate {
                DatePicker("", selection: dobBinding(model), in: ...Date(), displayedComponents: .date)
                    .datePickerStyle(.graphical)
                    .labelsHidden()
            }
        }
    }

    /// The picker needs a non-optional selection; writing through it is what makes the field "set".
    /// Opening on 18 years ago rather than today keeps the wheel near a plausible birth year instead
    /// of one that is guaranteed to be under age.
    private func dobBinding(_ model: ProfileBootstrapViewModel) -> Binding<Date> {
        Binding(get: { model.state.dateOfBirth ?? BootstrapValidator.defaultDateOfBirth() },
                set: { model.dateOfBirth = $0 })
    }

    @ViewBuilder
    private func phoneField(_ model: ProfileBootstrapViewModel) -> some View {
        @Bindable var bindable = model
        labelled("bootstrap_phone_label") {
            HStack(spacing: 0) {
                // A fixed prefix, not something the user can delete or duplicate — the server's
                // regex requires exactly one leading "+".
                Text(verbatim: "+")
                    .font(TypeScale.body(widthClass))
                    .foregroundStyle(Color.textSecondary)
                    .padding(.leading, Spacing.md(widthClass))
                    // Decoration, not content: VoiceOver reads the field's own label instead.
                    .accessibilityHidden(true)
                // The placeholder is the ONLY country hint on this screen (ruling C1 — no country
                // picker), so it has to show the SHAPE: a dial code then the national number.
                // Without it a user types their national number after the fixed "+", gets a disabled
                // button, and is never told a dial code is required. Digits need no translation,
                // hence `verbatim`; `bootstrap_phone_hint` stays the field's label.
                TextField(String(localized: "bootstrap_phone_hint"), text: $bindable.phoneNumber,
                          prompt: Text(verbatim: "31612345678"))
                    .textContentType(.telephoneNumber)
                    .keyboardType(.phonePad)
                    .autocorrectionDisabled()
                    .font(TypeScale.body(widthClass))
                    .padding(Spacing.md(widthClass))
                    .accessibilityLabel(String(localized: "bootstrap_phone_label"))
            }
            .frame(minHeight: 44)
            .background(Color.homeCard, in: RoundedRectangle(cornerRadius: Radius.card))
        }
    }

    @ViewBuilder
    private func passwordFields(_ model: ProfileBootstrapViewModel) -> some View {
        @Bindable var bindable = model
        Text(String(localized: "bootstrap_password_explainer"))
            .font(TypeScale.caption)
            .foregroundStyle(Color.textSecondary)
            .fixedSize(horizontal: false, vertical: true)

        labelled("bootstrap_password_label") {
            SecureField(String(localized: "bootstrap_password_label"), text: $bindable.password)
                .textContentType(.newPassword)
                .textInputAutocapitalization(.never)
                .fieldChrome(widthClass)
                .accessibilityLabel(String(localized: "bootstrap_password_label"))
        }

        labelled("bootstrap_password_confirm_label") {
            SecureField(String(localized: "bootstrap_password_confirm_label"), text: $bindable.passwordConfirm)
                .textContentType(.newPassword)
                .textInputAutocapitalization(.never)
                .fieldChrome(widthClass)
                .accessibilityLabel(String(localized: "bootstrap_password_confirm_label"))
        }
    }

    private func submitButton(_ model: ProfileBootstrapViewModel) -> some View {
        let title = String(localized: "bootstrap_submit_button")
        return Button {
            Task { await model.submit() }
        } label: {
            ZStack {
                Text(title).opacity(model.state.isLoading ? 0 : 1)
                if model.state.isLoading { ProgressView().tint(Color.onBrand) }
            }
            .font(TypeScale.body(widthClass))
            .foregroundStyle(Color.onBrand)
            .frame(maxWidth: .infinity, minHeight: Size.button(widthClass))
        }
        .buttonStyle(.borderedProminent)
        .tint(.brand)
        // The ONE validator's other consumer (ruling F11, no dead affordance): an incomplete form
        // makes the button visibly unavailable rather than tappable-and-refused.
        .disabled(model.state.isLoading || !model.isFormValid)
        .accessibilityLabel(title)
        .accessibilityValue(model.state.isLoading ? String(localized: "loading") : "")
        .accessibilityIdentifier("bootstrap.submit")
    }

    /// Label above the control, leading-aligned — RTL comes out of the alignment, never a literal.
    @ViewBuilder
    private func labelled(_ key: String, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Text(String(localized: String.LocalizationValue(key)))
                .font(TypeScale.caption)
                .foregroundStyle(Color.textSecondary)
            content()
        }
    }
}

#if DEBUG
private let previewGoogleUser = AuthUser(uid: "preview", email: "student@fitrah.test",
                                         isEmailVerified: true, providerIDs: ["google.com"])

#Preview {
    ProfileBootstrapScreen()
        .environment(\.container, .fake(auth: FakeAuthClient(state: .signedIn(previewGoogleUser))))
}

#Preview("RTL") {
    ProfileBootstrapScreen()
        .environment(\.container, .fake(auth: FakeAuthClient(state: .signedIn(previewGoogleUser))))
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}
#endif
