import UIKit
import XCTest

/// Task-14 cross-device / RTL / accessibility rig.
///
/// `xcrun simctl` cannot rotate, scroll or tap, so the earlier `capture.sh` scripts could only ever
/// produce portrait shots and could never read an accessibility label back. This XCUITest replaces
/// them for that pass: it drives the app's own DEBUG launch hooks (`FitrahTubeApp.swift`), rotates
/// via `XCUIDevice`, writes full-resolution PNGs to `$FITRAH_SHOTS_DIR` (host path, passed by
/// `ios/scripts/screenshots.sh` as `TEST_RUNNER_FITRAH_SHOTS_DIR`), and asserts the VoiceOver
/// label of the first row on every list screen (spec §14: "label + value on custom controls").
///
/// Deliberately NOT part of `FitrahTube.xctestplan` / `ios/scripts/test.sh`: it performs ~46 app
/// launches and would blow the 300 s gate. Its own plan is `FitrahTubeUITests.xctestplan`.
final class ScreenshotTests: XCTestCase {

    // MARK: - Matrix

    /// The element whose existence means a screen has finished loading (so no shot is ever taken
    /// mid-skeleton). Data, not a closure, so `screens` stays `Sendable` under Swift 6.
    private enum Anchor: Sendable {
        /// A row/card button whose VoiceOver label contains this substring.
        case button(String)
        /// A `Text` whose label contains this substring.
        case text(String)
        /// The first `Form` toggle (Settings) — locale-independent.
        case firstSwitch
        /// The second button on screen (Onboarding's Skip/CTA pair) — locale-independent.
        case secondButton
    }

    /// One capturable screen: the DEBUG launch hooks that land on it, plus its load anchor.
    private struct Screen: Sendable {
        let key: String
        let arguments: [String]
        let anchor: Anchor
    }

    /// Item titles come from `FakeCatalogClient`, which is locale-independent ("Video p0-0"), so the
    /// same anchor works in English and Arabic. Screens without catalog rows anchor on a structural
    /// element instead (a Form switch, the literal version number, the second button).
    private static let screens: [Screen] = [
        Screen(key: "home", arguments: ["-fitrah-tab", "home"], anchor: .button("s1-0")),
        Screen(key: "videos", arguments: ["-fitrah-tab", "videos"], anchor: .button("p0-0")),
        Screen(key: "channels", arguments: ["-fitrah-tab", "channels"], anchor: .button("p0-0")),
        Screen(key: "playlists", arguments: ["-fitrah-tab", "playlists"], anchor: .button("p0-0")),
        Screen(key: "search", arguments: ["-fitrah-route", "search", "-fitrah-search-query", "Video"],
               anchor: .button("search-0")),
        Screen(key: "categories", arguments: ["-fitrah-route", "categories"], anchor: .button("Quran")),
        Screen(key: "favorites", arguments: ["-fitrah-seed-favorites", "-fitrah-route", "favorites"],
               anchor: .button("Seeded Favorite 1")),
        Screen(key: "settings", arguments: ["-fitrah-route", "settings"], anchor: .firstSwitch),
        // `about_version_format` is the literal "Version %1$@ (%2$@)" in en, ar and nl alike
        // (untranslated by contract -- R7 fallback), so the word "Version" is a locale-independent
        // anchor that, unlike the version number itself, survives a MARKETING_VERSION bump. It is
        // a *button*, not a static text: `AboutView` puts `.accessibilityAddTraits(.isButton)` on
        // that `Text` for the 7-tap developer gesture, and traits decide the XCUIElement type.
        Screen(key: "about", arguments: ["-fitrah-route", "about"], anchor: .button("Version")),
        // The only screen that must NOT skip onboarding: `-fitrah-reset-onboarding` writes the flag
        // back to false, so `-onboarding_completed` is deliberately not added for this one.
        Screen(key: "onboarding", arguments: ["-fitrah-reset-onboarding"], anchor: .secondButton),
    ]

    private struct LocaleCase: Sendable {
        let key: String
        let theme: String
        let arguments: [String]
    }

    /// R-G's two columns. Theme rides the app's own `settings.theme` row (`SettingsStore`), set
    /// through the `NSArgumentDomain` key it already persists under — no new debug hook needed.
    private static let locales = [
        LocaleCase(key: "en", theme: "light", arguments: []),
        LocaleCase(key: "ar", theme: "dark", arguments: ["-AppleLanguages", "(ar)", "-AppleLocale", "ar_SA"]),
    ]

