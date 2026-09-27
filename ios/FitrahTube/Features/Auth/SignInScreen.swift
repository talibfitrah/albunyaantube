import SwiftUI

/// The first user-visible auth surface (spec §13): email/password with a sign-up toggle and
/// forgot-password, then the capability-filtered provider buttons. Owner ruling 2026-09-24
/// (overrides D11 / RULING 31): this is the forced gate in front of all content — `RootView`
/// renders it as the root for every signed-out user, with no close and no guest escape.
///
/// On success it dismisses (a no-op at the root): `RootView.destination(for:)` is the seam that
/// renders where spec §13 lands the account (`.emailVerification` / `.profileBootstrap` / the
/// shell), recomputed from `AccountSession` the moment the auth stream carries the new identity.
struct SignInScreen: View {
    @Environment(\.container) private var container
    @Environment(\.widthClass) private var widthClass
    @Environment(\.dismiss) private var dismiss

    @State private var viewModel: SignInViewModel?
    /// Google's "G" is 20 pt at the default text size and grows with Dynamic Type like the Apple
    /// symbol beside it (the asset is drawn at 66 pt, the size it reaches at the largest text setting).
    @ScaledMetric(relativeTo: .subheadline) private var googleLogoSize: CGFloat = 20
    @State private var banner: BannerMessage?
    /// Each provider label's natural height. Both buttons take the TALLER one, so a title that wraps
    /// at a large text size (Arabic "Sign in with Apple" at AX1) never leaves the pair unequal.
    @State private var providerLabelHeights: [SignInProvider: CGFloat] = [:]

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
                                            account: container.account, capabilities: container.capabilities)
            }
        }
        // A failed `/me` signs out and routes back here with its reason kept on the state
        // (Android toasts `splash_couldnt_connect` for the same row), as does a refused sign-out;
        // without this the form just reappears with no reason given. Follows the session, not
        // `.task`, so a failure while this screen is already up still says so.
        .onChange(of: container.session.failureNotice, initial: true) { _, notice in
            guard notice != nil, let message = container.session.consumeFailureNotice() else { return }
            banner = BannerMessage(text: message)
        }
        // Fix round 1 / I1: driven off `errorPresentation`, NOT `state.error`. Neither pre-network
        // gate clears the error first, so a second tap on the same malformed address was not a
        // value change and raised no banner at all — the one path a confused user takes went mute.
        .onChange(of: viewModel?.errorPresentation) { _, presentation in
            guard let presentation else { return }
            banner = BannerMessage(text: String(localized: String.LocalizationValue(presentation.code.messageKey)))
        }
        .onChange(of: viewModel?.state.passwordResetSent) { _, sent in
            guard sent == true else { return }
            banner = BannerMessage(text: String(localized: "auth_password_reset_sent"))
        }
        .onChange(of: viewModel?.landed) { _, landed in
            guard landed == true else { return }
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
                    legalFooter
                }
                .frame(maxWidth: .infinity)
            }
        }
    }

    /// Guideline 5.1.1: the privacy policy and terms, readable before an account exists — About's
    /// own entries (`AboutLinks.beforeSignIn`), side by side when they fit, stacked when they don't.
    private var legalFooter: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: Spacing.lg(widthClass)) { legalLinks }
            VStack(spacing: 0) { legalLinks }
        }
        .frame(maxWidth: .infinity)
    }

    private var legalLinks: some View {
        ForEach(AboutLinks.beforeSignIn) { link in
            Link(destination: link.url) {
                // Inside the label, so the 44 pt floor is the tappable area, not just the layout.
                Text(String(localized: String.LocalizationValue(link.titleKey)))
                    .font(TypeScale.caption)
                    .foregroundStyle(Color.brand)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
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
        // Fix round 1 / M4: while loading the title is at `.opacity(0)` and so out of the
        // accessibility tree, leaving an unlabelled `ProgressView` — VoiceOver announced a button
        // with no name mid-submit. Label and value, the floor for every stateful control.
        .accessibilityLabel(submitTitle(model))
        .accessibilityValue(model.state.isLoading ? String(localized: "loading") : "")
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

    /// Each provider in its brand's own published style (Apple HIG's black/white button, Google's
    /// light/dark themes — `Tokens.swift`), on ONE shape and size so neither outranks the other.
    @ViewBuilder
    private func providerButton(_ provider: SignInProvider, model: SignInViewModel) -> some View {
        if let source = source(for: provider) {
            let apple = provider == .apple
            Button {
                Task { await model.signIn(with: source) }
            } label: {
                // Google's spec puts 12 pt between its logo and the label.
                HStack(spacing: apple ? Spacing.sm : 12) {
                    if apple {
                        Image(systemName: "apple.logo")
                            .accessibilityHidden(true)
                        Text(String(localized: "auth_apple_button"))
                    } else {
                        // Google's branding rules require its own "G" (official asset), never a stand-in symbol.
                        Image("google-g")
                            .resizable()
                            .frame(width: googleLogoSize, height: googleLogoSize)
                            .accessibilityHidden(true)
                        Text(String(localized: "auth_google_button"))
                    }
                }
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { providerLabelHeights[provider] = $0 }
                .font(TypeScale.body(widthClass).weight(.medium))
                .foregroundStyle(apple ? Color.appleButtonText : Color.googleButtonText)
                .padding(.horizontal, Spacing.md(widthClass))
                .padding(.vertical, Spacing.sm)
                .frame(maxWidth: .infinity, minHeight: max(Size.button(widthClass),
                                                           (providerLabelHeights.values.max() ?? 0) + 2 * Spacing.sm))
                .background(apple ? Color.appleButtonFill : Color.googleButtonFill, in: Capsule())
                .overlay { if !apple { Capsule().strokeBorder(Color.googleButtonBorder, lineWidth: 1) } }
                .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            // Every control is dead while a sign-in is in flight — which is what keeps
            // `AppleAuthProvider`'s re-entrancy latch from ever being the user-visible path. A plain
            // style draws no disabled state of its own, so the whole button (logo, fill and label
            // alike) drops to Google's 38% disabled opacity.
            .disabled(model.state.isLoading)
            .opacity(model.state.isLoading ? 0.38 : 1)
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

extension View {
    /// One field chrome for every auth/bootstrap text field — RTL-safe (no leading/trailing
    /// literals) and tall enough for the 44 pt floor at every Dynamic Type size. Internal, not
    /// file-private, since Task 12: `ProfileBootstrapScreen`'s fields are the same chrome, and a
    /// second copy is a second thing to keep in step with the 44 pt floor.
    func fieldChrome(_ widthClass: WidthClass) -> some View {
        textFieldStyle(.plain)
            .font(TypeScale.body(widthClass))
            .padding(Spacing.md(widthClass))
            .frame(minHeight: 44)
            .background(Color.homeCard, in: RoundedRectangle(cornerRadius: Radius.card))
    }
}

#if DEBUG
// Fix round 1 / M1: `sharedFake` has `capabilities: nil` -> `.current()` -> all-false with no
// plist, so both previews rendered the unavailable `EmptyStateView` and nothing anywhere showed
// this screen's actual layout. The all-true capabilities are the seam Task 5 left for exactly this.
private let previewCapabilities = SignInCapabilities(emailPassword: true, google: true, apple: true)

#Preview {
    NavigationStack { SignInScreen() }
        .environment(\.container, .fake(capabilities: previewCapabilities))
}

#Preview("RTL") {
    NavigationStack { SignInScreen() }
        .environment(\.container, .fake(capabilities: previewCapabilities))
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}
#endif
