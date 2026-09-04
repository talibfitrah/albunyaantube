import Foundation

/// Week bucketing for the Me feed — `WeekBucket.kt:41-101`, minus the `startMs`/`endMs` window.
///
/// Android needs the window because its rows come out of a Room query with a `BETWEEN`; here the
/// items are already in memory (`AtomFeedFetcher.cached`), so the only question a bucket answers is
/// "which section does this upload belong in", and the interval never has to be materialised.
///
/// Boundaries are the CALENDAR'S OWN week, not a rolling 7-day window from `now`: the header says
/// "This week" about the user's calendar app, so Monday morning's week 0 is legitimately one day
/// long. `calendar` is an argument on purpose — `Calendar.current` on an Arabic/Gulf device is the
/// Islamic (Umm al-Qura) region calendar, and a suite that read it would pass in one region and
/// fail in another (the Task 12 lesson).
nonisolated enum WeekBucket {
    /// Hard sanity cap on how far back a bucket can be named (`MAX_WEEKS_BACK`, ~96 years). The
    /// real stop signal is running out of cached items, not this.
    static let maxWeeksBack = 5_000

    /// The week `uploadedAt` falls in, counted back from `now`'s week. `nil` past the cap.
    ///
    /// A future upload (clock skew, a scheduled premiere) clamps to 0 rather than producing a
    /// negative section index — `coerceAtLeast(0)` on Android.
    static func weekIndexOf(_ uploadedAt: Date, now: Date, calendar: Calendar) -> Int? {
        guard let uploadedWeek = calendar.dateInterval(of: .weekOfYear, for: uploadedAt)?.start,
              let currentWeek = calendar.dateInterval(of: .weekOfYear, for: now)?.start,
              let weeks = calendar.dateComponents([.weekOfYear], from: uploadedWeek, to: currentWeek).weekOfYear,
              weeks <= maxWeeksBack
        else { return nil }
        return max(weeks, 0)
    }

    /// 0 -> `me_week_this`, 1 -> `me_week_last`, n -> `me_week_n_ago` (argument = n).
    ///
    /// Returns the key rather than the rendered string so the caller keeps the `String(localized:)`
    /// call — and so the `%1$lld` substitution happens exactly once, at the view.
    static func headerKey(weekIndex: Int) -> (key: String, argument: Int?) {
        switch weekIndex {
        case 0: ("me_week_this", nil)
        case 1: ("me_week_last", nil)
        default: ("me_week_n_ago", weekIndex)
        }
    }
}
