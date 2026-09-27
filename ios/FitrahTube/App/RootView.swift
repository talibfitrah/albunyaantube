import AVFoundation
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
            // EVERY arrival on the sign-in wall (owner ruling 2026-09-24) — a sign-out from Settings
            // or the Me kebab, a Firebase force sign-out, a terminal verdict, account deletion, the
            // under-13 teardown, a failed `/me` (which signs out), AND a refused sign-out, which
            // leaves Firebase holding a user — ends what the last session left running. Keyed on
            // the DESTINATION, not `user == nil`, for exactly that last one (patch round 3). View-layer glue, like the
            // foreground and connectivity hooks (CF-A-12); the work is `didRoute(to:…)`.
            .onChange(of: outcome.destination) { _, destination in
                Self.didRoute(to: destination, router: router, container: container)
            }
            // `alert` is ADVISORY on the outcome (Task 8) — the caller is what acts on it, and this
            // is the caller. `initial: true` so a launch that already resolves to a blocked account
            // drops the session on the first pass, not on the next change.
            .onChange(of: outcome, initial: true) { _, outcome in
                Self.act(on: outcome, session: container.session, alert: &alert)
            }
            // Mid-session terminal events: `AuthorizedTransport` posts the 403 account-lifecycle
            // envelope here from whatever isolation the request ran on. `consume()` clears it, so a
            // re-render cannot route the user twice.
            .onChange(of: container.accountStatus.pending) { _, pending in
                guard pending != nil else { return }
                Self.routeAll(from: container.accountStatus, session: container.session, alert: &alert)
            }
            // R7-P1 #3: the terminal under-13 screen, presented OVER whatever the outcome
            // resolves to rather than routed to. The 422 tears the session down as it lands
            // (`AccountSession.terminateAgeIneligible`), because the server disabled the Firebase
            // account before answering and every later request would 401 into "your account has
            // been blocked" — so by the time this is up the destination underneath is already the
            // sign-in root, and a screen rendered BY the destination switch would be torn down by
            // the very drop that makes it correct. A cover has no back gesture and no tab bar,
            // which is what "terminal" means here; the flag is cleared by the screen's own OK.
            .fullScreenCover(isPresented: Binding(get: { container.session.isAgeIneligible },
                                                  set: { if !$0 { container.session.acknowledgeAgeIneligible() } })) {
                AgeIneligibleScreen()
            }
            // Non-dismissible: ONE button, no cancel role, and nothing outside it can close the
            // dialog — a blocked or deleted account cannot tap its way back into the app.
            .alert(alert.map { String(localized: String.LocalizationValue($0.titleKey)) } ?? "",
                   isPresented: Binding(get: { alert != nil }, set: { if !$0 { alert = nil } }),
                   presenting: alert) { _ in
                Button(String(localized: "ok")) { dropToSignIn() }
            } message: { terminal in
                Text(String(localized: String.LocalizationValue(terminal.bodyKey)))
            }
    }

    private var outcome: SplashOutcome {
        Self.outcome(onboardingCompleted: container.settings.onboardingCompleted, session: container.session)
    }

    /// The launch decision, recomputed whenever settings or the session change. `user` is the
    /// Firebase identity (spec §13 needs `hasPasswordProvider`/`isEmailVerified`, neither of which
    /// is on the backend's account record); `status` is nil until `/me` answers. A loaded account
    /// keeps its record through a failed refresh, and an offline launch routes on the persisted
    /// one (`AccountSession.fetch`). With no record, only a round positively in flight
    /// (`.loading`, which `start()` writes in the same turn it sets `user`) holds the spinner;
    /// anything else — a refused sign-out, a cancelled round — is the wall, never a hold with
    /// nothing left to end it (patch round 3).
    static func outcome(onboardingCompleted: Bool, session: AccountSession) -> SplashOutcome {
        let user = session.user
        return SplashRouter.outcome(onboardingCompleted: onboardingCompleted,
                                    signedIn: user != nil,
                                    hasPasswordProvider: user?.hasPasswordProvider ?? false,
                                    isEmailVerified: user?.isEmailVerified ?? false,
                                    status: session.state.me?.status,
                                    awaitingStatus: session.state == .loading)
    }

    /// The sign-out glue, out of the `.onChange` so a test can run it: every tab back to root, every
    /// player and the cast stopped, the import run ended. `players` is a parameter only so a test
    /// can pass its own table instead of stopping the suite's parallel players.
    static func didRoute(to destination: SplashDestination, router: Router, container: AppContainer,
                         players: NSHashTable<AVPlayer> = PlayerHostView.builtPlayers,
                         clearNowPlaying: (() -> Void)? = nil) {
        // Every tab back to root (the next account does not land inside the previous one's player —
        // only a deep link opened on the wall, which `Router.open` pushes after this, survives into
        // the next session), every player stopped (a PiP window defers its own teardown past the
        // shell's unmount, `PiPDismantlePolicy`), the cast on the TV ended, the import run ended.
        guard destination == .signIn else { return }
        Tab.allCases.forEach { router.popToRoot($0) }
        PlayerHostView.stopAllPlayback(players, clearNowPlaying: clearNowPlaying)
        container.castController.endSession()
        container.endImportRun()
    }

    /// The two advisory legs of the outcome, extracted so `RootViewDestinationTests` can pin BOTH —
    /// dropping a blocked account's session at launch is the one path nothing else covers.
    ///
    /// `static`, taking the session, rather than an instance method reading `container`:
    /// `@Environment` is only populated while a view is being rendered, and the tests construct
    /// `RootView()` directly. `inout` rather than a return so an outcome carrying no alert leaves one
    /// already on screen alone.
    static func act(on outcome: SplashOutcome, session: AccountSession, alert: inout AccountStatusAlert?) {
        // Stage 5 / M5: through `handle(_:)`, not `signOut()`. The `.deleted` advisory is the SAME
        // verdict the 403 envelope carries, and that path wipes the device (ruling C13); signing
        // out only would have left every row of a server-deleted account on it — a fourth residue
        // for one server state.
        //
        // `alert` is the ONLY field this reads. Stage 7 fix 2 / M8 deleted the `else if
        // outcome.signOut` arm as unreachable — the only two outcomes that dropped the session
        // (`.blocked`, `.deleted`) both carry an alert, and `handle(_:)` signs out — and Stage 8 /
        // S1 then deleted the `signOut` field itself, so there is no second field left to act on.
        if let event = outcome.alert {
            session.handle(event)
            alert = AccountStatusAlert(event)
        }
    }

    /// The mid-session signal's last link, extracted for `act(on:)`'s reason (CF-A-48): nothing
    /// constructed the `.onChange` above, so dropping `signal.uid` here restored wrong-account
    /// deletion with the whole suite green.
    static func route(_ signal: AccountStatusSignal, session: AccountSession, alert: inout AccountStatusAlert?) {
        // Task 33 / CF-A-44: the ALERT is gated on the same answer as the action. A verdict
        // for an account that is no longer signed in is refused by the session, and showing
        // "your account has been deleted" to whoever IS signed in — while deliberately
        // wiping nothing — would be the worse half of the bug rather than the fix.
        guard session.handle(signal.event, for: signal.uid) else { return }
        if let terminal = AccountStatusAlert(signal.event) { alert = terminal }
    }

    /// CF-A-55 (b): the centre holds one signal PER UID, and each is accepted or refused on its
    /// own — a stale one refused first must not stop the next from routing. Least terminal first,
    /// so the most terminal accepted one acts last and its alert is the one left standing.
    static func routeAll(from center: AccountStatusCenter, session: AccountSession, alert: inout AccountStatusAlert?) {
        while let signal = center.consume() { route(signal, session: session, alert: &alert) }
    }

    private func dropToSignIn() {
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
        case .profileBootstrap: ProfileBootstrapScreen()
        case .emailVerification: EmailVerificationScreen()
        // A stack only for the title bar: nothing is ever pushed, so there is no back button, and
        // `SignInScreen`'s own dismiss-on-land is a no-op at a root — the outcome re-routes instead.
        case .signIn: NavigationStack { SignInScreen() }
        // A plain spinner: the launch animation already played (`showSplash`), and replaying it
        // after every sign-in cost ~2.75 s — and parked on a static logo with a deep link pending.
        case .awaitingAccount:
            ProgressView()
                .accessibilityLabel(String(localized: "loading"))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color.background.ignoresSafeArea())
        }
    }
}

#Preview { RootView() }
