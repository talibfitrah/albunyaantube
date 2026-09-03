import SwiftUI

/// Root of the app. `SplashView` gates entry (its own animation/work timeline, `splash-onboarding.md`
/// §1); once it completes, `SplashRouter.outcome(...)` decides where to go, whether the session must
/// be dropped first, and which terminal alert to surface.
struct RootView: View {
    @Environment(\.container) private var container
    @Environment(\.router) private var router
    @State private var widthClass: WidthClass = .compact
    @State private var showSplash = true
    @State private var alert: AccountStatusAlert?

    var body: some View {
        #if DEBUG
        if LaunchArguments.debug.contains("-fitrah-gallery") {
            return AnyView(ComponentsGallery(section: gallerySection).environment(\.widthClass, widthClass))
        }
        #endif
        return AnyView(mainBody)
    }

    #if DEBUG
    /// `-fitrah-gallery-section <n>` narrows the Debug gallery rig to one third of the components,
    /// so a full-height screenshot on a phone captures every component (fix round 1). Absent or
    /// unparsable -> `nil`, which renders the whole gallery (original, pre-fix-round behaviour).
    private var gallerySection: ComponentsGallery.Section? {
        let args = LaunchArguments.debug
        guard let flagIndex = args.firstIndex(of: "-fitrah-gallery-section"), args.indices.contains(flagIndex + 1),
              let raw = Int(args[flagIndex + 1]) else { return nil }
        return ComponentsGallery.Section(rawValue: raw)
    }
    #endif

    private var mainBody: some View {
        destinationView
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            // Measures the *window*, not the safe-area-inset content (gate A-C2). Measuring the
            // content box made `WidthClass`'s "smallest width, never flips on rotation" invariant
            // false on the largest iPad: 13" is 1032×1376 pt, but landscape insets take 1032 down
            // to 980 — 20 pt under the 1000 pt `.large` threshold — so rotating the device flipped
            // the whole token table (4 columns → 3, Spacing.md 24 → 20, avatars 72 → 64,
            // NavigationRailMetrics.width 96 → 80). Lowering the threshold instead would only move
            // the same bug one device generation away.
            //
            // The insets are added back rather than reclaimed with `.ignoresSafeArea()`: measured
            // on an iPad Pro 13" in landscape, a `Color.clear.ignoresSafeArea()` background still
            // reported `size` 1376×980 with `safeAreaInsets` top 32 / bottom 20 — the ignore does
            // not expand what `onGeometryChange` sees, but the proxy does carry the insets, and
            // 980 + 32 + 20 is exactly the 1032 pt window.
            .onGeometryChange(for: WidthClass.self) { proxy in
                let insets = proxy.safeAreaInsets
                return WidthClass(size: CGSize(width: proxy.size.width + insets.leading + insets.trailing,
                                               height: proxy.size.height + insets.top + insets.bottom))
            } action: { widthClass = $0 }
            .environment(\.widthClass, widthClass)
            // task-13: Theme row (SettingsView) persists "system"/"light"/"dark"; applied here at
            // the root so it covers Onboarding and the main shell alike -- nil for "system" lets
            // the view inherit the environment's scheme, same as Android's FOLLOW_SYSTEM.
            .preferredColorScheme(container.settings.colorScheme)
            // The ONE `AccountSession.start()`: it owns the auth subscription that re-scopes every
            // per-user store, so it must outlive every screen, which is what makes the root the
            // only correct place for it.
            .task { await container.session.start() }
            // `signOut`/`alert` are ADVISORY on the outcome (Task 8) — the caller is what acts on
            // them, and this is the caller. `initial: true` so a launch that already resolves to a
            // blocked account drops the session on the first pass, not on the next change.
            .onChange(of: outcome, initial: true) { _, outcome in
                if outcome.signOut { container.session.signOut() }
                if let event = outcome.alert { alert = AccountStatusAlert(event) }
            }
            // Mid-session terminal events: `AuthorizedTransport` posts the 403 account-lifecycle
            // envelope here from whatever isolation the request ran on. `consume()` clears it, so a
            // re-render cannot route the user twice.
            .onChange(of: container.accountStatus.pending) { _, pending in
                guard pending != nil, let event = container.accountStatus.consume() else { return }
                container.session.handle(event)
                if let terminal = AccountStatusAlert(event) { alert = terminal }
            }
            // Non-dismissible: ONE button, no cancel role, and nothing outside it can close the
            // dialog — a blocked or deleted account cannot tap its way back into the app.
            .alert(alert.map { String(localized: String.LocalizationValue($0.titleKey)) } ?? "",
                   isPresented: Binding(get: { alert != nil }, set: { if !$0 { alert = nil } }),
                   presenting: alert) { _ in
                Button(String(localized: "ok")) { dropToGuest() }
            } message: { terminal in
                Text(String(localized: String.LocalizationValue(terminal.bodyKey)))
            }
    }

    /// The launch decision, recomputed whenever settings or the session change. `user` is the
    /// Firebase identity (spec §13 needs `hasPasswordProvider`/`isEmailVerified`, neither of which
    /// is on the backend's account record); `status` is nil until `/me` answers, which the matrix
    /// reads as "guest for now, the caller retries".
    private var outcome: SplashOutcome {
        let session = container.session
        return SplashRouter.outcome(onboardingCompleted: container.settings.onboardingCompleted,
                                    signedIn: session.user != nil,
                                    hasPasswordProvider: session.user?.hasPasswordProvider ?? false,
                                    isEmailVerified: session.user?.isEmailVerified ?? false,
                                    status: session.state.me?.status)
    }

    private func dropToGuest() {
        alert = nil
        container.session.signOut()
        // Every tab, not just the selected one: a pushed profile/submissions screen on a background
        // tab would still be there the moment the user switched to it.
        Tab.allCases.forEach { router.popToRoot($0) }
    }

    @ViewBuilder
    private var destinationView: some View {
        if showSplash {
            SplashView { showSplash = false }
        } else {
            destination(for: outcome)
        }
    }

    /// Internal, and taking the outcome, so `RootViewDestinationTests` can walk it — `destinationView`
    /// is a `private var` with no argument and no walker can reach it. Mirrors
    /// `MainShellView.destination(for:)`.
    @ViewBuilder
    func destination(for outcome: SplashOutcome) -> some View {
        switch outcome.destination {
        case .onboarding: OnboardingView()
        case .main: MainShellView()
        // Tasks 11/12 replace this arm with the real screen
        case .profileBootstrap: MainShellView()
        // Tasks 11/12 replace this arm with the real screen
        case .emailVerification: MainShellView()
        }
    }
}

#Preview { RootView() }
