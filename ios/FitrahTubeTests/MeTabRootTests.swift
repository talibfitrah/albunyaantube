import Testing
@testable import FitrahTube

/// Stage 5 / M4: there were TWO definitions of "signed in" in one build. `RootView.outcome` routes
/// on the Firebase identity (`session.user != nil`); this tab and Settings' Account section routed
/// on the backend record (`state.me != nil`). In the `.failed` window the app was therefore signed
/// in and signed out at once — the router could send a password user to the verification screen
/// while the Me tab offered them a sign-in card — and a bare 401 from a terminated account made
/// that window the permanent end state.
@Suite struct MeTabRootTests {

    @Test func aSignedOutUserGetsTheGuestScreen() {
        #expect(MeTabRoot.arm(signedIn: false, state: .signedOut) == .guest)
        #expect(MeTabRoot.arm(signedIn: false, state: .loading) == .guest)
        #expect(MeTabRoot.arm(signedIn: false, state: .failed(code: 401, message: "x")) == .guest)
    }

    @Test func aLoadedAccountGetsTheSignedInScreen() {
        let me = AccountMe(uid: "u", email: nil, displayName: nil, dateOfBirth: nil,
                           phoneNumber: nil, status: .active, role: "user")
        #expect(MeTabRoot.arm(signedIn: true, state: .loaded(me)) == .signedIn)
        // Even with no Firebase identity yet: the backend record is what the screen renders.
        #expect(MeTabRoot.arm(signedIn: false, state: .loaded(me)) == .signedIn)
    }

    /// The arm that did not exist. Telling a signed-in user they are a guest is worse than telling
    /// them their account could not be reached, and the second is the only one that offers a way out.
    @Test func aSignedInUserWhoseMeHasNotLandedGetsTheUnreachableArm() {
        #expect(MeTabRoot.arm(signedIn: true, state: .loading) == .unreachable)
        #expect(MeTabRoot.arm(signedIn: true, state: .failed(code: 401, message: "x")) == .unreachable)
        #expect(MeTabRoot.arm(signedIn: true, state: .signedOut) == .unreachable)
    }
}
