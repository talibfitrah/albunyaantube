import Foundation
import Testing
@testable import FitrahTube

@Suite(.perTest)
struct FormattingTests {

    // MARK: - duration (content-lists.md:466: Locale.US, h:mm:ss / m:ss, not zero-padded hours)

    @Test func durationUnderAMinute() {
        #expect(Format.duration(7) == "0:07")
    }

    @Test func durationUnderAnHour() {
        #expect(Format.duration(725) == "12:05")
    }

    @Test func durationOverAnHour() {
        #expect(Format.duration(3723) == "1:02:03")
    }

    @Test func durationBoundaries() {
        #expect(Format.duration(59) == "0:59")
        #expect(Format.duration(3600) == "1:00:00")
    }

    @Test func durationClampsNegativeToZero() {
        #expect(Format.duration(-7) == "0:00")
    }

    // MARK: - compactCount (strings-assets.md:172-176: CompactDecimalFormat SHORT, 1 fraction digit, drop .0)

    @Test func compactCountBelowThousandIsPlain() {
        #expect(Format.compactCount(999, locale: Locale(identifier: "en_US")) == "999")
    }

    @Test func compactCountThousands() {
        #expect(Format.compactCount(1200, locale: Locale(identifier: "en_US")) == "1.2K")
    }

    @Test func compactCountMillions() {
        #expect(Format.compactCount(1_500_000, locale: Locale(identifier: "en_US")) == "1.5M")
    }

    @Test func compactCountBillionsDropsWholeFraction() {
        #expect(Format.compactCount(2_000_000_000, locale: Locale(identifier: "en_US")) == "2B")
    }

    @Test func compactCountUsesLocaleDigits() {
        // Arabic-Indic digits below the compact threshold (RULINGS: locale-aware digits for counts).
        #expect(Format.compactCount(500, locale: Locale(identifier: "ar_EG")) == "\u{0665}\u{0660}\u{0660}")
    }

    @Test func compactCountArabicMoroccoUsesWesternDigits() {
        // Unlike ar_EG, CLDR's ar_MA numbering system is Western digits (`NumberFormat(locale)`
        // behaviour Android relies on too -- ChannelAdapter.kt:60-69, CountFormat.kt:31-47 --
        // per-locale, not "Arabic == Eastern digits"). Exact output captured via a standalone
        // `swift` run of the same `.formatted()` call before asserting it here.
        let result = Format.compactCount(1200, locale: Locale(identifier: "ar_MA"))
        #expect(result == "1,2\u{00A0}ألف")
        #expect(result.contains("1"))
        #expect(!result.contains("\u{0661}"))
    }

    @Test func compactCountAtThousandBoundary() {
        #expect(Format.compactCount(1000, locale: Locale(identifier: "en_US")) == "1K")
    }

    // MARK: - pluralQuantity (CountFormat.kt:58: `if (count >= 1_000L) 1_000_000L else count`)

    @Test func pluralQuantityPassesThroughBelowOneThousand() {
        #expect(Format.pluralQuantity(5) == 5)
        #expect(Format.pluralQuantity(999) == 999)
    }

    @Test func pluralQuantityClampsAtAndAboveOneThousand() {
        #expect(Format.pluralQuantity(1000) == 1_000_000)
        #expect(Format.pluralQuantity(5000) == 1_000_000)
        #expect(Format.pluralQuantity(1_000_000) == 1_000_000)
    }

    // MARK: - timeAgo (content-lists.md:466-475: one ladder everywhere, integer division, no rounding)

    @Test func timeAgoTodayEnglish() {
        #expect(Format.timeAgo(days: 0, locale: Locale(identifier: "en")) == "Today")
    }

    @Test func timeAgoNegativeDaysIsAlsoToday() {
        #expect(Format.timeAgo(days: -1, locale: Locale(identifier: "en")) == "Today")
    }

    @Test func timeAgoTodayIsLocalized() {
        #expect(Format.timeAgo(days: 0, locale: Locale(identifier: "nl")) == "Vandaag")
        #expect(Format.timeAgo(days: 0, locale: Locale(identifier: "ar")) == "اليوم")
    }

    @Test func timeAgoDaysSingularAndPlural() {
        #expect(Format.timeAgo(days: 1, locale: Locale(identifier: "en")) == "1 day ago")
        #expect(Format.timeAgo(days: 6, locale: Locale(identifier: "en")) == "6 days ago")
    }

    @Test func timeAgoWeeksBoundaryAndFloorDivision() {
        #expect(Format.timeAgo(days: 7, locale: Locale(identifier: "en")) == "1 week ago")
        #expect(Format.timeAgo(days: 29, locale: Locale(identifier: "en")) == "4 weeks ago")
    }

    @Test func timeAgoMonthsBoundaryAndFloorDivision() {
        #expect(Format.timeAgo(days: 30, locale: Locale(identifier: "en")) == "1 month ago")
        #expect(Format.timeAgo(days: 364, locale: Locale(identifier: "en")) == "12 months ago")
    }

    @Test func timeAgoYearsBoundary() {
        #expect(Format.timeAgo(days: 365, locale: Locale(identifier: "en")) == "1 year ago")
        #expect(Format.timeAgo(days: 800, locale: Locale(identifier: "en")) == "2 years ago")
    }

    // MARK: - categoryDisplayName

    @Test func categoryDisplayNameUsesLocalizedEntry() {
        let category = Category(
            id: "1", name: "Quran", slug: "quran", parentId: nil,
            localizedNames: ["en": "Quran", "ar": "قرآن"]
        )
        #expect(Format.categoryDisplayName(category, locale: Locale(identifier: "ar")) == "قرآن")
    }

    @Test func categoryDisplayNameFallsBackToNameWhenLocaleMissing() {
        let category = Category(
            id: "1", name: "Quran", slug: "quran", parentId: nil,
            localizedNames: ["en": "Quran", "ar": "قرآن"]
        )
        #expect(Format.categoryDisplayName(category, locale: Locale(identifier: "nl")) == "Quran")
    }

    @Test func categoryDisplayNameFallsBackToNameWhenNil() {
        let category = Category(id: "1", name: "Quran", slug: "quran", parentId: nil)
        #expect(Format.categoryDisplayName(category, locale: Locale(identifier: "en")) == "Quran")
    }

    // MARK: - Section title / See-all label (moved off `HomeViewModel.seeAllLabel`, gate wave-2
    // W8/W9: one implementation for Home and Featured, resolved against the passed-in locale
    // rather than `Locale.current`)

    @Test func sectionDisplayNamePrefersTheLocalizedName() {
        let section = HomeSection(id: "1", name: "Quran", localizedNames: ["en": "Quran", "ar": "قرآن"], icon: nil, items: [])
        #expect(Format.sectionDisplayName(section, locale: Locale(identifier: "ar")) == "قرآن")
        #expect(Format.sectionDisplayName(section, locale: Locale(identifier: "nl")) == "Quran") // no nl entry
    }

    @Test func sectionSeeAllLabelContainsTheDisplayName() {
        let section = HomeSection(id: "1", name: "Quran", localizedNames: ["en": "Quran", "ar": "قرآن"], icon: nil, items: [])
        #expect(Format.sectionSeeAllLabel(section, locale: Locale(identifier: "en")).contains("Quran"))
        #expect(Format.sectionSeeAllLabel(section, locale: Locale(identifier: "ar")).contains("قرآن"))
    }
}
