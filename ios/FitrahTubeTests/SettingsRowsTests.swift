import Foundation
import Testing
@testable import FitrahTube

/// `SettingsView`'s row/section order (`SettingsLayout.rows`), the 7-tap developer gesture
/// (`TapGate`), and the About screen's version string (`AboutVersionText`) -- pulled out as pure
/// data/state so they're testable without a live view (this project has no ViewInspector/snapshot
/// dependency). `theme -> colorScheme` itself is already covered by Task 4's
/// `SettingsStoreTests.colorSchemeMapsThemeSelection`, so it isn't re-tested here.
@Suite(.perTest)
struct SettingsRowsTests {
    // MARK: - Row/section order (task-13 brief order, favorites-settings-about.md:139-158 minus
    // phase-3/none rows; RULINGS 32-35). Deliberately not Android's literal section order -- the
    // brief states, twice, that Safe Mode folds into Playback (Android's own single-row "Content"
    // section) and Library moves from right after General to just before About & Support.

    @Test func rowOrderMatchesTask13BriefExactly() {
        let expected: [SettingsRow] = [
            .language, .theme,
            .audioOnly, .backgroundPlay, .safeMode,
            .downloadQuality, .wifiOnly,
            .favorites,
            .aboutSupport,
        ]
        #expect(SettingsLayout.rows.map(\.row) == expected)
    }

    @Test func sectionsFollowTask13BriefOrder() {
        let expected: [SettingsSection] = [
            .general, .general,
            .playback, .playback, .playback,
            .downloads, .downloads,
            .library,
            .aboutSupport,
        ]
        #expect(SettingsLayout.rows.map(\.section) == expected)
    }

    // Account/Sign-out (favorites-settings-about.md:143, "hidden unless signed in") never
    // appears: phase 1 has no signed-in state to show it for (spec D11, guest-only until phase 4
    // auth), so there is no `.signOut` case and no Account section case to ever render -- proved
    // by construction (the type simply has none), not by a runtime visibility flag.
    @Test func nineRowsInFiveSectionsNoAccountSection() {
        #expect(SettingsLayout.rows.count == 9)
        #expect(SettingsSection.allCases.count == 5)
    }

    // MARK: - TapGate (favorites-settings-about.md:279-288)

    @Test func firstThreeTapsAreSilent() {
        var gate = TapGate()
        #expect(gate.tap(now: 0) == .silent)
        #expect(gate.tap(now: 1) == .silent)
        #expect(gate.tap(now: 2) == .silent)
    }

    @Test func tapsFourThroughSixCountDownStepsAway() {
        var gate = TapGate()
        _ = gate.tap(now: 0)
        _ = gate.tap(now: 1)
        _ = gate.tap(now: 2)
        #expect(gate.tap(now: 3) == .stepsAway(3))
        #expect(gate.tap(now: 4) == .stepsAway(2))
        #expect(gate.tap(now: 5) == .stepsAway(1))
    }

    @Test func seventhTapUnlocksAndResetsCounter() {
        var gate = TapGate()
        for t in 0..<6 { _ = gate.tap(now: TimeInterval(t)) }
        #expect(gate.tap(now: 6) == .unlocked)
        #expect(gate.count == 0)
    }

    // "the counter resets to 0 before incrementing" on a >3s gap; `lastTapTime` updates on every
    // tap regardless.
    @Test func gapOverThreeSecondsResetsCounterBeforeIncrementing() {
        var gate = TapGate()
        _ = gate.tap(now: 0)
        _ = gate.tap(now: 1)
        _ = gate.tap(now: 2) // count 3
        #expect(gate.tap(now: 5.5) == .silent) // 5.5 - 2 = 3.5 > 3 -> reset to 0, then this tap -> count 1
        #expect(gate.count == 1)
    }

    // Contract: "if now - lastTapTime > 3000" -- strictly greater, so an exactly-3s gap does not reset.
    @Test func gapOfExactlyThreeSecondsDoesNotReset() {
        var gate = TapGate()
        _ = gate.tap(now: 0)
        _ = gate.tap(now: 3.0)
        #expect(gate.count == 2)
    }

    // MARK: - About version string (`about_version_format` = "Version %1$@ (%2$@)", both args
    // strings per the task brief)

    @Test func aboutVersionFormatsBothArgumentsAsStrings() {
        #expect(AboutVersionText.format(version: "1.0.0", build: "7") == "Version 1.0.0 (7)")
    }

    // Found via a live `-AppleLanguages (ar)` screenshot run (task-13): `about_version_format`
    // rendered as the literal key on screen instead of falling back to English.
    // `Bundle.main.localizedString(forKey:)` resolves to exactly one `.lproj`; when that `.lproj`
    // exists but lacks the key it returns the bare key, with no further cross-locale fallback --
    // disproving `LocalizationTests.untranslatedKeyFallsBackToEnglish`'s "Foundation's real
    // per-key fallback happens through Bundle.main's own localization negotiation" comment (that
    // test's own Bundle.main call only ever passes because the test host runs in English).
    // `ar.lproj` is opened directly here (same technique `LocalizationTests` uses) to force the
    // "resolved bundle lacks this key" case deterministically.
    @Test func englishOnlyStringsFallsBackWhenTheResolvedBundleLacksTheKey() throws {
        let arBundle = try #require(Bundle.main.path(forResource: "ar", ofType: "lproj").flatMap(Bundle.init(path:)))
        #expect(EnglishOnlyStrings.lookup("about_version_format", in: arBundle) == "Version %1$@ (%2$@)")
        #expect(EnglishOnlyStrings.lookup("dev_settings_steps_away", in: arBundle) == "%#@value@")
    }
}
