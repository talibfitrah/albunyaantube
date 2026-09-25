import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

@Suite(.perTest)
struct FormattingTests {

    // MARK: - duration (content-lists.md:466: Locale.US, h:mm:ss / m:ss, not zero-padded hours)

    /// Android parity (`CountFormat.kt`: "ar: ١٫٢ ألف"): Arabic is Arabic-Indic whatever the region.
    /// Plain `ar`/`ar_US`/`ar_NL` default to Latin digits in CLDR, so an Arabic user outside the
    /// regions that default to `arab` got "217 ألف" beside Home's "٤٫٤ مليون" from another device.
    @Test(arguments: ["ar", "ar_US", "ar_NL"])
    func arabicNumbersAreArabicIndicInEveryRegion(_ id: String) {
        let locale = Locale(identifier: id)
        let rendered = [
            Format.compactCount(217_000, locale: locale),
            Format.compactCount(12, locale: locale),
            Format.timeAgo(days: 13 * 365, locale: locale),
            Format.localizedFormat("playlist_metadata_format", locale: locale, Int64(200)),
            Format.number(7, locale: locale),
            OfflineStorage.byteText(52_428_800, locale: locale),
            SubmitContentModel.wait(7_200, locale: locale),
        ]
        for text in rendered {
            #expect(!text.contains { $0.isASCII && $0.isNumber }, "\(id): \(text)")
        }
    }

    @Test func otherLanguagesKeepTheirOwnDigits() {
        #expect(Format.compactCount(217_000, locale: Locale(identifier: "nl_NL")) == "217K")
        #expect(Format.number(7, locale: Locale(identifier: "en_US")) == "7")
    }

    /// Sub-day ages (Android shows NewPipe's "2 hours ago", `ChannelVideoAdapter.kt:60`): minutes,
    /// then hours, then the day ladder, with Arabic's two/few/many plurals.
    @Test func subDayAgesKeepTheirMinutesAndHours() {
        let en = Locale(identifier: "en"), ar = Locale(identifier: "ar"), nl = Locale(identifier: "nl")
        #expect(Format.timeAgo(minutes: 5, locale: en) == "5 minutes ago")
        #expect(Format.timeAgo(minutes: 1, locale: en) == "1 minute ago")
        #expect(Format.timeAgo(minutes: 120, locale: en) == "2 hours ago")
        #expect(Format.timeAgo(minutes: 120, locale: ar) == "منذ ساعتين")
        #expect(Format.timeAgo(minutes: 60, locale: ar) == "منذ ساعة")
        #expect(Format.timeAgo(minutes: 5 * 60, locale: ar) == "منذ ٥ ساعات")
        #expect(Format.timeAgo(minutes: 2, locale: ar) == "منذ دقيقتين")
        #expect(Format.timeAgo(minutes: 180, locale: nl) == "3 uur geleden")
        #expect(Format.timeAgo(minutes: 0, locale: en) == "1 minute ago")
        #expect(Format.timeAgo(minutes: 3 * 1440, locale: en) == Format.timeAgo(days: 3, locale: en))
    }

    /// A browse row's minutes win; a backend row keeps its days; no age at all is no segment,
    /// never "Today" (the backend sends null for an unknown upload date).
    @Test func anItemsAgeUsesTheFinestItHasAndNothingWhenUnknown() {
        func item(days: Int?, minutes: Int?) -> ContentItem {
            ContentItem(id: "v", type: .video, title: "t", category: nil, description: nil, thumbnailURL: nil,
                        durationSeconds: nil, uploadedDaysAgo: days, viewCount: nil, channelTitle: nil,
                        subscribers: nil, videoCount: nil, itemCount: nil, uploadedMinutesAgo: minutes)
        }
        let en = Locale(identifier: "en")
        #expect(Format.age(of: item(days: 0, minutes: 120), locale: en) == "2 hours ago")
        #expect(Format.age(of: item(days: 0, minutes: nil), locale: en) == Format.timeAgo(days: 0, locale: en))
        #expect(Format.age(of: item(days: nil, minutes: nil), locale: en) == nil)
    }

