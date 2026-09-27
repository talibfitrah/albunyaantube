import Foundation
import Observation

/// No success state — the terminal alert owns the screen from there (`DeleteAccountViewModel.kt:29-31`).
nonisolated enum DeleteAccountState: Equatable {
    case idle, reauthenticating, deleting
    /// Stage 4 / I3: the re-authentication was refused — a wrong current password, a dismissed
    /// provider sheet, or a provider credential Firebase would not accept.
    ///
    /// Stage 7 fix 2 / I2: WHICH leg was refused rides along, because the two refusals cannot share
    /// one message. "Incorrect current password" over a Google or Apple account — which has no
    /// password at all — is a refusal that states a WHY, and a false one.
    case failedReauth(password: Bool)
    case failedLastAdmin, failedNetwork, failedUnknown
}

/// `DELETE /api/account/me` and nothing else. Everything that happens after the 204 — the device
/// wipe, the Firebase delete, the sign-out and the terminal `.deleted` event — belongs to
/// `AccountSession.handleDeletion()`, which runs it DETACHED (CF-G-5) and exactly once however many
/// paths reach it.
@MainActor @Observable final class DeleteAccountViewModel {
    private let account: AccountClient
    private let session: AccountSession
    private let auth: any AuthClient
    private let google: any OAuthSignInProvider
    private let apple: any OAuthSignInProvider

    private(set) var state: DeleteAccountState = .idle
    /// The current-password field for a password account. Never persisted, never logged, and
    /// cleared the moment the re-authentication is over.
    var password = ""

    init(account: AccountClient, session: AccountSession, auth: any AuthClient,
         google: any OAuthSignInProvider, apple: any OAuthSignInProvider) {
        self.account = account
        self.session = session
        self.auth = auth
        self.google = google
        self.apple = apple
    }

    private enum ReauthMethod { case apple, password, google }

    /// Set when Apple's re-authentication was refused (e.g. another Apple ID on the device).
    private var appleRefused = false

    /// How this attempt proves the account, from the methods it actually has. Apple first when
    /// usable: its sheet is the only proof that yields the code the grant is revoked with (guideline
    /// 5.1.1(v)). Then the password, then Google — the order they had before Apple went first. A
    /// refused Apple sheet steps aside for the next method, and stays the retry when it is the only
    /// one; either way the deletion only skips the revocation.
    private var reauthMethod: ReauthMethod? {
        guard let user = session.user else { return nil }
        var methods: [ReauthMethod] = []
        if user.hasAppleProvider, apple.isAvailable { methods.append(.apple) }
        if user.hasPasswordProvider { methods.append(.password) }
        if user.providerIDs.contains("google.com") { methods.append(.google) }
        if appleRefused, methods.count > 1 { methods.removeAll { $0 == .apple } }
        return methods.first
    }

    /// Whether the confirmation must collect a password, or run a provider's own sheet instead.
    var requiresPassword: Bool { reauthMethod == .password }

    nonisolated static func messageKey(for state: DeleteAccountState) -> String? {
        switch state {
        case .idle, .reauthenticating, .deleting: nil
        // Reused, not authored: the same "That password is incorrect" the password sheet renders
        // for exactly the same refused re-authentication.
        //
        // Stage 7 fix 2 / I2: the PASSWORD leg only. A Google or Apple account that dismisses its
        // provider sheet has no password to have got wrong, so that copy is a WHY, and a false one;
        // the generic refusal says WHAT happened and nothing it cannot know. Also reused.
        case .failedReauth(let password): password ? "edit_password_wrong_current" : "auth_error_generic"
        case .failedLastAdmin: "profile_delete_account_error_last_admin"
        case .failedNetwork: "profile_delete_account_error_network"
        case .failedUnknown: "profile_delete_account_error_unknown"
        }
    }

