import Foundation
import Observation
import SwiftUI

/// `EditPasswordError.kt`.
nonisolated enum EditPasswordError: Sendable, Equatable {
    case weakPassword, passwordMismatch, wrongCurrentPassword, network, unknown

    var messageKey: String {
        switch self {
        case .weakPassword: "edit_password_weak"
        case .passwordMismatch: "edit_password_mismatch"
        case .wrongCurrentPassword: "edit_password_wrong_current"
        case .network: "auth_error_network"
        case .unknown: "auth_error_generic"
        }
    }
}

/// `EditPasswordViewModel.kt`. Local checks FIRST — length, then equality — so a too-short or
/// mistyped password never spends a re-authentication attempt against Firebase's throttle.
@MainActor @Observable final class EditPasswordViewModel {

    nonisolated struct UiState: Equatable {
        var current = ""
        var newPassword = ""
        var confirm = ""
        var saving = false
        var error: EditPasswordError?
    }

    private let auth: any AuthClient
    private(set) var state = UiState()
    private(set) var didUpdate = false

    init(auth: any AuthClient) { self.auth = auth }

    var current: String {
        get { state.current }
        set { state.current = newValue; state.error = nil }
    }

    var newPassword: String {
        get { state.newPassword }
        set { state.newPassword = newValue; state.error = nil }
    }

    var confirm: String {
        get { state.confirm }
        set { state.confirm = newValue; state.error = nil }
    }

    /// `BootstrapValidator.minPasswordLength`, not a second 8 — the bootstrap form and this sheet
    /// set the same credential and must refuse the same inputs.
    nonisolated static func localError(newPassword: String, confirm: String) -> EditPasswordError? {
        if newPassword.count < BootstrapValidator.minPasswordLength { return .weakPassword }
        if newPassword != confirm { return .passwordMismatch }
        return nil
    }

    func submit() async {
        guard !state.saving else { return }
        if let local = Self.localError(newPassword: state.newPassword, confirm: state.confirm) {
            state.error = local
            return
        }
        // R9-P3 #2: the latch closes BEFORE the first suspension, for `EditEmailSheet`'s reason —
        // two taps inside the `await auth.currentUser()` hop both passed `guard !state.saving`.
        state.saving = true
        state.error = nil
        guard let user = await auth.currentUser(), let email = user.email, !email.isEmpty else {
            state.saving = false
            state.error = .unknown
            return
        }
        do {
            try await auth.reauthenticate(password: state.current)
        } catch {
            state.saving = false
            state.error = Self.reauthFailure(error)
            return
        }
        do {
            try await auth.updatePassword(state.newPassword)
            state.saving = false
            didUpdate = true
        } catch {
            state.saving = false
            state.error = error == .weakPassword ? .weakPassword : .network
        }
    }

    /// An invalid credential from the RE-AUTH is the current password being wrong, not an unknown
    /// failure (`EditPasswordViewModel.kt:68-70`) — that is the one field the user can act on, so
    /// it is the one the message has to name.
    nonisolated static func reauthFailure(_ error: AuthErrorCode) -> EditPasswordError {
        switch error {
        case .wrongPassword, .invalidCredential: .wrongCurrentPassword
        default: .network
        }
    }
}

struct EditPasswordSheet: View {
    let onUpdated: (String) -> Void

    @Environment(\.container) private var container
    @Environment(\.widthClass) private var widthClass
    @State private var model: EditPasswordViewModel?

    var body: some View {
        EditSheetScaffold(title: String(localized: "edit_password_title")) {
            if let model {
                @Bindable var bindable = model
                LabelledField(key: "edit_password_current") {
                    secureField("edit_password_current", text: $bindable.current, isNew: false)
                }
                LabelledField(key: "edit_password_new") {
                    secureField("edit_password_new", text: $bindable.newPassword, isNew: true)
                }
                LabelledField(key: "edit_password_confirm") {
                    secureField("edit_password_confirm", text: $bindable.confirm, isNew: true)
                }
                if let error = model.state.error {
                    InlineError(key: error.messageKey)
                }
                EditSheetAction(title: String(localized: "edit_password_update"),
                                isLoading: model.state.saving) {
                    await model.submit()
                    if model.didUpdate { onUpdated(String(localized: "edit_password_updated")) }
                }
            }
        }
        .task { if model == nil { model = EditPasswordViewModel(auth: container.auth) } }
    }

    /// `isNew` picks `.newPassword` over `.password`, which is what tells the keychain to offer a
    /// generated password on the two new fields and the stored one on the current field.
    private func secureField(_ key: String, text: Binding<String>, isNew: Bool) -> some View {
        SecureField(String(localized: String.LocalizationValue(key)), text: text)
            .textContentType(isNew ? .newPassword : .password)
            .textInputAutocapitalization(.never)
            .fieldChrome(widthClass)
            .accessibilityLabel(String(localized: String.LocalizationValue(key)))
    }
}
