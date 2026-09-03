import Foundation
import SwiftUI
import Testing
@testable import FitrahTube

/// `RootView.destination(for:)` is the launch switch, and this walks it the same way
/// `MainShellRoutingTests` walks the shell's: down the `_ConditionalContent` chain a `@ViewBuilder`
/// switch produces, to the leaf that was actually chosen. The 10-line walker is COPIED, not shared —
/// it is a test-local mirror of a switch in a different file, and coupling the two would make one
/// task's arm change the other's failure.
@Suite(.perTest)
struct RootViewDestinationTests {
    private func leafTypeName(for destination: SplashDestination) -> String {
        var mirror = Mirror(reflecting: RootView().destination(for: SplashOutcome(destination: destination)))
        while String(describing: mirror.subjectType).hasPrefix("_ConditionalContent"),
              let storage = mirror.children.first(where: { $0.label == "storage" }) {
            let payload = Mirror(reflecting: storage.value)
            guard let inner = payload.children.first else { break }
            mirror = Mirror(reflecting: inner.value)
        }
        return String(describing: mirror.subjectType)
    }

    @Test func everySplashDestinationRendersItsScreen() {
        #expect(leafTypeName(for: .onboarding) == "OnboardingView")
        #expect(leafTypeName(for: .main) == "MainShellView")
        // Task 11 flips this to "ProfileBootstrapScreen"
        #expect(leafTypeName(for: .profileBootstrap) == "MainShellView")
        // Task 12 flips this to "EmailVerificationScreen"
        #expect(leafTypeName(for: .emailVerification) == "MainShellView")
    }

    /// The terminal alert is blocked/deleted and nothing else: `.signedOut` is the user's own
    /// sign-out, and popping a non-dismissible dialog on every sign-out would trap the guest.
    @Test func theTerminalAlertCoversBlockedAndDeletedOnly() {
        #expect(AccountStatusAlert(.blocked)
                == AccountStatusAlert(titleKey: "account_blocked_title", bodyKey: "account_blocked_body"))
        #expect(AccountStatusAlert(.deleted)
                == AccountStatusAlert(titleKey: "account_deleted_title", bodyKey: "account_deleted_body"))
        #expect(AccountStatusAlert(.signedOut) == nil)
    }

    /// Fix round 1 / I3. `signOut`/`alert` are ADVISORY on the outcome (Task 8) — `RootView` is the
    /// caller that must act on both, and the dispatch said "its tests must pin both". Only the alert
    /// MAPPING was pinned (above, as data); the leg that actually drops a blocked account's session at
    /// launch was untested. `RootView.act` is the seam that makes it reachable: static and taking the
    /// session, because `@Environment` is only populated while a view is being rendered and these
    /// tests construct `RootView()` directly.
    @Test @MainActor func theOutcomeAdvisoriesDropTheSessionAndRaiseTheAlert() async {
        let container = AppContainer.fake(auth: FakeAuthClient(state: .signedIn(FakeAuthClient.defaultUser)))
        let session = container.session
        await session.refresh()
        #expect(session.state.me != nil, "the fixture container answers one canned /me")

        var alert: AccountStatusAlert?
        RootView.act(on: SplashOutcome(destination: .main), session: session, alert: &alert)
        #expect(session.state.me != nil, "a plain .main outcome touches neither leg")
        #expect(alert == nil)

        RootView.act(on: SplashOutcome(destination: .main, signOut: true, alert: .blocked),
                     session: session, alert: &alert)
        #expect(session.state == .signedOut)
        #expect(await container.auth.currentUser() == nil, "the auth client was signed out, not just the state")
        #expect(alert == AccountStatusAlert(.blocked))
    }
}
