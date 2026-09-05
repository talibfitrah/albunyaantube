import Foundation
import Observation

/// The editable account, as the screen holds it (`ProfileUiState.kt`). `dateOfBirth` is a `Date`,
/// not Android's ISO string: the picker hands over an instant, and keeping the wire spelling here
/// would make every comparison ("did the user change it?") a string comparison against a value
/// formatted somewhere else.
nonisolated struct ProfileFields: Sendable, Equatable {
    var displayName: String
    var dateOfBirth: Date?
    /// Firebase's address, shown read-only — the sheet changes it through
    /// `verifyBeforeUpdateEmail`, so it is never edited in place here.
    var emailReadOnly: String?
    var phoneNumber: String?
    var hasPasswordProvider: Bool
}

nonisolated enum ProfileUiState: Sendable, Equatable {
    case loading
    case editing(original: ProfileFields, draft: ProfileFields, saving: Bool, error: AccountError?)
    /// Terminal: the age dialog's confirm dropped the session. The screen pops.
    case signedOut
}

/// Drives `ProfileScreen` (`ProfileViewModel.kt`). Firebase stays behind `AuthClient`, the backend
/// behind `AccountClient`, and the calendar and clock are injected so the age gate and the wire
/// date are pure functions of what a test hands over.
@MainActor @Observable final class ProfileViewModel {

    private let account: AccountClient
    private let auth: any AuthClient
    private let session: AccountSession
    private let calendar: Calendar
    private let today: @Sendable () -> Date

    private(set) var state: ProfileUiState = .loading
    /// Set once per successful save, consumed by the screen's banner. Not part of `state`: a
    /// success is an EVENT, and parking it in the state would re-announce it on every re-render.
    private(set) var saveSucceeded = false

    init(account: AccountClient, auth: any AuthClient, session: AccountSession,
         calendar: Calendar = .current, today: @escaping @Sendable () -> Date = { Date() }) {
        self.account = account
        self.auth = auth
        self.session = session
        self.calendar = calendar
        self.today = today
    }

    // MARK: - Lifecycle

    /// The screen's `.task` AND its `.onChange(of: session.state)` — one method, because Android's
    /// single `accountState.collect` is one decision with two arms (`ProfileViewModel.kt:31-58`):
    /// the FIRST `.loaded` promotes `.loading` to `.editing`, and every later one reconciles only
    /// the fields something ELSE can change (phone, email) into a draft the user may be typing in.
    /// Overwriting `displayName`/`dateOfBirth` here would delete keystrokes.
    func sync() async {
        guard session.state.me != nil else { return }
        switch state {
        case .loading:
            // `currentUser()` is async on iOS, so the password-provider flag cannot be read at
            // construction the way Android's fragment reads `firebaseAuth.currentUser`.
            let hasPassword = await auth.currentUser()?.hasPasswordProvider ?? false
            // Re-read across the await: `state` may have been promoted, and `me` may have moved.
            guard case .loading = state, let me = session.state.me else { return }
            let fields = ProfileFields(displayName: me.displayName ?? "",
                                       dateOfBirth: Self.parseWireDate(me.dateOfBirth, calendar: calendar),
                                       emailReadOnly: me.email, phoneNumber: me.phoneNumber,
                                       hasPasswordProvider: hasPassword)
            state = .editing(original: fields, draft: fields, saving: false, error: nil)
        case .editing(var original, var draft, let saving, let error):
            guard let me = session.state.me else { return }
            original.phoneNumber = me.phoneNumber
            original.emailReadOnly = me.email
            // The phone has no in-screen draft (its own sheet owns it), so the draft tracks the
            // original rather than holding a value the user cannot have typed.
            draft.phoneNumber = me.phoneNumber
            draft.emailReadOnly = me.email
            state = .editing(original: original, draft: draft, saving: saving, error: error)
        case .signedOut:
            break
        }
    }

    // MARK: - Bindings

    var displayName: String {
        get { draft?.displayName ?? "" }
        set { edit { $0.displayName = newValue } }
    }

