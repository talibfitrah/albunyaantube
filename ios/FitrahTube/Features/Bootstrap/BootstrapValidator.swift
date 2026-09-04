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

    /// `CompleteProfileRequest.java:22`, verbatim — and `.asciiOnlyDigits()`, without which the two
    /// are only byte-identical and not equivalent: Swift's `\d` is Unicode-aware while Java's
    /// `@Pattern` is ASCII-only, so `+3١٢٣٤٥٦٧` used to pass the enabled button and 400 at the server
    /// as an unexplained "couldn't save your profile". The digits a user actually types are
    /// normalised to ASCII at the field (`ProfileBootstrapViewModel.phoneNumber`), so this only ever
    /// refuses input that no keypad produced.
    ///
    /// Computed, not a `static let`: `Regex<Substring>` is not `Sendable`, so under
    /// `SWIFT_STRICT_CONCURRENCY: complete` a stored static is a compile error. The literal is
    /// re-built per call, which on one 15-character field is not a cost worth an
    /// `nonisolated(unsafe)` escape hatch.
    static var phonePattern: Regex<Substring> { /^\+[1-9]\d{7,14}$/.asciiOnlyDigits() }

    /// ONE rule, both phone fields (Stage 1 / B3a): every character that IS a digit becomes its
    /// ASCII digit, everything else is dropped. That covers the three ways these fields get input
    /// `phonePattern` would refuse — the "+" both screens render as a fixed prefix (a pasted "+31…"
    /// would otherwise become "++31…"), the spaces and dashes `.telephoneNumber` autofill hands
    /// over, and the Arabic-Indic digits an Arabic keypad produces, which the server's ASCII-only
    /// `@Pattern` rejects. It is a VALIDATION rule — which characters survive into a number the
    /// backend will accept — so it lives beside the pattern it feeds, not twice in two view models.
    static func normalizedDigits(_ raw: String) -> String {
        raw.compactMap { $0.wholeNumberValue.map(String.init) }.joined()
    }

    /// E.164 as the server sees it: the fixed "+" both screens render, plus the normalised digits.
    static func e164(_ digits: String) -> String { "+" + digits }

    /// The date the pickers open on when the account has none: eighteen years ago rather than today,
    /// so the very first flick is not out of one that is guaranteed to be under age.
    ///
    /// Stage 1 / B3b: ONE copy, and NOT `Calendar.current` — that is the device's REGION calendar,
    /// which an Arabic/Gulf user can set to Islamic (Umm al-Qura), and subtracting 18 Hijri years is
    /// ~17 y 5 m. The same `gregorian(_:)` normalisation the wire date and the age gate already use.
    static func defaultDateOfBirth(today: Date = Date(), calendar: Calendar = .current) -> Date {
        gregorian(calendar).date(byAdding: .year, value: -18, to: today) ?? today
    }

    /// The GREGORIAN calendar on `calendar`'s time zone.
    ///
    /// `Calendar.current` is the device's REGION calendar, which an Arabic/Gulf user can and does set
    /// to Islamic (Umm al-Qura) — the audience this app is built for. Left alone it corrupts both
    /// consumers at once: the wire `dateOfBirth` becomes a Hijri-numbered "1420-09-24" the backend
    /// parses as the year 1420, and the age gate counts thirteen LUNAR years (~12 y 7 m), passing a
    /// genuinely under-13 user for a ~4.7-month window. Normalised HERE rather than at the default
    /// arguments so an injected calendar is fixed too — the callers all mean "the day the picker
    /// showed", never "the Hijri numbering of it". The time zone is kept: that is the other half of
    /// the wire date and is deliberate.
    static func gregorian(_ calendar: Calendar) -> Calendar {
        guard calendar.identifier != .gregorian else { return calendar }
        var gregorian = Calendar(identifier: .gregorian)
        gregorian.timeZone = calendar.timeZone
        return gregorian
    }

    /// `dob.isAfter(today.minusYears(13))` (`ProfileBootstrapViewModel.kt:220`), in whole DAYS: a
    /// `DatePicker` hands over an instant with a time-of-day, and comparing instants would read the
    /// morning of a user's thirteenth birthday as a few hours under age.
    static func isUnderMinimumAge(dob: Date, today: Date, calendar: Calendar = .current) -> Bool {
        let calendar = gregorian(calendar)
        // The DOB on the wire is the day the picker SHOWED (local), but the server measures it
        // against `LocalDate.now(clock)` — a UTC day, which east of Greenwich is still yesterday.
        // Take the earlier of the two: being told "you're too young" for one day is a correctable
        // form error, and the server's answer is an irreversible tombstone.
        var utc = calendar
        utc.timeZone = TimeZone(identifier: "UTC")!
        let localToday = calendar.startOfDay(for: today)
        let utcParts = utc.dateComponents([.year, .month, .day], from: today)
        let utcToday = calendar.date(from: DateComponents(year: utcParts.year, month: utcParts.month,
                                                          day: utcParts.day)) ?? localToday
        guard let threshold = calendar.date(byAdding: .year, value: -minAgeYears,
                                            to: min(localToday, utcToday)) else {
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
