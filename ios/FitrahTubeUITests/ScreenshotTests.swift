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
        /// The first `Form` toggle (Settings) — locale-independent.
        case firstSwitch
        /// The second button on screen (Onboarding's Skip/CTA pair) — locale-independent.
        case secondButton
        /// A non-button element whose VoiceOver label equals this string exactly.
        /// `AVPlayerViewController`'s own content view labels itself this way once it hosts a
        /// player — task-3-report.md: on this SDK its transport chrome (play/pause, scrubber) never
        /// showed up as separate accessibility elements in an XCUITest run, so this is the anchor
        /// `testPlayerScreen` uses instead — present as soon as the host mounts, not tied to chrome
        /// auto-hide.
        case element(String)
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

    // MARK: - B1 task 3: AVPlayer host (docs/superpowers/plans/2026-08-24-ios-phase2b1-player-core.md)

    /// No locale/rotation matrix (unlike R-G above) -- this only needs to prove `PlayerHostView`
    /// actually decodes and plays a real frame with zero network access. `-fitrah-fake-player`
    /// swaps `PlayerScreen`'s resolver for one that resolves to the bundled `player-fixture.mp4`
    /// (`ios/FitrahTube/Resources/`).
    ///
    /// Anchor: `AVPlayerViewController`'s content view (VoiceOver label "Video"), not its transport
    /// chrome -- task-3-report.md's diagnosis (`app.debugDescription` dumped on a failed run) found
    /// the play/pause button never appears as its own accessibility element on this SDK (Xcode
    /// 26.3 / iOS 26.2 Simulator), so it's not a usable anchor here. The dump *did* show an
    /// `Image` labeled "Liftable subject available" sized to the letterboxed video rect --
    /// iOS's subject-lifting analysis only runs against real rendered pixels, so that (plus the
    /// visibly colored, non-black captured frame) is the actual proof of decode; "Video" is just
    /// the deterministic, chrome-independent load signal to gate the screenshot on.
    func testPlayerScreen() throws {
        let directory = try shotsDirectory()
        let screen = Screen(key: "player",
                            arguments: ["-fitrah-fake-player", "-fitrah-route", "player", "fixture-video"],
                            anchor: .element("Video"))
        XCUIDevice.shared.orientation = .portrait
        let app = launch(screen, locale: Self.locales[0], extraArguments: [])
        let content = element(for: screen.anchor, in: app)
        XCTAssertTrue(content.waitForExistence(timeout: 20), "player: AVPlayerViewController's content view never appeared")
        try write(named: "player-ready", into: directory)
    }

    /// Plan B1 task 4: proof the quality menu is FitrahTube's own SwiftUI control, not AVKit chrome
    /// -- it's found and driven purely by `player.qualityMenu.button` / `player.qualityOption.*`
    /// (`PlayerScreen.qualityMenu`), the identifiers this app defines, never an AVKit accessibility
    /// element. `-fitrah-fake-player-hls` (not `-fitrah-fake-player`) is required: the plain fixture
    /// resolves `.rung2Progressive`, where the control is hidden by contract (spec §10).
    func testPlayerQualityMenu() throws {
        let directory = try shotsDirectory()
        let screen = Screen(key: "player-quality",
                            arguments: ["-fitrah-fake-player-hls", "-fitrah-route", "player", "fixture-video"],
                            anchor: .element("Video"))
        XCUIDevice.shared.orientation = .portrait
        let app = launch(screen, locale: Self.locales[0], extraArguments: [])
        let content = element(for: screen.anchor, in: app)
        XCTAssertTrue(content.waitForExistence(timeout: 20), "player-quality: AVPlayerViewController's content view never appeared")
        let button = app.buttons["player.qualityMenu.button"]
        XCTAssertTrue(button.waitForExistence(timeout: 10), "player-quality: quality menu button never appeared")
        button.tap()
        let option = app.buttons["player.qualityOption.p720"]
        XCTAssertTrue(option.waitForExistence(timeout: 10), "player-quality: quality menu never opened")
        try write(named: "player-quality-menu", into: directory)
    }

    /// Plan B1 task 5: `AudioLanguageMenu` (`player.audioLanguageMenu.button` /
    /// `player.audioLanguageOption.*`) must stay HIDDEN when the asset's audible media-selection
    /// group has <=1 option. The bundled `player-fixture.mp4` has exactly one audio track and no
    /// bundled multi-audio HLS sample exists (task-5-report.md), so unlike task 4's menu-open shot
    /// this proves the negative: the button never appears once the player is ready and the async
    /// `loadMediaSelectionGroup(for:)` has had time to settle. A screenshot of the OPEN menu needs a
    /// real multi-audio stream -- deferred to a later live pass (Task 10 / B1 live).
    func testPlayerAudioLanguageMenuHiddenForSingleTrackFixture() throws {
        let directory = try shotsDirectory()
        let screen = Screen(key: "player-audio-language",
                            arguments: ["-fitrah-fake-player-hls", "-fitrah-route", "player", "fixture-video"],
                            anchor: .element("Video"))
        XCUIDevice.shared.orientation = .portrait
        let app = launch(screen, locale: Self.locales[0], extraArguments: [])
        let content = element(for: screen.anchor, in: app)
        XCTAssertTrue(content.waitForExistence(timeout: 20), "player-audio-language: AVPlayerViewController's content view never appeared")
        let qualityButton = app.buttons["player.qualityMenu.button"]
        XCTAssertTrue(qualityButton.waitForExistence(timeout: 10), "player-audio-language: quality menu button never appeared (sanity: player is ready)")
        let audioButton = app.buttons["player.audioLanguageMenu.button"]
        XCTAssertFalse(audioButton.waitForExistence(timeout: 5), "player-audio-language: audio-language menu button should stay hidden for a single-track asset")
        try write(named: "player-audio-language-hidden", into: directory)
    }

    /// Plan B1 task 6: `PlayerScreen.captionsMenu` (`player.captionsMenu.button` /
    /// `player.captionsOption.*`) must stay HIDDEN when the resolved stream carries no caption
    /// tracks. `FixtureHLSPlayerResolver` resolves `.hls` with `captionTracks: []` (no bundled HLS
    /// sample carries real caption tracks), so -- same shape as Task 5's hidden-state proof --
    /// this proves the negative: the button never appears once the player is ready (anchored on
    /// the quality button, proving `.ready`, mirroring Task 5's pattern).
    func testPlayerCaptionsMenuHiddenForFixtureWithNoTracks() throws {
        let directory = try shotsDirectory()
        let screen = Screen(key: "player-captions",
                            arguments: ["-fitrah-fake-player-hls", "-fitrah-route", "player", "fixture-video"],
                            anchor: .element("Video"))
        XCUIDevice.shared.orientation = .portrait
        let app = launch(screen, locale: Self.locales[0], extraArguments: [])
        let content = element(for: screen.anchor, in: app)
        XCTAssertTrue(content.waitForExistence(timeout: 20), "player-captions: AVPlayerViewController's content view never appeared")
        let qualityButton = app.buttons["player.qualityMenu.button"]
        XCTAssertTrue(qualityButton.waitForExistence(timeout: 10), "player-captions: quality menu button never appeared (sanity: player is ready)")
        let captionsButton = app.buttons["player.captionsMenu.button"]
        XCTAssertFalse(captionsButton.waitForExistence(timeout: 5), "player-captions: captions menu button should stay hidden when the stream has no caption tracks")
        try write(named: "player-captions-hidden", into: directory)
    }

    /// Plan B1 task 7: rung 2 (`-fitrah-fake-player` resolves `.progressive`, which
    /// `PlayerViewModel.map` tags `.rung2Progressive`) shows the persistent
    /// "Standard quality (360p)" pill and hides the quality control entirely (spec §10: "Rung 2
    /// hides the control"). Both halves asserted: the pill present, the quality button absent.
    func testPlayerRung2Pill() throws {
        let directory = try shotsDirectory()
        let screen = Screen(key: "player-rung2",
                            arguments: ["-fitrah-fake-player", "-fitrah-route", "player", "fixture-video"],
                            anchor: .element("Video"))
        XCUIDevice.shared.orientation = .portrait
        let app = launch(screen, locale: Self.locales[0], extraArguments: [])
        let content = element(for: screen.anchor, in: app)
        XCTAssertTrue(content.waitForExistence(timeout: 20), "player-rung2: AVPlayerViewController's content view never appeared")
        let pill = app.staticTexts["player.rung2Pill"]
        XCTAssertTrue(pill.waitForExistence(timeout: 10), "player-rung2: the standard-quality pill never appeared")
        XCTAssertFalse(app.buttons["player.qualityMenu.button"].exists, "player-rung2: the quality control must be hidden on rung 2")
        // Plan B2 task 7: a muxed 360p progressive has no separate audio rendition, so the
        // audio-only control has nothing to back it and must be absent -- not offered and inert.
        XCTAssertFalse(app.buttons["player.audioOnly.button"].exists,
                       "player-rung2: the audio-only control must be hidden on rung 2 (no itag 140 rendition)")
        try write(named: "player-rung2-pill", into: directory)
    }

    /// Plan B2 task 3: the audio-only toggle (`player.audioOnly.button`, ruling 34). Offered only
    /// when the resolved stream carries an itag 140 rendition, so this uses
    /// `-fitrah-fake-player-audio-only` (the bundled fixture tagged `.hls` WITH a non-nil
    /// `audioOnlyURL`). Tapping it must raise the audio-only status surface
    /// (`player.audioOnlyPill`) and take the quality/captions/audio-language controls away -- on an
    /// m4a item every one of them would be inert.
    func testPlayerAudioOnly() throws {
        let directory = try shotsDirectory()
        let screen = Screen(key: "player-audio-only",
                            arguments: ["-fitrah-fake-player-audio-only", "-fitrah-route", "player", "fixture-video"],
                            anchor: .element("Video"))
        XCUIDevice.shared.orientation = .portrait
        let app = launch(screen, locale: Self.locales[0], extraArguments: [])
        let content = element(for: screen.anchor, in: app)
        XCTAssertTrue(content.waitForExistence(timeout: 20), "player-audio-only: AVPlayerViewController's content view never appeared")
        let button = app.buttons["player.audioOnly.button"]
        XCTAssertTrue(button.waitForExistence(timeout: 10), "player-audio-only: the audio-only button never appeared")
        button.tap()
        let pill = app.staticTexts["player.audioOnlyPill"]
        XCTAssertTrue(pill.waitForExistence(timeout: 10), "player-audio-only: the audio-only status surface never appeared")
        // Fix round 1, C1: a PILL, not a fill. The status used to be a full-bleed opaque rectangle
        // over AVKit's own transport, which left no way to pause while audio-only.
        XCTAssertLessThan(pill.frame.width, content.frame.width / 2,
                          "player-audio-only: the status must be a pill sized to its text, not a fill over the video surface")
        XCTAssertFalse(app.buttons["player.qualityMenu.button"].exists, "player-audio-only: the quality control must be hidden while audio-only")
        // VoiceOver state (plan task 7): the control reads its label AND its on/off state. It is
        // `.isSelected`, not an `accessibilityValue` -- no catalog string exists for on/off and
        // inventing one would ship an untranslated key.
        XCTAssertTrue(button.isSelected, "player-audio-only: the toggle must expose its ON state to VoiceOver")
        try write(named: "player-audio-only", into: directory)

        // ar portrait (RTL: the control mirrors with the rest of the overlay column) and en at
        // Dynamic Type `.accessibility3` (the status string is the longest one on this surface and
        // the pill is width-guarded, so this is where it would clip). Fresh launch each time --
        // locale and content-size are launch arguments. Anchored on our OWN identifier, never
        // AVKit's system-localized "Video" content-view label, which does not exist under ar.
        for (name, locale, extra) in [("player-audio-only-ar-portrait", Self.locales[1], [String]()),
                                      ("player-audio-only-en-a11y3-portrait", Self.locales[0], Self.accessibility3)] {
            let app = launch(screen, locale: locale, extraArguments: extra)
            let toggle = app.buttons["player.audioOnly.button"]
            XCTAssertTrue(toggle.waitForExistence(timeout: 20), "\(name): the audio-only button never appeared")
            toggle.tap()
            let pill = app.staticTexts["player.audioOnlyPill"]
            XCTAssertTrue(pill.waitForExistence(timeout: 10), "\(name): the audio-only status surface never appeared")
            let window = app.windows.firstMatch.frame
            XCTAssertTrue(window.contains(pill.frame),
                          "\(name): the status pill (\(pill.frame)) must stay inside the screen (\(window))")
            try write(named: name, into: directory)
        }
    }

    /// Plan B1 task 8: metadata panel + toolbar below the player. `-fitrah-route player` now seeds
    /// title/channel/description/views (`FitrahTubeApp.pushDebugRouteIfRequested`), so the
    /// screenshot shows real content, not an empty panel. Anchors on the favorite button (proves
    /// the toolbar rendered) then on the metadata title (proves the panel rendered).
    func testPlayerMetadataAndToolbar() throws {
        let directory = try shotsDirectory()
        let screen = Screen(key: "player-metadata",
                            arguments: ["-fitrah-fake-player-hls", "-fitrah-route", "player", "fixture-video"],
                            anchor: .element("Video"))
        XCUIDevice.shared.orientation = .portrait
        let app = launch(screen, locale: Self.locales[0], extraArguments: [])
        let content = element(for: screen.anchor, in: app)
        XCTAssertTrue(content.waitForExistence(timeout: 20), "player-metadata: AVPlayerViewController's content view never appeared")
        let favoriteButton = app.buttons["player.favoriteButton"]
        XCTAssertTrue(favoriteButton.waitForExistence(timeout: 10), "player-metadata: favorite button never appeared")
        let title = app.staticTexts["player.metadata.title"]
        XCTAssertTrue(title.waitForExistence(timeout: 10), "player-metadata: metadata title never appeared")
        try write(named: "player-metadata-toolbar", into: directory)
    }

    /// Plan B1 task 9: `.error` -- real localized message (not the raw key the old placeholder
    /// rendered) + Retry (`PlayerStateCopy.map`, `PlayerStateView`). `-fitrah-fake-player-error`
    /// throws `ExtractionError.transport`, no network involved.
    func testPlayerErrorState() throws {
        let directory = try shotsDirectory()
        let screen = Screen(key: "player-error",
                            arguments: ["-fitrah-fake-player-error", "-fitrah-route", "player", "fixture-video"],
                            anchor: .button("unused"))
        XCUIDevice.shared.orientation = .portrait
        let app = launch(screen, locale: Self.locales[0], extraArguments: [])
        let retryButton = app.buttons["player.state.retryButton"]
        XCTAssertTrue(retryButton.waitForExistence(timeout: 20), "player-error: retry button never appeared")
        try write(named: "player-error-state", into: directory)
    }

    /// Plan B1 task 9: `.contentUnavailable` -- no Retry (ruling 14: terminal, not retryable).
    func testPlayerContentUnavailableState() throws {
        let directory = try shotsDirectory()
        let screen = Screen(key: "player-unavailable",
                            arguments: ["-fitrah-fake-player-unavailable", "-fitrah-route", "player", "fixture-video"],
                            anchor: .button("unused"))
        XCUIDevice.shared.orientation = .portrait
        let app = launch(screen, locale: Self.locales[0], extraArguments: [])
        let message = app.staticTexts["player.state.message"]
        XCTAssertTrue(message.waitForExistence(timeout: 20), "player-unavailable: message never appeared")
        XCTAssertFalse(app.buttons["player.state.retryButton"].exists, "player-unavailable: must not offer Retry")
        try write(named: "player-unavailable-state", into: directory)
    }

    /// Plan B1 task 9: `.cooldown(until:)` -- live countdown, Retry hidden until it reaches zero.
    /// `-fitrah-fake-player-cooldown` is 45s out, so the captured frame always shows a non-zero
    /// countdown with no Retry button.
    func testPlayerCooldownState() throws {
        let directory = try shotsDirectory()
        let screen = Screen(key: "player-cooldown",
                            arguments: ["-fitrah-fake-player-cooldown", "-fitrah-route", "player", "fixture-video"],
                            anchor: .button("unused"))
        XCUIDevice.shared.orientation = .portrait
        let app = launch(screen, locale: Self.locales[0], extraArguments: [])
        let countdown = app.staticTexts["player.state.countdown"]
        XCTAssertTrue(countdown.waitForExistence(timeout: 20), "player-cooldown: countdown never appeared")
        XCTAssertFalse(app.buttons["player.state.retryButton"].exists, "player-cooldown: must not offer Retry yet")
        try write(named: "player-cooldown-state", into: directory)
    }

    /// T7-M3 (deferred-minors.md, MUST): `.recoveryExhausted` used to be a bare `ProgressView` dead
    /// end -- `-fitrah-fake-player-recovery-exhausted` forces it via
    /// `PlayerViewModel.debugForceRecoveryExhausted()` after a real fixture resolve, same technique
    /// as `NetworkMonitor`'s `-fitrah-offline` hook.
    func testPlayerRecoveryExhaustedState() throws {
        let directory = try shotsDirectory()
        let screen = Screen(key: "player-recovery-exhausted",
                            arguments: ["-fitrah-fake-player", "-fitrah-fake-player-recovery-exhausted",
                                        "-fitrah-route", "player", "fixture-video"],
                            anchor: .button("unused"))
        XCUIDevice.shared.orientation = .portrait
        let app = launch(screen, locale: Self.locales[0], extraArguments: [])
        let retryButton = app.buttons["player.state.retryButton"]
        XCTAssertTrue(retryButton.waitForExistence(timeout: 20), "player-recovery-exhausted: retry button never appeared")
        try write(named: "player-recovery-exhausted-state", into: directory)
    }

    // MARK: - B1 task 10: iPad / RTL / Dynamic Type / VoiceOver pass
    // (docs/superpowers/plans/2026-08-24-ios-phase2b1-player-core.md, spec §6.11)

    /// iPhone leg: en portrait (playing + metadata), en landscape (metadata hidden -- compact
    /// vertical size class), ar portrait (RTL leading-aligned metadata), en `.accessibility3`
    /// portrait (no clipped title/channel/views/toolbar/state text). Same fixture-HLS launch as
    /// `testPlayerMetadataAndToolbar` so a real title/channel/description/views panel is on
    /// screen, not an empty one.
    func testPlayerB1Task10IPhone() throws {
        let directory = try shotsDirectory()
        // No `.element("Video")` anchor here (unlike the other B1 player tests): AVKit's content
        // view label is a SYSTEM-localized string ("Video" only under English), so it never
        // matches under the ar leg below. `player.metadata.title` is our own accessibility
        // identifier -- locale-independent, and a strictly stronger readiness signal anyway (it
        // only renders inside `PlayerScreen`'s `.ready`/`.rung2Progressive` branch alongside
        // `PlayerHostView`, so its existence already proves the player is mounted).
        let screen = Screen(key: "player-b1-task10",
                            arguments: ["-fitrah-fake-player-hls", "-fitrah-route", "player", "fixture-video"],
                            anchor: .button("unused"))

        XCUIDevice.shared.orientation = .portrait
        var app = launch(screen, locale: Self.locales[0], extraArguments: [])
        var title = app.staticTexts["player.metadata.title"]
        XCTAssertTrue(title.waitForExistence(timeout: 20), "b1-task10 iphone en portrait: metadata title never appeared")
        try write(named: "player-b1task10-iphone-en-portrait", into: directory)

        // Compact vertical size class (iPhone landscape) hides the metadata panel (spec §6.11).
        XCUIDevice.shared.orientation = .landscapeLeft
        settle(app, landscape: true)
        XCTAssertFalse(app.staticTexts["player.metadata.title"].exists,
                       "b1-task10 iphone en landscape: metadata must be hidden at compact vertical size class")
        // I1 (B1 final review): only the METADATA panel is compact-height-hidden. The toolbar
        // stays -- Favorite has no other route in from the player, so hiding it in landscape lost
        // the action entirely.
        XCTAssertTrue(app.buttons["player.favoriteButton"].exists,
                      "b1-task10 iphone en landscape: the toolbar must stay visible (spec §6.11 hides metadata only)")
        try write(named: "player-b1task10-iphone-en-landscape", into: directory)
        XCUIDevice.shared.orientation = .portrait

        // ar portrait -- fresh launch, locale is a launch argument.
        app = launch(screen, locale: Self.locales[1], extraArguments: [])
        title = app.staticTexts["player.metadata.title"]
        XCTAssertTrue(title.waitForExistence(timeout: 20), "b1-task10 iphone ar portrait: metadata title never appeared")
        try write(named: "player-b1task10-iphone-ar-portrait", into: directory)

        // en .accessibility3 portrait.
        app = launch(screen, locale: Self.locales[0], extraArguments: Self.accessibility3)
        title = app.staticTexts["player.metadata.title"]
        XCTAssertTrue(title.waitForExistence(timeout: 20), "b1-task10 iphone a11y3: metadata title never appeared")
        try write(named: "player-b1task10-iphone-en-a11y3-portrait", into: directory)
    }

    /// iPad leg: en portrait and en landscape, proving the content column is capped at
    /// `Size.playerMaxWidth` (spec §11 `content_max_width`) and metadata/toolbar stay visible in
    /// landscape -- iPad landscape is `.regular` vertical size class, not `.compact`, so only
    /// iPhone landscape hides the panel.
    func testPlayerB1Task10IPad() throws {
        let directory = try shotsDirectory()
        let screen = Screen(key: "player-b1-task10-ipad",
                            arguments: ["-fitrah-fake-player-hls", "-fitrah-route", "player", "fixture-video"],
                            anchor: .element("Video"))

        XCUIDevice.shared.orientation = .portrait
        let app = launch(screen, locale: Self.locales[0], extraArguments: [])
        let content = element(for: screen.anchor, in: app)
        XCTAssertTrue(content.waitForExistence(timeout: 20), "b1-task10 ipad en portrait: player never appeared")
        let title = app.staticTexts["player.metadata.title"]
        XCTAssertTrue(title.waitForExistence(timeout: 10), "b1-task10 ipad en portrait: metadata title never appeared")
        try write(named: "player-b1task10-ipad-en-portrait", into: directory)

        XCUIDevice.shared.orientation = .landscapeLeft
        settle(app, landscape: true)
        XCTAssertTrue(app.staticTexts["player.metadata.title"].exists,
                      "b1-task10 ipad en landscape: metadata must stay visible (regular vertical size class)")
        try write(named: "player-b1task10-ipad-en-landscape", into: directory)
        XCUIDevice.shared.orientation = .portrait
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
        case .firstSwitch:
            return app.switches.firstMatch
        case .secondButton:
            return app.buttons.element(boundBy: 1)
        case .element(let label):
            return app.otherElements.matching(NSPredicate(format: "label == %@", label)).firstMatch
        }
    }
}
