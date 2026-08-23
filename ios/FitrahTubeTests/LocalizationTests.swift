import Foundation
import Testing
@testable import FitrahTube

@Suite(.perTest)
struct LocalizationTests {
    private func string(_ key: String, locale: String, _ args: CVarArg...) -> String {
        let bundle = Bundle.main.path(forResource: locale, ofType: "lproj").flatMap(Bundle.init(path:)) ?? .main
        let format = bundle.localizedString(forKey: key, value: nil, table: nil)
        return String(format: format, locale: Locale(identifier: locale), arguments: args)
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
        // sentence) -- verified against the catalog's own Arabic value, not a hard-coded string.
        // Bundle.main has no raw .xcstrings resource -- Xcode compiles the catalog into per-locale
        // .strings/.stringsdict at build time -- so the source JSON is read directly from disk.
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("FitrahTube/Resources/Localizable.xcstrings")
        let data = try Data(contentsOf: url)
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let strings = json?["strings"] as? [String: Any]
        let retry = strings?["retry"] as? [String: Any]
        let localizations = retry?["localizations"] as? [String: Any]
        let arLocalization = localizations?["ar"] as? [String: Any]
        let stringUnit = arLocalization?["stringUnit"] as? [String: Any]
        let arabicRetry = try #require(stringUnit?["value"] as? String)

        #expect(string("retry", locale: "ar") == arabicRetry)
        #expect(string("retry", locale: "ar") != "Retry")
    }
}
