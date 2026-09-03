import Foundation

/// The bootstrap form's failure modes (`ProfileBootstrapViewModel.kt:22-32`) minus
/// `INVALID_PHONE_COUNTRY`: iOS has no country picker (ruling C1/F5 — one free-text field with a
/// fixed leading "+"), so that error has no input to point at and its two strings were refused in
/// Task 3.
nonisolated enum BootstrapError: Sendable, Equatable {
    case invalidName, invalidDOB, underAge, invalidPhone, invalidPassword, passwordMismatch
    case passwordSetFailed, saveFailed
}

/// ONE validator, TWO consumers — the submit button's enabled state and `submit()`'s error dispatch
/// — so they cannot drift (`ProfileBootstrapViewModel.kt:80-108`). Pure and `nonisolated`: the
/// suite constructs it off the main actor and hands it every date it decides on.
nonisolated enum BootstrapValidator {
    static let maxNameLength = 40
    /// Mirrors `AccountProfileService.MIN_AGE`.
    static let minAgeYears = 13
    static let minPasswordLength = 8

    /// `CompleteProfileRequest.java:22`, verbatim — client and server agree byte-for-byte, so a form
    /// this accepts can never be the one the server's `@Pattern` then rejects.
    ///
    /// Computed, not a `static let`: `Regex<Substring>` is not `Sendable`, so under
    /// `SWIFT_STRICT_CONCURRENCY: complete` a stored static is a compile error. The literal is
    /// re-built per call, which on one 15-character field is not a cost worth an
    /// `nonisolated(unsafe)` escape hatch.
    static var phonePattern: Regex<Substring> { /^\+[1-9]\d{7,14}$/ }

    /// `dob.isAfter(today.minusYears(13))` (`ProfileBootstrapViewModel.kt:220`), in whole DAYS: a
    /// `DatePicker` hands over an instant with a time-of-day, and comparing instants would read the
    /// morning of a user's thirteenth birthday as a few hours under age.
    static func isUnderMinimumAge(dob: Date, today: Date, calendar: Calendar = .current) -> Bool {
        let startOfToday = calendar.startOfDay(for: today)
        guard let threshold = calendar.date(byAdding: .year, value: -minAgeYears, to: startOfToday) else {
            // Unreachable for a Gregorian calendar; refusing to guess is the safe leg — the server
            // still enforces the gate, and its rejection is permanent.
            return false
        }
        return calendar.startOfDay(for: dob) > threshold
    }

    /// The first error in FIELD ORDER, or nil when the form is valid.
    ///
    /// The under-13 gate runs HERE, before the request, and that is not a nicety
    /// (`ProfileBootstrapViewModel.kt:93-98`): the server's rejection is *permanent* — it revokes
    /// refresh tokens, disables the Firebase account and tombstones the Firestore doc. A mistyped
    /// year would destroy the account with no recovery. Failing locally keeps an honest mistake a
    /// correctable form error.
    static func firstError(name: String, dob: Date?, phone: String, password: String,
                           passwordConfirm: String, passwordRequired: Bool,
                           today: Date, calendar: Calendar = .current) -> BootstrapError? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed.count > maxNameLength { return .invalidName }
        // Presence before age: a nil DOB has no age to test, and the age gate would have to invent
        // one to run first.
        guard let dob else { return .invalidDOB }
        if isUnderMinimumAge(dob: dob, today: today, calendar: calendar) { return .underAge }
        // `wholeMatch`, not `firstMatch`: `$` alone can match ahead of a trailing newline, and a
        // pasted number carrying one must fail here rather than at the server.
        if phone.wholeMatch(of: phonePattern) == nil { return .invalidPhone }
        if passwordRequired {
            if password.count < minPasswordLength { return .invalidPassword }
            if password != passwordConfirm { return .passwordMismatch }
        }
        return nil
    }
}
