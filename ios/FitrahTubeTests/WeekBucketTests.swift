import Foundation
import Testing
@testable import FitrahTube

/// Task 15. Week bucketing is pure calendar math, so every date here is built from an INJECTED
/// `Calendar` on an explicit `TimeZone` and an explicit `firstWeekday` — never `Calendar.current`,
/// which on an Arabic/Gulf device is the Islamic (Umm al-Qura) region calendar and on any machine
/// carries whatever week start and zone the host happens to have (the Task 12 lesson, one layer up).
///
/// Mirrors `WeekBucket.kt:88-101`: Monday-first ISO weeks, `coerceAtLeast(0)` on a future upload,
/// and `MAX_WEEKS_BACK = 5_000` as the hard pagination cap.
@Suite(.perTest)
struct WeekBucketTests {

    private static func gregorian(firstWeekday: Int = 2, zone: String = "UTC") -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: zone)!
        calendar.firstWeekday = firstWeekday
        return calendar
    }

    /// Monday-first (`firstWeekday = 2`), UTC — Android's ISO week, without DST in the way.
    private static let utc = gregorian()

    private static func at(_ year: Int, _ month: Int, _ day: Int, _ hour: Int = 0, _ minute: Int = 0,
                           in calendar: Calendar = utc) -> Date {
        calendar.date(from: DateComponents(year: year, month: month, day: day, hour: hour, minute: minute))!
    }

    /// Wednesday 2026-09-02, mid-week on purpose: "eight days ago" is only *last* week when `now`
    /// sits far enough into its own week — from a Monday the same eight days land two weeks back.
    private static let wednesday = at(2026, 9, 2, 10)

    @Test func anUploadEarlierTodayIsThisWeek() {
        #expect(WeekBucket.weekIndexOf(Self.at(2026, 9, 2, 8), now: Self.wednesday, calendar: Self.utc) == 0)
        // Monday 00:00 of the same ISO week — the earliest instant that is still week 0.
        #expect(WeekBucket.weekIndexOf(Self.at(2026, 8, 31), now: Self.wednesday, calendar: Self.utc) == 0)
    }

    @Test func eightDaysBackIsLastWeek() {
        #expect(WeekBucket.weekIndexOf(Self.at(2026, 8, 25, 10), now: Self.wednesday, calendar: Self.utc) == 1)
        #expect(WeekBucket.weekIndexOf(Self.at(2026, 8, 18, 10), now: Self.wednesday, calendar: Self.utc) == 2)
    }

    /// The boundary is the calendar's own first weekday, not a rolling 7-day window from `now`:
    /// one minute either side of the same Monday midnight lands in different buckets, and moving
    /// `firstWeekday` to Sunday moves the boundary with it.
    @Test func theWeekTurnsOverAtTheCalendarsOwnFirstWeekday() {
        let monday = Self.at(2026, 8, 31)
        let sundayNight = Self.at(2026, 8, 30, 23, 59)
        let mondayFirst = Self.gregorian(firstWeekday: 2)
        #expect(WeekBucket.weekIndexOf(sundayNight, now: monday, calendar: mondayFirst) == 1)
        #expect(WeekBucket.weekIndexOf(monday, now: monday, calendar: mondayFirst) == 0)

        let sundayFirst = Self.gregorian(firstWeekday: 1)
        #expect(WeekBucket.weekIndexOf(sundayNight, now: monday, calendar: sundayFirst) == 0)
    }

    /// The same instant buckets differently in two zones, which is the point: the header says
    /// "This week" about the user's local calendar, not about UTC.
    @Test func theWeekBoundaryFollowsTheCalendarsTimeZone() {
        // 22:30Z on Sunday 2026-08-30 is 00:30 on Monday 2026-08-31 in Amsterdam (UTC+2 in August).
        let instant = Self.at(2026, 8, 30, 22, 30)
        #expect(WeekBucket.weekIndexOf(instant, now: Self.wednesday, calendar: Self.utc) == 1)
        #expect(WeekBucket.weekIndexOf(instant, now: Self.wednesday,
                                       calendar: Self.gregorian(zone: "Europe/Amsterdam")) == 0)
    }

    /// A skipped local midnight must not swallow a week. Cuba springs forward at 00:00 on Sunday
    /// 2026-03-08, so on a Sunday-first calendar that week's `dateInterval(of: .weekOfYear,…)?.start`
    /// is 01:00 and the gap to the next week's start is 6 d 23 h — which
    /// `dateComponents([.weekOfYear],…)` truncates to 0, filing last week's uploads under
    /// "This week" for the whole of the following week.
    @Test func aWeekWithNoLocalMidnightIsStillAFullWeek() {
        let havana = Self.gregorian(firstWeekday: 1, zone: "America/Havana")
        // Tuesday inside the week that starts Sunday 2026-03-08, `now` inside the next one.
        let uploaded = Self.at(2026, 3, 10, 12, in: havana)
        let now = Self.at(2026, 3, 17, 12, in: havana)
        #expect(WeekBucket.weekIndexOf(uploaded, now: now, calendar: havana) == 1)
    }

    @Test func theCapIsFiveThousandWeeksBack() {
        let atTheCap = Self.utc.date(byAdding: .weekOfYear, value: -WeekBucket.maxWeeksBack, to: Self.wednesday)!
        #expect(WeekBucket.weekIndexOf(atTheCap, now: Self.wednesday, calendar: Self.utc) == WeekBucket.maxWeeksBack)

        let beyond = Self.utc.date(byAdding: .weekOfYear, value: -1, to: atTheCap)!
        #expect(WeekBucket.weekIndexOf(beyond, now: Self.wednesday, calendar: Self.utc) == nil)
    }

    /// `coerceAtLeast(0)`: a clock-skewed or scheduled-ahead upload date belongs in "This week",
    /// never in a negative bucket the section list has no row for.
    @Test func aFutureUploadClampsToThisWeek() {
        let ahead = Self.utc.date(byAdding: .weekOfYear, value: 3, to: Self.wednesday)!
        #expect(WeekBucket.weekIndexOf(ahead, now: Self.wednesday, calendar: Self.utc) == 0)
    }

    @Test func theHeaderKeyAndArgumentFollowTheIndex() {
        let thisWeek = WeekBucket.headerKey(weekIndex: 0)
        #expect(thisWeek.key == "me_week_this")
        #expect(thisWeek.argument == nil)

        let lastWeek = WeekBucket.headerKey(weekIndex: 1)
        #expect(lastWeek.key == "me_week_last")
        #expect(lastWeek.argument == nil)

        for index in [2, 7, WeekBucket.maxWeeksBack] {
            let older = WeekBucket.headerKey(weekIndex: index)
            #expect(older.key == "me_week_n_ago")
            #expect(older.argument == index)
        }
    }
}
