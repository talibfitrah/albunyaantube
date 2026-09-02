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

    /// The local-network prompt is the app's one Info.plist usage description, and iOS shows it on
    /// the first cast-button tap — an English sentence in front of an Arabic or Dutch user, while
    /// every other user-facing string is en/ar/nl. It lives in `Resources/InfoPlist.xcstrings`,
    /// which compiles to a per-locale `InfoPlist.strings` table (NOT `Localizable`), and the
    /// English value is the one `project.yml` writes into the plist. Each translation has to name
    /// TV playback — the prompt is asking for the local network, so "why" is the whole point — and
    /// obeys the copy rule: never "Download", never "ad-free".
    @Test func theLocalNetworkPromptIsLocalizedAndNamesTVPlayback() throws {
        let key = "NSLocalNetworkUsageDescription"
        let tvWord = ["en": "TV", "ar": "التلفزيون", "nl": "tv"]
        var values: [String] = []
        for locale in ["en", "ar", "nl"] {
            let value = try Self.lproj(locale).localizedString(forKey: key, value: nil, table: "InfoPlist")
            #expect(value != key, "\(locale) has no \(key): the prompt renders in English")
            #expect(value.contains(try #require(tvWord[locale])), "\(locale)/\(key) never names the TV: \(value)")
            #expect(Self.bannedStem(in: value) == nil, "\(locale)/\(key) carries a banned stem: \(value)")
            values.append(value)
        }
        // The English value is the source string `project.yml` puts in the plist, verbatim.
        #expect(values[0] == "FitrahTube finds Chromecast and other TV devices on your local "
                + "network so you can play videos on your TV.")
        #expect(Set(values).count == 3, "ar/nl are still the English sentence")
    }

    private static func lproj(_ locale: String) throws -> Bundle {
        try #require(Bundle.main.path(forResource: locale, ofType: "lproj").flatMap(Bundle.init(path:)))
    }

    // MARK: - The banned-word net (adversarial r1 P1-1)

    /// Words no user-visible string may carry, in any locale (owner directives 2026-09-01 /
    /// spec D10). The net was ASCII-only, so `حمّل` in `share_app_promo` rode every outbound share
    /// message past a test literally named "never says Download".
    ///
    /// `تحميل` is deliberately ABSENT, and this is the one judgement call in the list: it is the
    /// verbal noun of `حمّل` and means BOTH "downloading" and "loading", and eight keys with live
    /// Swift callers use it for the second sense — `load_more` ("Load more"), `loading`/
    /// `loading_more`/`home_loading_more` ("Loading…"), `list_error_title` ("Unable to load
    /// content"), `load_more_error`, `channel_tab_error_generic`, `player_error_message` ("problem
    /// loading this video"). Banning it would refuse eight correct translations to catch nothing:
    /// zero keys use it in the download sense. `تنزيل` (48 catalog hits, all in the orphaned
    /// Android `download_*` keys) and `حمل`/`حمّل` carry no such ambiguity.
    private static let bannedStems = [
        "download", "ad-free", "ad free",
        // `حمّل` (with the shadda) is the promo's own spelling; `حمل` is the bare stem, zero hits
        // in the catalog today, kept so an un-shadda'd re-authoring cannot slip through.
        "حمّل", "حمل", "تنزيل",
        // The ad-free directive in the other two locales. Zero hits today, same reason.
        "advertentievrij", "reclamevrij", "بدون إعلانات",
    ]

    private static func bannedStem(in value: String) -> String? {
        bannedStems.first { value.range(of: $0, options: .caseInsensitive) != nil }
    }

    /// Every catalog key with at least one Swift caller. The test bundle reaches the source tree
    /// through `#filePath`; every `*.swift` under `ios/FitrahTube` is scanned for plain string
    /// literals and the ones that name a catalog key are what "referenced" means.
    ///
    /// This distinction is the whole point (a flat catalog loop cannot make it): ~170 orphaned
    /// Android `download_*`/`downloads_*` keys stay in the catalog on purpose — pruning them is a
    /// converter change with its own blast radius — and they carry the banned words legitimately,
    /// because nothing on iOS renders them. A key a Swift file names DOES reach a screen.
    ///
    /// A key built by interpolation is invisible here; every such builder in the app
    /// (`SavedRowText.captionKey`, `OfflineStatus.captionKey`) returns whole literals, which are.
    private static func referencedKeys() throws -> Set<String> {
        let sources = URL(filePath: #filePath)          // …/ios/FitrahTubeTests/LocalizationTests.swift
            .deletingLastPathComponent()                 // …/ios/FitrahTubeTests
            .deletingLastPathComponent()                 // …/ios
            .appending(path: "FitrahTube", directoryHint: .isDirectory)
        let catalog = Set(try compiledKeys(locale: "en"))
        let literal = try NSRegularExpression(pattern: "\"([^\"\\\\\n]*)\"")
        let files = try #require(FileManager.default.enumerator(at: sources, includingPropertiesForKeys: nil))
        var referenced: Set<String> = []
        for case let url as URL in files where url.pathExtension == "swift" {
            let text = try String(contentsOf: url, encoding: .utf8)
            for match in literal.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
                guard let range = Range(match.range(at: 1), in: text) else { continue }
                let candidate = String(text[range])
                if catalog.contains(candidate) { referenced.insert(candidate) }
            }
        }
        return referenced
    }

    /// The durable half of P1-1: not a 33-key allowlist and not ASCII-only, but every key the app
    /// can actually render, in all three locales, against every banned stem.
    @Test func noKeyWithASwiftCallerCarriesABannedStemInAnyLocale() throws {
        let referenced = try Self.referencedKeys()
        #expect(referenced.count > 200,
                "only \(referenced.count) keys matched a Swift caller — the source scan found nothing to check")
        for locale in ["en", "ar", "nl"] {
            let bundle = try Self.lproj(locale)
            for key in referenced.sorted() {
                let value = bundle.localizedString(forKey: key, value: nil, table: nil)
                // A key missing from this locale resolves to the key ITSELF, and three referenced
                // keys carry "download" in their NAME (`settings_downloads`,
                // `settings_download_quality`, `settings_download_quality_title`) — so without this
                // a lost translation would be reported as banned copy instead of a missing string.
                // `everyCatalogKeyResolvesInArabicAndDutch` is the test that owns that failure.
                guard value != key else { continue }
                if let stem = Self.bannedStem(in: value) {
                    Issue.record("\(locale)/\(key) carries the banned stem \"\(stem)\": \(value)")
                }
            }
        }
    }

    /// The other side of the same net: an orphaned Android key that carries the word is NOT a
    /// failure — it renders nowhere — and the net must not have quietly started checking the whole
    /// catalog, which would be a 170-entry allowlist by another name.
    @Test func theOrphanedAndroidDownloadKeysAreOutsideTheNet() throws {
        let referenced = try Self.referencedKeys()
        let catalog = try Self.compiledKeys(locale: "en")
        let orphanedOffenders = catalog.filter { key in
            guard !referenced.contains(key) else { return false }
            let value = (try? Self.lproj("en"))?.localizedString(forKey: key, value: nil, table: nil) ?? key
            return Self.bannedStem(in: value) != nil
        }
        // Pruned on purpose one day -> delete this test. Failing with them still in the catalog
        // means the source scan broke and every key now looks referenced.
        #expect(!orphanedOffenders.isEmpty, "no unreferenced key carries a banned stem any more")
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

    /// Phase 3 Task 5 — the naming-ruling pin. Every `offline_*` key plus the five re-authored
    /// `settings_*` keys must exist in all three locales, and NO value may contain "Download"
    /// (any case) or "ad-free" (owner directives 2026-09-01 / spec D10). No offline_action_cancel
    /// or offline_action_retry: those reuse the Android generics `cancel`/`retry` in the catalog.
    private static let offlineRulingKeys = [
        "offline_save", "offline_saved_title", "offline_not_saveable",
        "offline_quality_title", "offline_quality_audio_only", "offline_quality_standard_ceiling",
        "offline_status_queued", "offline_status_saving", "offline_status_paused",
        "offline_status_completed", "offline_status_failed", "offline_status_cancelled",
        "offline_error_403", "offline_error_429", "offline_error_network",
        "offline_error_no_stream", "offline_error_invalid", "offline_error_unknown",
        "offline_empty_state", "offline_footer_format",
        "offline_action_pause", "offline_action_resume", "offline_action_remove",
        "offline_action_open", "offline_action_delete",
        "settings_offline_storage", "settings_offline_clear", "settings_offline_clear_confirm",
        "settings_downloads", "settings_download_quality", "settings_download_quality_title",
        "settings_wifi_only", "settings_wifi_only_desc",
        // Security r1 P1-1: the first-run carousel's third page (`OnboardingView.swift:14`) shipped
        // Android's "Download for offline" title under a download glyph — the banned word verbatim,
        // on the screen every first-run user and every App Review pass sees, while its own sibling
        // `onboarding_page3_desc` had already been re-authored. The net covered three sets and not
        // this key; now it does.
        "onboarding_page3_title", "onboarding_page3_desc",
    ]

    @Test func everyOfflineKeyExistsInAllLocalesWithoutDownloadOrAdFree() throws {
        for locale in ["en", "ar", "nl"] {
            let bundle = try Self.lproj(locale)
            for key in Self.offlineRulingKeys {
                let value = bundle.localizedString(forKey: key, value: nil, table: nil)
                #expect(value != key, "\(locale)/\(key) is missing from the catalog")
                #expect(Self.bannedStem(in: value) == nil, "\(locale)/\(key) carries a banned stem: \(value)")
            }
        }
    }

    /// The re-authored settings copy actually carries the "Save for offline" language (not just
    /// any Download-free value), and the refusal copy says WHAT, never WHY.
    @Test func reauthoredSettingsAndRefusalCopyAreOfflineShaped() {
        for key in ["settings_downloads", "settings_download_quality", "settings_download_quality_title", "settings_wifi_only_desc"] {
            #expect(string(key, locale: "en").localizedCaseInsensitiveContains("offline"), "\(key) lost the offline language")
        }
        #expect(string("offline_save", locale: "en") == "Save for offline")
        #expect(string("offline_not_saveable", locale: "en") == "This video can't be saved for offline")
        // gstack P3: the 429 copy named upstream throttling ("Too many requests"), which tells the
        // user how the app talks to YouTube. A failure string says WHAT, never WHY.
        #expect(string("offline_error_429", locale: "en") == "Couldn't save right now. Try again later")
    }

    /// gstack P2: the promo's Dutch value shipped the literal word "Download" — the string that
    /// leaves the device on every share (`ShareLinks.swift:42`). The comment here used to call it
    /// "the last hit in a string any surface renders", which was false twice over: the ASCII-only
    /// check could not see the Arabic value's own `حمّل` (adversarial r1 P0-1b), and the first-run
    /// onboarding headline was rendering the word too. Both are fixed; the stems are shared with
    /// the caller-aware net above, so this key can never regress behind a locale the check can't read.
    @Test func theSharePromoNeverSaysDownloadOrAdFree() throws {
        for locale in ["en", "ar", "nl"] {
            let value = try Self.lproj(locale).localizedString(forKey: "share_app_promo", value: nil, table: nil)
            #expect(value != "share_app_promo", "\(locale) has no share_app_promo")
            #expect(Self.bannedStem(in: value) == nil, "\(locale) carries a banned stem: \(value)")
        }
        // The exact re-authored Arabic verb, so a well-meaning revert to "حمّل" is a named failure.
        #expect(try Self.lproj("ar").localizedString(forKey: "share_app_promo", value: nil, table: nil)
                    .hasPrefix("احصل على"))
    }
}
