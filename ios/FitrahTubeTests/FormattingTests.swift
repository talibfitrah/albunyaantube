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

    // MARK: - pluralQuantity (RULINGS #3b: clamp >= 1000 -> 1000 so the plural category is `other`)

    @Test func pluralQuantityPassesThroughSmallCounts() {
        #expect(Format.pluralQuantity(5) == 5)
        #expect(Format.pluralQuantity(999) == 999)
    }

    @Test func pluralQuantityClampsAtThreshold() {
        #expect(Format.pluralQuantity(1000) == 1000)
        #expect(Format.pluralQuantity(1_500_000) == 1000)
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
}
