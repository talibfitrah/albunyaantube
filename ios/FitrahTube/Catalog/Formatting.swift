import Foundation
import InnerTubeKit

/// Text formatting rules ported verbatim from the Android adapters (content-lists.md:462-491,
/// strings-assets.md:147-182). `nonisolated` because the app target defaults to `MainActor`
/// isolation but every function here is a pure, thread-safe transform.
nonisolated enum Format {
    private static let posix = Locale(identifier: "en_US_POSIX")

    /// The ONE place a count's digits are chosen: Arabic is Arabic-Indic in every region, as on
    /// Android (`CountFormat.kt`, "ar: ١٫٢ ألف"). CLDR gives plain `ar`/`ar_US`/`ar_NL` Latin digits,
    /// so without this an Arabic user's digits depended on their region. Every count below uses it,
    /// and so do the two outside this file (`OfflineStorage.byteText`, `SubmitContentModel.wait`).
    /// Calendar dates (profile date of birth) keep the system date formatter's own digits.
    static func numberLocale(_ locale: Locale) -> Locale {
        guard locale.language.languageCode?.identifier == "ar" else { return locale }
        var components = Locale.Components(locale: locale)
        components.numberingSystem = "arab"
        return Locale(components: components)
    }

    /// `h:mm:ss` when there are hours, else `m:ss` -- always Western digits
    /// (VideoGridAdapter.kt:89-98: `Locale.US`, never zero-padded hours/minutes leading digit).
    /// Negative input (shouldn't happen, but a bad duration from the API is not a crash) clamps to 0.
    static func duration(_ seconds: Int) -> String {
        let seconds = max(0, seconds)
        let h = seconds / 3600
        let m = (seconds % 3600) / 60
        let s = seconds % 60
        // %lld, not %d: `String(format:)` reads a Swift `Int` (64-bit) through a 32-bit %d
        // conversion. Correct for any real duration, garbage above 2^31 (gate A-M16).
        return h > 0
            ? String(format: "%lld:%02lld:%02lld", locale: posix, Int64(h), Int64(m), Int64(s))
            : String(format: "%lld:%02lld", locale: posix, Int64(m), Int64(s))
    }

    /// `< 1000` renders plainly; `>= 1000` uses compact K/M/B notation with at most one fraction
    /// digit, dropped when it's `.0` (matches ICU `CompactDecimalFormat` SHORT --
    /// util/CountFormat.kt:40-46). Digits follow `locale` (Eastern Arabic-Indic for `ar`, etc).
    static func compactCount(_ n: Int64, locale: Locale) -> String {
        let locale = numberLocale(locale)
        // `abs(n)`, not `n`: -5000 rendered as "-5,000" rather than "-5K" (gate A-M16). Backend
        // counts are non-negative, so this is exactness, not a live bug.
        guard n.magnitude >= 1000 else {
            return n.formatted(.number.locale(locale))
        }
        return n.formatted(
            .number.locale(locale).notation(.compactName).precision(.fractionLength(0...1))
        )
    }

    /// A plain integer in `locale`'s digits (the playlist row numeral).
    static func number(_ n: Int64, locale: Locale) -> String {
        n.formatted(.number.locale(numberLocale(locale)))
    }

    /// Clamps the plural-selector quantity so any compacted magnitude (`compactCount` abbreviates
    /// starting at 1,000) resolves to the CLDR `other` category everywhere, matching Android's
    /// `compactPluralCount` verbatim (util/CountFormat.kt:58: `if (count >= 1_000L) 1_000_000L
    /// else count`) -- Arabic counted-noun agreement follows the unit word once the number is
    /// abbreviated, not the raw count's one/two/few form.
    static func pluralQuantity(_ n: Int64) -> Int {
        n >= 1_000 ? 1_000_000 : Int(n)
    }

    /// An item's age line, or nil when its age is unknown (never "Today" for nothing): a browse
    /// row's minutes when it has them, else the backend's days.
    static func age(of item: ContentItem, locale: Locale) -> String? {
        if let stat = englishStat(item.ageText, locale: locale) { return stat }
        if let minutes = item.uploadedMinutesAgo { return timeAgo(minutes: minutes, locale: locale) }
        return item.uploadedDaysAgo.map { timeAgo(days: $0, locale: locale) } ?? item.ageText
    }

    /// "38K views" in the app's language, else a live/upcoming stat ("1.2K watching"), else YouTube's own text.
    static func views(of item: ContentItem, locale: Locale) -> String? {
        guard let views = item.viewCount else { return englishStat(item.viewsText, locale: locale) ?? item.viewsText }
        return localizedFormat("video_views", locale: locale, compactCount(views, locale: locale),
                               Int64(pluralQuantity(views)))
    }

    /// "12 items" in the app's language, else "12 episodes", else the tile's own unparsed text.
    static func itemCount(of item: ContentItem, locale: Locale) -> String? {
        guard let count = item.itemCount else { return englishStat(item.itemCountText, locale: locale) ?? item.itemCountText }
        return localizedFormat("playlist_item_count", locale: locale, Int64(count))
    }

    /// YouTube's English live/upcoming/playlist stat in the app's language, or nil when `text` is none
    /// of them. The date of "Scheduled for 10/1/26" stays YouTube's text: M/D vs D/M is not provable
    /// from it (NewPipe itself parses premiere dates as dd/MM). Same rule as Android `UploadAge.stat`.
    static func englishStat(_ text: String?, locale: Locale) -> String? {
        guard let text else { return nil }
        for (unit, key) in [("watching", "live_watching_count"), ("waiting", "live_waiting_count")] {
            if let n = EnglishCounts.count(text, unit: unit) {
                return localizedFormat(key, locale: locale, compactCount(n, locale: locale), Int64(pluralQuantity(n)))
            }
        }
        if let n = EnglishCounts.count(text, unit: "episode") { return localizedFormat("playlist_episode_count", locale: locale, n) }
        if let m = text.wholeMatch(of: /(Streamed|Premiered) ([0-9]+ [a-z]+ ago)/) {
            guard let minutes = EnglishCounts.minutesAgo(String(m.2)) else { return nil }
            return localizedFormat(m.1 == "Streamed" ? "live_streamed_ago" : "live_premiered_ago", locale: locale,
                                   timeAgo(minutes: minutes, locale: locale))
        }
        if let m = text.wholeMatch(of: /Premieres in ([0-9]+) (minute|hour|day)s?/), let n = Int64(m.1) {
            let key = switch m.2 {
            case "minute": "live_premieres_in_minutes"; case "hour": "live_premieres_in_hours"; default: "live_premieres_in_days"
            }
            return localizedFormat(key, locale: locale, n)
        }
        if let m = text.wholeMatch(of: /(Scheduled for|Premieres) ([0-9]{1,2}\/[0-9]{1,2}\/[0-9]{2,4}(?:, [0-9]{1,2}:[0-9]{2}(?: [AP]M)?)?)/) {
            return localizedFormat(m.1 == "Premieres" ? "live_premieres_on" : "live_scheduled_for", locale: locale, String(m.2))
        }
        return nil
    }

    /// VoiceOver label for a playlist: "Playlist: t, 12 items", or with the tile's own count text when
    /// it did not parse to videos -- joined by the locale's own separator, never a hard-coded ", ".
    static func playlistAccessibilityLabel(_ item: ContentItem, locale: Locale) -> String {
        if let count = item.itemCount { return localizedFormat("a11y_playlist_item", locale: locale, item.title, Int64(count)) }
        return itemCount(of: item, locale: locale).map { localizedFormat("a11y_playlist_item_text", locale: locale, item.title, $0) }
            ?? item.title
    }

    /// N minutes / N hours below a day (what Android shows from NewPipe's "2 hours ago"), then the
    /// day ladder. A just-published row reads "1 minute ago", not "0 minutes ago".
    static func timeAgo(minutes: Int, locale: Locale) -> String {
        guard minutes < 1440 else { return timeAgo(days: minutes / 1440, locale: locale) }
        let (key, quantity) = minutes < 60 ? ("time_ago_minutes", max(1, minutes)) : ("time_ago_hours", minutes / 60)
        let format = localizedBundle(for: locale).localizedString(forKey: key, value: nil, table: nil)
        return String(format: format, locale: numberLocale(locale), arguments: [Int64(quantity)])
    }

    /// Today / N days / N weeks / N months / N years, one ladder everywhere (RULINGS.md #4).
    /// Integer division, no rounding (content-lists.md:466-475).
    static func timeAgo(days: Int, locale: Locale) -> String {
        let bundle = localizedBundle(for: locale)
        guard days > 0 else {
            return bundle.localizedString(forKey: "video_uploaded_today", value: nil, table: nil)
        }
        let key: String
        let quantity: Int
        switch days {
        case ..<7:
            key = "video_uploaded_days_ago"
            quantity = days
        case ..<30:
            key = "time_ago_weeks"
            quantity = days / 7
        case ..<365:
            key = "time_ago_months"
            quantity = days / 30
        default:
            key = "time_ago_years"
            quantity = days / 365
        }
        let format = bundle.localizedString(forKey: key, value: nil, table: nil)
        return String(format: format, locale: numberLocale(locale), arguments: [Int64(quantity)])
    }

    /// `localizedNames[lang] ?? name`; `lang` falls back to `"en"` when `locale` has none.
    static func categoryDisplayName(_ category: Category, locale: Locale) -> String {
        let lang = locale.language.languageCode?.identifier ?? "en"
        return category.localizedNames?[lang] ?? category.name
    }

    /// The same rule for a Home/Featured section title (gate wave-2 W9: `HomeView` and
    /// `FeaturedView` each carried their own copy).
    static func sectionDisplayName(_ section: HomeSection, locale: Locale) -> String {
        let lang = locale.language.languageCode?.identifier ?? "en"
        return section.localizedNames?[lang] ?? section.name
    }

    /// shell-home.md:207 -- VoiceOver label for a section's See-all control ("See all content in
    /// {displayName}"), built from the same untruncated display name as the visible title. Routed
    /// through `localizedFormat` like every other formatted string, so it resolves against the
    /// app/environment locale; `HomeViewModel.seeAllLabel` used `Locale.current` and would have
    /// produced a mixed-language label under an in-app locale override (gate wave-2 W8).
    static func sectionSeeAllLabel(_ section: HomeSection, locale: Locale) -> String {
        localizedFormat("home_see_all_category", locale: locale, sectionDisplayName(section, locale: locale))
    }

    /// Built once from the app's three shipped languages -- a `static let` of a `Bundle`-valued
    /// dictionary needs no `nonisolated(unsafe)` because it's computed a single time and only
    /// ever read afterward.
    private static let languageBundles: [String: Bundle] = Dictionary(
        uniqueKeysWithValues: ["en", "ar", "nl"].compactMap { lang in
            Bundle.main.path(forResource: lang, ofType: "lproj")
                .flatMap(Bundle.init(path:))
                .map { (lang, $0) }
        }
    )

    /// The compiled catalog only answers `Bundle.main.localizedString` for the *device's*
    /// preferred language; a caller-supplied `locale` must resolve its own `.lproj` sub-bundle
    /// so Arabic/Dutch counts render correctly regardless of the simulator's system language.
    /// `internal` (not `private`) so LocalizationTests.swift shares this instead of its own copy.
    static func localizedBundle(for locale: Locale) -> Bundle {
        let lang = locale.language.languageCode?.identifier ?? "en"
        return languageBundles[lang] ?? .main
    }

    /// Looks `key` up in `locale`'s own `.lproj` bundle (not `Bundle.main`, which only answers for
    /// the *device's* preferred language) and formats it there, so `%1$@` substitutions and
    /// `.xcstrings` plural/substitution variants resolve for the requested language regardless of
    /// the simulator's system language.
    ///
    /// One copy (gate s1-1): this was duplicated byte-for-byte as a `private func` in seven files.
    /// A drift between copies would have silently broken substitution on one screen only.
    static func localizedFormat(_ key: String, locale: Locale, _ args: CVarArg...) -> String {
        let format = localizedBundle(for: locale).localizedString(forKey: key, value: nil, table: nil)
        return String(format: format, locale: numberLocale(locale), arguments: args)
    }
}
