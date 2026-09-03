import SwiftUI

/// The terminal under-13 screen (`AgeIneligibleFragment.kt`). Reached only from
/// `ProfileBootstrapScreen` when the server answered 422 / `AGE_INELIGIBLE`, and it is a dead end by
/// design: there is no back affordance, because bouncing back to the form to retry with a different
/// date of birth is exactly what the age gate exists to prevent.
///
/// The one button deletes the Firebase user and drops to guest. The backend has already revoked the
/// account's refresh tokens, so a failed delete is not terminal — the ID token expires and the
/// account cannot sign back in either way.
struct AgeIneligibleScreen: View {
    @Environment(\.container) private var container
    @Environment(\.router) private var router
    @Environment(\.widthClass) private var widthClass

    @State private var isWorking = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.md(widthClass)) {
                // The screen's own heading, not `.navigationTitle`: it renders as a root destination
                // too, where there is no navigation bar to put one in.
                Text(String(localized: "age_ineligible_title"))
                    .font(TypeScale.headline(widthClass))
                    .foregroundStyle(Color.textPrimary)

                Text(String(localized: "age_ineligible_body"))
                    .font(TypeScale.body(widthClass))
                    .foregroundStyle(Color.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)

                okButton
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(Spacing.md(widthClass))
        }
        .background(Color.background.ignoresSafeArea())
        .navigationBarBackButtonHidden()
        .navigationBarTitleDisplayMode(.inline)
    }

    private var okButton: some View {
        let title = String(localized: "age_ineligible_ok_button")
        return Button {
            Task { await acknowledge() }
        } label: {
            ZStack {
                // Hidden rather than removed, so the row does not jump while the delete runs.
                Text(title).opacity(isWorking ? 0 : 1)
                if isWorking { ProgressView().tint(Color.onBrand) }
            }
            .font(TypeScale.body(widthClass))
            .foregroundStyle(Color.onBrand)
            .frame(maxWidth: .infinity, minHeight: Size.button(widthClass))
        }
        .buttonStyle(.borderedProminent)
        .tint(.brand)
        .disabled(isWorking)
        .accessibilityLabel(title)
        .accessibilityValue(isWorking ? String(localized: "loading") : "")
        .accessibilityIdentifier("ageIneligible.ok")
    }

    private func acknowledge() async {
        guard !isWorking else { return }
        isWorking = true
        // `try?`: the tokens are already revoked server-side, so a network failure here changes
        // nothing the user can act on (`AgeIneligibleViewModel.kt:36-40` logs and proceeds).
        try? await container.auth.deleteUser()
        // Through the session, never the auth client directly — only the session re-scopes every
        // per-user store back to the guest sentinel.
        container.session.signOut()
        // `RootView` recomputes its outcome off the dropped session and renders the guest shell; the
        // pop is for the in-shell `Route.ageIneligible` entry, where a pushed stack would survive it.
        Tab.allCases.forEach { router.popToRoot($0) }
        isWorking = false
    }
}

#if DEBUG
#Preview {
    AgeIneligibleScreen()
        .environment(\.container, .fake(auth: FakeAuthClient(state: .signedIn(FakeAuthClient.defaultUser))))
}

#Preview("RTL") {
    AgeIneligibleScreen()
        .environment(\.container, .fake(auth: FakeAuthClient(state: .signedIn(FakeAuthClient.defaultUser))))
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}
#endif