    /// Cubic round 6 / P3: normalised to the START OF THE DAY on write. Only the day round-trips
    /// — `wireDate` formats `yyyy-MM-dd` and `parseWireDate` gives it back at midnight — so
    /// comparing the picker's raw instant made a re-pick of the SAME day dirty and sent a no-op
    /// `PUT`. Done here, once, so `canSave`, `save()`'s guard and its `dob` term cannot disagree;
    /// the calendar is `BootstrapValidator.gregorian(calendar)`, never `Calendar.current`, for the
    /// reason every other date site in this flow uses it.
    var dateOfBirth: Date? {
        get { draft?.dateOfBirth }
        set { edit { $0.dateOfBirth = newValue.map { BootstrapValidator.gregorian(calendar).startOfDay(for: $0) } } }
    }

    // The `.editing` payload, one accessor per field rather than one tuple: readers would otherwise
    // reach `model.fields?.error`, whose `AccountError??` is a shape no comparison should have to
    // spell.
    /// What the screen renders. nil while loading or once signed out.
    var draft: ProfileFields? { if case .editing(_, let draft, _, _) = state { draft } else { nil } }
    var original: ProfileFields? { if case .editing(let original, _, _, _) = state { original } else { nil } }
    var isSaving: Bool { if case .editing(_, _, let saving, _) = state { saving } else { false } }
    var error: AccountError? { if case .editing(_, _, _, let error) = state { error } else { nil } }

    /// Drives the Save button's enabled state — the same `isDirty && !saving` `save()` enforces, so
    /// the button and the guard cannot drift.
    var canSave: Bool {
        guard let original, let draft else { return false }
        return original != draft && !isSaving
    }

    func consumeSaveSuccess() { saveSucceeded = false }

    // MARK: - Save

    /// **Changed fields only.** A draft that differs in `displayName` alone sends
    /// `{"displayName": …}` — `dateOfBirth` is ABSENT from the JSON, not null, because
    /// `AccountClient.updateProfile` omits nils and the backend reads a present null as "clear
    /// this". `phoneNumber` is never in this request at all: it has its own sheet
    /// (`ProfileViewModel.kt:129-133`).
    func save() async {
        guard case .editing(let original, let draft, let saving, _) = state,
              !saving, original != draft else { return }

        // The local age gate, BEFORE the request, for Task 12's reason: the server's rejection is
        // PERMANENT (revoked tokens, disabled Firebase account, tombstoned document). A mistyped
        // year would destroy the account with no recovery, so an honest mistake stays a correctable
        // field error here. The SERVER's `.ageIneligible` is the terminal dialog below; this is not
        // that, and must not sign anyone out.
        if let dob = draft.dateOfBirth, dob != original.dateOfBirth,
           BootstrapValidator.isUnderMinimumAge(dob: dob, today: today(), calendar: calendar) {
            state = .editing(original: original, draft: draft, saving: false,
                             error: .validation(field: "dateOfBirth",
                                                message: String(localized: "bootstrap_error_under_age")))
            return
        }

        // Trimmed INTO the draft, not only into the request: the wire has always carried the
        // trimmed name, and a field left showing "Aisha  " states a value the record does not have.
        var sent = draft
        sent.displayName = draft.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        state = .editing(original: original, draft: sent, saving: true, error: nil)
        let name = draft.displayName == original.displayName ? nil : sent.displayName
        let dob = draft.dateOfBirth == original.dateOfBirth
            ? nil : draft.dateOfBirth.map { ProfileBootstrapViewModel.wireDate($0, calendar: calendar) }
        do {
            let updated = try await account.updateProfile(displayName: name, dateOfBirth: dob,
                                                          phoneNumber: nil)
            // Nothing else re-reads `/me` after an edit, so without this every other reader of
            // `state.me` — this screen's own next visit included — keeps the replaced value.
            session.apply(updated)
            // Re-read across the await, exactly as `sync()`'s `.loading` arm does: the text fields
            // stay enabled while the request is in flight (only the button is disabled), so
            // anything typed meanwhile is in `state` NOW — writing the captured copy back would
            // delete those keystrokes and then call the form clean while showing text that was
            // never saved.
            guard case .editing(var reconciled, let onScreen, _, _) = state else { return }
            // The server holds what was SENT, so that is the new original; a mid-flight phone or
            // email reconcile stays, and what is on screen stays on screen — dirty again if the
            // user typed past the save.
            reconciled.displayName = sent.displayName
            reconciled.dateOfBirth = sent.dateOfBirth
            state = .editing(original: reconciled, draft: onScreen, saving: false, error: nil)
            saveSucceeded = true
        } catch {
            guard case .editing(let reconciled, let onScreen, _, _) = state else { return }
            state = .editing(original: reconciled, draft: onScreen, saving: false, error: error)
        }
    }

