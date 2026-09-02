import FirebaseAuth
import Foundation

/// The ONE `AuthClient` conformer that imports FirebaseAuth (plan Global Constraints: Firebase
/// names live in five app files and nowhere else — never in a ViewModel, never in a test-visible
/// interface). Everything above this file sees `AuthUser`/`AuthErrorCode` and nothing else.
///
/// **Configure-first invariant.** `Auth.auth()` traps on an unconfigured `FirebaseApp`, so the
/// `init?` below is the single gate: it returns nil unless `FirebaseBootstrap.configureIfPossible()`
/// returned true, and the container falls back to `UnavailableAuthClient`. Every member is therefore
/// behind ONE guard rather than thirteen — an instance cannot exist on an unconfigured app.
///
/// **Build it from the main actor only** (`AppContainer.auth`, a `@MainActor` lazy var — hence the
/// `@MainActor init?`): `FirebaseBootstrap`'s `nonisolated` members touch
/// `GIDSignIn.sharedInstance`, which is main-thread-affine. The async operations below are
/// `nonisolated` and go straight to Firebase's own thread-safe `Auth`/`User` async API.
nonisolated final class FirebaseAuthClient: AuthClient {

    /// One element per auth-state transition, current state first — Firebase's listener, verbatim.
    /// The listener is never removed: this object is `AppContainer.auth` and lives for the process.
    let state: AsyncStream<AuthState>

    @MainActor init?() {
        guard FirebaseBootstrap.configureIfPossible() else { return nil }
        let (stream, continuation) = AsyncStream<AuthState>.makeStream()
        state = stream
        _ = Auth.auth().addStateDidChangeListener { _, user in
            continuation.yield(user.map { .signedIn(AuthUser($0)) } ?? .signedOut)
        }
    }

    func currentUser() async -> AuthUser? { Auth.auth().currentUser.map(AuthUser.init) }

    func idToken(forceRefresh: Bool) async -> String? {
        guard let user = Auth.auth().currentUser else { return nil }
        return try? await user.getIDToken(forcingRefresh: forceRefresh)
    }

    func signIn(email: String, password: String) async throws(AuthErrorCode) -> AuthUser {
        try await mapped { AuthUser(try await Auth.auth().signIn(withEmail: email, password: password).user) }
    }

    func signUp(email: String, password: String) async throws(AuthErrorCode) -> AuthUser {
        try await mapped { AuthUser(try await Auth.auth().createUser(withEmail: email, password: password).user) }
    }

    func signIn(with credential: FitrahTube.OAuthCredential) async throws(AuthErrorCode) -> AuthUser {
        let firebase = Self.firebaseCredential(credential)
        return try await mapped { AuthUser(try await Auth.auth().signIn(with: firebase).user) }
    }

    func sendPasswordReset(email: String) async throws(AuthErrorCode) {
        try await mapped { try await Auth.auth().sendPasswordReset(withEmail: email) }
    }

    func sendVerificationEmail() async throws(AuthErrorCode) {
        try await mapped { try await Self.requireUser().sendEmailVerification() }
    }

    func reload() async throws(AuthErrorCode) -> AuthUser {
        try await mapped {
            let user = try Self.requireUser()
            try await user.reload()
            return AuthUser(user)
        }
    }

    func reauthenticate(password: String) async throws(AuthErrorCode) {
        try await mapped {
            let user = try Self.requireUser()
            // An account with no email cannot be re-authenticated by password at all — that is the
            // Google/Apple-only case, which reauthenticates through its provider (Task 5), not here.
            guard let email = user.email else { throw AuthErrorCode.unknown }
            _ = try await user.reauthenticate(with: EmailAuthProvider.credential(withEmail: email, password: password))
        }
    }

    func updatePassword(_ new: String) async throws(AuthErrorCode) {
        try await mapped { try await Self.requireUser().updatePassword(to: new) }
    }

    func verifyBeforeUpdateEmail(_ new: String) async throws(AuthErrorCode) {
        try await mapped { try await Self.requireUser().sendEmailVerification(beforeUpdatingEmail: new) }
    }

    func deleteUser() async throws(AuthErrorCode) {
        try await mapped { try await Self.requireUser().delete() }
    }

    /// Local only — the listener above turns it into `.signedOut`. Firebase's `signOut()` throws
    /// solely on a keychain failure, and there is no useful recovery: the user asked to leave.
    func signOut() { try? Auth.auth().signOut() }

    // MARK: - Firebase → app

    /// The one place an SDK error becomes an app code. The DOMAIN check is what keeps the Int
    /// table honest: a URL-loading or keychain `NSError` carrying, say, code 17008 is `.unknown`,
    /// not "invalid email". `requireUser()`'s own `.unknown` lands in the same arm (its bridged
    /// domain is not Firebase's), which is the answer it wants anyway.
    ///
    /// The SDK's non-`Sendable` `User` is fetched INSIDE `body` by every caller: a `User` hoisted
    /// out and captured is a region-isolation error under `SWIFT_STRICT_CONCURRENCY: complete`.
    private func mapped<T>(_ body: () async throws -> T) async throws(AuthErrorCode) -> T {
        do {
            return try await body()
        } catch {
            let error = error as NSError
            guard error.domain == AuthErrors.domain else { throw AuthErrorCode.unknown }
            throw AuthErrorCode(firebaseCode: error.code)
        }
    }

    private static func requireUser() throws(AuthErrorCode) -> User {
        guard let user = Auth.auth().currentUser else { throw AuthErrorCode.unknown }
        return user
    }

    /// Spec §3: exactly two federated providers reach this app. Google hands back an access token,
    /// Apple a raw nonce — which is why `OAuthCredential` carries one field for both.
    private static func firebaseCredential(_ credential: FitrahTube.OAuthCredential) -> AuthCredential {
        if credential.providerID == GoogleAuthProvider.id {
            return GoogleAuthProvider.credential(withIDToken: credential.idToken,
                                                 accessToken: credential.accessTokenOrNonce ?? "")
        }
        return OAuthProvider.credential(providerID: .apple, idToken: credential.idToken,
                                        rawNonce: credential.accessTokenOrNonce ?? "")
    }
}

/// `nonisolated` explicitly: the app target defaults new declarations to `@MainActor`
/// (`project.yml:87`), and every caller above is a `nonisolated` async operation.
private nonisolated extension AuthUser {
    init(_ user: User) {
        self.init(uid: user.uid, email: user.email, isEmailVerified: user.isEmailVerified,
                  providerIDs: user.providerData.map(\.providerID))
    }
}
