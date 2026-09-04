#if DEBUG
import FitrahAPI
import Foundation
import Synchronization

/// The fixture `AuthClient`. It lives in the APP target, not `FitrahTubeTests`, for the same reason
/// `ParkedOfflineEngine`/`FixedStatusTransport` do: `AppContainer.fake()` and Task 13's
/// `-fitrah-fake-auth` screenshot hook both need it, and neither can see the test bundle.
/// `#if DEBUG` is the whole fixture surface — Release has no fake auth path at all.
///
/// Scripted, never timed: `scriptedErrors` is a queue consumed in order and `nextError` fails
/// exactly the next call. No sleeps, no clock.
nonisolated final class FakeAuthClient: AuthClient {
    /// A fresh stream per access, replaying the current state — the ONE `AuthClient.state` contract
    /// (`AuthClient.swift`). Two subscribers both see everything.
    var state: AsyncStream<AuthState> { broadcaster.stream }

    /// Fix round 1 / M5: `signIn(email:)`, `signUp(email:)` and `signIn(with:)` share one body, so
    /// nothing downstream could tell which one ran — `SignInViewModelTests`' mode-toggle test named
    /// a fact it did not pin. Recorded in order.
    nonisolated enum EntryPoint: Sendable, Equatable { case signIn, signUp, credential }

    /// Task 17: the credential-mutating calls, in order. The email sheet's contract is "re-auth,
    /// then `verifyBeforeUpdateEmail`, and NEVER an `updateEmail`-shaped call" — an ORDER and an
    /// absence, neither of which a per-method flag can express. Only these three are recorded:
    /// nothing else in the suite asserts on a call sequence.
    nonisolated enum Operation: Sendable, Equatable { case reauthenticate, updatePassword, verifyBeforeUpdateEmail }

    private struct Storage {
        var user: AuthUser
        var scriptedErrors: [AuthErrorCode]
        var nextError: AuthErrorCode?
        var reloadedUser: AuthUser?
        var entryPoints: [EntryPoint] = []
        var operations: [Operation] = []
    }

    /// `Mutex` rather than `@unchecked Sendable` + bare vars: `AuthClient` is `Sendable` (it refines
    /// `AuthTokenProviding`) and a fixture is driven from whatever isolation a test happens to use.
    private let storage: Mutex<Storage>
    private let broadcaster: AuthStateBroadcaster

    /// The default account when a caller does not care who is signed in.
    static let defaultUser = AuthUser(uid: "fake-uid", email: "student@fitrah.test",
                                      isEmailVerified: true, providerIDs: ["password"])

    init(state: AuthState, user: AuthUser? = nil, scriptedErrors: [AuthErrorCode] = []) {
        broadcaster = AuthStateBroadcaster(current: state)
        storage = Mutex(Storage(user: user ?? Self.defaultUser,
                                scriptedErrors: scriptedErrors, nextError: nil))
    }

    /// The per-call failure leg: set it, and the next operation throws it and clears it.
    var nextError: AuthErrorCode? {
        get { storage.withLock { $0.nextError } }
        set { storage.withLock { $0.nextError = newValue } }
    }

    /// Task 11: what the next `reload()` answers, when that differs from the state the fixture was
    /// constructed with. Firebase's listener does not fire on a reload, so a real client can hold a
    /// stale `isEmailVerified` while `reload()` returns the flipped one — this is that gap, and
    /// `EmailVerificationViewModel` exists to close it. Unset -> `reload()` answers the current user.
    var reloadedUser: AuthUser? {
        get { storage.withLock { $0.reloadedUser } }
        set { storage.withLock { $0.reloadedUser = newValue } }
    }

    func currentUser() async -> AuthUser? { signedInUser() }

    /// The uid rides along so `BearerRetry`'s cross-account guard is exercisable from a fixture:
    /// two `FakeAuthClient`s with different uids are two different signing identities.
    func idToken(forceRefresh: Bool) async -> BearerToken? {
        signedInUser().map { BearerToken(value: "fake-id-token-\($0.uid)", identity: $0.uid) }
    }

    /// Every sign-in entry point that has been called, in order.
    var entryPoints: [EntryPoint] { storage.withLock { $0.entryPoints } }

    func signIn(email: String, password: String) async throws(AuthErrorCode) -> AuthUser { try signInSucceeds(.signIn) }
    func signUp(email: String, password: String) async throws(AuthErrorCode) -> AuthUser { try signInSucceeds(.signUp) }
    func signIn(with credential: OAuthCredential) async throws(AuthErrorCode) -> AuthUser { try signInSucceeds(.credential) }

    /// Every recorded operation, in order.
    var operations: [Operation] { storage.withLock { $0.operations } }

    func sendPasswordReset(email: String) async throws(AuthErrorCode) { try consumeError() }
    func sendVerificationEmail() async throws(AuthErrorCode) { try consumeError() }

    // Recorded BEFORE the scripted error is consumed, for `signInSucceeds`'s reason: a refused
    // attempt still reached the call, which is the fact a sequence assertion needs.
    func reauthenticate(password: String) async throws(AuthErrorCode) { try record(.reauthenticate) }
    func updatePassword(_ new: String) async throws(AuthErrorCode) { try record(.updatePassword) }
    func verifyBeforeUpdateEmail(_ new: String) async throws(AuthErrorCode) { try record(.verifyBeforeUpdateEmail) }

    func reload() async throws(AuthErrorCode) -> AuthUser {
        try consumeError()
        guard let user = signedInUser() else { throw AuthErrorCode.unknown }
        return reloadedUser ?? user
    }

    func deleteUser() async throws(AuthErrorCode) {
        try consumeError()
        transition(to: .signedOut)
    }

    func signOut() { transition(to: .signedOut) }

    // MARK: -

    /// Recorded BEFORE the scripted error is consumed: a refused attempt still reached this entry
    /// point, which is the fact a caller-routing assertion needs.
    private func signInSucceeds(_ entryPoint: EntryPoint) throws(AuthErrorCode) -> AuthUser {
        storage.withLock { $0.entryPoints.append(entryPoint) }
        try consumeError()
        let user = storage.withLock { $0.user }
        transition(to: .signedIn(user))
        return user
    }

    private func record(_ operation: Operation) throws(AuthErrorCode) {
        storage.withLock { $0.operations.append(operation) }
        try consumeError()
    }

    /// `nextError` first, then the scripted queue. Both are consumed, so a scripted failure never
    /// wedges the fixture into failing forever.
    private func consumeError() throws(AuthErrorCode) {
        let error = storage.withLock { storage -> AuthErrorCode? in
            if let next = storage.nextError {
                storage.nextError = nil
                return next
            }
            return storage.scriptedErrors.isEmpty ? nil : storage.scriptedErrors.removeFirst()
        }
        if let error { throw error }
    }

    private func signedInUser() -> AuthUser? {
        if case .signedIn(let user) = broadcaster.current { return user }
        return nil
    }

    /// A no-op transition emits NOTHING — `signOut()` on an already-signed-out fixture must not
    /// produce a duplicate `.signedOut` the Firebase listener would never send. The drop lives in
    /// `AuthStateBroadcaster.send`, so both conformers get it.
    private func transition(to state: AuthState) { broadcaster.send(state) }
}
#endif