    /// The age dialog's confirm. Staged deliberately (`ProfileViewModel.kt:103-108,124-127`): the
    /// `.ageIneligible` state is what puts the dialog on screen, and signing out inside the same
    /// update would be conflated past it — the user would be dropped to guest with no explanation.
    func confirmAgeIneligibleSignOut() async {
        // Stage 5 / C4.1: one server verdict (`AGE_INELIGIBLE`) had three arrivals and three
        // different residues. Stage 8 / S7: and ONE method now, the same one
        // `AgeIneligibleScreen.acknowledge()` calls — this site used to spell the pair out again.
        await session.terminateAgeIneligible()
        state = .signedOut
    }

    // MARK: - Pure helpers

    private func edit(_ transform: (inout ProfileFields) -> Void) {
        guard case .editing(let original, var draft, let saving, _) = state else { return }
        transform(&draft)
        // Every edit clears the standing error: a message about the previous attempt has nothing
        // to say about the text now on screen.
        state = .editing(original: original, draft: draft, saving: saving, error: nil)
    }

    /// `AccountMe.dateOfBirth` is the wire's `yyyy-MM-dd`, kept as text on purpose (ruling F1).
    /// Parsed HERE in the same Gregorian calendar `ProfileBootstrapViewModel.wireDate` formats
    /// with, so the round trip is lossless and a Hijri device calendar cannot renumber the year.
    nonisolated static func parseWireDate(_ iso: String?, calendar: Calendar) -> Date? {
        guard let iso else { return nil }
        let parts = iso.split(separator: "-")
        guard parts.count == 3, let year = Int(parts[0]), let month = Int(parts[1]),
              let day = Int(parts[2]) else { return nil }
        return BootstrapValidator.gregorian(calendar)
            .date(from: DateComponents(year: year, month: month, day: day))
    }

    /// The banner copy for a failed save, or nil when this error is not a banner — the age dialog
    /// owns `.ageIneligible`, and a field-scoped validation message belongs under its own field.
    ///
    /// Rate limiting is reported in MINUTES (`ProfileFragment.kt:182-185`), floored at one: the
    /// string says "min", and "try again in 0 min" is not an instruction.
    nonisolated static func bannerMessage(for error: AccountError, locale: Locale) -> String? {
        switch error {
        case .ageIneligible: nil
        case .validation(let field, let message): field == nil ? message : nil
        case .rateLimited(let seconds):
            Format.localizedFormat("profile_error_rate_limited", locale: locale,
                                   Int64(max(1, seconds / 60)))
        case .network: Format.localizedFormat("profile_error_network", locale: locale)
        default: Format.localizedFormat("auth_error_generic", locale: locale)
        }
    }

    /// The inline message under `field`, or nil. Android defaults an unattributed validation error
    /// to `displayName`, which points at an input the user never touched; `AccountClient` already
    /// reports no field in that case and `bannerMessage` shows the whole sentence instead.
    nonisolated static func fieldMessage(for error: AccountError?, field: String) -> String? {
        guard case .validation(let errorField, let message) = error, errorField == field else { return nil }
        return message
    }
}
