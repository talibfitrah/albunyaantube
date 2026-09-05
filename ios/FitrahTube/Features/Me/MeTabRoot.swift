import SwiftUI

/// Ruling C5's seam: the ONE thing `MainShellView.rootView(for: .me)` renders. Two screens behind
/// one tab root — the guest `MeGuestView` (spec D11: never a forced sign-in) or `MeSignedInView`.
struct MeTabRoot: View {
    @Environment(\.container) private var container

    /// Stage 5 / M4: THREE arms, not two. Routing decides "signed in" off the FIREBASE identity
    /// (`RootView.outcome`'s `signedIn: session.user != nil`) while this tab and Settings' Account
    /// section decided it off the BACKEND record (`state.me != nil`), so in the `.failed` window the
    /// app was signed in and signed out at once: the router could even send a password user to the
    /// verification screen while the Me tab offered them a sign-in card. Telling a signed-in user
    /// they are a guest is worse than telling them their account could not be reached, and the
    /// second is the only one of the two that offers a way out.
    nonisolated enum Arm: Sendable, Equatable { case guest, signedIn, loading, unreachable }

    /// Stage 7 fix 2 / I1(b): `.loading` is its OWN arm. A request in flight is not a failure, and
    /// sending it to `.unreachable` put "Something went wrong" over the Me tab and Settings' Account
    /// section on every cold launch — the shell is on screen from the moment the splash resolves,
    /// while `/me` is still going. `AccountSession.fetch` keeps a loaded account rendered across its
    /// own refresh, so this row is the FIRST load only: a restored Firebase identity, nothing to
    /// show yet.
    ///
    /// Stage 9 round 2 / P2: the FIREBASE identity is asked FIRST. With the backend record tested
    /// ahead of it, a guest holding a stale `.loaded` — the window `AccountSession.fetch`'s late
    /// answer used to open — rendered the signed-in Me screen for somebody who is signed out. The
    /// record is a cache of an identity, never an identity of its own, and `RootView.outcome`
    /// already routes on `session.user != nil`; this is the same question with the same answer.
    nonisolated static func arm(signedIn: Bool, state: AccountState) -> Arm {
        guard signedIn else { return .guest }
        if state.me != nil { return .signedIn }
        return state == .loading ? .loading : .unreachable
    }

    var body: some View {
        switch Self.arm(signedIn: container.session.user != nil, state: container.session.state) {
        case .signedIn: MeSignedInView()
        case .guest: MeGuestView()
        case .loading: loading
        case .unreachable: unreachable
        }
    }

    /// Reused copy, nothing authored: `loading` ("Loading…") is the app's own spinner label, and it
    /// is the LABEL only — a `ProgressView` with visible text would be a second, louder empty state.
    private var loading: some View {
        ProgressView()
            .accessibilityLabel(String(localized: "loading"))
    }

    /// Reused copy, nothing authored: `auth_error_generic` is the message `AccountSession` itself
    /// already puts on `.failed`, and `retry` is the app's one Retry label.
    private var unreachable: some View {
        ContentUnavailableView {
            Text(String(localized: "auth_error_generic"))
        } actions: {
            Button(String(localized: "retry")) {
                Task { await container.session.refresh() }
            }
            .buttonStyle(.borderedProminent)
            .tint(.brand)
            .frame(minWidth: 44, minHeight: 44)
            .accessibilityLabel(String(localized: "retry"))
        }
    }
}

#if DEBUG
#Preview {
    NavigationStack { MeTabRoot() }
        .environment(\.container, .sharedFake)
}
#endif