    /// `.accessibility3` is `UIContentSizeCategory.accessibilityExtraLarge`, whose raw value is
    /// `UICTContentSizeCategoryAccessibilityXL` (AccessibilityL is `.accessibility2`). The brief
    /// asks for `.accessibility3`, so that is what is captured — see task-14-report.md.
    private static let accessibility3 = ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXL"]

    private static let accessibilityScreenKeys = ["home", "videos", "settings"]

    // MARK: - Lifecycle

    override func setUp() {
        super.setUp()
        continueAfterFailure = true // one unreachable screen must not abort the rest of the matrix
    }

    override func tearDown() {
        // Simulators keep their orientation between runs; leave every device upright.
        XCUIDevice.shared.orientation = .portrait
        super.tearDown()
    }

    // MARK: - Capture (R-G)

    /// iPad matrix: every Phase 1 screen × {en-light, ar-dark} × {portrait, landscape}.
    func testCatalogScreens() throws {
        let directory = try shotsDirectory()
        for screen in Self.screens {
            for locale in Self.locales {
                try capture(screen, locale: locale, extraArguments: [], suffix: "", into: directory)
            }
        }
    }

    /// iPhone Dynamic Type matrix (R-E): Home, Videos and Settings at `.accessibility3`.
    func testAccessibilityTextSizes() throws {
        let directory = try shotsDirectory()
        for screen in Self.screens where Self.accessibilityScreenKeys.contains(screen.key) {
            for locale in Self.locales {
                try capture(screen, locale: locale, extraArguments: Self.accessibility3, suffix: "-a11y3", into: directory)
            }
        }
    }

    private func capture(_ screen: Screen, locale: LocaleCase, extraArguments: [String],
                         suffix: String, into directory: URL) throws {
        XCUIDevice.shared.orientation = .portrait
        let app = launch(screen, locale: locale, extraArguments: extraArguments)
        let anchor = element(for: screen.anchor, in: app)
        XCTAssertTrue(anchor.waitForExistence(timeout: 20),
                      "\(screen.key)/\(locale.key)\(suffix): never reached a loaded state")
        try write(named: "\(screen.key)-\(locale.key)-\(locale.theme)\(suffix)-portrait", into: directory)

        // Rotation never reloads data (the portrait assertion above already proved the screen
        // reached a loaded state in this same process), and on a short landscape iPhone at
        // `.accessibility3` the first row legitimately sits below the fold -- so this is a wait,
        // not an assertion.
        XCUIDevice.shared.orientation = .landscapeLeft
        _ = anchor.waitForExistence(timeout: 10)
        settle(app, landscape: true)
        try write(named: "\(screen.key)-\(locale.key)-\(locale.theme)\(suffix)-landscape", into: directory)
        XCUIDevice.shared.orientation = .portrait
    }

    /// R-F: the shell's offline banner (`OfflineBanner`, start-aligned text + `wifi.slash`) never
    /// appears in the R-G matrix because it needs `-fitrah-offline`, so it gets its own pass --
    /// en-light and ar-dark, portrait and landscape, on whichever device this runs on.
    func testOfflineBanner() throws {
        let directory = try shotsDirectory()
        let offline = Screen(key: "offline", arguments: ["-fitrah-offline", "-fitrah-tab", "home"],
                             anchor: .button("s1-0"))
        for locale in Self.locales {
            try capture(offline, locale: locale, extraArguments: [], suffix: "", into: directory)
        }
    }

    // MARK: - Accessibility assertions (R-C, spec §14 "label + value on custom controls")

    /// Every list row must expose a VoiceOver label that carries the item title *and* the value the
    /// row's contract gives it — never a bare title. Values come from `FakeCatalogClient`:
    /// duration 120 s -> "2:00", view count 0, uploaded 1 day ago; the seeded favorites carry their
    /// own durations. Channels/playlists in the fake feed have no subscriber/item count, so those
    /// rows are asserted against the "unknown" branch of their label instead.
    func testListRowAccessibilityLabels() throws {
        try assertFirstRowLabel(screenKey: "home", contains: ["Video s1-0", "2:00", "0", "1"])
        try assertFirstRowLabel(screenKey: "videos", contains: ["Video p0-0", "2:00", "0", "1"])
        try assertFirstRowLabel(screenKey: "channels", contains: ["Channel", "Video p0-0"])
        try assertFirstRowLabel(screenKey: "playlists", contains: ["Video p0-0"])
        try assertFirstRowLabel(screenKey: "search", contains: ["Video search-0", "2:00"])
        try assertFirstRowLabel(screenKey: "favorites", contains: ["Seeded Favorite 1", "6:00"])
    }

