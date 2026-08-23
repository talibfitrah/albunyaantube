import Foundation
import Testing
@testable import FitrahTube

@Suite(.perTest)
struct LocalizationTests {
    private func string(_ key: String, locale: String, _ args: CVarArg...) -> String {
        let loc = Locale(identifier: locale)
        let bundle = Format.localizedBundle(for: loc)
        let format = bundle.localizedString(forKey: key, value: nil, table: nil)
        return String(format: format, locale: loc, arguments: args)
    }

    @Test func englishKeysResolve() {
        #expect(string("app_name", locale: "en") == "FitrahTube")
        // Brief's example key `home_see_all` doesn't exist on Android (strings-assets.md §8a);
        // `see_all` (values/strings.xml:246) is the real "See all" key for the home screen.
        #expect(string("see_all", locale: "en") == "See all")
    }

    @Test func arabicAppNameIsTranslated() {
        #expect(string("app_name", locale: "ar") == "فطرة تيوب")
    }

    @Test func untranslatedKeyFallsBackToEnglish() {
        // `about_version_format` is English-only on Android (strings-assets.md §2). R7 requires
        // the catalog to OMIT the nl localization rather than copy English in as a fake
        // "translated" entry (strings-assets.md:303-313). A single manually-opened nl.lproj
        // bundle has no visibility into sibling locales (verified against the compiled
        // nl.lproj/Localizable.strings, which has no `about_version_format` entry at all) --
        // Foundation's real per-key fallback happens through Bundle.main's own localization
        // negotiation, so that's what production code -- and this test -- must use.
        let nlBundle = Bundle.main.path(forResource: "nl", ofType: "lproj").flatMap(Bundle.init(path:))
        let nlLookup = nlBundle?.localizedString(forKey: "about_version_format", value: nil, table: nil)
        #expect(nlLookup == "about_version_format", "nl catalog must not carry a copied-English entry")

        let format = Bundle.main.localizedString(forKey: "about_version_format", value: nil, table: nil)
        let result = String(format: format, arguments: ["1.0.0", "7"])
        #expect(result.contains("1.0.0"))
    }

    @Test func pluralSelectsCategory() {
        #expect(string("video_count", locale: "en", 1) == "1 video")
        #expect(string("video_count", locale: "en", 3) == "3 videos")
    }

    @Test func retryKeyIsTheAndroidKeyNotTheEnglishSentence() throws {
        // StateViews.swift must look up "retry" (the Android key), not "Retry" (the English
        // sentence) -- verified against the compiled Arabic catalog directly (not the source
        // .xcstrings JSON), so a regression back to raw English text is caught.
        let arabicRetry = try #require(
            Bundle.main.path(forResource: "ar", ofType: "lproj")
                .flatMap(Bundle.init(path:))?
                .localizedString(forKey: "retry", value: nil, table: nil)
        )
        #expect(!arabicRetry.isEmpty)
        #expect(arabicRetry != "Retry")
    }
}
