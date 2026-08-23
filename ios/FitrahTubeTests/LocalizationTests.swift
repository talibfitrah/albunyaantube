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

    /// Foundation has **no per-key fallback**: `Bundle.localizedString(forKey:)` resolves inside
    /// exactly one `.lproj`, and a key missing from a `.lproj` that otherwise exists comes back as
    /// the bare key -- which is what rendered on screen during the Task 13 `-AppleLanguages (ar)`
    /// run. R7 as amended (strings-assets.md:303-313) therefore makes the converter emit every key
    /// into every locale (English under `needs_review` where Android has no translation), and this
    /// is the regression net for that: every key in the compiled catalog resolves to something
    /// other than itself under both `ar` and `nl`.
    @Test func everyCatalogKeyResolvesInArabicAndDutch() throws {
        let keys = try Self.compiledKeys(locale: "en")
        #expect(keys.count > 700, "expected the whole catalog, got \(keys.count) keys")

        for locale in ["ar", "nl"] {
            let bundle = try Self.lproj(locale)
            let unresolved = keys.filter { bundle.localizedString(forKey: $0, value: nil, table: nil) == $0 }.sorted()
            #expect(unresolved.isEmpty, "\(locale) renders the raw key for: \(unresolved.prefix(10))")
        }
    }

    /// The specific key the Task 13 review caught: English-only on Android, so both locales must
    /// now carry the English value rather than nothing.
    @Test func englishOnlyKeyCarriesEnglishInEveryLocale() throws {
        for locale in ["ar", "nl"] {
            let format = try Self.lproj(locale).localizedString(forKey: "about_version_format", value: nil, table: nil)
            #expect(format == "Version %1$@ (%2$@)")
            #expect(String(format: format, arguments: ["1.0.0", "7"]) == "Version 1.0.0 (7)")
        }
    }

    private static func lproj(_ locale: String) throws -> Bundle {
        try #require(Bundle.main.path(forResource: locale, ofType: "lproj").flatMap(Bundle.init(path:)))
    }

    /// Every key the String Catalog compiled for `locale`, read straight off the built `.app` --
    /// the source `.xcstrings` JSON isn't in the test bundle, and the compiled table is the
    /// stricter thing to assert against anyway. Pure-plural keys live in the sibling
    /// `.stringsdict` and are out of scope here (the bug was in the plain-string table).
    private static func compiledKeys(locale: String) throws -> [String] {
        let url = try #require(lproj(locale).url(forResource: "Localizable", withExtension: "strings"))
        let table = try #require(NSDictionary(contentsOf: url) as? [String: String])
        return Array(table.keys)
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