    private func assertFirstRowLabel(screenKey: String, contains needles: [String]) throws {
        let screen = try XCTUnwrap(Self.screens.first { $0.key == screenKey })
        XCUIDevice.shared.orientation = .portrait
        let app = launch(screen, locale: Self.locales[0], extraArguments: [])
        let row = element(for: screen.anchor, in: app)
        XCTAssertTrue(row.waitForExistence(timeout: 20), "\(screenKey): first row never appeared")
        let label = row.label
        XCTAssertFalse(label.isEmpty, "\(screenKey): first row has an empty accessibility label")
        for needle in needles {
            XCTAssertTrue(label.contains(needle), "\(screenKey): row label '\(label)' is missing '\(needle)'")
        }
    }

    // MARK: - Helpers

    private func launch(_ screen: Screen, locale: LocaleCase, extraArguments: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        var arguments = ["-fitrah-fake-container", "-theme", locale.theme]
        if screen.key != "onboarding" {
            // NSArgumentDomain override of the key `UserDefaultsSettingsStore` already persists —
            // the fake container's suite reads it, so every non-onboarding screen starts past the
            // onboarding gate without a separate debug hook.
            arguments += ["-onboarding_completed", "YES"]
        }
        arguments += locale.arguments + extraArguments + screen.arguments
        app.launchArguments = arguments
        app.launch()
        return app
    }

    private func shotsDirectory() throws -> URL {
        let path = try XCTUnwrap(ProcessInfo.processInfo.environment["FITRAH_SHOTS_DIR"],
                                 "FITRAH_SHOTS_DIR unset — run via ios/scripts/screenshots.sh")
        let url = URL(fileURLWithPath: path, isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// `XCUIScreenshot.pngRepresentation` is the raw portrait framebuffer, so a landscape shot is
    /// encoded sideways and has to be read with your head tilted. `.image` carries the orientation
    /// the device was in, so redraw it upright (at the device's own scale) before encoding.
    private func write(named name: String, into directory: URL) throws {
        let raw = XCUIScreen.main.screenshot().image
        let format = UIGraphicsImageRendererFormat()
        format.scale = raw.scale
        format.opaque = true
        let upright = UIGraphicsImageRenderer(size: raw.size, format: format).image { _ in
            raw.draw(in: CGRect(origin: .zero, size: raw.size))
        }
        try XCTUnwrap(upright.pngData()).write(to: directory.appendingPathComponent("\(name).png"))
    }

    /// Rotation is animated and the anchor exists again well before the animation finishes: shots
    /// taken too early carry the pre-rotation snapshot cross-faded over the new layout (a ghost
    /// rail on the opposite edge). This used to be `Thread.sleep(forTimeInterval: 3)`, a constant
    /// tuned against this machine (gate B2-2) -- nothing asserts a frame is settled, so on a
    /// slower or loaded CI runner it would silently produce ghosted screenshots without ever going
    /// red, defeating the whole point of the rig.
    ///
    /// Poll for the two things that actually define "settled": the window has taken the target
    /// orientation, and two consecutive full-screen reads are byte-identical. Adapts to machine
    /// speed instead of assuming it; the 6 s ceiling only bounds a pathological case.
    private func settle(_ app: XCUIApplication, landscape: Bool) {
        let deadline = Date().addingTimeInterval(6)
        var previous: Data?
        while Date() < deadline {
            let frame = app.windows.firstMatch.frame
            if frame.width > 0, (frame.width > frame.height) == landscape {
                let current = XCUIScreen.main.screenshot().pngRepresentation
                if current == previous { return }
                previous = current
            }
            Thread.sleep(forTimeInterval: 0.25)
        }
    }

    private func element(for anchor: Anchor, in app: XCUIApplication) -> XCUIElement {
        switch anchor {
        case .button(let needle):
            return app.buttons.matching(NSPredicate(format: "label CONTAINS %@", needle)).firstMatch
        case .text(let needle):
            return app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", needle)).firstMatch
        case .firstSwitch:
            return app.switches.firstMatch
        case .secondButton:
            return app.buttons.element(boundBy: 1)
        }
    }
}
