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

    // Phase 3 Task 6: the Downloads section (now titled with the re-authored "Save for offline"
    // copy) keeps its two Android rows and gains Saved library / Storage / Clear, in the plan's
    // listing order (kept rows first, gained rows after).
    @Test func rowOrderMatchesAndroidExactly() {
        let expected: [SettingsRow] = [
            .language, .theme,
            .favorites,
            .audioOnly, .backgroundPlay,
            .downloadQuality, .wifiOnly, .savedLibrary, .storage, .clearOffline,
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
            .downloads, .downloads, .downloads, .downloads, .downloads,
            .content,
            .aboutSupport,
        ]
        #expect(SettingsLayout.rows.map(\.section) == expected)
        // `SettingsView` renders `SettingsSection.allCases` in declaration order, so that order
        // must match the row table's own section sequence or the screen would disagree with it.
        #expect(SettingsSection.allCases == [.general, .library, .playback, .downloads, .content, .aboutSupport])
    }

    // Account/Sign-out (favorites-settings-about.md:143, "hidden unless signed in") is not IN THIS
    // TABLE, which is the whole claim: a static row list cannot express a row that appears only for
    // a signed-in user, so Task 13 renders it as a conditional `Section` instead (`SettingsView`
    // `accountSection`) and this stays a by-construction pin -- there is no `.signOut` row case and
    // no Account section case, and no runtime visibility flag standing in for one.
    @Test func twelveRowsInSixSectionsNoAccountRowInTheStaticTable() {
        #expect(SettingsLayout.rows.count == 12)
        #expect(SettingsSection.allCases.count == 6)
    }

    // Pins every row's titleKey/descriptionKey and every section's titleKey against the real
    // catalog, not just the three keys Android deleted on 2026-08-25 (commit 2ffde712). A missing
    // key is not a crash and not a build error -- `String(localized:)` returns the key itself, so
    // the row renders "settings_safe_mode" to the user. One assertion over the whole layout, so
    // the next deletion fails here instead of shipping invisible.
    @Test func everySettingsKeyResolvesToRealCopy() {
        for row in SettingsLayout.rows {
            let title = String(localized: String.LocalizationValue(row.row.titleKey))
            #expect(title != row.row.titleKey, "missing catalog entry for \(row.row.titleKey)")
            let section = String(localized: String.LocalizationValue(row.section.titleKey))
            #expect(section != row.section.titleKey, "missing catalog entry for \(row.section.titleKey)")
            if let descriptionKey = row.row.descriptionKey {
                let description = String(localized: String.LocalizationValue(descriptionKey))
                #expect(description != descriptionKey, "missing catalog entry for \(descriptionKey)")
            }
        }
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

    /// Owner directive 2026-09-21: fitrahtube.com only (why: `AboutLinks`' comment), no exception --
    /// Phase 6 OQ-1 (a) dropped the GitHub row, the one off-domain link the screen used to carry.
    /// A dead privacy link is an App Review rejection, so the three legal URLs are pinned exactly.
    @Test func everyAboutLinkIsOnFitrahtube() {
        #expect(AboutLinks.legal.map(\.url.absoluteString) == [
            "https://app.fitrahtube.com/privacy",
            "https://app.fitrahtube.com/terms",
            "https://app.fitrahtube.com/licenses",
        ])
        for link in AboutLinks.legal {
            let host = link.url.host() ?? ""
            #expect(host == "fitrahtube.com" || host.hasSuffix(".fitrahtube.com"),
                    "\(link.titleKey) leaves the app's domain: \(link.url)")
            #expect(link.url.scheme == "https")
        }
    }

    @Test func aboutVersionFormatsBothArgumentsAsStrings() {
        #expect(AboutVersionText.format(version: "1.0.0", build: "7") == "Version 1.0.0 (7)")
    }
}
