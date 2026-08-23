import Foundation

/// Text formatting rules ported verbatim from the Android adapters (content-lists.md:462-491,
/// strings-assets.md:147-182). `nonisolated` because the app target defaults to `MainActor`
/// isolation but every function here is a pure, thread-safe transform.
nonisolated enum Format {

    /// `h:mm:ss` when there are hours, else `m:ss` -- always Western digits
    /// (VideoGridAdapter.kt:89-98: `Locale.US`, never zero-padded hours/minutes leading digit).
    static func duration(_ seconds: Int) -> String {
        let posix = Locale(identifier: "en_US_POSIX")
        let h = seconds / 3600
        let m = (seconds % 3600) / 60
        let s = seconds % 60
        return h > 0
            ? String(format: "%d:%02d:%02d", locale: posix, h, m, s)
            : String(format: "%d:%02d", locale: posix, m, s)
    }

    /// `< 1000` renders plainly; `>= 1000` uses compact K/M/B notation with at most one fraction
    /// digit, dropped when it's `.0` (matches ICU `CompactDecimalFormat` SHORT --
    /// util/CountFormat.kt:40-46). Digits follow `locale` (Eastern Arabic-Indic for `ar`, etc).
    static func compactCount(_ n: Int64, locale: Locale) -> String {
        guard n >= 1000 else {
            return n.formatted(.number.locale(locale))
        }
        return n.formatted(
            .number.locale(locale).notation(.compactName).precision(.fractionLength(0...1))
        )
    }

    /// Clamps the plural-selector quantity so any compacted magnitude >= 1000 resolves to the
    /// CLDR `other` category everywhere, matching Android's `compactPluralCount`
    /// (util/CountFormat.kt:58) -- Arabic counted-noun agreement follows the unit word once the
    /// number is abbreviated, not the raw count's one/two/few form.
    static func pluralQuantity(_ n: Int64) -> Int {
        n >= 1000 ? 1000 : Int(n)
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
        return String(format: format, locale: locale, arguments: [Int64(quantity)])
    }

    /// `localizedNames[lang] ?? name`; `lang` falls back to `"en"` when `locale` has none.
    static func categoryDisplayName(_ category: Category, locale: Locale) -> String {
        let lang = locale.language.languageCode?.identifier ?? "en"
        return category.localizedNames?[lang] ?? category.name
    }

    /// The compiled catalog only answers `Bundle.main.localizedString` for the *device's*
    /// preferred language; a caller-supplied `locale` must resolve its own `.lproj` sub-bundle
    /// (same technique as LocalizationTests.swift) so Arabic/Dutch counts render correctly
    /// regardless of the simulator's system language.
    private static func localizedBundle(for locale: Locale) -> Bundle {
        let lang = locale.language.languageCode?.identifier ?? "en"
        return Bundle.main.path(forResource: lang, ofType: "lproj").flatMap(Bundle.init(path:)) ?? .main
    }
}
