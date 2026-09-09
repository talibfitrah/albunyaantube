import Foundation
import Observation
import SwiftUI

/// `EditEmailError.kt`. `.network` and `.unknown` render different copy here (Android collapses
/// both onto NETWORK) — "no signed-in user" is not "the server was unreachable", and telling a
/// user to check a connection that is fine is worse than saying nothing specific.
nonisolated enum EditEmailError: Sendable, Equatable {
    case invalidEmail, wrongPassword, emailInUse, network, unknown

    var messageKey: String {
        switch self {
        case .invalidEmail: "edit_email_invalid"
        case .wrongPassword: "edit_email_wrong_password"
        case .emailInUse: "edit_email_in_use"
        case .network: "auth_error_network"
        case .unknown: "auth_error_generic"
        }
    }
}

/// `EditEmailViewModel.kt`. **`verifyBeforeUpdateEmail` is the only email path there is** — the
/// address does not change until the user opens the link Firebase mails to the NEW address, and
/// the current one keeps working until then. `AuthClient` deliberately carries no `updateEmail`,
/// which would flip the address on an unverified mailbox and lock the account out of its own
/// sign-in.
@MainActor @Observable final class EditEmailViewModel {

    nonisolated struct UiState: Equatable {
        var newEmail = ""
        var currentPassword = ""
        var saving = false
        var error: EditEmailError?
    }

    private let auth: any AuthClient
    private(set) var state = UiState()
    /// The address the link went to, once sent. Non-nil is the success signal the sheet reports.
    private(set) var sentTo: String?

    init(auth: any AuthClient) { self.auth = auth }

    var newEmail: String {
        get { state.newEmail }
        set { state.newEmail = newValue; state.error = nil }
    }

    var currentPassword: String {
        get { state.currentPassword }
        set { state.currentPassword = newValue; state.error = nil }
    }

    /// The shape check runs FIRST and never reaches the network: a malformed address would burn
    /// Firebase's IP throttle window that legitimate users on flaky networks then hit.
    func submit() async {
        guard !state.saving else { return }
        guard EmailShape.isValid(state.newEmail) else {
            state.error = .invalidEmail
            return
        }
        // R9-P3 #2: the latch closes BEFORE the first suspension. `guard !state.saving` and the
        // write used to sit either side of `await auth.currentUser()`, so two taps landing inside
        // that hop both passed the guard and both re-authenticated.
        state.saving = true
        state.error = nil
        guard let user = await auth.currentUser(), let current = user.email, !current.isEmpty else {
            state.saving = false
            state.error = .unknown
            return
        }
        do {
            try await auth.reauthenticate(password: state.currentPassword)
        } catch {
            state.saving = false
            state.error = Self.reauthFailure(error)
            return
        }
        do {
            try await auth.verifyBeforeUpdateEmail(state.newEmail)
            state.saving = false
            sentTo = state.newEmail
        } catch {
            state.saving = false
            state.error = Self.verifyFailure(error)
        }
    }

    /// A rejected credential is the WRONG PASSWORD, not "something went wrong"
    /// (`EditEmailViewModel.kt:62-71`): the field the user can fix is the one the message must name.
    nonisolated static func reauthFailure(_ error: AuthErrorCode) -> EditEmailError {
        switch error {
        case .wrongPassword, .invalidCredential: .wrongPassword
        default: .network
        }
    }

    nonisolated static func verifyFailure(_ error: AuthErrorCode) -> EditEmailError {
        switch error {
        case .emailAlreadyInUse: .emailInUse
        case .invalidEmail: .invalidEmail
        default: .network
        }
    }
}

/// One sheet, one field pair, one action. `.presentationDragIndicator(.visible)` is the native
/// grabber — SwiftUI draws it on the sheet's own chrome, so there is no hand-rolled handle view to
/// keep in step with the system's metrics.
struct EditEmailSheet: View {
    /// Reports the confirmation copy to the Profile screen, which banners it and dismisses.
    let onSent: (String) -> Void

    @Environment(\.container) private var container
    @Environment(\.widthClass) private var widthClass
    @Environment(\.locale) private var locale
    @State private var model: EditEmailViewModel?

    var body: some View {
        EditSheetScaffold(title: String(localized: "edit_email_title")) {
            if let model {
                @Bindable var bindable = model
                LabelledField(key: "edit_email_new_email") {
                    TextField(String(localized: "edit_email_new_email"), text: $bindable.newEmail)
                        .textContentType(.emailAddress)
                        .keyboardType(.emailAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .fieldChrome(widthClass)
                        .accessibilityLabel(String(localized: "edit_email_new_email"))
                }
                LabelledField(key: "edit_email_current_password") {
                    SecureField(String(localized: "edit_email_current_password"),
                                text: $bindable.currentPassword)
                        .textContentType(.password)
                        .textInputAutocapitalization(.never)
                        .fieldChrome(widthClass)
                        .accessibilityLabel(String(localized: "edit_email_current_password"))
                }
                if let error = model.state.error {
                    InlineError(key: error.messageKey)
                }
                EditSheetAction(title: String(localized: "edit_email_send"),
                                isLoading: model.state.saving) {
                    await model.submit()
                    if let sent = model.sentTo {
                        // `\u{2068}…\u{2069}` around the address, as `EmailVerificationScreen`
                        // does for the same content: `edit_email_sent` is a full sentence in ar and
                        // nl, and an unisolated address's dots and trailing period reorder in RTL.
                        onSent(Format.localizedFormat("edit_email_sent", locale: locale,
                                                      "\u{2068}\(sent)\u{2069}"))
                    }
                }
            }
        }
        .task { if model == nil { model = EditEmailViewModel(auth: container.auth) } }
    }
}
