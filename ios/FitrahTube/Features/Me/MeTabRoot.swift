import SwiftUI

/// Ruling C5's seam: the ONE thing `MainShellView.rootView(for: .me)` renders. Two screens behind
/// one tab root — the guest `MeGuestView` (spec D11: never a forced sign-in) or `MeSignedInView`.
struct MeTabRoot: View {
    @Environment(\.container) private var container

    /// The decision, pure so it is testable without a render (`@Environment` is only populated
    /// while a view is being rendered). `.loading`/`.failed` render the GUEST screen: both are
    /// states in which the app knows of no account, and the guest screen is the one that is always
    /// correct to show a user who is not (yet) known — it holds the same local favorites either way.
    nonisolated static func showsSignedInScreen(for state: AccountState) -> Bool { state.me != nil }

    var body: some View {
        if Self.showsSignedInScreen(for: container.session.state) {
            MeSignedInView()
        } else {
            MeGuestView()
        }
    }
}

#if DEBUG
#Preview {
    NavigationStack { MeTabRoot() }
        .environment(\.container, .sharedFake)
}
#endif
