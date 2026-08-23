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
    // MARK: - Row/section order: Android's literal order (favorites-settings-about.md:139-153)
    // minus the phase-3/none rows (RULINGS 32-35). RULINGS.md line 3 makes parity the default, so
    // Safe Mode keeps its own "Content" section and Library stays right after General.

    @Test func rowOrderMatchesAndroidExactly() {
        let expected: [SettingsRow] = [
            .language, .theme,
            .favorites,
            .audioOnly, .backgroundPlay,
            .downloadQuality, .wifiOnly,
            .safeMode,
            .aboutSupport,
        ]
        #expect(SettingsLayout.rows.map(\.row) == expected)
    }

    @Test func sectionsFollowAndroidOrder() {
        let expected: [SettingsSection] = [
            .general, .general,
            .library,
            .playback, .playback,
            .downloads, .downloads,
            .content,
            .aboutSupport,
        ]
        #expect(SettingsLayout.rows.map(\.section) == expected)
        // `SettingsView` renders `SettingsSection.allCases` in declaration order, so that order
        // must match the row table's own section sequence or the screen would disagree with it.
        #expect(SettingsSection.allCases == [.general, .library, .playback, .downloads, .content, .aboutSupport])
    }

    // Account/Sign-out (favorites-settings-about.md:143, "hidden unless signed in") never
    // appears: phase 1 has no signed-in state to show it for (spec D11, guest-only until phase 4
    // auth), so there is no `.signOut` case and no Account section case to ever render -- proved
    // by construction (the type simply has none), not by a runtime visibility flag.
    @Test func nineRowsInSixSectionsNoAccountSection() {
        #expect(SettingsLayout.rows.count == 9)
        #expect(SettingsSection.allCases.count == 6)
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
}
