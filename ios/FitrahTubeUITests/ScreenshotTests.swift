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

    /// Plan B3 task 4: rung 3 -- the caption ABOVE the frame (never an overlay on the player, RMF),
    /// the 16:9 frame, and FitrahTube's toolbar + metadata below it. Every FitrahTube PLAYBACK
    /// control (quality / captions / audio-language / audio-only / rung-2 pill / PiP) is absent by
    /// construction: that whole overlay column lives in `PlayerScreen`'s `.ready`/`.rung2Progressive`
    /// branch, and this is a different branch with no `AVPlayer` at all.
    ///
    /// The IFrame itself exposes NOTHING to XCUITest (remote content) and on a machine with no
    /// network it never loads, so every assertion here anchors on FitrahTube's own identifiers.
    func testPlayerEmbedRung() throws {
        let directory = try shotsDirectory()
        let screen = Screen(key: "player-embed",
                            // A REAL 11-char id, unlike every other player shot's "fixture-video":
                            // `EmbedPage.html` refuses anything that fails `^[A-Za-z0-9_-]{11}$`
                            // (it is a substitution into a `<script>`), and a refusal takes the
                            // rung straight to the error card with no caption to capture.
                            arguments: ["-fitrah-fake-player-embed", "-fitrah-route", "player", Self.liveEmbeddableId],
                            anchor: .button("unused"))
        XCUIDevice.shared.orientation = .portrait
        let app = launch(screen, locale: Self.locales[0], extraArguments: [])
        let caption = app.staticTexts["player.embedCaption"]
        XCTAssertTrue(caption.waitForExistence(timeout: 20), "player-embed: caption never appeared")
        XCTAssertEqual(caption.label, "Playing in YouTube's player")
        XCTAssertFalse(app.buttons["player.qualityMenu.button"].exists,
                       "player-embed: the embed rung hides every FitrahTube playback control")
        XCTAssertFalse(app.buttons["player.audioOnly.button"].exists,
                       "player-embed: the embed rung hides every FitrahTube playback control")
        XCTAssertFalse(app.buttons["player.captionsMenu.button"].exists,
                       "player-embed: the embed rung hides every FitrahTube playback control")
        XCTAssertFalse(app.staticTexts["player.rung2Pill"].exists,
                       "player-embed: the rung-2 pill belongs to the native branch")
        // The toolbar and metadata are NOT playback controls and stay (plan task 4's layout ruling).
        XCTAssertTrue(app.buttons["player.shareButton"].waitForExistence(timeout: 10),
                      "player-embed: the toolbar stays below the frame")
        XCTAssertTrue(app.staticTexts["player.metadata.views"].exists,
                      "player-embed: the metadata panel stays below the toolbar")
        try write(named: "player-embed-rung", into: directory)
    }

    /// Plan B3 task 4: ENDED -> FitrahTube's own opaque Replay/Back cover over the frame, so
    /// YouTube's end-screen recommendation cards are never visible or tappable. `-fitrah-fake-embed-ended`
    /// seeds the cover (same `#if DEBUG` launch-arg technique as `-fitrah-fake-player-recovery-exhausted`)
    /// because driving a real ENDED needs a real IFrame load, a network and a short video.
    func testPlayerEmbedEnded() throws {
        let directory = try shotsDirectory()
        let screen = Screen(key: "player-embed-ended",
                            arguments: ["-fitrah-fake-player-embed", "-fitrah-fake-embed-ended",
                                        "-fitrah-route", "player", Self.liveEmbeddableId],
                            anchor: .button("unused"))
        XCUIDevice.shared.orientation = .portrait
        let app = launch(screen, locale: Self.locales[0], extraArguments: [])
        let replay = app.buttons["player.embedReplay"]
        XCTAssertTrue(replay.waitForExistence(timeout: 20), "player-embed-ended: replay never appeared")
        XCTAssertTrue(app.buttons["player.embedBack"].exists, "player-embed-ended: back never appeared")
        try write(named: "player-embed-ended-cover", into: directory)
    }

    // MARK: - B4 task 3: Shorts chrome (docs/superpowers/plans/2026-08-27-ios-phase2b4-shorts.md)

    /// The Shorts surface with its own chrome: rail, kebab, scrubber, channel row. Anchors only on
    /// `shorts.*` / `player.*` identifiers -- the stock transport is OFF on this presentation, so
    /// there is no AVKit chrome even in principle. The fixture clip is single-track, so the
    /// audio-language menu must stay hidden (ruling 52 -- the same proof
    /// `testPlayerAudioLanguageMenuHiddenForSingleTrackFixture` uses). en + ar, portrait only:
    /// the screen is portrait-locked on iPhone.
    func testShortsScreen() throws {
        let directory = try shotsDirectory()
        let screen = Screen(key: "shorts",
                            arguments: ["-fitrah-fake-player", "-fitrah-route", "shorts", Self.liveEmbeddableId],
                            anchor: .button("unused"))
        for locale in Self.locales {
            XCUIDevice.shared.orientation = .portrait
            let app = launch(screen, locale: locale, extraArguments: [])
            let like = app.buttons["shorts.likeButton"]
            XCTAssertTrue(like.waitForExistence(timeout: 20), "shorts/\(locale.key): like button never appeared")
            XCTAssertTrue(app.descendants(matching: .any)["shorts.stage"].exists, "shorts/\(locale.key): stage missing")
            XCTAssertTrue(app.buttons["shorts.kebab.button"].exists, "shorts/\(locale.key): kebab missing")
            XCTAssertTrue(app.sliders["shorts.scrubber"].exists, "shorts/\(locale.key): scrubber missing")
            XCTAssertTrue(app.buttons["shorts.shareButton"].exists, "shorts/\(locale.key): share missing")
            XCTAssertTrue(app.buttons["shorts.back"].exists, "shorts/\(locale.key): back missing")
            XCTAssertTrue(app.buttons["shorts.channelHandle"].exists, "shorts/\(locale.key): channel handle missing")
            XCTAssertFalse(app.buttons["player.audioLanguageMenu.button"].waitForExistence(timeout: 3),
                           "shorts/\(locale.key): audio-language menu must hide for a single-track asset")
            XCTAssertFalse(app.buttons["player.captionsMenu.button"].exists,
                           "shorts/\(locale.key): captions menu must hide with no tracks")
            try write(named: "shorts-\(locale.key)-\(locale.theme)", into: directory)
        }
        // Accessibility 3 (spec §6.11: title/handle scale, glyphs do not).
        let app = launch(screen, locale: Self.locales[0], extraArguments: Self.accessibility3)
        XCTAssertTrue(app.buttons["shorts.likeButton"].waitForExistence(timeout: 20), "shorts a11y3: like never appeared")
        try write(named: "shorts-en-a11y3", into: directory)
    }

    /// The kebab open: Quality submenu + Report (ruling 53). Report is the coming-soon banner
    /// `PlayerToolbar.reportButton` shows (CF-B1-9: one report path).
    func testShortsKebab() throws {
        let directory = try shotsDirectory()
        let screen = Screen(key: "shorts-kebab",
                            arguments: ["-fitrah-fake-player", "-fitrah-route", "shorts", Self.liveEmbeddableId],
                            anchor: .button("unused"))
        XCUIDevice.shared.orientation = .portrait
        let app = launch(screen, locale: Self.locales[0], extraArguments: [])
        let kebab = app.buttons["shorts.kebab.button"]
        XCTAssertTrue(kebab.waitForExistence(timeout: 20), "shorts-kebab: kebab never appeared")
        kebab.tap()
        let report = app.buttons["shorts.kebab.report"]
        XCTAssertTrue(report.waitForExistence(timeout: 10), "shorts-kebab: menu never opened")
        try write(named: "shorts-kebab-open", into: directory)
    }

    // MARK: - B3 task 5: acceptance matrix + the live-YouTube pass

    /// The live checks load real videos, so the ids are drawn ONLY from the app's approved catalog
    /// (owner directive 2026-08-27: never an arbitrary YouTube video, and never a music video --
    /// this is a Muslim audience app). Both come from the InnerTubeKit browse fixtures, i.e. the
    /// curated lecture channels this app actually serves:
    ///   - `xc7keR2piUM`  the shared catalog lecture id (`LiveResolveTests.knownGoodVideoId`), 2h21m
    ///   - `16QZlP5da1Y`  a 52 s catalog clip -- short enough to reach ENDED without a long seek
    ///   - `aaaaaaaaaaa`  NOT a video at all: a well-formed id that matches nothing, which is how
    ///                    IFrame error 100 ("removed or private") is provoked without loading
    ///                    anyone's content.
    /// There is no live check for IFrame error 101/150: every video in the approved catalog is
    /// embeddable (all 19 fixture ids probed 2026-08-27, `"playableInEmbed":true`), and reaching
    /// that code would mean loading a video from outside the catalog. `EmbedPolicyTests` pins the
    /// 101/150 mapping instead.
    private static let liveEmbeddableId = "xc7keR2piUM"
    private static let liveShortId = "16QZlP5da1Y"
    private static let liveRemovedId = "aaaaaaaaaaa"

    /// The live checks talk to youtube.com. They are OFF by default (a red test on a machine with
    /// no network proves nothing) and run under `EMBED_LIVE=1`, exactly like InnerTubeKit's
    /// `LiveResolveTests` and its `INNERTUBE_LIVE`. `ios/scripts/screenshots.sh` passes it through
    /// as `TEST_RUNNER_EMBED_LIVE`.
    private func requireLive() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["EMBED_LIVE"] == "1",
                          "live IFrame checks are opt-in: EMBED_LIVE=1")
    }

    private func embedScreen(_ videoId: String, extra: [String] = []) -> Screen {
        Screen(key: "player-embed-live",
               arguments: ["-fitrah-fake-player-embed"] + extra + ["-fitrah-route", "player", videoId],
               anchor: .button("unused"))
    }

    /// Plan §6.4 row 3's floor is "at least 200x200 pt of video". This reads the WKWebView's own
    /// frame (points, not pixels) rather than trusting the 16:9 aspect ratio to have produced it.
    private func assertEmbedFrameFloor(_ app: XCUIApplication, _ label: String) {
        let frame = app.webViews["player.embedFrame"]
        XCTAssertTrue(frame.waitForExistence(timeout: 20), "\(label): embed frame never appeared")
        XCTAssertGreaterThanOrEqual(frame.frame.width, 200, "\(label): embed frame narrower than 200 pt")
        XCTAssertGreaterThanOrEqual(frame.frame.height, 200, "\(label): embed frame shorter than 200 pt")
    }

    /// iPhone leg of the B3 acceptance matrix: en portrait at Dynamic Type `.accessibility3` (the
    /// caption wraps, it does not clip) and ar portrait (RTL -- the caption is leading-aligned and
    /// mirrors; the 16:9 frame does not).
    func testPlayerEmbedB3Task5IPhone() throws {
        let directory = try shotsDirectory()
        let screen = embedScreen(Self.liveEmbeddableId)

        XCUIDevice.shared.orientation = .portrait
        var app = launch(screen, locale: Self.locales[0], extraArguments: Self.accessibility3)
        XCTAssertTrue(app.staticTexts["player.embedCaption"].waitForExistence(timeout: 20),
                      "b3-task5 iphone a11y3: caption never appeared")
        assertEmbedFrameFloor(app, "b3-task5 iphone a11y3")
        try write(named: "player-embed-iphone-en-a11y3-portrait", into: directory)

        app = launch(screen, locale: Self.locales[1], extraArguments: [])
        let caption = app.staticTexts["player.embedCaption"]
        XCTAssertTrue(caption.waitForExistence(timeout: 20), "b3-task5 iphone ar: caption never appeared")
        XCTAssertEqual(caption.label, "يتم التشغيل في مشغّل يوتيوب",
                       "b3-task5 iphone ar: the caption must be the Arabic copy, not the key or the en value")
        assertEmbedFrameFloor(app, "b3-task5 iphone ar")
        try write(named: "player-embed-iphone-ar-portrait", into: directory)
    }

    /// iPad leg: portrait and landscape, both over the 200x200 pt floor with the content column
    /// capped at `Size.playerMaxWidth`.
    func testPlayerEmbedB3Task5IPad() throws {
        let directory = try shotsDirectory()
        let screen = embedScreen(Self.liveEmbeddableId)

        XCUIDevice.shared.orientation = .portrait
        let app = launch(screen, locale: Self.locales[0], extraArguments: [])
        XCTAssertTrue(app.staticTexts["player.embedCaption"].waitForExistence(timeout: 20),
                      "b3-task5 ipad portrait: caption never appeared")
        assertEmbedFrameFloor(app, "b3-task5 ipad portrait")
        try write(named: "player-embed-ipad-en-portrait", into: directory)

        XCUIDevice.shared.orientation = .landscapeLeft
        settle(app, landscape: true)
        assertEmbedFrameFloor(app, "b3-task5 ipad landscape")
        try write(named: "player-embed-ipad-en-landscape", into: directory)
        XCUIDevice.shared.orientation = .portrait
    }

    // MARK: - B3 task 5 step 2: live IFrame checks (EMBED_LIVE=1)

    /// The bridge's own record of what the web content process did. `-fitrah-embed-debug-events`
    /// renders it under the frame (DEBUG only) because a `WKWebView`'s remote content exposes
    /// nothing else an XCUITest can assert on.
    private func embedLog(_ app: XCUIApplication) -> String {
        // `.label` on a missing element THROWS ("no matches found"), so the existence check is not
        // defensive padding -- the log view only appears once the first event lands, and the first
        // poll always runs before that.
        let element = app.staticTexts["player.embedDebugLog"]
        return element.exists ? element.label : ""
    }

    private func waitForEmbedLog(_ app: XCUIApplication, containing needle: String,
                                 timeout: TimeInterval = 45) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if embedLog(app).contains(needle) { return true }
            Thread.sleep(forTimeInterval: 0.5)
        }
        return false
    }

    /// Live 1: a real embeddable video loads, the `origin` player var is accepted (error 153 is the
    /// "no/!bad Referer" answer and must never appear), and the page AUTOPLAYS off `onReady` --
    /// state 1 arrives without anyone tapping anything.
    func testEmbedLivePlaysAndAutoplays() throws {
        try requireLive()
        let directory = try shotsDirectory()
        let app = launch(embedScreen(Self.liveEmbeddableId, extra: ["-fitrah-embed-debug-events"]),
                         locale: Self.locales[0], extraArguments: [])
        XCTAssertTrue(app.staticTexts["player.embedCaption"].waitForExistence(timeout: 20),
                      "live: caption never appeared")
        XCTAssertTrue(waitForEmbedLog(app, containing: "ready"), "live: onReady never arrived — log: \(embedLog(app))")
        XCTAssertTrue(waitForEmbedLog(app, containing: "state(1)"),
                      "live: never autoplayed (no state 1) — log: \(embedLog(app))")
        let log = embedLog(app)
        XCTAssertFalse(log.contains("error(153)"), "live: origin rejected (error 153) — log: \(log)")
        XCTAssertFalse(log.contains("error("), "live: unexpected IFrame error — log: \(log)")
        print("[EMBED_LIVE] playsAndAutoplays log:\n\(log)")
        try write(named: "live-embed-playing", into: directory)
    }

    /// Live 2: every escape the IFrame offers (title, channel avatar, YouTube logo, "Watch on
    /// YouTube", share, end-screen cards) is a MAIN-FRAME navigation, and the lock cancels every
    /// one. Asserted from the lock's own verdicts: no main-frame navigation off
    /// `app.fitrahtube.com` may ever be allowed.
    func testEmbedLiveNavigationLockCancelsEveryEscape() throws {
        try requireLive()
        let directory = try shotsDirectory()
        let app = launch(embedScreen(Self.liveEmbeddableId, extra: ["-fitrah-embed-debug-events"]),
                         locale: Self.locales[0], extraArguments: [])
        XCTAssertTrue(waitForEmbedLog(app, containing: "state(1)"),
                      "live nav lock: video never started — log: \(embedLog(app))")
        let frame = app.webViews["player.embedFrame"]
        XCTAssertTrue(frame.exists, "live nav lock: embed frame never appeared")

        // Reveal the player chrome, then hit each escape in turn: the title bar (title + channel
        // avatar + "Watch on YouTube"/share at the top of the frame) and the YouTube logo in the
        // bottom-right of the control bar.
        let targets: [(String, CGVector)] = [
            ("center/controls", CGVector(dx: 0.5, dy: 0.25)),
            ("title", CGVector(dx: 0.25, dy: 0.12)),
            ("watch-on-youtube/share", CGVector(dx: 0.92, dy: 0.12)),
            ("channel avatar", CGVector(dx: 0.06, dy: 0.12)),
            ("youtube logo", CGVector(dx: 0.9, dy: 0.9))
        ]
        for (name, offset) in targets {
            frame.coordinate(withNormalizedOffset: offset).tap()
            Thread.sleep(forTimeInterval: 1.5)
            XCTAssertEqual(app.state, .runningForeground, "live nav lock: \(name) backgrounded FitrahTube")
            XCTAssertTrue(app.staticTexts["player.embedCaption"].exists,
                          "live nav lock: \(name) left the embed screen")
        }

        let log = embedLog(app)
        print("[EMBED_LIVE] navigationLock log:\n\(log)")
        // Positive half (I3, B3 final review): five escape taps must produce at least one CANCEL
        // verdict, or the loop below passes vacuously over a log with no main-frame lines at all.
        XCTAssertTrue(log.contains("CANCEL"), "live nav lock: no escape was ever cancelled — log: \(log)")
        for line in log.split(separator: "\n").map(String.init) where line.hasPrefix("allow main ") {
            XCTAssertTrue(line.contains("https://app.fitrahtube.com") || line.contains("about:blank"),
                          "live nav lock: a main-frame navigation off the bundled page was ALLOWED: \(line)")
        }
        try write(named: "live-embed-after-escape-taps", into: directory)
    }

    /// Live 3: a genuinely short video reaches ENDED, the bridge carries state 0, the cover rises
    /// over YouTube's end screen, and Replay restarts it (state 1 again after the cover is gone).
    /// No `-fitrah-fake-embed-ended` here -- that seeds the cover with no IFrame behind it.
    func testEmbedLiveEndedRaisesCoverAndReplayRestarts() throws {
        try requireLive()
        let directory = try shotsDirectory()
        let app = launch(embedScreen(Self.liveShortId,
                                     extra: ["-fitrah-embed-debug-events", "-fitrah-embed-seek-to-end"]),
                         locale: Self.locales[0], extraArguments: [])
        XCTAssertTrue(waitForEmbedLog(app, containing: "state(0)"),
                      "live ended: state 0 never arrived — log: \(embedLog(app))")
        let replay = app.buttons["player.embedReplay"]
        XCTAssertTrue(replay.waitForExistence(timeout: 15),
                      "live ended: the cover never rose — log: \(embedLog(app))")
        XCTAssertTrue(app.buttons["player.embedBack"].exists, "live ended: Back missing from the cover")
        print("[EMBED_LIVE] ended log:\n\(embedLog(app))")
        try write(named: "live-embed-ended-cover", into: directory)

        replay.tap()
        // Replay seeks to 0 and plays: state 1 clears the cover through the bridge, never locally
        // (M2, Task 4 review). Polled rather than `expectation(for:evaluatedWith:)` -- that API
        // sends `self` across an isolation boundary, which Swift 6 rejects in this target.
        let deadline = Date().addingTimeInterval(30)
        while replay.exists, Date() < deadline { Thread.sleep(forTimeInterval: 0.5) }
        XCTAssertFalse(replay.exists, "live ended: Replay did not restart playback — log: \(embedLog(app))")
        print("[EMBED_LIVE] after replay log:\n\(embedLog(app))")
        try write(named: "live-embed-after-replay", into: directory)
    }

    /// Live 4: a well-formed but nonexistent id answers 100 -> the removed card. Terminal in both
    /// Safe Mode states, with no route out of the app in either (owner directive 2026-08-27).
    func testEmbedLiveRemovedCard() throws {
        try requireLive()
        let directory = try shotsDirectory()
        for (suffix, extra) in [("safemode-on", [String]()), ("safemode-off", ["-safe_mode", "NO"])] {
            let app = launch(embedScreen(Self.liveRemovedId,
                                         extra: ["-fitrah-embed-debug-events"] + extra),
                             locale: Self.locales[0], extraArguments: [])
            let message = app.staticTexts["player.state.message"]
            XCTAssertTrue(message.waitForExistence(timeout: 45),
                          "live removed (\(suffix)): never left the embed — log: \(embedLog(app))")
            let copy = message.label
            print("[EMBED_LIVE] removed(\(suffix)) copy=\(copy) log:\n\(embedLog(app))")
            // The exact code is YouTube's to choose (observed live 2026-08-27: 150, not the 100 the
            // plan predicted for a nonexistent id), so the assertion is the INVARIANT rather than
            // the code: whatever it answers, the rung is terminal, silent about YouTube, and has no
            // Retry that could only fail again.
            XCTAssertTrue(["This video was removed", "This video is not available for playback."].contains(copy),
                          "live removed (\(suffix)): unexpected terminal copy \(copy)")
            XCTAssertFalse(app.buttons["player.state.retryButton"].exists,
                           "live removed (\(suffix)): a terminal embed error must offer no Retry")
            XCTAssertEqual(app.buttons.matching(NSPredicate(format: "label CONTAINS 'YouTube'")).count, 0,
                           "live removed (\(suffix)): no control may route the user out to YouTube")
            try write(named: "live-embed-removed-\(suffix)", into: directory)
        }
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
        // CF-B2-11: the old assertion proved nothing -- it only checked the title existed, which it
        // does at any width. The constraint under test is Size.playerMaxWidth (1600 pt on `.large`),
        // so assert the video box's own frame against it AND against the window, which is what a
        // regression (an unconstrained full-width column) would actually break.
        let box = app.otherElements["player.videoBox"]
        XCTAssertTrue(box.waitForExistence(timeout: 10), "ipad: player.videoBox never appeared")
        XCTAssertLessThanOrEqual(box.frame.width, 1600, "ipad: player column exceeds playerMaxWidth")
        XCTAssertLessThan(box.frame.width, app.frame.width,
                          "ipad landscape: the player column must be narrower than the window")
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

    // MARK: - B4 task 4: Shorts acceptance matrix (docs/superpowers/plans/2026-08-27-ios-phase2b4-shorts.md)

    private func shortsScreen(_ arguments: [String]) -> Screen {
        Screen(key: "shorts-b4-task4", arguments: arguments, anchor: .button("unused"))
    }

    private static let shortsFixture = ["-fitrah-fake-player", "-fitrah-route", "shorts", liveEmbeddableId]

    /// Everything the plan's Step 1 matrix can prove from XCUITest on an iPhone: frames (every tap
    /// target >= 44 pt, the 9:16 stage, the rail's edge in ar), the loop (30 s of scrubber samples
    /// with no stall), the tap indicator, the scrub bar, Like x10 with the audio/CC buttons staying
    /// absent, the kebab, the channel row's presence rule, Back, the edge swipe, the portrait lock,
    /// Dynamic Type, the embed arm and the three status states. Measurements go to
    /// `b4-task4-measurements.txt` next to the PNGs so the report quotes numbers, not eyeballs.
    func testShortsB4Task4IPhone() throws {
        let directory = try shotsDirectory()
        var notes: [String] = []
        defer { try? notes.joined(separator: "\n").write(to: directory.appendingPathComponent("b4-task4-iphone-measurements.txt"), atomically: true, encoding: .utf8) }

        // -- en: frames, loop, tap, scrub, rail, kebab, share --
        XCUIDevice.shared.orientation = .portrait
        var app = launch(shortsScreen(Self.shortsFixture), locale: Self.locales[0], extraArguments: [])
        let like = app.buttons["shorts.likeButton"]
        XCTAssertTrue(like.waitForExistence(timeout: 20), "b4t4 en: like never appeared")
        let stage = app.descendants(matching: .any)["shorts.stage"]
        XCTAssertTrue(stage.exists, "b4t4 en: stage missing")
        notes += measureShortsChrome(app, label: "iphone-en")
        XCTAssertTrue(app.tabBars.firstMatch.exists, "b4t4 en: tab bar must stay visible on iPhone (ruling 57)")
        notes.append("iphone-en tabBar frame=\(app.tabBars.firstMatch.frame) window=\(app.windows.firstMatch.frame)")
        try write(named: "shorts-b4t4-iphone-en-portrait", into: directory)

        // Loop: 30 s of scrubber samples on the 2 s fixture. A restart is a value decrease; a stall
        // is any value that stands still longer than the clip itself.
        let loop = sampleScrubber(app, seconds: 30)
        notes.append("iphone-en loop: restarts=\(loop.restarts) maxSeconds=\(loop.maxSeconds) longestFlatRun=\(String(format: "%.2f", loop.longestFlat))s samples=\(loop.samples)")
        XCTAssertGreaterThanOrEqual(loop.restarts, 8, "b4t4 en loop: expected the 2 s fixture to restart >= 8 times in 30 s, saw \(loop.restarts)")
        XCTAssertLessThan(loop.longestFlat, 2.5, "b4t4 en loop: scrubber stood still for \(loop.longestFlat)s (stall / re-buffer)")

        // Tap once -> pause glyph appears and STAYS; tap again -> play glyph flashes and fades.
        let indicator = app.images["shorts.playPauseIndicator"]
        stage.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.4)).tap()
        XCTAssertTrue(indicator.waitForExistence(timeout: 3), "b4t4 en tap: pause indicator never appeared")
        Thread.sleep(forTimeInterval: 1.5)
        XCTAssertTrue(indicator.exists, "b4t4 en tap: the paused glyph must stay while paused")
        notes.append("iphone-en indicator frame=\(indicator.frame)")
        try write(named: "shorts-b4t4-iphone-en-paused", into: directory)
        stage.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.4)).tap()
        // I2 (final review): `waitForExistence` polls too slowly to catch a 600 ms element; a tight
        // `.exists` loop straight after the tap is the fastest observation XCUITest offers.
        var flashed = false
        let flashDeadline = Date().addingTimeInterval(1.2)
        repeat { flashed = indicator.exists } while !flashed && Date() < flashDeadline
        Thread.sleep(forTimeInterval: 1.5)
        XCTAssertFalse(indicator.exists, "b4t4 en tap: the play glyph must fade after ~600 ms")
        notes.append("iphone-en play glyph flashed=\(flashed) gone-after-1.5s=\(!indicator.exists)")
        XCTAssertTrue(flashed, "b4t4 en tap: the play glyph never appeared within 1.2 s of the resume tap")

        // M5: a tap on the channel/title scrim must still reach the play/pause target.
        app.staticTexts["shorts.title"].coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
        let scrimTapPaused = indicator.waitForExistence(timeout: 3)
        notes.append("iphone-en tap on title scrim toggled play/pause=\(scrimTapPaused)")
        XCTAssertTrue(scrimTapPaused, "b4t4 en M5: the title scrim swallowed the stage tap")
        if !scrimTapPaused { stage.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.4)).tap() }

        // Scrub (paused): a real finger drag of the thumb to 75 % (1.5 s -> "0:01"). The value
        // string is the accessibility value ("m:ss") -- the observer only rewrites it once the seek
        // lands, so a stale value here means the release did not seek. M9 (final review): the
        // past-the-end drag is RECORDED, not asserted. Diagnosed 2026-08-28 (six drag variants,
        // thumb-located and raw, forward and back): only the FIRST drag on this 2 s fixture yields
        // a value matching its target; every later one reports a time inconsistent with where the
        // thumb was released, so the string is not an oracle for a second seek. The clamp itself is
        // pinned by `ShortsScreenTests.scrubMathsRoundTripsAndSurvivesADegenerateDuration`.
        let slider = app.sliders["shorts.scrubber"]
        let thumbStart = slider.coordinate(withNormalizedOffset: CGVector(dx: 0.0, dy: 0.5))
        thumbStart.press(forDuration: 0.3, thenDragTo: slider.coordinate(withNormalizedOffset: CGVector(dx: 0.75, dy: 0.5)))
        Thread.sleep(forTimeInterval: 1)
        let mid = slider.value as? String ?? ""
        slider.coordinate(withNormalizedOffset: CGVector(dx: 0.75, dy: 0.5))
            .press(forDuration: 0.3, thenDragTo: slider.coordinate(withNormalizedOffset: CGVector(dx: 1.4, dy: 0.5)))
        Thread.sleep(forTimeInterval: 1)
        let end = slider.value as? String ?? ""
        notes.append("iphone-en scrub: after drag to 0.75 value=\(mid); after drag past the end value=\(end) (recorded only, see M9 note)")
        XCTAssertEqual(mid, "0:01", "b4t4 en scrub: expected 0:01 after a drag to 75 % of the 2 s fixture, got \(mid)")

        // Rail: Like x10 (ruling 52) -- the audio-language and CC buttons stay absent throughout.
        let audio = app.buttons["player.audioLanguageMenu.button"]
        let cc = app.buttons["player.captionsMenu.button"]
        var flicker = 0
        for i in 1...10 {
            like.tap()
            if audio.exists || cc.exists { flicker += 1 }
            let expected = i % 2 == 1
            XCTAssertEqual(like.isSelected, expected, "b4t4 en like #\(i): selected should be \(expected)")
        }
        notes.append("iphone-en like x10: audio/cc appearances=\(flicker) final value=\(like.value ?? "nil") selected=\(like.isSelected)")
        XCTAssertEqual(flicker, 0, "b4t4 en like x10: audio/CC buttons flickered in \(flicker) times")
        XCTAssertFalse(audio.exists); XCTAssertFalse(cc.exists)
        try write(named: "shorts-b4t4-iphone-en-liked-banner", into: directory)

        // Share: the sheet opens (system UI; only its presence is asserted) and is dismissed.
        app.buttons["shorts.shareButton"].tap()
        let sheet = app.otherElements["ActivityListView"]
        let sheetShown = sheet.waitForExistence(timeout: 10)
        notes.append("iphone-en share sheet shown=\(sheetShown)")
        XCTAssertTrue(sheetShown, "b4t4 en share: share sheet never appeared")
        try write(named: "shorts-b4t4-iphone-en-share", into: directory)
        if app.buttons["Close"].exists { app.buttons["Close"].tap() } else { app.swipeDown() }
        _ = like.waitForExistence(timeout: 5)

        // Kebab: five quality options, pick 480p, Report shows the coming-soon banner.
        app.buttons["shorts.kebab.button"].tap()
        XCTAssertTrue(app.buttons["shorts.kebab.report"].waitForExistence(timeout: 10), "b4t4 en kebab: never opened")
        let options = ["auto", "p1080", "p720", "p480", "dataSaver"].map { app.buttons["shorts.qualityOption.\($0)"].exists }
        notes.append("iphone-en kebab quality options present=\(options)")
        XCTAssertEqual(options.filter { $0 }.count, 5, "b4t4 en kebab: expected 5 quality options, present=\(options)")
        try write(named: "shorts-b4t4-iphone-en-kebab", into: directory)
        app.buttons["shorts.qualityOption.p480"].tap()
        app.buttons["shorts.kebab.button"].tap()
        XCTAssertTrue(app.buttons["shorts.kebab.report"].waitForExistence(timeout: 10))
        try write(named: "shorts-b4t4-iphone-en-kebab-480p", into: directory)
        app.buttons["shorts.kebab.report"].tap()
        // The banner auto-dismisses after 2.5 s and an XCUITest snapshot of this screen can take
        // longer than that on a loaded machine (caught in 3 of 5 runs) -- recorded, not asserted.
        // `testPlayerMetadataAndToolbar` covers the same `player_report_coming_soon` path.
        let bannerCaught = app.staticTexts["Reporting is coming soon"].waitForExistence(timeout: 5)
        notes.append("iphone-en report banner caught within 5 s=\(bannerCaught)")
        XCTAssertTrue(app.buttons["shorts.kebab.report"].waitForNonExistence(timeout: 5), "b4t4 en report: menu did not close on tap")
        try write(named: "shorts-b4t4-iphone-en-report-banner", into: directory)

        // Back: our own button pops the route; the tab bar is still there.
        app.buttons["shorts.back"].tap()
        XCTAssertTrue(stage.waitForNonExistence(timeout: 5), "b4t4 en back: stage still present after Back")
        XCTAssertTrue(app.tabBars.firstMatch.exists)

        // Edge swipe back (fork D): the hidden navigation bar must not kill the interactive pop.
        app = launch(shortsScreen(Self.shortsFixture), locale: Self.locales[0], extraArguments: [])
        XCTAssertTrue(app.buttons["shorts.likeButton"].waitForExistence(timeout: 20))
        let edgeStart = app.coordinate(withNormalizedOffset: CGVector(dx: 0.001, dy: 0.5))
        edgeStart.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)))
        let popped = app.descendants(matching: .any)["shorts.stage"].waitForNonExistence(timeout: 5)
        notes.append("iphone-en edge-swipe popped=\(popped)")
        XCTAssertTrue(popped, "b4t4 en: swipe-from-edge back did not pop the Shorts screen")

        // Portrait lock (iPhone only): rotate the device, the window stays portrait; Back, and the
        // rest of the app rotates again.
        app = launch(shortsScreen(Self.shortsFixture), locale: Self.locales[0], extraArguments: [])
        XCTAssertTrue(app.buttons["shorts.likeButton"].waitForExistence(timeout: 20))
        XCUIDevice.shared.orientation = .landscapeLeft
        Thread.sleep(forTimeInterval: 3)
        var window = app.windows.firstMatch.frame
        notes.append("iphone-en rotated-on-shorts window=\(window)")
        XCTAssertLessThan(window.width, window.height, "b4t4 en lock: Shorts window went landscape \(window)")
        try write(named: "shorts-b4t4-iphone-en-locked-while-rotated", into: directory)
        app.buttons["shorts.back"].tap()
        var deadline = Date().addingTimeInterval(8)
        repeat { window = app.windows.firstMatch.frame; Thread.sleep(forTimeInterval: 0.25) } while window.width < window.height && Date() < deadline
        notes.append("iphone-en after-back (device still sideways) window=\(window)")
        XCTAssertGreaterThan(window.width, window.height, "b4t4 en lock: app did not follow the sideways device after leaving Shorts \(window)")
        // Distinguish "lock leaked" from "no auto re-rotation": rotate again and re-read.
        XCUIDevice.shared.orientation = .portrait
        Thread.sleep(forTimeInterval: 2)
        XCUIDevice.shared.orientation = .landscapeLeft
        deadline = Date().addingTimeInterval(8)
        repeat { window = app.windows.firstMatch.frame; Thread.sleep(forTimeInterval: 0.25) } while window.width < window.height && Date() < deadline
        notes.append("iphone-en after-back (re-rotated) window=\(window)")
        XCTAssertGreaterThan(window.width, window.height, "b4t4 en lock: the lock leaked -- the app never rotates again \(window)")
        try write(named: "shorts-b4t4-iphone-en-after-back-landscape", into: directory)
        XCUIDevice.shared.orientation = .portrait

        // Channel row: absent through the id-only deep link.
        app = launch(shortsScreen(["-fitrah-fake-player", "-fitrah-deeplink", "albunyaantube://shorts/\(Self.liveEmbeddableId)"]),
                     locale: Self.locales[0], extraArguments: [])
        XCTAssertTrue(app.buttons["shorts.likeButton"].waitForExistence(timeout: 20), "b4t4 deeplink: like never appeared")
        XCTAssertFalse(app.buttons["shorts.channelHandle"].exists, "b4t4 deeplink: channel row must be absent without a channel name")
        try write(named: "shorts-b4t4-iphone-en-deeplink-no-channel", into: directory)

        // -- ar (RTL): the rail mirrors to the LEFT (Android alignParentEnd), the handle keeps its LRM.
        app = launch(shortsScreen(Self.shortsFixture), locale: Self.locales[1], extraArguments: [])
        XCTAssertTrue(app.buttons["shorts.likeButton"].waitForExistence(timeout: 20), "b4t4 ar: like never appeared")
        notes += measureShortsChrome(app, label: "iphone-ar")
        let arWindow = app.windows.firstMatch.frame
        XCTAssertLessThan(app.buttons["shorts.likeButton"].frame.midX, arWindow.midX, "b4t4 ar: rail must mirror to the left half")
        XCTAssertGreaterThan(app.buttons["shorts.channelHandle"].frame.midX, arWindow.midX, "b4t4 ar: channel row must mirror to the right half")
        let handle = app.buttons["shorts.channelHandle"].label
        notes.append("iphone-ar handle label=\(handle.debugDescription) unicodeScalars=\(handle.unicodeScalars.prefix(3).map { String(format: "U+%04X", $0.value) })")
        XCTAssertTrue(handle.unicodeScalars.contains("\u{200E}"), "b4t4 ar: the @handle lost its LRM")
        try write(named: "shorts-b4t4-iphone-ar-portrait", into: directory)

        // -- Dynamic Type .accessibility3: the title stays within 2 lines, the rail glyphs do not grow.
        app = launch(shortsScreen(Self.shortsFixture), locale: Self.locales[0], extraArguments: Self.accessibility3)
        XCTAssertTrue(app.buttons["shorts.likeButton"].waitForExistence(timeout: 20), "b4t4 a11y3: like never appeared")
        notes += measureShortsChrome(app, label: "iphone-en-a11y3")
        let a11yLike = app.buttons["shorts.likeButton"].frame
        let a11yWindow = app.windows.firstMatch.frame
        XCTAssertEqual(a11yLike.width, 44, accuracy: 1, "b4t4 a11y3: rail glyph grew to \(a11yLike.width)")
        XCTAssertLessThanOrEqual(a11yLike.maxX, a11yWindow.maxX, "b4t4 a11y3: rail pushed off screen")
        try write(named: "shorts-b4t4-iphone-en-a11y3", into: directory)

        // -- Embed arm at 9:16 (`-fitrah-fake-player-embed`): frame ratio, the 200x200 floor, caption above.
        app = launch(shortsScreen(["-fitrah-fake-player-embed", "-fitrah-route", "shorts", Self.liveEmbeddableId]),
                     locale: Self.locales[0], extraArguments: [])
        let caption = app.staticTexts["player.embedCaption"]
        XCTAssertTrue(caption.waitForExistence(timeout: 20), "b4t4 embed: caption never appeared")
        assertEmbedFrameFloor(app, "b4t4 embed")
        let frame = app.webViews["player.embedFrame"].frame
        notes.append("iphone-en embed frame=\(frame) ratio(h/w)=\(frame.height / frame.width) caption=\(caption.frame)")
        XCTAssertEqual(frame.height / frame.width, 16.0 / 9.0, accuracy: 0.05, "b4t4 embed: frame is not 9:16")
        XCTAssertLessThanOrEqual(caption.frame.maxY, frame.minY + 1, "b4t4 embed: caption must sit above the frame")
        XCTAssertTrue(app.buttons["shorts.back"].exists, "b4t4 embed: Back missing on the embed arm")
        try write(named: "shorts-b4t4-iphone-en-embed", into: directory)

        // -- Status states on the Shorts background: Retry only where it belongs.
        for (flag, name, retry) in [("-fitrah-fake-player-error", "error", true),
                                    ("-fitrah-fake-player-unavailable", "unavailable", false),
                                    ("-fitrah-fake-player-cooldown", "cooldown", false)] {
            app = launch(shortsScreen([flag, "-fitrah-route", "shorts", Self.liveEmbeddableId]), locale: Self.locales[0], extraArguments: [])
            let message = name == "cooldown" ? app.staticTexts["player.state.countdown"] : app.staticTexts["player.state.message"]
            XCTAssertTrue(message.waitForExistence(timeout: 20), "b4t4 \(name): state message never appeared")
            XCTAssertEqual(app.buttons["player.state.retryButton"].exists, retry, "b4t4 \(name): Retry presence should be \(retry)")
            XCTAssertTrue(app.buttons["shorts.back"].exists, "b4t4 \(name): Back missing")
            notes.append("iphone-en \(name): message=\(message.label) retry=\(app.buttons["player.state.retryButton"].exists)")
            try write(named: "shorts-b4t4-iphone-en-state-\(name)", into: directory)
        }
    }

    /// iPad leg: the 9:16 stage is a centred, letterboxed column (not full-bleed) in en and ar; the
    /// device rotates WITH the screen (no `UIRequiresFullScreen`; the mask is ignored under
    /// multitasking -- expected, per the Task 2 fix-round ruling), and the column survives it.
    func testShortsB4Task4IPad() throws {
        let directory = try shotsDirectory()
        var notes: [String] = []
        defer { try? notes.joined(separator: "\n").write(to: directory.appendingPathComponent("b4-task4-ipad-measurements.txt"), atomically: true, encoding: .utf8) }

        for locale in Self.locales {
            XCUIDevice.shared.orientation = .portrait
            let app = launch(shortsScreen(Self.shortsFixture), locale: locale, extraArguments: [])
            XCTAssertTrue(app.buttons["shorts.likeButton"].waitForExistence(timeout: 20), "b4t4 ipad \(locale.key): like never appeared")
            notes += measureShortsChrome(app, label: "ipad-\(locale.key)")
            let stage = app.descendants(matching: .any)["shorts.stage"].frame
            // `app.windows` reports a zero frame on this iPad and the sidebar tab rail is not a
            // `TabBar` element, so the content area cannot be queried: the column is centred in the
            // screen MINUS the 96 pt rail, i.e. offset 48 pt from the screen's own centre (leading
            // in en, trailing in ar). The exact frames are in the notes; the assertion allows the
            // rail's half-width and the stage/rail containment check below is the strict one.
            let window = app.frame
            notes.append("ipad-\(locale.key) portrait screen=\(window) stage=\(stage) gaps L=\(stage.minX) R=\(window.width - stage.maxX)")
            XCTAssertEqual(stage.height / stage.width, 16.0 / 9.0, accuracy: 0.05, "b4t4 ipad \(locale.key): stage is not 9:16 \(stage)")
            XCTAssertLessThan(stage.width, window.width - 200, "b4t4 ipad \(locale.key): stage is full-bleed, not a letterboxed column")
            XCTAssertEqual(stage.midX, window.midX, accuracy: 50, "b4t4 ipad \(locale.key): stage column is not centred in the content area")
            // The chrome lives inside the column, not on the letterbox.
            let like = app.buttons["shorts.likeButton"].frame
            XCTAssertTrue(stage.insetBy(dx: -1, dy: -1).contains(like), "b4t4 ipad \(locale.key): the rail \(like) sits outside the stage \(stage)")
            try write(named: "shorts-b4t4-ipad-\(locale.key)-portrait", into: directory)

            XCUIDevice.shared.orientation = .landscapeLeft
            settle(app, landscape: true)
            let rotated = app.frame
            let landscapeStage = app.descendants(matching: .any)["shorts.stage"].frame
            notes.append("ipad-\(locale.key) landscape window=\(rotated) stage=\(landscapeStage)")
            XCTAssertGreaterThan(rotated.width, rotated.height, "b4t4 ipad \(locale.key): iPad is expected to rotate with the device")
            XCTAssertEqual(landscapeStage.height / landscapeStage.width, 16.0 / 9.0, accuracy: 0.05, "b4t4 ipad \(locale.key) landscape: stage lost 9:16")
            try write(named: "shorts-b4t4-ipad-\(locale.key)-landscape", into: directory)
            XCUIDevice.shared.orientation = .portrait
        }
    }

    /// Frames of every Shorts control, in accessibility order, plus the >= 44 pt assertion on each
    /// tap target (spec §6.11; the plan asks for a measurement, not an eyeball).
    private func measureShortsChrome(_ app: XCUIApplication, label: String) -> [String] {
        var notes = ["\(label) window=\(app.windows.firstMatch.frame)"]
        let stage = app.descendants(matching: .any)["shorts.stage"].frame
        notes.append("\(label) stage=\(stage) ratio(h/w)=\(stage.height / stage.width)")
        XCTAssertEqual(stage.height / stage.width, 16.0 / 9.0, accuracy: 0.05, "\(label): stage is not 9:16 \(stage)")
        let targets = ["shorts.back", "shorts.kebab.button", "shorts.likeButton", "shorts.shareButton", "shorts.channelHandle"]
        for id in targets {
            let element = app.buttons[id]
            guard element.exists else { notes.append("\(label) \(id) ABSENT"); continue }
            let f = element.frame
            notes.append("\(label) \(id) frame=\(f) label=\(element.label.debugDescription) value=\(String(describing: element.value))")
            XCTAssertGreaterThanOrEqual(f.width, 44, "\(label): \(id) width \(f.width) < 44 pt")
            XCTAssertGreaterThanOrEqual(f.height, 44, "\(label): \(id) height \(f.height) < 44 pt")
        }
        let slider = app.sliders["shorts.scrubber"]
        notes.append("\(label) shorts.scrubber frame=\(slider.frame) label=\(slider.label.debugDescription) value=\(String(describing: slider.value))")
        // I1 (final review): the element was 31 pt until `.contentShape(Rectangle())` joined the
        // 44 pt row -- measured 44 on en/ar/a11y3 after that, so the floor is asserted like the rest.
        XCTAssertGreaterThanOrEqual(slider.frame.height, 44, "\(label): scrubber height \(slider.frame.height) < 44 pt")
        let title = app.staticTexts["shorts.title"]
        if title.exists { notes.append("\(label) shorts.title frame=\(title.frame) label=\(title.label.debugDescription)") }
        // Accessibility order: every identified element as XCUITest enumerates it.
        let order = app.descendants(matching: .any).allElementsBoundByIndex
            .map { $0.identifier }.filter { $0.hasPrefix("shorts.") || $0.hasPrefix("player.") }
        notes.append("\(label) a11y order=\(order)")
        // `accessibilitySortPriority` (M8) orders VoiceOver, not this enumeration -- recorded only.
        return notes
    }

    private struct LoopSample { var restarts: Int; var maxSeconds: Int; var longestFlat: TimeInterval; var samples: Int }

    private func sampleScrubber(_ app: XCUIApplication, seconds: TimeInterval) -> LoopSample {
        let slider = app.sliders["shorts.scrubber"]
        func parse(_ value: Any?) -> Int? {
            guard let text = value as? String else { return nil }
            let parts = text.split(separator: ":").compactMap { Int($0) }
            return parts.count == 2 ? parts[0] * 60 + parts[1] : nil
        }
        var result = LoopSample(restarts: 0, maxSeconds: 0, longestFlat: 0, samples: 0)
        var last: Int?
        var flatSince = Date()
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            let now = parse(slider.value)
            result.samples += 1
            if let now {
                result.maxSeconds = max(result.maxSeconds, now)
                if let last, now < last { result.restarts += 1 }
                if now != last { flatSince = Date() }
                result.longestFlat = max(result.longestFlat, Date().timeIntervalSince(flatSince))
            }
            last = now
            Thread.sleep(forTimeInterval: 0.1)
        }
        return result
    }

    // MARK: - B4 task 4 step 2: live Shorts checks (SHORTS_LIVE=1)

    /// Approved-catalog ids ONLY (owner directive): both are on the channel `xc7keR2piUM` belongs to
    /// (its Shorts tab, browsed 2026-08-28). `liveShortId` is the 16:9 catalog clip B3 already uses.
    private static let liveVerticalShortId = "C1ADv39HtmY"

    private func requireShortsLive() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["SHORTS_LIVE"] == "1",
                          "live Shorts checks are opt-in: SHORTS_LIVE=1")
    }

    /// I3 (B4 final review): `.shortsGlyph()` resized the MAIN player's audio-language / captions
    /// menus too (40 -> 44 pt, Dynamic Type capped). No fixture carries a caption track or a second
    /// audible group, so only a live resolve renders them there. Opt-in like the rest (SHORTS_LIVE=1);
    /// asserts the CC glyph is 44 pt and inside the window in en and at .accessibility3.
    func testPlayerLiveCaptionsGlyphAfterShortsGlyph() throws {
        try requireShortsLive()
        let directory = try shotsDirectory()
        let screen = Screen(key: "player-live-glyph", arguments: ["-fitrah-route", "player", Self.liveEmbeddableId],
                            anchor: .button("unused"))
        XCUIDevice.shared.orientation = .portrait
        for (name, extra) in [("en", [String]()), ("en-a11y3", Self.accessibility3)] {
            let app = launch(screen, locale: Self.locales[0], extraArguments: extra)
            let cc = app.buttons["player.captionsMenu.button"]
            XCTAssertTrue(cc.waitForExistence(timeout: 60), "live glyph \(name): CC button never appeared (resolve failed?)")
            let frame = cc.frame, window = app.windows.firstMatch.frame
            XCTAssertEqual(frame.width, 44, accuracy: 1, "live glyph \(name): CC glyph width \(frame.width)")
            XCTAssertEqual(frame.height, 44, accuracy: 1, "live glyph \(name): CC glyph height \(frame.height)")
            XCTAssertTrue(window.contains(frame), "live glyph \(name): CC glyph \(frame) outside window \(window)")
            try write(named: "player-live-cc-glyph-\(name)", into: directory)
        }
    }

    /// Step 2 items 1, 3, 4, 6 on a real 9:16 Short; item 2 on the 16:9 clip through the deep link.
    /// The real resolver runs (no `-fitrah-fake-player`); `-fitrah-fake-container` only fakes the
    /// catalog. Item 5 (airplane mode) has no simulator lever and is recorded NOT RUN by the report.
    func testShortsB4Task4Live() throws {
        try requireShortsLive()
        let directory = try shotsDirectory()
        var notes: [String] = []
        defer { try? notes.joined(separator: "\n").write(to: directory.appendingPathComponent("b4-task4-live-measurements.txt"), atomically: true, encoding: .utf8) }

        XCUIDevice.shared.orientation = .portrait
        var app = launch(shortsScreen(["-fitrah-route", "shorts", Self.liveVerticalShortId]), locale: Self.locales[0], extraArguments: [])
        let like = app.buttons["shorts.likeButton"]
        XCTAssertTrue(like.waitForExistence(timeout: 60), "live 1: like never appeared (resolve failed?)")
        // 1. Plays, loops, and the scrub bar tracks a real duration.
        let loop = sampleScrubber(app, seconds: 45)
        notes.append("live-1 loop: restarts=\(loop.restarts) maxSeconds=\(loop.maxSeconds) longestFlat=\(String(format: "%.2f", loop.longestFlat))s samples=\(loop.samples)")
        XCTAssertGreaterThan(loop.maxSeconds, 3, "live 1: scrubber never advanced")
        XCTAssertGreaterThanOrEqual(loop.restarts, 1, "live 1: no loop restart observed in 45 s (clip may be longer -- see maxSeconds)")
        notes += measureShortsChrome(app, label: "live-1")
        try write(named: "shorts-b4t4-live-vertical", into: directory)

        // 3. Audio languages: the rail's globe appears only when the asset carries > 1 audible option.
        let audio = app.buttons["player.audioLanguageMenu.button"]
        notes.append("live-3 audioLanguageMenu present=\(audio.waitForExistence(timeout: 5))")

        // 4. Auto-generated captions: CC appears, a pick renders cues that clear the bottom block.
        let cc = app.buttons["player.captionsMenu.button"]
        XCTAssertTrue(cc.waitForExistence(timeout: 10), "live 4: CC button never appeared for an asr track")
        cc.tap()
        let option = app.buttons["player.captionsOption.ar"]
        XCTAssertTrue(option.waitForExistence(timeout: 10), "live 4: captions menu never opened / no ar option")
        option.tap()
        let cue = app.staticTexts["player.captionOverlay.text"]
        let cueShown = cue.waitForExistence(timeout: 30)
        let title = app.staticTexts["shorts.title"].frame
        let handle = app.buttons["shorts.channelHandle"].frame
        let scrubber = app.sliders["shorts.scrubber"].frame
        notes.append("live-4 cue shown=\(cueShown) cue=\(cueShown ? "\(cue.frame)" : "-") title=\(title) handle=\(handle) scrubber=\(scrubber)")
        XCTAssertTrue(cueShown, "live 4: no caption cue ever rendered on the Shorts stage")
        if cueShown {
            let blockTop = min(title.minY, handle.minY)
            XCTAssertLessThanOrEqual(cue.frame.maxY, blockTop, "live 4: cue \(cue.frame) collides with the bottom block (top \(blockTop))")
        }
        try write(named: "shorts-b4t4-live-captions", into: directory)

        // 6. Quality: 480p pick applies through the shared ladder; AUTO's cap is the host's own bounds.
        app.buttons["shorts.kebab.button"].tap()
        XCTAssertTrue(app.buttons["shorts.qualityOption.p480"].waitForExistence(timeout: 10), "live 6: kebab never opened")
        app.buttons["shorts.qualityOption.p480"].tap()
        Thread.sleep(forTimeInterval: 8)
        try write(named: "shorts-b4t4-live-480p", into: directory)

        // 2. A 16:9 video through `albunyaantube://shorts/{id}` is cropped to fill the 9:16 stage.
        app = launch(shortsScreen(["-fitrah-deeplink", "albunyaantube://shorts/\(Self.liveShortId)"]), locale: Self.locales[0], extraArguments: [])
        XCTAssertTrue(app.buttons["shorts.likeButton"].waitForExistence(timeout: 60), "live 2: like never appeared")
        Thread.sleep(forTimeInterval: 5)
        let stage = app.descendants(matching: .any)["shorts.stage"].frame
        notes.append("live-2 16:9 clip stage=\(stage) ratio(h/w)=\(stage.height / stage.width)")
        XCTAssertEqual(stage.height / stage.width, 16.0 / 9.0, accuracy: 0.05, "live 2: stage not 9:16 for a 16:9 source")
        try write(named: "shorts-b4t4-live-16x9-cropped", into: directory)
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

