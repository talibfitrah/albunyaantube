import Foundation
import Observation

/// No success state — the terminal alert owns the screen from there (`DeleteAccountViewModel.kt:29-31`).
nonisolated enum DeleteAccountState: Equatable { case idle, deleting, failedLastAdmin, failedNetwork, failedUnknown }

/// `DELETE /api/account/me` and nothing else. Everything that happens after the 204 — the device
/// wipe, the Firebase delete, the sign-out and the terminal `.deleted` event — belongs to
/// `AccountSession.handleDeletion()`, which runs it DETACHED (CF-G-5) and exactly once however many
/// paths reach it.
@MainActor @Observable final class DeleteAccountViewModel {
    private let account: AccountClient
    private let session: AccountSession

    private(set) var state: DeleteAccountState = .idle

    init(account: AccountClient, session: AccountSession) {
        self.account = account
        self.session = session
    }

    nonisolated static func messageKey(for state: DeleteAccountState) -> String? {
        switch state {
        case .idle, .deleting: nil
        case .failedLastAdmin: "profile_delete_account_error_last_admin"
        case .failedNetwork: "profile_delete_account_error_network"
        case .failedUnknown: "profile_delete_account_error_unknown"
        }
    }

    /// A refusal leaves the device COMPLETELY untouched — nothing local is cleaned up on a
    /// `DELETE` the server did not honour. There is no success arm: the terminal alert owns the
    /// screen from the 204 on, so the state stays `.deleting` and the row keeps saying so.
    func delete() async {
        guard state != .deleting else { return }
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

    private nonisolated static func state(for error: AccountError) -> DeleteAccountState {
        switch error {
        case .lastAdmin: .failedLastAdmin
        case .network: .failedNetwork
        default: .failedUnknown
        }
    }
}
