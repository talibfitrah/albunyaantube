import SwiftUI

/// The first user-visible auth surface (spec §13): email/password with a sign-up toggle and
/// forgot-password, then the capability-filtered provider buttons. Reached from the guest Me tab's
/// sign-in card — never forced, never a gate in front of the catalog (D11 / RULING 31).
///
/// On success it dismisses: `RootView.destination(for:)` is the seam that renders where spec §13
/// lands the account (`.emailVerification` / `.profileBootstrap` / the shell), recomputed from
/// `AccountSession` the moment the auth stream carries the new identity.
struct SignInScreen: View {
    @Environment(\.container) private var container
    @Environment(\.widthClass) private var widthClass
    @Environment(\.dismiss) private var dismiss

    @State private var viewModel: SignInViewModel?
    @State private var banner: BannerMessage?

    var body: some View {
        ScrollView {
            content.padding(Spacing.md(widthClass))
        }
        .background(Color.background.ignoresSafeArea())
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .transientBanner($banner)
        .task {
            if viewModel == nil {
                viewModel = SignInViewModel(auth: container.auth, session: container.session,
                                            capabilities: container.capabilities)
            }
        }
        .onChange(of: viewModel?.state.error) { _, error in
            guard let error else { return }
            banner = BannerMessage(text: String(localized: String.LocalizationValue(error.messageKey)))
        }
        .onChange(of: viewModel?.state.passwordResetSent) { _, sent in
            guard sent == true else { return }
            banner = BannerMessage(text: String(localized: "auth_password_reset_sent"))
        }
        .onChange(of: viewModel?.landing) { _, landing in
            guard landing != nil else { return }
            dismiss()
        }
    }

    private var title: String {
        viewModel?.state.mode == .signUp
            ? String(localized: "auth_sign_up_title")
            : String(localized: "auth_sign_in_title")
    }

    @ViewBuilder
    private var content: some View {
        if let viewModel {
            // With no Firebase options file NOTHING here can complete, so the screen offers nothing
            // rather than a form that always fails — ruling F11, the same rule that hides a provider
            // button without its prerequisite. WHAT, never why.
            if viewModel.visibleProviders.isEmpty {
                EmptyStateView(systemImage: "person.crop.circle.badge.exclamationmark",
                               message: String(localized: "auth_error_generic"))
                    .frame(minHeight: Size.iconXL(widthClass) * 3)
            } else {
                VStack(alignment: .leading, spacing: Spacing.md(widthClass)) {
                    if viewModel.visibleProviders.contains(.emailPassword) {
                        emailSection(viewModel)
                    }
                    let federated = viewModel.visibleProviders.filter { $0 != .emailPassword }
                    if !federated.isEmpty {
                        divider
                        ForEach(federated, id: \.self) { provider in
                            providerButton(provider, model: viewModel)
                        }
                    }
                }
                .frame(maxWidth: .infinity)
            }
        }
    }

    // MARK: - Email/password

    @ViewBuilder
    private func emailSection(_ model: SignInViewModel) -> some View {
        @Bindable var bindable = model

        TextField(String(localized: "auth_email_hint"), text: $bindable.email)
            .textContentType(.emailAddress)
            .keyboardType(.emailAddress)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .fieldChrome(widthClass)
            .accessibilityLabel(String(localized: "auth_email_hint"))

        SecureField(String(localized: "auth_password_hint"), text: $bindable.password)
            // `.password` signing in, `.newPassword` signing up: the second is what stops iOS
            // offering a saved credential on a form that is creating a different account.
            .textContentType(model.state.mode == .signIn ? .password : .newPassword)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .fieldChrome(widthClass)
            .accessibilityLabel(String(localized: "auth_password_hint"))

        Button {
            Task { await model.submit() }
        } label: {
            ZStack {
                // Hidden, not removed: the label holds the button's height while the spinner runs,
                // so the layout does not jump on every tap.
                submitTitle(model).opacity(model.state.isLoading ? 0 : 1)
                if model.state.isLoading {
                    ProgressView().tint(Color.onBrand)
                }
            }
            .foregroundStyle(Color.onBrand)
            .frame(maxWidth: .infinity, minHeight: Size.button(widthClass))
        }
        .buttonStyle(.borderedProminent)
        .tint(.brand)
        .disabled(model.state.isLoading)
        .accessibilityIdentifier("signIn.submit")

        if model.state.mode == .signIn {
            linkButton(String(localized: "auth_forgot_password"), model: model) {
                Task { await model.forgotPassword() }
            }
            linkButton(String(localized: "auth_create_account_link"), model: model) { model.toggleMode() }
        } else {
            linkButton(String(localized: "auth_have_account_link"), model: model) { model.toggleMode() }
        }
    }

    private func submitTitle(_ model: SignInViewModel) -> Text {
        model.state.mode == .signIn
            ? Text(String(localized: "auth_sign_in_button"))
            : Text(String(localized: "auth_sign_up_button"))
    }

    /// A text link that still meets the 44 pt floor.
    private func linkButton(_ title: String, model: SignInViewModel,
                            action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(TypeScale.body(widthClass))
                .foregroundStyle(Color.brand)
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(model.state.isLoading)
    }

    // MARK: - Providers

    private var divider: some View {
        HStack(spacing: Spacing.sm) {
            line
            Text(String(localized: "auth_divider_or"))
                .font(TypeScale.body(widthClass))
                .foregroundStyle(Color.textSecondary)
            line
        }
        .accessibilityHidden(true)
    }

    private var line: some View {
        Rectangle().fill(Color.textSecondary.opacity(0.3)).frame(height: 1)
    }

    @ViewBuilder
    private func providerButton(_ provider: SignInProvider, model: SignInViewModel) -> some View {
        if let source = source(for: provider) {
            Button {
                Task { await model.signIn(with: source) }
            } label: {
                HStack(spacing: Spacing.sm) {
                    Image(systemName: provider == .apple ? "apple.logo" : "g.circle")
                        .accessibilityHidden(true)
                    if provider == .apple {
                        Text(String(localized: "auth_apple_button"))
                    } else {
                        Text(String(localized: "auth_google_button"))
                    }
                }
                .font(TypeScale.body(widthClass))
                .foregroundStyle(Color.textPrimary)
                .frame(maxWidth: .infinity, minHeight: Size.button(widthClass))
            }
            .buttonStyle(.bordered)
            // Every control is dead while a sign-in is in flight — which is what keeps
            // `AppleAuthProvider`'s re-entrancy latch from ever being the user-visible path.
            .disabled(model.state.isLoading)
        }
    }

    private func source(for provider: SignInProvider) -> (any OAuthSignInProvider)? {
        switch provider {
        case .google: container.googleSignIn
        case .apple: container.appleSignIn
        case .emailPassword: nil
        }
    }
}

private extension View {
    /// One field chrome for both text fields — RTL-safe (no leading/trailing literals) and tall
    /// enough for the 44 pt floor at every Dynamic Type size.
    func fieldChrome(_ widthClass: WidthClass) -> some View {
        textFieldStyle(.plain)
            .font(TypeScale.body(widthClass))
            .padding(Spacing.md(widthClass))
            .frame(minHeight: 44)
            .background(Color.homeCard, in: RoundedRectangle(cornerRadius: Radius.card))
    }
}

#if DEBUG
#Preview {
    NavigationStack { SignInScreen() }
        .environment(\.container, .sharedFake)
}

#Preview("RTL") {
    NavigationStack { SignInScreen() }
        .environment(\.container, .sharedFake)
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}
#endif
