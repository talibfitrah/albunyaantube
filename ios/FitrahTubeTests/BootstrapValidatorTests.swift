import Foundation
import Testing
@testable import FitrahTube

/// Task 12. `BootstrapValidator` is the ONE validator behind both consumers — the submit button's
/// enabled state and `submit()`'s error dispatch (`ProfileBootstrapViewModel.kt:80-108`) — so the
/// field order, the age gate and the phone regex are pinned here once, as pure data.
///
/// Every date is built from an INJECTED `Calendar(identifier: .gregorian)` on a fixed time zone,
/// never `Calendar.current`: a suite that read the machine's calendar would pass in Amsterdam and
/// fail in Auckland on exactly the birthday boundary it exists to pin.
@Suite(.perTest)
struct BootstrapValidatorTests {

    private static let calendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }()

    /// What an Arabic/Gulf user can and does set the device REGION calendar to — the audience this
    /// app is built for. Injected here exactly as `Calendar.current` would arrive from the app.
    private static let hijri: Calendar = {
        var calendar = Calendar(identifier: .islamicUmmAlQura)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }()

    private static func day(_ year: Int, _ month: Int, _ day: Int) -> Date {
        calendar.date(from: DateComponents(year: year, month: month, day: day))!
    }

    /// A form that passes every rule, so each test can spoil exactly one field.
    private static let today = day(2026, 9, 3)
    private static let validDOB = day(2000, 1, 1)

    private func firstError(name: String = "Aisha", dob: Date? = validDOB, phone: String = "+31612345678",
                            password: String = "hunter2hunter2", passwordConfirm: String = "hunter2hunter2",
                            passwordRequired: Bool = false, today: Date = today,
                            calendar: Calendar = calendar) -> BootstrapError? {
        BootstrapValidator.firstError(name: name, dob: dob, phone: phone, password: password,
                                      passwordConfirm: passwordConfirm, passwordRequired: passwordRequired,
                                      today: today, calendar: calendar)
    }

    // MARK: - Field order

    @Test func aValidFormHasNoError() {
        #expect(firstError() == nil)
        #expect(firstError(passwordRequired: true) == nil)
    }

    /// Each row spoils EVERY field from its own position onwards, so the reported error can only be
    /// the one whose check runs first.
    @Test func theFirstErrorFollowsFieldOrder() {
        #expect(firstError(name: "", dob: nil, phone: "nope", password: "x", passwordConfirm: "y",
                           passwordRequired: true) == .invalidName)
        #expect(firstError(dob: nil, phone: "nope", password: "x", passwordConfirm: "y",
                           passwordRequired: true) == .invalidDOB)
        #expect(firstError(dob: Self.day(2020, 1, 1), phone: "nope", password: "x", passwordConfirm: "y",
                           passwordRequired: true) == .underAge)
        #expect(firstError(phone: "nope", password: "x", passwordConfirm: "y",
                           passwordRequired: true) == .invalidPhone)
        #expect(firstError(password: "x", passwordConfirm: "y", passwordRequired: true) == .invalidPassword)
        #expect(firstError(passwordConfirm: "y", passwordRequired: true) == .passwordMismatch)
    }

    /// The two checks share ONE field, so their order is not visible from the table above: a nil DOB
    /// has no age to test, and running the age gate first would have to invent one.
    @Test func theDobPresenceCheckPrecedesTheAgeCheck() {
        #expect(firstError(dob: nil) == .invalidDOB)
        #expect(firstError(dob: Self.day(2020, 1, 1)) == .underAge)
    }

    // MARK: - Name

    @Test func theNameLengthIsMeasuredOnTheTrimmedValue() {
        #expect(BootstrapValidator.maxNameLength == 40)
        let forty = String(repeating: "a", count: 40)
        #expect(firstError(name: forty) == nil)
        #expect(firstError(name: forty + "a") == .invalidName)
        // Trimmed, so surrounding whitespace neither passes a blank nor fails a 40-char name.
        #expect(firstError(name: "  \(forty)  ") == nil)
        #expect(firstError(name: "   ") == .invalidName)
        #expect(firstError(name: "\n\t") == .invalidName)
    }

    // MARK: - Age gate (mirrors AccountProfileService.MIN_AGE)

    @Test func theExactThirteenthBirthdayIsEligible() {
        #expect(BootstrapValidator.minAgeYears == 13)
        let birthday = Self.day(2013, 9, 3)
        #expect(BootstrapValidator.isUnderMinimumAge(dob: birthday, today: Self.day(2026, 9, 3),
                                                     calendar: Self.calendar) == false)
    }

    @Test func theDayBeforeTheThirteenthBirthdayIsUnderAge() {
        let birthday = Self.day(2013, 9, 3)
        #expect(BootstrapValidator.isUnderMinimumAge(dob: birthday, today: Self.day(2026, 9, 2),
                                                     calendar: Self.calendar))
    }

    /// A 29 February birthday has no 13th anniversary in 2025, so the boundary lands on the calendar's
    /// own clamping rather than on a date that exists. `2025-02-28 - 13y` is `2012-02-28`, which a
    /// 2012-02-29 birth IS after (under age); `2025-03-01 - 13y` is `2012-03-01`, which it is not
    /// (eligible).
    @Test func aLeapDayBirthdayResolvesThroughTheCalendar() {
        let leapDay = Self.day(2012, 2, 29)
        #expect(BootstrapValidator.isUnderMinimumAge(dob: leapDay, today: Self.day(2025, 2, 28),
                                                     calendar: Self.calendar))
        #expect(BootstrapValidator.isUnderMinimumAge(dob: leapDay, today: Self.day(2025, 3, 1),
                                                     calendar: Self.calendar) == false)
    }

    /// The gate compares whole DAYS, not instants: a DOB carrying a time-of-day (which is what a
    /// `DatePicker` hands over) must not read as "a few hours under age" on the birthday itself.
    @Test func theAgeGateIgnoresTheTimeOfDay() {
        let birthdayEvening = Self.day(2013, 9, 3).addingTimeInterval(23 * 3600)
        #expect(BootstrapValidator.isUnderMinimumAge(dob: birthdayEvening, today: Self.day(2026, 9, 3),
                                                     calendar: Self.calendar) == false)
    }

    /// The gate counts GREGORIAN years whatever the device's region calendar is. Umm al-Qura years
    /// are lunar (~354 days), so thirteen of them elapse at ~12 y 7 m: from 2013-09-03 to 2026-06-03
    /// is 12 y 9 m — genuinely under 13 — and a Hijri-counting gate waves that user through.
    @Test func theAgeGateCountsGregorianYearsOnAHijriDeviceCalendar() {
        let dob = Self.day(2013, 9, 3)
        let today = Self.day(2026, 6, 3)
        #expect(BootstrapValidator.isUnderMinimumAge(dob: dob, today: today, calendar: Self.hijri))
        #expect(firstError(dob: dob, today: today, calendar: Self.hijri) == .underAge)
        // The eligible side stays eligible: the calendar is normalised, not made stricter.
        #expect(BootstrapValidator.isUnderMinimumAge(dob: dob, today: Self.day(2026, 9, 3),
                                                     calendar: Self.hijri) == false)
    }

    /// The client's "today" is the device's LOCAL day; the server's is `LocalDate.now(clock)` in UTC,
    /// which east of Greenwich is still yesterday. The server's rejection is a permanent tombstone,
    /// so the earlier of the two days wins — a correctable form error for one day beats that.
    @Test func theUtcDayCountsWhenItIsBehindTheDeviceDay() {
        var plus13 = Calendar(identifier: .gregorian)
        plus13.timeZone = TimeZone(secondsFromGMT: 13 * 3600)!
        let dob = plus13.date(from: DateComponents(year: 2013, month: 9, day: 3))!
        let birthdayMorning = plus13.date(from: DateComponents(year: 2026, month: 9, day: 3, hour: 9))!
        #expect(BootstrapValidator.isUnderMinimumAge(dob: dob, today: birthdayMorning, calendar: plus13),
                "UTC is still 2026-09-02, and the server would tombstone the account")
        #expect(BootstrapValidator.isUnderMinimumAge(dob: dob, today: birthdayMorning.addingTimeInterval(86_400),
                                                     calendar: plus13) == false,
                "one day later UTC has caught up and the margin must let go")
    }

    // MARK: - Phone (CompleteProfileRequest.java:22, byte-for-byte)

    @Test func thePhoneRuleMatchesTheServersRegex() {
        let accepted = ["+31612345678",          // the ordinary case
                        "+12345678",             // 8 digits after the +, the minimum
                        "+123456789012345"]      // 15, the maximum
        let refused = ["+0123456789",            // leading zero
                       "+1234567",               // 7 digits, one under the minimum
                       "+1234567890123456",      // 16, one over the maximum
                       "0031612345678",          // no +
                       "+31 612345678",          // a space
                       "+31-612345678",
                       "",
                       "+",
                       "++31612345678",
                       "+31612345678\n"]         // a trailing newline is not a match
        for phone in accepted {
            #expect(firstError(phone: phone) == nil, "\(phone) should be accepted")
        }
        for phone in refused {
            #expect(firstError(phone: phone) == .invalidPhone, "\(phone) should be refused")
        }
    }

    /// The literal matches the server's, the SEMANTICS have to as well: Swift's `\d` is Unicode-aware
    /// and Java's `@Pattern` is ASCII-only, so a mixed-digit entry used to pass the enabled button and
    /// 400 at the server as an unexplained "couldn't save your profile".
    @Test func thePhoneRuleRejectsNonAsciiDigitsAsTheServersPatternDoes() {
        // "+3" then Arabic-Indic ١٢٣٤٥٦٧ — escaped so the source stays readable left to right.
        let mixed = "+3\u{0661}\u{0662}\u{0663}\u{0664}\u{0665}\u{0666}\u{0667}"
        #expect(firstError(phone: mixed) == .invalidPhone)
        // Wholly Arabic-Indic never matched `[1-9]` either; the setter is what turns those into
        // ASCII before they arrive here (`thePhoneSetterNormalisesToAsciiDigits`).
        #expect(firstError(phone: "+\u{0663}\u{0661}\u{0666}\u{0661}\u{0662}\u{0663}\u{0664}\u{0665}\u{0666}\u{0667}\u{0668}")
                == .invalidPhone)
    }

    // MARK: - Password (only when the account has no password provider)

    @Test func thePasswordRulesAreSkippedWhenNoPasswordIsRequired() {
        #expect(BootstrapValidator.minPasswordLength == 8)
        #expect(firstError(password: "", passwordConfirm: "nope", passwordRequired: false) == nil)
    }

    @Test func theShortPasswordAndTheMismatchAreSeparateErrors() {
        #expect(firstError(password: "1234567", passwordConfirm: "1234567",
                           passwordRequired: true) == .invalidPassword)
        #expect(firstError(password: "12345678", passwordConfirm: "12345678",
                           passwordRequired: true) == nil)
        #expect(firstError(password: "12345678", passwordConfirm: "12345679",
                           passwordRequired: true) == .passwordMismatch)
    }
}
