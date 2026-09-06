import SwiftUI

/// The terminal under-13 screen (`AgeIneligibleFragment.kt`). `RootView` presents it as a full
/// screen cover on `AccountSession.isAgeIneligible`, which BOTH arrivals of the 422 set — the
/// bootstrap form's submit and the profile edit's save. A dead end by design: no back affordance,
/// because bouncing back to the form to retry with a different date of birth is exactly what the
/// age gate exists to prevent.
///
/// R7-P1 #3: PURELY INFORMATIONAL. The Firebase delete, the sign-out and the `.signedOut`
/// announcement all ran when the verdict arrived — leaving them on this button meant a foreground
/// refresh could replace the screen with the blocked message and they would never run at all — so
/// the one button only navigates, and there is nothing here to spin for.
struct AgeIneligibleScreen: View {
    @Environment(\.container) private var container
    @Environment(\.router) private var router
    @Environment(\.widthClass) private var widthClass

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
        return Button(action: acknowledge) {
            Text(title)
                .font(TypeScale.body(widthClass))
                .foregroundStyle(Color.onBrand)
                .frame(maxWidth: .infinity, minHeight: Size.button(widthClass))
        }
        .buttonStyle(.borderedProminent)
        .tint(.brand)
        .accessibilityLabel(title)
        .accessibilityIdentifier("ageIneligible.ok")
    }

    private func acknowledge() {
        // Clearing the flag is what dismisses the cover; underneath it `RootView` is already
        // rendering the guest shell. The pop clears whatever the guest had pushed before signing
        // in, the same hygiene `RootView.dropToGuest()` applies on the other terminal path.
        container.session.acknowledgeAgeIneligible()
        Tab.allCases.forEach { router.popToRoot($0) }
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
