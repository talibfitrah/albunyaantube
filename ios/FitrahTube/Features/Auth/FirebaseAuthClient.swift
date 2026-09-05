import FirebaseAuth
import FitrahAPI
import Foundation
import Synchronization

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
    ///
    /// ONE Firebase listener, N subscribers: each `state` access is a fresh stream replaying the
    /// current state (`AuthClient.swift`'s contract). Registering a listener per subscriber instead
    /// would either leak one per access or need the non-`Sendable` handle carried into an
    /// `onTermination` closure; the broadcaster costs neither.
    var state: AsyncStream<AuthState> { broadcaster.stream }

    private let broadcaster: AuthStateBroadcaster

    /// Stage 9 round 3 / R3-P1: why the LAST mint was refused, recorded where the refusal actually
    /// happened. Read (and cleared) by `refreshRefusal()`.
    ///
    /// Stage 9 round 4 / R4-P2 + NB-A: its life is bounded by "until the next mint". Only a FORCED
    /// mint writes it, and every successful mint — plus the no-user guard — clears it, so a code
    /// nobody consumed cannot outlive the session it belongs to. It used to be written by unforced
    /// mints and cleared by nothing but a read: an unconsumed terminal code (a discarded
    /// `EmailVerificationViewModel.checkNow()` mint, or a `token(false)` refusal whose `token(true)`
    /// succeeded) then survived a sign-out, and the next 401 taken while `currentUser` is nil
    /// returned at the guard WITHOUT recording, so `refreshRefusal()` handed the dead account's
    /// code to a session that had none — `.deleted`, `handleDeletion()`, and the ruling-C13 wipe
    /// running against the GUEST library.
    private let lastRefusal = Mutex<AuthErrorCode?>(nil)

    @MainActor init?() {
        guard FirebaseBootstrap.configureIfPossible() else { return nil }
        let broadcaster = AuthStateBroadcaster(
            current: Auth.auth().currentUser.map { .signedIn(AuthUser($0)) } ?? .signedOut)
        self.broadcaster = broadcaster
        _ = Auth.auth().addStateDidChangeListener { _, user in
            broadcaster.send(user.map { .signedIn(AuthUser($0)) } ?? .signedOut)
        }
    }

    func currentUser() async -> AuthUser? { Auth.auth().currentUser.map(AuthUser.init) }

    /// The uid is read from the SAME `User` the token came from, so `BearerRetry`'s cross-account
    /// guard compares two identities that were each atomic with their bearer.
    ///
    /// Stage 9 round 3 / R3-P1: the refusal is RECORDED here rather than discarded by a `try?`.
    /// Firebase signs the user out before it rethrows — `User.internalGetTokenAsync`'s catch
    /// (`User.swift:1621-1626`) calls `signOutIfTokenIsInvalid`, which for `userNotFound`,
    /// `userDisabled`, `invalidUserToken` and `userTokenExpired` (`:1577-1587`) runs
    /// `auth?.signOutByForce(withUserID:)` → `updateCurrentUser(nil, byForce: true, …)`
    /// (`Auth.swift:1872-1877`), i.e. `currentUser` is already nil by the time anything can ask a
    /// second time. Both codes `terminalEvent(for:)` maps are in that set, so re-deriving the
    /// verdict after the fact answered nil for exactly the two cases that matter and the ruling-C13
    /// device wipe had no working trigger on the bare-401 path.
    func idToken(forceRefresh: Bool) async -> BearerToken? {
        guard let user = Auth.auth().currentUser else {
            lastRefusal.withLock { $0 = nil }
            return nil
        }
        do {
            let token = try await user.getIDToken(forcingRefresh: forceRefresh)
            lastRefusal.withLock { $0 = nil }
            return BearerToken(value: token, identity: user.uid)
        } catch {
            guard forceRefresh else { return nil }
            let error = error as NSError
            lastRefusal.withLock {
                $0 = error.domain == AuthErrors.domain ? AuthErrorCode(firebaseCode: error.code) : .unknown
            }
            return nil
        }
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

    /// Stage 9 / P1: `User.reauthenticate(with:)`, NOT `Auth.signIn(with:)` — the same primitive
    /// the password leg above uses, on the same `currentUser`. Firebase raises `userMismatch`
    /// (17024) when the sheet returned a credential for a DIFFERENT account, which is not in
    /// `AuthErrorCode(firebaseCode:)`'s table and therefore lands on `.unknown`; `.unknown`'s
    /// `messageKey` IS `auth_error_generic`, the provider-refusal copy the delete confirmation
    /// renders, so a new case would be a second code carrying the identical string and no reader.
    /// With no current user this throws `.unknown` too, through `requireUser()` — the same answer
    /// `reauthenticate(password:)` gives for it.
    func reauthenticate(with credential: FitrahTube.OAuthCredential) async throws(AuthErrorCode) {
        let firebase = Self.firebaseCredential(credential)
        try await mapped { _ = try await Self.requireUser().reauthenticate(with: firebase) }
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

    /// Local only — the listener above turns it into `.signedOut`. Stage 5 / C1.3: the keychain
    /// failure it throws on is NOT ignorable. `Auth.signOut()` calls `updateCurrentUser(nil,
    /// byForce: false, savingToDisk: true)`, which assigns `_currentUser = nil` only when that write
    /// succeeded — so a swallowed throw leaves a live session minting bearers under a UI that says
    /// signed out. `AccountSession.dropSession()` is what refuses to publish `.signedOut` over it.
    func signOut() throws(AuthErrorCode) {
        do {
            try Auth.auth().signOut()
        } catch {
            let error = error as NSError
            throw error.domain == AuthErrors.domain ? AuthErrorCode(firebaseCode: error.code) : .unknown
        }
    }

    /// Reports what the mint above recorded, and clears it. Nil for BOTH "no session" and "it
    /// worked", because neither is a terminal verdict.
    ///
    /// Stage 9 round 3 / R3-P1: no second forced mint. This used to re-ask
    /// `Auth.auth().currentUser` for another forced token, which (a) was dead code for the only two
    /// codes that decide anything, because Firebase had already nilled `currentUser`, and (b) cost
    /// a second network round trip for one 401 — round 1's accepted P3, retired here.
    func refreshRefusal() async -> AuthErrorCode? {
        lastRefusal.withLock { refusal in
            let recorded = refusal
            refusal = nil
            return recorded
        }
    }

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
        // MODULE-QUALIFIED (Task 5): the app now has its own `GoogleAuthProvider` — the
        // `OAuthSignInProvider` conformer — which shadows the SDK's inside this module. Same
        // defence this file already applies to `FitrahTube.OAuthCredential`, in the other direction.
        if credential.providerID == FirebaseAuth.GoogleAuthProvider.id {
            return FirebaseAuth.GoogleAuthProvider.credential(withIDToken: credential.idToken,
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