    /// Android's `UploadAge.fromEnglish` rule, per segment: the localized number when YouTube's
    /// English text parses, else that text as it was. A number is never invented.
    @Test func unparsedStatsFallBackToYouTubesTextNeverAnInventedNumber() {
        let en = Locale(identifier: "en")
        let live = ContentItem(video: VideoItem(id: "v", title: "t", viewCountText: "1.2K watching",
                                                publishedText: "Scheduled for 10/1/26"))
        #expect(live.viewCount == nil)
        #expect(Format.views(of: live, locale: en) == "1.2K watching")
        #expect(Format.age(of: live, locale: en) == "Scheduled for 10/1/26")
        let parsed = ContentItem(video: VideoItem(id: "v", title: "t", viewCountText: "38K views", publishedText: "2 hours ago"))
        #expect(Format.views(of: parsed, locale: Locale(identifier: "ar")) == Format.localizedFormat(
            "video_views", locale: Locale(identifier: "ar"), Format.compactCount(38_000, locale: Locale(identifier: "ar")), Int64(1_000_000)))
        #expect(Format.age(of: parsed, locale: en) == "2 hours ago")
    }

    @Test func aPlaylistTilesUnparsedCountFallsBackToItsText() {
        let en = Locale(identifier: "en")
        #expect(Format.itemCount(of: ContentItem(tile: PlaylistTile(id: "p", title: "t", itemCountText: "12 episodes")), locale: en)
                == "12 episodes")
        #expect(Format.itemCount(of: ContentItem(tile: PlaylistTile(id: "p", title: "t", itemCountText: "99 videos")), locale: en)
                == Format.localizedFormat("playlist_item_count", locale: en, Int64(99)))
        #expect(Format.itemCount(of: ContentItem(tile: PlaylistTile(id: "p", title: "t")), locale: en) == nil)
    }

    /// Android `UploadAge.fromEnglish` parity: YouTube's English live/upcoming/playlist stats render in
    /// the app's language, prefixes ("Streamed", "Premiered") kept; the date stays YouTube's own text.
    @Test func liveUpcomingAndPlaylistStatsRenderInTheAppLanguage() {
        let en = Locale(identifier: "en"), ar = Locale(identifier: "ar"), nl = Locale(identifier: "nl")
        func views(_ text: String, _ locale: Locale) -> String? {
            Format.views(of: ContentItem(video: VideoItem(id: "v", title: "t", viewCountText: text)), locale: locale)
        }
        func age(_ text: String, _ locale: Locale) -> String? {
            Format.age(of: ContentItem(video: VideoItem(id: "v", title: "t", publishedText: text)), locale: locale)
        }
        func episodes(_ text: String, _ locale: Locale) -> String? {
            Format.itemCount(of: ContentItem(tile: PlaylistTile(id: "p", title: "t", itemCountText: text)), locale: locale)
        }
        #expect(views("1.2K watching", nl) == "1,2K kijken")
        #expect(views("12 waiting", nl) == "12 wachten")
        #expect(views("Scheduled for 10/1/26", nl) == "Gepland voor 10/1/26")
        #expect(episodes("12 episodes", nl) == "12 afleveringen")
        #expect(age("Streamed 3 days ago", nl) == "Gestreamd 3 dagen geleden")
        #expect(age("Premiered 2 days ago", nl) == "Première was 2 dagen geleden")
        #expect(age("Scheduled for 10/1/26", nl) == "Gepland voor 10/1/26")
        #expect(age("Premieres 10/1/26, 8:00 PM", nl) == "Première op 10/1/26, 8:00 PM")
        #expect(age("Premieres in 3 hours", nl) == "Première over 3 uur")
        // Foundation isolates each RTL argument (U+2068/U+2069), which keeps "10/1/26" left-to-right.
        func plain(_ s: String?) -> String? { s?.filter { $0 != "\u{2068}" && $0 != "\u{2069}" } }
        #expect(plain(age("Streamed 2 days ago", ar)) == "بُثّ منذ يومين")
        #expect(episodes("2 episodes", ar) == "حلقتان")
        #expect(age("Premieres in 2 hours", ar) == "العرض الأول بعد ساعتين")
        #expect(plain(age("Scheduled for 10/1/26", ar)) == "مجدول في 10/1/26")
        #expect(age("Streamed 3 days ago", en) == "Streamed 3 days ago")
        #expect(age("Premiered 2 hours ago", en) == "Premiered 2 hours ago")
        // Not a pattern we can read: YouTube's own text, nothing invented.
        #expect(age("Scheduled for tomorrow", en) == "Scheduled for tomorrow")
        #expect(age("Premieres in 2 weeks", en) == "Premieres in 2 weeks")
        #expect(age("Streamed live", en) == "Streamed live")
    }

    /// VoiceOver: a playlist whose count did not parse still reads with the locale's own separator.
    @Test func aPlaylistsAccessibilityLabelIsLocalizedEvenWithoutACount() {
        let ar = Locale(identifier: "ar")
        let raw = ContentItem(tile: PlaylistTile(id: "p", title: "t", itemCountText: "Mix"))
        // Foundation wraps each RTL argument in bidi isolates (U+2068/U+2069); the words and separator are the point.
        #expect(Format.playlistAccessibilityLabel(raw, locale: ar).filter { $0 != "\u{2068}" && $0 != "\u{2069}" }
                == "قائمة تشغيل: t، Mix")
        let counted = ContentItem(tile: PlaylistTile(id: "p", title: "t", itemCountText: "99 videos"))
        #expect(Format.playlistAccessibilityLabel(counted, locale: ar)
                == Format.localizedFormat("a11y_playlist_item", locale: ar, "t", Int64(99)))
        #expect(Format.playlistAccessibilityLabel(ContentItem(tile: PlaylistTile(id: "p", title: "t")), locale: ar) == "t")
    }

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

    @Test func compactCountArabicMoroccoIsArabicIndicLikeAndroid() {
        // Android never formats with a region: `LocaleManager.applyLocale` sets the bare language
        // tag `SettingsPreferences.getSystemLocale()` resolves ("ar"), and ICU's plain `ar` is
        // Arabic-Indic (`CountFormat.kt`: "ar: ١٫٢ ألف"). So an ar_MA device shows ١٫٢ there, and
        // `Format.numberLocale` does the same here (this test used to pin CLDR's ar_MA Latin digits).
        let result = Format.compactCount(1200, locale: Locale(identifier: "ar_MA"))
        #expect(result.contains("\u{0661}"))
        #expect(!result.contains { $0.isASCII && $0.isNumber })
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
    // MARK: - videoAccessibilityLabel (C T5 fix I1: nil segments are omitted, never spoken empty)

    private func item(duration: Int? = nil, views: Int64? = nil, days: Int? = nil, channel: String? = nil) -> ContentItem {
        ContentItem(id: "v1", type: .video, title: "Tafsir 1", category: nil, description: nil, thumbnailURL: nil,
                    durationSeconds: duration, uploadedDaysAgo: days, viewCount: views, channelTitle: channel,
                    subscribers: nil, videoCount: nil, itemCount: nil)
    }

    @Test func videoLabelOmitsEverySegmentWithNoValue() {
        let label = videoAccessibilityLabel(item(), locale: Locale(identifier: "en_US"))
        #expect(label == "Tafsir 1")
        #expect(!label.contains(", ,"))
        #expect(!label.contains("Duration:"))
    }

    @Test func videoLabelJoinsPresentSegments() {
        let label = videoAccessibilityLabel(item(duration: 725, views: 1200, days: 3, channel: "Alafasy"),
                                            locale: Locale(identifier: "en_US"))
        #expect(label == "Tafsir 1, Duration: 12:05, 1.2K views, 3 days ago, Alafasy")
    }

    @Test func videoLabelLeadsWithPlaylistPosition() {
        let label = videoAccessibilityLabel(item(channel: "Alafasy"), locale: Locale(identifier: "en_US"), position: 3)
        #expect(label == "Position 3, Tafsir 1, Alafasy")
    }
}
