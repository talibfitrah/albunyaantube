import SwiftUI

/// Spec §13: a password account that has not verified its address lands here instead of the shell.
/// Rendered as a ROOT destination by `RootView` (the account cannot tab away from it) and, for the
/// same account reaching it from inside the shell, as `Route.emailVerification`.
///
/// There is no "open your mail app" affordance and no link out — the user is told what to do and
/// given the two actions the app itself can perform.
struct EmailVerificationScreen: View {
    @Environment(\.container) private var container
    @Environment(\.widthClass) private var widthClass
    @Environment(\.locale) private var locale

    @State private var viewModel: EmailVerificationViewModel?
    @State private var banner: BannerMessage?

    var body: some View {
        ScrollView {
            content.padding(Spacing.md(widthClass))
        }
        .background(Color.background.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        // Fix round 1 / I1: the outcome of "I've verified my email" used to be a bare inline `Text`
        // below the button, so VoiceOver — whose focus stays on the button, and whose value goes
        // "" -> "Loading…" -> "" — heard the spinner start and then nothing at all. The banner is
        // the app's ONE feedback path for this class of result (`SignInScreen`, `PlayerScreen`) and
        // it posts an `AccessibilityNotification.Announcement`; a second path would be a second
        // thing to keep in step. A REPEATED outcome still announces: every arm the screen can reach
        // is written after an explicit `state.error = nil`, and the one arm that is not
        // (`resend()`'s pre-network refusal) is unreachable while the button it guards is disabled.
        .transientBanner($banner)
        .onChange(of: viewModel?.state.error) { _, error in
            guard let key = errorKey(error ?? nil) else { return }
            banner = BannerMessage(text: String(localized: String.LocalizationValue(key)))
        }
        .task {
            if viewModel == nil {
                viewModel = EmailVerificationViewModel(auth: container.auth, session: container.session,
                                                       account: container.account,
                                                       defaults: container.userDefaults)
            }
            // Latched inside the view model by the PERSISTED timestamp, so a re-entry (or a second
            // `.task` after an identity change) sends nothing.
            await viewModel?.send()
        }
    }

    @ViewBuilder
    private var content: some View {
        if let viewModel {
            VStack(alignment: .leading, spacing: Spacing.md(widthClass)) {
                // The screen's own heading, not `.navigationTitle`: as a root destination there is
                // no navigation bar to put one in.
                Text(String(localized: "email_verification_title"))
                    .font(TypeScale.headline(widthClass))
                    .foregroundStyle(Color.textPrimary)

                // `\u{2068}…\u{2069}` around the address: an email is a neutral-direction run and,
                // unisolated, its leading/trailing punctuation reorders inside an Arabic sentence.
                Text(Format.localizedFormat("email_verification_body", locale: locale,
                                            "\u{2068}\(viewModel.state.email)\u{2069}"))
                    .font(TypeScale.body(widthClass))
                    .foregroundStyle(Color.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)

                checkNowButton(viewModel)
                // One second is the resolution `email_verification_last_sent` prints, so the tick
                // and the label agree; the same tick is what re-enables Resend the moment the
                // cooldown lapses, with no timer object to own or cancel.
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    resendSection(viewModel, at: context.date)
                }
                signOutButton(viewModel)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func checkNowButton(_ model: EmailVerificationViewModel) -> some View {
        let title = String(localized: "email_verification_check_now")
        return Button {
            Task { _ = await model.checkNow() }
        } label: {
            ZStack {
                // Hidden rather than removed, so the row does not jump while the check runs.
                Text(title).opacity(model.state.isChecking ? 0 : 1)
                if model.state.isChecking { ProgressView().tint(Color.onBrand) }
            }
            .font(TypeScale.body(widthClass))
            .foregroundStyle(Color.onBrand)
            .frame(maxWidth: .infinity, minHeight: Size.button(widthClass))
        }
        .buttonStyle(.borderedProminent)
        .tint(.brand)
        .disabled(busy(model))
        .accessibilityLabel(title)
        .accessibilityValue(model.state.isChecking ? String(localized: "loading") : "")
        .accessibilityIdentifier("emailVerification.checkNow")
    }

    @ViewBuilder
    private func resendSection(_ model: EmailVerificationViewModel, at date: Date) -> some View {
        let title = String(localized: "email_verification_resend")
        let elapsed = model.secondsSinceLastSend(at: date)
        let lastSent = elapsed.map {
            Format.localizedFormat("email_verification_last_sent", locale: locale, Int64($0))
        }

        VStack(alignment: .leading, spacing: Spacing.xs) {
            Button {
                Task { await model.resend() }
            } label: {
                ZStack {
                    Text(title).opacity(model.state.isResending ? 0 : 1)
                    if model.state.isResending { ProgressView() }
                }
                .font(TypeScale.body(widthClass))
                .foregroundStyle(Color.textPrimary)
                .frame(maxWidth: .infinity, minHeight: Size.button(widthClass))
            }
            .buttonStyle(.bordered)
            // Ruling F11, no dead affordance: inside the cooldown the button is visibly unavailable
            // rather than tappable-and-refused.
            .disabled(busy(model) || !model.canResend(at: date))
            .accessibilityLabel(title)
            // Label AND value: the countdown is the whole reason the button is disabled, so a
            // VoiceOver user has to hear it.
            .accessibilityValue(model.state.isResending ? String(localized: "loading") : (lastSent ?? ""))
            .accessibilityIdentifier("emailVerification.resend")

            if let lastSent {
                Text(lastSent)
                    .font(TypeScale.caption)
                    .foregroundStyle(Color.textSecondary)
                    .accessibilityHidden(true)   // already the button's value
            }
        }
    }

    private func signOutButton(_ model: EmailVerificationViewModel) -> some View {
        // Spec §13's back affordance: it SIGNS OUT. `RootView` recomputes the outcome off the
        // dropped session and renders the guest shell, so there is nothing to dismiss here.
        Button {
            model.signOut()
        } label: {
            Text(String(localized: "email_verification_use_different"))
                .font(TypeScale.body(widthClass))
                .foregroundStyle(Color.brand)
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(busy(model))
    }

    private func busy(_ model: EmailVerificationViewModel) -> Bool {
        model.state.isChecking || model.state.isResending
    }

    /// `.network` and `.unknown` share Android's copy — one thing went wrong that the user can only
    /// answer by trying again, and saying WHICH would say why (never the rule).
    private func errorKey(_ error: EmailVerificationViewModel.EmailVerifyError?) -> String? {
        switch error {
        case .notYetVerified: "email_verification_not_yet"
        // One sentence for both throttles: the user waits either way, and WHICH throttle refused
        // is a WHY (Stage 9 round 3 / (b)).
        case .rateLimited, .throttled: "email_verification_rate_limited"
        case .network, .unknown: "email_verification_network_error"
        case nil: nil
        }
    }
}

#if DEBUG
/// Fix round 1 / M2: its OWN suite per `AppContainer.fake(defaults:)`'s contract — this screen
/// WRITES the auto-send latch through the container's defaults, and the shared "fitrahtube.fake"
/// suite would carry it into every other preview (and back into the second run of this one, which
/// then opens on a stale countdown having auto-sent nothing).
private func previewDefaults(_ name: String) -> UserDefaults {
    UserDefaults(suiteName: "fitrahtube.preview.emailVerification.\(name)") ?? .standard
}

private let previewUser = AuthUser(uid: "preview", email: "student@fitrah.test",
                                   isEmailVerified: false, providerIDs: ["password"])

#Preview {
    EmailVerificationScreen()
        .environment(\.container, .fake(defaults: previewDefaults("ltr"),
                                       auth: FakeAuthClient(state: .signedIn(previewUser))))
}

#Preview("RTL") {
    EmailVerificationScreen()
        .environment(\.container, .fake(defaults: previewDefaults("rtl"),
                                       auth: FakeAuthClient(state: .signedIn(previewUser))))
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}
#endif
