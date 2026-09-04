import Foundation
import Observation
import SwiftUI

/// `EditPhoneError.kt` minus `INVALID_COUNTRY`: iOS has no country picker (ruling C1/F5 — Android's
/// fed libphonenumber, which iOS does not carry), so that error has no input to point at and
/// `edit_phone_country` was refused in Task 3.
nonisolated enum EditPhoneError: Sendable, Equatable {
    case invalidPhone, rateLimited, network, unknown

    var messageKey: String {
        switch self {
        case .invalidPhone: "bootstrap_error_invalid_phone"
        case .rateLimited: "profile_error_rate_limited_short"
        case .network: "profile_error_network"
        case .unknown: "auth_error_generic"
        }
    }
}

/// `EditPhoneViewModel.kt`, over ONE free-text field with a fixed leading "+" — the same field the
/// bootstrap form has, and validated by the same `BootstrapValidator.phonePattern`, which is the
/// server's own regex.
@MainActor @Observable final class EditPhoneViewModel {

    nonisolated struct UiState: Equatable {
        /// The NATIONAL portion as typed; the screen renders the "+", so E.164 is assembled here
        /// rather than being something the user can get wrong.
        var number = ""
        var saving = false
        var error: EditPhoneError?
    }

    private let account: AccountClient
    private let session: AccountSession
    private(set) var state = UiState()
    private(set) var didUpdate = false

    init(account: AccountClient, session: AccountSession) {
        self.account = account
        self.session = session
    }

    /// Seeds from the stored E.164 by dropping its "+", so re-opening the sheet shows the number
    /// the account already has rather than an empty field.
    func seed(_ e164: String?) {
        guard state.number.isEmpty else { return }
        number = e164 ?? ""
    }

    var number: String {
        get { state.number }
        // `BootstrapValidator.normalizedDigits` — the same rule the bootstrap field applies,
        // spelled once beside the pattern it feeds (Stage 1 / B3a).
        set {
            state.number = BootstrapValidator.normalizedDigits(newValue)
            state.error = nil
        }
    }

    var e164: String { BootstrapValidator.e164(state.number) }

    /// `wholeMatch`, not `firstMatch`: `$` alone can match ahead of a trailing newline.
    var isValid: Bool { e164.wholeMatch(of: BootstrapValidator.phonePattern) != nil }

    /// **A cleared field sends NOTHING.** `nil` on the wire means "no change", never "clear this"
    /// (`AccountClient.updateProfile` omits nils), so there is no way to express a deletion here
    /// and no affordance that pretends there is. An empty field simply fails the pattern — one
    /// rule, no special case — and never reaches the network.
    func submit() async {
        guard !state.saving else { return }
        guard isValid else {
            state.error = .invalidPhone
            return
        }
        state.saving = true
        state.error = nil
        do {
            let updated = try await account.updateProfile(displayName: nil, dateOfBirth: nil,
                                                          phoneNumber: e164)
            session.apply(updated)
            state.saving = false
            didUpdate = true
        } catch {
            state.saving = false
            state.error = Self.failure(error)
        }
    }

    nonisolated static func failure(_ error: AccountError) -> EditPhoneError {
        switch error {
        case .rateLimited: .rateLimited
        case .network: .network
        // A 400/422 on this request can only be about the one field it carries.
        case .validation: .invalidPhone
        default: .unknown
        }
    }
}

struct EditPhoneSheet: View {
    let current: String?
    let onUpdated: (String) -> Void

    @Environment(\.container) private var container
    @Environment(\.widthClass) private var widthClass
    @State private var model: EditPhoneViewModel?

    var body: some View {
        EditSheetScaffold(title: String(localized: "edit_phone_title")) {
            if let model {
                @Bindable var bindable = model
                LabelledField(key: "edit_phone_number") {
                    HStack(spacing: 0) {
                        // A fixed prefix, not something the user can delete or duplicate — the
                        // server's regex requires exactly one leading "+".
                        Text(verbatim: "+")
                            .font(TypeScale.body(widthClass))
                            .foregroundStyle(Color.textSecondary)
                            .padding(.leading, Spacing.md(widthClass))
                            // Decoration, not content: VoiceOver reads the field's own label.
                            .accessibilityHidden(true)
                        // The placeholder is the only country hint on this sheet (ruling C1), so it
                        // has to show the SHAPE: a dial code then the national number. Digits need
                        // no translation, hence `verbatim`.
                        TextField(String(localized: "edit_phone_number"), text: $bindable.number,
                                  prompt: Text(verbatim: "31612345678"))
                            .textContentType(.telephoneNumber)
                            .keyboardType(.phonePad)
                            .autocorrectionDisabled()
                            .font(TypeScale.body(widthClass))
                            .padding(Spacing.md(widthClass))
                            .accessibilityLabel(String(localized: "edit_phone_number"))
                    }
                    .frame(minHeight: 44)
                    .background(Color.homeCard, in: RoundedRectangle(cornerRadius: Radius.card))
                }
                if let error = model.state.error {
                    InlineError(key: error.messageKey)
                }
                EditSheetAction(title: String(localized: "edit_phone_save"),
                                isLoading: model.state.saving) {
                    await model.submit()
                    if model.didUpdate { onUpdated(String(localized: "edit_phone_updated")) }
                }
            }
        }
        .task {
            if model == nil {
                model = EditPhoneViewModel(account: container.account, session: container.session)
            }
            model?.seed(current)
        }
    }
}