    /// A refusal leaves the device COMPLETELY untouched — nothing local is cleaned up on a
    /// `DELETE` the server did not honour. There is no success arm: the terminal alert owns the
    /// screen from the 204 on, so the state stays `.deleting` and the row keeps saying so.
    /// Stage 4 / I3: the re-authentication comes FIRST, and no `DELETE` is sent without it.
    ///
    /// Every *reversible* credential operation in Part A already re-authenticates
    /// (`EditEmailSheet`, `EditPasswordSheet`); the one IRREVERSIBLE operation did not, so an
    /// `.alert` confirm button was the entire barrier between a briefly unlocked device and a
    /// permanently tombstoned account. Firebase's own `requiresRecentLogin` cannot stand in: the
    /// Firebase delete happens after the 204 and is deliberately `try?`-swallowed, by which point
    /// there is nowhere left to route a re-auth prompt. Doing it here also means that delete never
    /// meets `requiresRecentLogin` at all.
    func delete() async {
        guard state != .deleting, state != .reauthenticating else { return }
        state = .reauthenticating
        // Read BEFORE the await: which leg ran is what the refusal message depends on (I2), and
        // `session.user` is not this call's to assume unchanged across a provider sheet. Stage 7
        // re-review 2 / m2: the SAME read drives the leg — one `reauthMethod` per attempt, so
        // the provider sheet's own suspension cannot run one leg and render the other's copy.
        let method = reauthMethod
        let passwordLeg = method == .password
        let reauthenticated = await reauthenticate(method)
        password = ""
        guard reauthenticated else {
            state = .failedReauth(password: passwordLeg)
            return
        }
        state = .deleting
        // CF-A-53 / C1: WHOSE deletion this is, read BEFORE the await. The DELETE runs in an
        // unstructured task that outlives this screen, so by the time the 204 lands the user may
        // have backed out and signed out and somebody else may be signed in — and a cleanup that
        // names nobody takes its latch for whoever is current. `handleDeletion` refuses a name
        // that is not this session's and pays that account's debt by uid instead.
        //
        // CF-A-55 (d): and with no name at all there is nothing to delete. This screen requires an
        // account, so nil means the session went during the re-authentication's own await. Belt
        // and braces, not a named path: a real force-sign-out usually fails `reauthenticate` first
        // and returns above with `.failedReauth`. Passed through, `handleDeletion(for: nil)` is
        // UNATTRIBUTED: it takes a fresh latch (which then swallows the real verdict for the
        // account that did hold this device), device-wipes for nobody and posts a `.deleted` no
        // account can be matched to. The generic refusal already says WHAT happened, and nothing
        // it cannot know. `.failedUnknown` is the honest end state. CF-A-60: the same sign-out
        // dismisses this screen, so its banner is also handed to the sign-in wall's, same words.
        guard let deleting = session.currentUid else {
            state = .failedUnknown
            if let key = Self.messageKey(for: state) {
                session.reportFailure(String(localized: String.LocalizationValue(key)))
            }
            return
        }
        do {
            try await account.deleteAccount()
        } catch {
            state = Self.state(for: error)
            return
        }
        // Not awaited: the cleanup is deliberately detached from this call's task (CF-G-5).
        // `deletingFirebaseUser: true` unconditionally now: the guard above is what stands between
        // this call and a nameless one, so the credential deleted here is always the credential of
        // the account named in `deleting` — `handleDeletion` refuses the name outright once it is
        // somebody else's, and `performDeletion` re-asks Firebase who it holds before deleting.
        session.handleDeletion(deletingFirebaseUser: true, for: deleting)
    }

    /// A password account re-types its password; a federated one runs its provider's own sheet and
    /// redeems the credential, which is that provider's equivalent of the same proof.
    ///
    /// Stage 9 / P1: the federated leg RE-AUTHENTICATES, it does not sign in. `signIn(with:)`
    /// replaces the Firebase session with whoever the sheet returned, so on a device with a second
    /// Google account the DELETE that follows tombstoned the account the user did not pick — and
    /// `BearerRetry`'s cross-account guard cannot see a swap that happened before the request
    /// started. A refused credential (Firebase's `userMismatch`) is a refusal like a dismissed
    /// sheet: nothing is deleted.
    private func reauthenticate(_ method: ReauthMethod?) async -> Bool {
        if method != .apple, session.user?.hasAppleProvider == true {
            print("DeleteAccountViewModel: Apple re-authentication unavailable; deleting without revoking the Apple grant")
        }
        if method == .password {
            do {
                try await auth.reauthenticate(password: password)
                return true
            } catch {
                return false
            }
        }
        // Stage 9 round 5 / NB-D: UNAVAILABLE is a refusal, checked before the sheet. `isAvailable`
        // is `SignInCapabilities.current()`, which since R5-P1 also demands a callback scheme
        // matching the plist's client id — without that `GIDSignIn` raises an uncatchable
        // `NSInvalidArgumentException` and the app terminates on this tap. The sign-in screen has
        // refused that call since Task 10 (`SignInViewModel:125`); this leg had not.
        let provider: any OAuthSignInProvider
        switch method {
        case .apple: provider = apple
        case .google: provider = google
        case .password, nil: return false
        }
        guard provider.isAvailable else { return false }
        do {
            let credential = try await provider.presentSignIn()
            try await auth.reauthenticate(with: credential)
            // Guideline 5.1.1(v): an Apple account's grant is revoked with the code THIS sheet just
            // issued, and before the DELETE — the server destroys the Firebase user the revocation
            // needs. It cannot fail outward, so it never blocks the deletion. Trade-off: a DELETE the
            // server then refuses leaves the grant revoked on a live account; the next Apple
            // sign-in simply asks for consent again and lands on the same account.
            if let code = credential.authorizationCode { await auth.revokeAppleToken(authorizationCode: code) }
            return true
        } catch {
            // A cancel is a refusal like any other: nothing is deleted, and the row goes back to
            // saying what it is.
            if method == .apple { appleRefused = true }
            return false
        }
    }

    private nonisolated static func state(for error: AccountError) -> DeleteAccountState {
        switch error {
        case .lastAdmin: .failedLastAdmin
        case .network: .failedNetwork
        default: .failedUnknown
        }
    }
}
