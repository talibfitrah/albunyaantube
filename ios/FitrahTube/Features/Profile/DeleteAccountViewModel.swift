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

    /// Whether the confirmation must collect a password, or run the provider's own sheet instead.
    var requiresPassword: Bool { session.user?.hasPasswordProvider == true }

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
        // re-review 2 / m2: the SAME read drives the leg — one `requiresPassword` per attempt, so
        // the provider sheet's own suspension cannot run one leg and render the other's copy.
        let passwordLeg = requiresPassword
        let reauthenticated = await reauthenticate(password: passwordLeg)
        password = ""
        guard reauthenticated else {
            state = .failedReauth(password: passwordLeg)
            return
        }
        state = .deleting
        do {
            try await account.deleteAccount()
        } catch {
            state = Self.state(for: error)
            return
        }
        // Not awaited: the cleanup is deliberately detached from this call's task (CF-G-5).
        session.handleDeletion(deletingFirebaseUser: true)
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
    private func reauthenticate(password passwordLeg: Bool) async -> Bool {
        if passwordLeg {
            do {
                try await auth.reauthenticate(password: password)
                return true
            } catch {
                return false
            }
        }
        guard let provider = federatedProvider else { return false }
        do {
            let credential = try await provider.presentSignIn()
            try await auth.reauthenticate(with: credential)
            return true
        } catch {
            return false
        }
    }

    /// The provider this account actually signed in with. A cancel is a refusal like any other —
    /// the account is not deleted, and the row goes back to saying what it is.
    private var federatedProvider: (any OAuthSignInProvider)? {
        let ids = session.user?.providerIDs ?? []
        if ids.contains("google.com") { return google }
        if ids.contains("apple.com") { return apple }
        return nil
    }

    private nonisolated static func state(for error: AccountError) -> DeleteAccountState {
        switch error {
        case .lastAdmin: .failedLastAdmin
        case .network: .failedNetwork
        default: .failedUnknown
        }
    }
}
