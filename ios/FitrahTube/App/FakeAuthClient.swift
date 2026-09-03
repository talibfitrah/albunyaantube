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

    private struct Storage {
        var user: AuthUser
        var scriptedErrors: [AuthErrorCode]
        var nextError: AuthErrorCode?
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

    func currentUser() async -> AuthUser? { signedInUser() }

    /// The uid rides along so `BearerRetry`'s cross-account guard is exercisable from a fixture:
    /// two `FakeAuthClient`s with different uids are two different signing identities.
    func idToken(forceRefresh: Bool) async -> BearerToken? {
        signedInUser().map { BearerToken(value: "fake-id-token-\($0.uid)", identity: $0.uid) }
    }

    func signIn(email: String, password: String) async throws(AuthErrorCode) -> AuthUser { try signInSucceeds() }
    func signUp(email: String, password: String) async throws(AuthErrorCode) -> AuthUser { try signInSucceeds() }
    func signIn(with credential: OAuthCredential) async throws(AuthErrorCode) -> AuthUser { try signInSucceeds() }

    func sendPasswordReset(email: String) async throws(AuthErrorCode) { try consumeError() }
    func sendVerificationEmail() async throws(AuthErrorCode) { try consumeError() }
    func reauthenticate(password: String) async throws(AuthErrorCode) { try consumeError() }
    func updatePassword(_ new: String) async throws(AuthErrorCode) { try consumeError() }
    func verifyBeforeUpdateEmail(_ new: String) async throws(AuthErrorCode) { try consumeError() }

    func reload() async throws(AuthErrorCode) -> AuthUser {
        try consumeError()
        guard let user = signedInUser() else { throw AuthErrorCode.unknown }
        return user
    }

    func deleteUser() async throws(AuthErrorCode) {
        try consumeError()
        transition(to: .signedOut)
    }

    func signOut() { transition(to: .signedOut) }

    // MARK: -

    private func signInSucceeds() throws(AuthErrorCode) -> AuthUser {
        try consumeError()
        let user = storage.withLock { $0.user }
        transition(to: .signedIn(user))
        return user
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
