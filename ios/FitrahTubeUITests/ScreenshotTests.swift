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
        /// A button by accessibility IDENTIFIER, not label. The Phase 4 account screens carry no
        /// locale-independent row title the way the fake catalog's items do (their only stable text
        /// is localized copy), but they do carry identifiers — `bootstrap.submit`, `profile.save`,
        /// `settings.signOut` — which read the same in en and ar.
        case buttonID(String)
        /// Any element by accessibility IDENTIFIER, whatever XCUIElementType it resolves to. Task
        /// 30's `me.tabs` is a `.segmented` `Picker`, which is a segmented CONTROL and not a
        /// button, so `buttonID` never matched it — and the rig writes its PNG either way, so the
        /// only symptom was a red assertion beside a screenshot that looked fine.
        case anyID(String)
        /// The topmost alert. `RootView`'s terminal account dialog IS the screen for the blocked
        /// row: `SplashRouter` routes a blocked account to the GUEST shell and raises the
        /// non-dismissible alert over it, so nothing on the shell behind it distinguishes the state.
        case alert
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
        // Phase 4 Task 13: the signed-in Me shell. `-fitrah-fake-auth active` selects the fixture
        // auth state at CONTAINER CONSTRUCTION (`AppContainer.sharedFake`) — `auth` is a
        // `private(set) lazy var`, so the `-fitrah-seed-*` shape, which runs in the scene's
        // `.task` after the container exists, cannot reach it. The favorites row is the anchor
        // because it is the one block with locale-independent seeded titles.
        Screen(key: "me-signed-in",
               arguments: ["-fitrah-fake-auth", "active", "-fitrah-seed-subscriptions",
                           "-fitrah-seed-favorites", "-fitrah-tab", "me"],
               anchor: .button("Seeded Favorite 1")),
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

    /// The kebab open: Quality submenu + Report (ruling 53). Report presents `ReportSheet`
    /// (C Task 3), the same sheet `PlayerToolbar.reportButton` presents (CF-B1-9: one report path).
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
            ("center/controls", CGVector(dx: 0.5, dy: 0.5)),
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

        // Kebab: five quality options, pick 480p, Report presents the ReportSheet.
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
        // The ReportSheet presents over the stage; dismiss it or the Back tap below hits the dimming layer.
        XCTAssertTrue(app.buttons["report.cancel"].waitForExistence(timeout: 5), "b4t4 en report: sheet never presented")
        try write(named: "shorts-b4t4-iphone-en-report-sheet", into: directory)
        app.buttons["report.cancel"].tap()
        XCTAssertTrue(app.buttons["report.cancel"].waitForNonExistence(timeout: 5), "b4t4 en report: sheet did not dismiss")

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

    // MARK: - B5 task 4: fullscreen + queue acceptance matrix
    // (docs/superpowers/plans/2026-08-27-ios-phase2b5-fullscreen-queue.md)

    /// `-fitrah-route player <videoId> <playlistId> [targetVideoId]` is the queue launch contract
    /// (B5 Task 4 rig hook); `-fitrah-fake-player-queue` serves eight fixture items
    /// (`fixture-1`…`fixture-8`) with no network. `-fitrah-fake-player` (rung 2) rather than `-hls`
    /// because the dead-id rule that drives the auto-skip walk lives on that resolver.
    private static let queueFixture = ["-fitrah-fake-player", "-fitrah-fake-player-queue",
                                       "-fitrah-route", "player", "fixture-video", "fixture-playlist"]

    private func playerScreen(_ arguments: [String]) -> Screen {
        Screen(key: "player-b5-task4", arguments: arguments, anchor: .button("unused"))
    }

    private func upNextRows(_ app: XCUIApplication) -> [XCUIElement] {
        app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH 'player.upNext.row.'"))
            .allElementsBoundByIndex
    }

    /// Row identifiers are `player.upNext.row.<queue index>.<video id>` (Cubic P2: the index makes
    /// duplicate playlist members distinct), so a video is addressed by its `.<id>` suffix.
    private func row(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH 'player.upNext.row.' AND identifier ENDSWITH %@", ".\(id)"))
            .firstMatch
    }

    /// The `player.*` identifiers in enumeration order (the VoiceOver-order record, as B4 does).
    private func playerA11yOrder(_ app: XCUIApplication) -> [String] {
        app.descendants(matching: .any).allElementsBoundByIndex
            .map { $0.identifier }.filter { $0.hasPrefix("player.") }
    }

    private func doubleTap(_ element: XCUIElement, at offset: CGVector) {
        element.coordinate(withNormalizedOffset: offset).doubleTap()
    }

    /// iPhone leg. Every Step 1 row XCUITest can prove with the fixture queue: Up Next (one
    /// column, tap-to-play, the played row leaves), Safe Mode ON/OFF at end-of-clip, the auto-skip
    /// walk, the queue-ended terminus (en + ar), fullscreen on rotation (no tab/nav bar, no
    /// toolbar, no metadata, no Up Next; full-bleed box; >= 44 pt exit control), ruling 45's
    /// two-step back, the latch, the zoom hint, the gestures (seek flash captured as PNG, zoom
    /// banner asserted), the single-video launch, RTL, Dynamic Type and the a11y order. Notes go
    /// to `b5-task4-iphone-measurements.txt`.
    func testPlayerB5Task4IPhone() throws {
        let directory = try shotsDirectory()
        var notes: [String] = []
        defer { try? notes.joined(separator: "\n").write(to: directory.appendingPathComponent("b5-task4-iphone-measurements.txt"), atomically: true, encoding: .utf8) }
        let hintCopy = "Double-tap to toggle fit/zoom"

        // -- A. en, Safe Mode ON (default): Up Next, no advance at end-of-clip, tap-to-play.
        XCUIDevice.shared.orientation = .portrait
        // `-fullscreen_zoom_hint_shown NO` (NSArgumentDomain) makes this launch the "first ever"
        // fullscreen regardless of what earlier runs left in the install -- the plan's "delete the
        // app" step, without the uninstall.
        var app = launch(playerScreen(Self.queueFixture), locale: Self.locales[0],
                         extraArguments: ["-fullscreen_zoom_hint_shown", "NO"])
        let header = app.staticTexts["player.upNext.header"]
        XCTAssertTrue(header.waitForExistence(timeout: 20), "b5t4 en: Up Next header never appeared")
        XCTAssertEqual(header.label, "Up next")
        var rows = upNextRows(app)
        notes.append("iphone-en rows=\(rows.map { $0.identifier })")
        XCTAssertEqual(rows.count, 7, "b5t4 en: expected fixture-2…8 queued, got \(rows.count)")
        XCTAssertEqual(rows.first?.identifier, "player.upNext.row.1.fixture-2")
        if rows.count >= 2 {
            notes.append("iphone-en row0=\(rows[0].frame) row1=\(rows[1].frame)")
            XCTAssertEqual(rows[0].frame.minX, rows[1].frame.minX, accuracy: 1, "b5t4 en: Up Next must be ONE column on iPhone")
            XCTAssertGreaterThanOrEqual(rows[1].frame.minY, rows[0].frame.maxY - 1, "b5t4 en: rows must stack vertically")
        }
        XCTAssertTrue(app.tabBars.firstMatch.exists, "b5t4 en portrait: tab bar missing")
        XCTAssertTrue(app.navigationBars.firstMatch.exists, "b5t4 en portrait: navigation bar missing")
        XCTAssertFalse(app.buttons["player.fullscreenExit"].exists, "b5t4 en portrait: no fullscreen exit outside fullscreen")
        let order = playerA11yOrder(app)
        notes.append("iphone-en a11y order=\(order)")
        XCTAssertFalse(order.contains("player.seekFeedback"), "b5t4 en: the seek-feedback layer must be absent from the accessibility tree")
        if let h = order.firstIndex(of: "player.upNext.header"), let r = order.firstIndex(where: { $0.hasSuffix(".fixture-2") }) {
            XCTAssertLessThan(h, r, "b5t4 en: header must precede the rows in VoiceOver order")
        } else { XCTFail("b5t4 en: header/row missing from the a11y order \(order)") }
        try write(named: "player-b5t4-iphone-en-portrait", into: directory)

        // Safe Mode ON: the 2 s fixture ends, nothing advances, the queue stays listed.
        let title = app.staticTexts["player.metadata.title"]
        XCTAssertTrue(title.waitForExistence(timeout: 10))
        let titleBefore = title.exists ? title.label : ""
        Thread.sleep(forTimeInterval: 6)
        XCTAssertTrue(row(app, "fixture-2").exists, "b5t4 safe-mode ON: the queue must stay listed after end-of-clip")
        let titleAfter = title.exists ? title.label : ""
        XCTAssertEqual(titleAfter, titleBefore, "b5t4 safe-mode ON: playback must NOT auto-advance")
        notes.append("iphone-en safe-mode-on: title after 6 s=\(titleAfter.debugDescription) rows=\(upNextRows(app).count)")

        // Up Next tap: the row plays, leaves the list and does not come back.
        let third = row(app, "fixture-3")
        if !third.isHittable { app.swipeUp() }
        third.tap()
        XCTAssertTrue(third.waitForNonExistence(timeout: 10), "b5t4 en tap: the tapped row must leave the list")
        XCTAssertTrue(app.staticTexts["player.metadata.title"].waitForExistence(timeout: 10))
        XCTAssertEqual(title.exists ? title.label : "-", "Lecture 3: Tafsir of Surah Al-Kahf")
        XCTAssertFalse(row(app, "fixture-2").exists, "b5t4 en tap: items before the played one are not 'up next'")
        XCTAssertTrue(row(app, "fixture-4").exists)
        Thread.sleep(forTimeInterval: 4)
        XCTAssertFalse(third.exists, "b5t4 en tap: the played row must not reappear")
        notes.append("iphone-en after tap rows=\(upNextRows(app).map { $0.identifier })")
        try write(named: "player-b5t4-iphone-en-after-tap", into: directory)

        // Centre double-tap OUTSIDE fullscreen: nothing (no zoom banner).
        let box = app.otherElements["player.videoBox"]
        XCTAssertTrue(box.exists)
        doubleTap(box, at: CGVector(dx: 0.5, dy: 0.25))
        XCTAssertFalse(app.staticTexts["Fill screen"].waitForExistence(timeout: 2), "b5t4 en: centre double-tap must be inert outside fullscreen")

        // -- Fullscreen on rotation.
        XCUIDevice.shared.orientation = .landscapeLeft
        let exit = app.buttons["player.fullscreenExit"]
        // The hint is a 2.5 s transient banner raised at the instant of the transition, so it is
        // polled tightly from the rotation itself -- a `waitForExistence` after the exit control's
        // own wait lands past its lifetime (run 1: rotation at 38.8 s, first hint query at 41.8 s).
        var hintShown = false
        let hintDeadline = Date().addingTimeInterval(6)
        repeat { hintShown = app.staticTexts[hintCopy].exists } while !hintShown && Date() < hintDeadline
        XCTAssertTrue(exit.waitForExistence(timeout: 10), "b5t4 en landscape: fullscreen never engaged")
        notes.append("iphone-en first-ever fullscreen: zoom hint shown=\(hintShown)")
        XCTAssertTrue(hintShown, "b5t4 en: the first fullscreen must show the zoom hint")
        settle(app, landscape: true)
        let window = app.windows.firstMatch.frame
        notes.append("iphone-en fullscreen window=\(window) videoBox=\(box.frame) exit=\(exit.frame) label=\(exit.label.debugDescription)")
        XCTAssertGreaterThanOrEqual(exit.frame.width, 44, "b5t4: exit control narrower than 44 pt")
        XCTAssertGreaterThanOrEqual(exit.frame.height, 44, "b5t4: exit control shorter than 44 pt")
        XCTAssertEqual(exit.label, "Toggle fullscreen")
        XCTAssertGreaterThan(exit.frame.midX, window.midX, "b5t4 en: exit control belongs on the trailing (right) edge")
        XCTAssertEqual(box.frame.width, window.width, accuracy: 1, "b5t4 en fullscreen: video box must be full-bleed (width)")
        XCTAssertEqual(box.frame.height, window.height, accuracy: 1, "b5t4 en fullscreen: video box must be full-bleed (height)")
        XCTAssertFalse(app.tabBars.firstMatch.exists, "b5t4 en fullscreen: tab bar must be gone")
        XCTAssertFalse(app.navigationBars.firstMatch.exists, "b5t4 en fullscreen: navigation bar must be gone")
        XCTAssertFalse(app.buttons["player.favoriteButton"].exists, "b5t4 en fullscreen: toolbar must be gone")
        XCTAssertFalse(app.staticTexts["player.metadata.title"].exists, "b5t4 en fullscreen: metadata must be gone")
        XCTAssertFalse(header.exists, "b5t4 en fullscreen: Up Next must be gone")
        notes.append("iphone-en fullscreen a11y order=\(playerA11yOrder(app))")
        try write(named: "player-b5t4-iphone-en-fullscreen", into: directory)

        // Gestures in fullscreen. The seek flash is accessibility-hidden by design, so its evidence
        // is the PNG taken straight after the tap (600 ms window). The zoom banner is a real
        // staticText and is asserted.
        doubleTap(box, at: CGVector(dx: 0.12, dy: 0.5))
        try write(named: "player-b5t4-iphone-en-seek-back-flash", into: directory)
        Thread.sleep(forTimeInterval: 1)
        doubleTap(box, at: CGVector(dx: 0.88, dy: 0.5))
        try write(named: "player-b5t4-iphone-en-seek-forward-flash", into: directory)
        Thread.sleep(forTimeInterval: 1)
        doubleTap(box, at: CGVector(dx: 0.5, dy: 0.25))
        let fill = app.staticTexts["Fill screen"].waitForExistence(timeout: 3)
        notes.append("iphone-en centre double-tap in fullscreen: 'Fill screen' banner=\(fill)")
        XCTAssertTrue(fill, "b5t4 en: centre double-tap in fullscreen must toggle to fill with its banner")
        try write(named: "player-b5t4-iphone-en-zoom-fill", into: directory)
        Thread.sleep(forTimeInterval: 3)
        doubleTap(box, at: CGVector(dx: 0.5, dy: 0.25))
        let fit = app.staticTexts["Fit to screen"].waitForExistence(timeout: 3)
        notes.append("iphone-en second centre double-tap: 'Fit to screen' banner=\(fit)")
        XCTAssertTrue(fit, "b5t4 en: the second centre double-tap must toggle back to fit")

        // Rotate back: everything returns, and the tab bar is really back (hittable, on screen).
        XCUIDevice.shared.orientation = .portrait
        settle(app, landscape: false)
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 5), "b5t4 en: tab bar did not return after rotating back")
        let tabBar = app.tabBars.firstMatch.frame, portraitWindow = app.windows.firstMatch.frame
        notes.append("iphone-en after rotate-back tabBar=\(tabBar) window=\(portraitWindow) hittable=\(app.tabBars.firstMatch.isHittable)")
        XCTAssertTrue(app.tabBars.firstMatch.isHittable, "b5t4 en: tab bar is back but not hittable")
        XCTAssertLessThanOrEqual(tabBar.maxY, portraitWindow.maxY + 1, "b5t4 en: tab bar is off-screen")
        XCTAssertTrue(app.navigationBars.firstMatch.exists)
        XCTAssertTrue(header.waitForExistence(timeout: 5), "b5t4 en: Up Next did not return")
        XCTAssertFalse(exit.exists)

        // Second fullscreen in the same install: no hint (the flag persisted).
        XCUIDevice.shared.orientation = .landscapeLeft
        var hintAgain = false
        let againDeadline = Date().addingTimeInterval(4)
        repeat { hintAgain = app.staticTexts[hintCopy].exists } while !hintAgain && Date() < againDeadline
        XCTAssertTrue(exit.waitForExistence(timeout: 10))
        notes.append("iphone-en second fullscreen: zoom hint shown=\(hintAgain)")
        XCTAssertFalse(hintAgain, "b5t4 en: the zoom hint must show once only")

        // Ruling 45: the edge swipe must NOT pop while fullscreen.
        let edgeStart = app.coordinate(withNormalizedOffset: CGVector(dx: 0.001, dy: 0.5))
        edgeStart.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)))
        Thread.sleep(forTimeInterval: 2)
        let stayed = exit.exists && box.exists
        notes.append("iphone-en edge swipe in fullscreen: stayed on player=\(stayed)")
        XCTAssertTrue(stayed, "b5t4 ruling 45: the edge swipe popped the player while fullscreen")
        // Step one: the exit control drops to the normal landscape column, bar back, no orientation forced.
        exit.tap()
        XCTAssertTrue(exit.waitForNonExistence(timeout: 5), "b5t4 ruling 45: exit control did not leave")
        XCTAssertTrue(app.navigationBars.firstMatch.waitForExistence(timeout: 5), "b5t4 ruling 45: navigation bar did not return on exit")
        XCTAssertTrue(app.buttons["player.favoriteButton"].waitForExistence(timeout: 5), "b5t4 ruling 45: toolbar missing in the landscape column")
        XCTAssertFalse(app.staticTexts["player.metadata.title"].exists, "b5t4: metadata stays hidden at compact height (B1 rule)")
        var w = app.windows.firstMatch.frame
        XCTAssertGreaterThan(w.width, w.height, "b5t4 ruling 45: exit must not force portrait")
        notes.append("iphone-en after exit: window=\(w) tabBar exists=\(app.tabBars.firstMatch.exists) header exists=\(header.exists)")
        try write(named: "player-b5t4-iphone-en-landscape-exited", into: directory)
        // The latch: stays exited while landscape.
        Thread.sleep(forTimeInterval: 3)
        XCTAssertFalse(exit.exists, "b5t4 latch: fullscreen re-entered while still landscape")
        // Rotate to portrait and back: re-armed.
        XCUIDevice.shared.orientation = .portrait
        settle(app, landscape: false)
        XCUIDevice.shared.orientation = .landscapeLeft
        let rearmed = exit.waitForExistence(timeout: 10)
        notes.append("iphone-en latch: re-armed after portrait->landscape=\(rearmed)")
        XCTAssertTrue(rearmed, "b5t4 latch: rotating out and back must re-enter fullscreen")
        // Step two: exit, then the edge swipe pops.
        exit.tap()
        XCTAssertTrue(app.navigationBars.firstMatch.waitForExistence(timeout: 5))
        edgeStart.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)))
        let popped = box.waitForNonExistence(timeout: 5)
        notes.append("iphone-en edge swipe after exit: popped=\(popped)")
        XCTAssertTrue(popped, "b5t4 ruling 45 step two: the edge swipe must pop once out of fullscreen")
        XCUIDevice.shared.orientation = .portrait

        // -- B. Safe Mode OFF: end-of-clip auto-advances.
        app = launch(playerScreen(Self.queueFixture + ["-safe_mode", "NO"]), locale: Self.locales[0], extraArguments: [])
        XCTAssertTrue(app.staticTexts["player.upNext.header"].waitForExistence(timeout: 20), "b5t4 safe-mode OFF: header never appeared")
        let advanced = row(app, "fixture-2").waitForNonExistence(timeout: 15)
        let offTitle = app.staticTexts["player.metadata.title"]
        notes.append("iphone-en safe-mode OFF: auto-advanced (fixture-2 left the list)=\(advanced) title=\(offTitle.exists ? offTitle.label.debugDescription : "-")")
        XCTAssertTrue(advanced, "b5t4 safe-mode OFF: end-of-clip must auto-advance (ruling 58)")
        try write(named: "player-b5t4-iphone-en-auto-advanced", into: directory)

        // -- C. Auto-skip: fixture-1, dead-2, dead-3, dead-4, fixture-5… -- the walk crosses exactly
        // three dead items and lands on Lecture 5 (MAX_CONSECUTIVE_SKIPS = 3, pinned by
        // PlayerViewModelQueueTests.autoSkipWalksPastUnplayableItemsAndStopsAfterThree).
        app = launch(playerScreen(["-fitrah-fake-player", "-fitrah-fake-player-queue-dead", "-safe_mode", "NO",
                                   "-fitrah-route", "player", "fixture-video", "fixture-playlist"]),
                     locale: Self.locales[0], extraArguments: [])
        XCTAssertTrue(app.staticTexts["player.upNext.header"].waitForExistence(timeout: 20), "b5t4 auto-skip: header never appeared")
        XCTAssertTrue(row(app, "dead-2").exists, "b5t4 auto-skip: the dead rows must be listed before the walk")
        var cardSamples = 0, samples = 0
        let walkDeadline = Date().addingTimeInterval(20)
        let metaTitle = app.staticTexts["player.metadata.title"]
        while Date() < walkDeadline {
            samples += 1
            if app.staticTexts["player.state.message"].exists { cardSamples += 1 }
            if metaTitle.exists, metaTitle.label.hasPrefix("Lecture 5") { break }
            Thread.sleep(forTimeInterval: 0.2)
        }
        let landed = metaTitle.exists ? metaTitle.label : "(no metadata title)"
        notes.append("iphone-en auto-skip: landed on \(landed.debugDescription) after \(samples) samples; error-card visible in \(cardSamples) samples; rows=\(upNextRows(app).map { $0.identifier })")
        XCTAssertTrue(landed.hasPrefix("Lecture 5"), "b5t4 auto-skip: expected to land on Lecture 5, got \(landed)")
        XCTAssertFalse(row(app, "dead-2").exists); XCTAssertFalse(row(app, "dead-4").exists)
        XCTAssertTrue(row(app, "fixture-6").exists)
        try write(named: "player-b5t4-iphone-en-auto-skipped", into: directory)

        // -- D. Queue ended (en + ar): deep start on fixture-7 so two advances reach the terminus.
        let ended = ["You've reached the end of the playlist", "لقد وصلت إلى نهاية قائمة التشغيل"]
        for (locale, expected) in zip(Self.locales, ended) {
            app = launch(playerScreen(Self.queueFixture + ["fixture-7", "-safe_mode", "NO"]), locale: locale, extraArguments: [])
            let message = app.staticTexts["player.state.message"]
            let appeared = message.waitForExistence(timeout: 30)
            XCTAssertTrue(appeared, "b5t4 queue-ended \(locale.key): terminus never appeared (title=\(app.staticTexts["player.metadata.title"].exists ? app.staticTexts["player.metadata.title"].label : "-"))")
            guard appeared else { continue }
            XCTAssertEqual(message.label, expected, "b5t4 queue-ended \(locale.key): wrong copy")
            XCTAssertFalse(app.buttons["player.state.retryButton"].exists, "b5t4 queue-ended \(locale.key): must offer no Retry")
            XCTAssertEqual(app.activityIndicators.count, 0, "b5t4 queue-ended \(locale.key): no spinner")
            let win = app.windows.firstMatch.frame
            notes.append("iphone-\(locale.key) queue-ended message=\(message.frame) window=\(win) label=\(message.label.debugDescription)")
            XCTAssertTrue(win.contains(message.frame), "b5t4 queue-ended \(locale.key): copy clips the window")
            XCTAssertTrue(app.navigationBars.buttons.firstMatch.exists, "b5t4 queue-ended \(locale.key): Back missing")
            try write(named: "player-b5t4-iphone-\(locale.key)-queue-ended", into: directory)
            app.navigationBars.buttons.firstMatch.tap()
            XCTAssertTrue(message.waitForNonExistence(timeout: 5), "b5t4 queue-ended \(locale.key): Back did not pop")
        }

        // -- E. Single-video launch: no header, no section at all (ruling 33).
        app = launch(playerScreen(["-fitrah-fake-player", "-fitrah-route", "player", "fixture-video"]), locale: Self.locales[0], extraArguments: [])
        XCTAssertTrue(app.staticTexts["player.metadata.title"].waitForExistence(timeout: 20))
        Thread.sleep(forTimeInterval: 2)
        XCTAssertFalse(app.staticTexts["player.upNext.header"].exists, "b5t4 single video: Up Next header must be absent")
        XCTAssertEqual(upNextRows(app).count, 0, "b5t4 single video: no Up Next rows")
        try write(named: "player-b5t4-iphone-en-single-video", into: directory)

        // -- F. Short -> player rotation: B4's portrait lock must have released.
        app = launch(shortsScreen(Self.shortsFixture), locale: Self.locales[0], extraArguments: [])
        XCTAssertTrue(app.buttons["shorts.likeButton"].waitForExistence(timeout: 20), "b5t4 short->player: Short never appeared")
        app.buttons["shorts.back"].tap()
        let homeRow = app.buttons.matching(NSPredicate(format: "label CONTAINS 's1-0'")).firstMatch
        XCTAssertTrue(homeRow.waitForExistence(timeout: 10), "b5t4 short->player: Home row never appeared after Back")
        homeRow.tap()
        XCTAssertTrue(app.otherElements["player.videoBox"].waitForExistence(timeout: 20), "b5t4 short->player: player never opened")
        XCUIDevice.shared.orientation = .landscapeLeft
        let fsAfterShort = app.buttons["player.fullscreenExit"].waitForExistence(timeout: 10)
        w = app.windows.firstMatch.frame
        notes.append("iphone-en short->player: window=\(w) fullscreen engaged=\(fsAfterShort)")
        XCTAssertTrue(fsAfterShort, "b5t4 short->player: fullscreen did not engage after leaving a Short (OrientationLock leak?)")
        try write(named: "player-b5t4-iphone-en-after-short-fullscreen", into: directory)
        XCUIDevice.shared.orientation = .portrait

        // -- G. ar (RTL): rows mirror, the exit control sits on the trailing (LEFT) edge, the seek
        // zones do not mirror (the left third still flashes `gobackward` -- PNG evidence).
        app = launch(playerScreen(Self.queueFixture), locale: Self.locales[1], extraArguments: [])
        let arHeader = app.staticTexts["player.upNext.header"]
        XCTAssertTrue(arHeader.waitForExistence(timeout: 20), "b5t4 ar: header never appeared")
        let arWindow = app.windows.firstMatch.frame
        rows = upNextRows(app)
        notes.append("iphone-ar header=\(arHeader.frame) label=\(arHeader.label.debugDescription) window=\(arWindow) row0=\(rows.first.map { "\($0.frame)" } ?? "-")")
        XCTAssertGreaterThan(arHeader.frame.midX, arWindow.midX, "b5t4 ar: the header must lead from the right")
        try write(named: "player-b5t4-iphone-ar-portrait", into: directory)
        XCUIDevice.shared.orientation = .landscapeLeft
        let arExit = app.buttons["player.fullscreenExit"]
        XCTAssertTrue(arExit.waitForExistence(timeout: 10), "b5t4 ar: fullscreen never engaged")
        settle(app, landscape: true)
        let arLandscape = app.windows.firstMatch.frame
        notes.append("iphone-ar fullscreen exit=\(arExit.frame) window=\(arLandscape)")
        XCTAssertLessThan(arExit.frame.midX, arLandscape.midX, "b5t4 ar: exit control must sit on the trailing (left) edge")
        let arBox = app.otherElements["player.videoBox"]
        doubleTap(arBox, at: CGVector(dx: 0.12, dy: 0.5))
        try write(named: "player-b5t4-iphone-ar-seek-back-flash", into: directory)
        try write(named: "player-b5t4-iphone-ar-fullscreen", into: directory)
        XCUIDevice.shared.orientation = .portrait

        // -- H. Dynamic Type .accessibility3: header intact, rows inside the window.
        app = launch(playerScreen(Self.queueFixture), locale: Self.locales[0], extraArguments: Self.accessibility3)
        let a11yHeader = app.staticTexts["player.upNext.header"]
        XCTAssertTrue(a11yHeader.waitForExistence(timeout: 20), "b5t4 a11y3: header never appeared")
        XCTAssertEqual(a11yHeader.label, "Up next")
        rows = upNextRows(app)
        let a11yWindow = app.windows.firstMatch.frame
        notes.append("iphone-en-a11y3 header=\(a11yHeader.frame) row0=\(rows.first.map { "\($0.frame)" } ?? "-") window=\(a11yWindow)")
        if let first = rows.first {
            XCTAssertGreaterThanOrEqual(first.frame.minX, a11yWindow.minX - 1)
            XCTAssertLessThanOrEqual(first.frame.maxX, a11yWindow.maxX + 1, "b5t4 a11y3: row clips the window")
        }
        try write(named: "player-b5t4-iphone-en-a11y3", into: directory)
    }

    /// iPad leg: rotating never auto-fullscreens (ruling 42); Up Next is TWO columns in en and ar
    /// (mirrored), collapsing to one at `.accessibility3`. AVKit's stock fullscreen button is not
    /// XCUITest-accessible, so the rail-hides-under-AVKit-fullscreen half is recorded NOT RUN.
    func testPlayerB5Task4IPad() throws {
        let directory = try shotsDirectory()
        var notes: [String] = []
        defer { try? notes.joined(separator: "\n").write(to: directory.appendingPathComponent("b5-task4-ipad-measurements.txt"), atomically: true, encoding: .utf8) }

        for locale in Self.locales {
            XCUIDevice.shared.orientation = .portrait
            let app = launch(playerScreen(Self.queueFixture), locale: locale, extraArguments: [])
            XCTAssertTrue(app.staticTexts["player.upNext.header"].waitForExistence(timeout: 20), "b5t4 ipad \(locale.key): header never appeared")
            let rows = upNextRows(app)
            notes.append("ipad-\(locale.key) rows=\(rows.count) row0=\(rows.first.map { "\($0.frame)" } ?? "-") row1=\(rows.dropFirst().first.map { "\($0.frame)" } ?? "-") screen=\(app.frame)")
            XCTAssertEqual(rows.count, 7)
            if rows.count >= 2 {
                XCTAssertEqual(rows[0].frame.minY, rows[1].frame.minY, accuracy: 2, "b5t4 ipad \(locale.key): Up Next must be TWO columns")
                if locale.key == "ar" {
                    XCTAssertGreaterThan(rows[0].frame.minX, rows[1].frame.minX, "b5t4 ipad ar: the grid must mirror (first cell on the right)")
                } else {
                    XCTAssertLessThan(rows[0].frame.minX, rows[1].frame.minX)
                }
            }
            try write(named: "player-b5t4-ipad-\(locale.key)-portrait", into: directory)

            XCUIDevice.shared.orientation = .landscapeLeft
            settle(app, landscape: true)
            let autoFullscreen = app.buttons["player.fullscreenExit"].waitForExistence(timeout: 3)
            notes.append("ipad-\(locale.key) landscape: auto-fullscreen=\(autoFullscreen) metadata=\(app.staticTexts["player.metadata.title"].exists) header=\(app.staticTexts["player.upNext.header"].exists)")
            XCTAssertFalse(autoFullscreen, "b5t4 ipad \(locale.key): rotation must never auto-fullscreen (ruling 42)")
            XCTAssertTrue(app.staticTexts["player.metadata.title"].exists, "b5t4 ipad \(locale.key) landscape: metadata must stay")
            XCTAssertTrue(app.staticTexts["player.upNext.header"].exists, "b5t4 ipad \(locale.key) landscape: Up Next must stay")
            try write(named: "player-b5t4-ipad-\(locale.key)-landscape", into: directory)
            XCUIDevice.shared.orientation = .portrait
        }

        // .accessibility3: one column, header intact.
        let app = launch(playerScreen(Self.queueFixture), locale: Self.locales[0], extraArguments: Self.accessibility3)
        let header = app.staticTexts["player.upNext.header"]
        XCTAssertTrue(header.waitForExistence(timeout: 20), "b5t4 ipad a11y3: header never appeared")
        XCTAssertEqual(header.label, "Up next")
        let rows = upNextRows(app)
        notes.append("ipad-en-a11y3 rows=\(rows.count) row0=\(rows.first.map { "\($0.frame)" } ?? "-") row1=\(rows.dropFirst().first.map { "\($0.frame)" } ?? "-")")
        if rows.count >= 2 {
            XCTAssertEqual(rows[0].frame.minX, rows[1].frame.minX, accuracy: 1, "b5t4 ipad a11y3: the grid must collapse to ONE column")
            XCTAssertGreaterThanOrEqual(rows[1].frame.minY, rows[0].frame.maxY - 1)
        }
        try write(named: "player-b5t4-ipad-en-a11y3", into: directory)
    }

    // MARK: - B5 task 4 step 2: live playlist checks (B5_LIVE=1)

    /// Approved-catalog playlist ONLY: `PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc` is the playlist
    /// InnerTubeKit's `browse-playlist.json` fixture was recorded from (the "Alafasy" catalog
    /// channel, `BrowseClientTests`). Probed 2026-08-29: 200 items on 2 pages of 100, no
    /// unplayable member. The ids below are page 1 #0, page 1 #97 and page 2 #0/#1.
    private static let livePlaylistId = "PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc"
    private static let livePlaylistFirst = "5ZMMARhgvsw"
    private static let livePlaylistPage1Index97 = "Q_kEjwhNThc"
    private static let livePlaylistPage2First = "uhewocUEY6U"
    private static let livePlaylistPage2Second = "wJK4fnvep0o"

    private func requireB5Live() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["B5_LIVE"] == "1",
                          "live playlist checks are opt-in: B5_LIVE=1")
    }

    private func livePlayer(_ videoId: String, target: String? = nil, extra: [String] = []) -> Screen {
        playerScreen(extra + ["-fitrah-route", "player", videoId, Self.livePlaylistId] + (target.map { [$0] } ?? []))
    }

    /// Step 2 items 1, 2, 3 (page 2 -- the playlist has no page 3), 4, 5, 8, 9. Item 6 is measured
    /// at the resolver level by the report; item 7 has no lever (no unplayable member in the
    /// catalog playlist); item 10 needs a rung-2 stream on demand, which nothing provides.
    func testPlayerB5Task4Live() throws {
        try requireB5Live()
        let directory = try shotsDirectory()
        var notes: [String] = []
        defer { try? notes.joined(separator: "\n").write(to: directory.appendingPathComponent("b5-task4-live-measurements.txt"), atomically: true, encoding: .utf8) }
        let seededTitle = "Understanding Tawakkul: Trusting Allah in Every Situation"
        let duration = try NSRegularExpression(pattern: "\\d+:\\d\\d")

        // 1. Real siblings: title + channel + duration, no view count. 9. Rotate mid-playback.
        XCUIDevice.shared.orientation = .portrait
        var app = launch(livePlayer(Self.livePlaylistFirst, target: Self.livePlaylistFirst), locale: Self.locales[0], extraArguments: [])
        let header = app.staticTexts["player.upNext.header"]
        XCTAssertTrue(header.waitForExistence(timeout: 90), "live 1: Up Next never appeared (resolve or browse failed?)")
        var rows = upNextRows(app)
        let labels = rows.prefix(3).map { $0.label }
        notes.append("live-1 rows=\(rows.count) first3 ids=\(rows.prefix(3).map { $0.identifier }) labels=\(labels)")
        XCTAssertEqual(rows.count, 99, "live 1: page 1 minus the launched video")
        for label in labels {
            XCTAssertFalse(label.lowercased().contains("view"), "live 1: a queue row must carry NO view count: \(label)")
            XCTAssertNotNil(duration.firstMatch(in: label, range: NSRange(label.startIndex..., in: label)), "live 1: row label lacks a duration: \(label)")
        }
        try write(named: "player-b5t4-live-up-next", into: directory)
        XCTAssertTrue(app.otherElements["player.videoBox"].exists)
        XCUIDevice.shared.orientation = .landscapeLeft
        let t9 = Date()
        let fs = app.buttons["player.fullscreenExit"].waitForExistence(timeout: 10)
        notes.append("live-9 rotate: fullscreen in \(String(format: "%.2f", Date().timeIntervalSince(t9)))s=\(fs) state card=\(app.staticTexts["player.state.message"].exists)")
        XCTAssertTrue(fs, "live 9: fullscreen did not engage on a real HLS stream")
        XCTAssertFalse(app.staticTexts["player.state.message"].exists, "live 9: rotation must not surface a state card (host rebuilt?)")
        try write(named: "player-b5t4-live-fullscreen", into: directory)
        XCUIDevice.shared.orientation = .portrait
        XCTAssertTrue(app.staticTexts["player.metadata.title"].waitForExistence(timeout: 10), "live 9: metadata did not return")
        XCTAssertFalse(app.staticTexts["player.state.message"].exists)

        // 2. A real end-of-item advances; the next video does NOT inherit the position (it would
        // end again within seconds if it started near the previous item's end).
        app = launch(livePlayer(Self.livePlaylistFirst, target: Self.livePlaylistFirst,
                                extra: ["-fitrah-player-seek-near-end", "-safe_mode", "NO"]),
                     locale: Self.locales[0], extraArguments: [])
        let title = app.staticTexts["player.metadata.title"]
        XCTAssertTrue(title.waitForExistence(timeout: 90), "live 2: player never opened")
        let t0 = Date()
        var deadline = Date().addingTimeInterval(120)
        while Date() < deadline, title.exists, title.label == seededTitle { Thread.sleep(forTimeInterval: 0.5) }
        let advancedTitle = title.exists ? title.label : "(no title)"
        let advanceAfter = Date().timeIntervalSince(t0)
        notes.append("live-2 advanced after \(String(format: "%.1f", advanceAfter))s to \(advancedTitle.debugDescription); first row now=\(upNextRows(app).first?.identifier ?? "-")")
        XCTAssertNotEqual(advancedTitle, seededTitle, "live 2: no auto-advance within 120 s")
        XCTAssertFalse(row(app, Self.livePlaylistFirst).exists)
        Thread.sleep(forTimeInterval: 20)
        let laterTitle = title.exists ? title.label : "(no title)"
        notes.append("live-2 20 s later title=\(laterTitle.debugDescription) state card=\(app.staticTexts["player.state.message"].exists)")
        XCTAssertEqual(laterTitle, advancedTitle, "live 2: the next video ended again within 20 s -- it inherited the previous position")
        XCTAssertFalse(app.staticTexts["player.state.message"].exists)
        try write(named: "player-b5t4-live-advanced", into: directory)

        // 8. Background auto-advance: background the app across the end-of-item.
        app = launch(livePlayer(Self.livePlaylistFirst, target: Self.livePlaylistFirst,
                                extra: ["-fitrah-player-seek-near-end", "-safe_mode", "NO", "-background_play", "YES"]),
                     locale: Self.locales[0], extraArguments: [])
        XCTAssertTrue(title.waitForExistence(timeout: 90), "live 8: player never opened")
        XCTAssertTrue(app.staticTexts["player.upNext.header"].waitForExistence(timeout: 30))
        XCUIDevice.shared.press(.home)
        Thread.sleep(forTimeInterval: 30)
        app.activate()
        XCTAssertTrue(title.waitForExistence(timeout: 10), "live 8: player gone after foregrounding")
        let bgTitle = title.exists ? title.label : "(no title)"
        notes.append("live-8 after 30 s in background: title=\(bgTitle.debugDescription) state card=\(app.staticTexts["player.state.message"].exists)")
        XCTAssertNotEqual(bgTitle, seededTitle, "live 8: the queue did not advance while backgrounded")
        try write(named: "player-b5t4-live-background-advanced", into: directory)

        // 3. Deep start on page 2 (the playlist has two pages): the scan must page and land there.
        let t3 = Date()
        app = launch(livePlayer(Self.livePlaylistPage2First, target: Self.livePlaylistPage2First), locale: Self.locales[0], extraArguments: [])
        XCTAssertTrue(app.staticTexts["player.upNext.header"].waitForExistence(timeout: 90), "live 3: Up Next never appeared")
        rows = upNextRows(app)
        notes.append("live-3 deep start: header after \(String(format: "%.1f", Date().timeIntervalSince(t3)))s (incl. launch); rows=\(rows.count) first=\(rows.first?.identifier ?? "-")")
        XCTAssertEqual(rows.first?.identifier, "player.upNext.row.101.\(Self.livePlaylistPage2Second)", "live 3: deep start did not land on the page-2 target")
        XCTAssertEqual(rows.count, 99)
        try write(named: "player-b5t4-live-deep-start", into: directory)

        // 4. Shuffle: the launched video is pinned first (never listed as up next), the order
        // differs across two launches, and only page 1 is ever loaded (paging disabled).
        var shuffleOrders: [[String]] = []
        for i in 1...2 {
            app = launch(livePlayer(Self.livePlaylistFirst, target: Self.livePlaylistFirst, extra: ["-fitrah-shuffled"]), locale: Self.locales[0], extraArguments: [])
            XCTAssertTrue(app.staticTexts["player.upNext.header"].waitForExistence(timeout: 90), "live 4 launch \(i): Up Next never appeared")
            rows = upNextRows(app)
            shuffleOrders.append(rows.prefix(5).map { $0.identifier })
            notes.append("live-4 launch \(i): rows=\(rows.count) first5=\(shuffleOrders[i - 1])")
            XCTAssertFalse(row(app, Self.livePlaylistFirst).exists, "live 4: the launched video must be pinned first, not queued")
            XCTAssertEqual(rows.count, 99, "live 4: shuffle must not page")
        }
        XCTAssertNotEqual(shuffleOrders[0], shuffleOrders[1], "live 4: two shuffled launches produced the same order")
        try write(named: "player-b5t4-live-shuffled", into: directory)

        // 5. Paging: a start within five of the page end pages at once (Task 4 fix), no stall.
        let t5 = Date()
        app = launch(livePlayer(Self.livePlaylistPage1Index97, target: Self.livePlaylistPage1Index97), locale: Self.locales[0], extraArguments: [])
        XCTAssertTrue(app.staticTexts["player.upNext.header"].waitForExistence(timeout: 90), "live 5: Up Next never appeared")
        deadline = Date().addingTimeInterval(20)
        repeat { rows = upNextRows(app); if rows.count > 2 { break }; Thread.sleep(forTimeInterval: 0.5) } while Date() < deadline
        notes.append("live-5 paging: rows=\(rows.count) after \(String(format: "%.1f", Date().timeIntervalSince(t5)))s (incl. launch); first=\(rows.first?.identifier ?? "-") state card=\(app.staticTexts["player.state.message"].exists)")
        XCTAssertEqual(rows.count, 102, "live 5: expected 2 remaining + 100 paged rows")
        XCTAssertFalse(app.staticTexts["player.state.message"].exists, "live 5: paging must not touch the playing video")
        try write(named: "player-b5t4-live-paged", into: directory)
    }

    // MARK: - Plan C task 6: channel / playlist / report matrix

    /// Approved-catalog ids only (the plan's Task 6 list); the fake browse source ignores them, so
    /// they matter for the share URL and the deep-link shape, not for the data.
    private static let cChannelId = "UCmMcOjsVehVlEOteyrhjI2Q"
    private static let cPlaylistId = "PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc"

    private func channelScreen(_ extra: [String] = []) -> Screen {
        Screen(key: "channel-c-task6", arguments: ["-fitrah-route", "channel", Self.cChannelId, "Fixture Channel"] + extra,
               anchor: .button("unused"))
    }

    /// `-fitrah-fake-player(-queue)` so Play All / Shuffle / a row tap land on a player that
    /// resolves the bundled clip and lists the fixture queue (no network).
    private func playlistScreen(_ extra: [String] = []) -> Screen {
        Screen(key: "playlist-c-task6",
               arguments: ["-fitrah-fake-player", "-fitrah-fake-player-queue", "-fitrah-route", "playlist", Self.cPlaylistId, "Fixture Playlist"] + extra,
               anchor: .button("unused"))
    }

    private func any(_ app: XCUIApplication, _ id: String) -> XCUIElement { app.descendants(matching: .any)[id] }

    private func ids(_ app: XCUIApplication, prefix: String) -> [XCUIElement] {
        app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", prefix)).allElementsBoundByIndex
    }

    /// The plan's "≥44×44 pt, measured": frame + label + value into the notes, and the floor asserted.
    private func measure(_ element: XCUIElement, _ name: String, into notes: Notes, barItem: Bool = false) {
        guard element.exists else {
            notes.append("\(name): MISSING")
            XCTFail("\(name): element missing, nothing to measure")
            return
        }
        let f = element.frame
        notes.append("\(name) frame=\(f) label=\(element.label.debugDescription) value=\((element.value as? String).debugDescription) selected=\(element.isSelected)")
        // A navigation-bar item's frame is the system's glass capsule (44x36 on this SDK), not the
        // 44x44 label inside it; UIKit owns that chrome, so bar items are recorded, not asserted.
        guard !barItem else { return }
        XCTAssertGreaterThanOrEqual(f.width, 44, "\(name): narrower than 44 pt (\(f.width))")
        XCTAssertGreaterThanOrEqual(f.height, 44, "\(name): shorter than 44 pt (\(f.height))")
    }

    /// Append-and-flush note taker for the measurement files.
    private final class Notes {
        private let file: URL
        private var lines: [String] = []
        init(file: URL, append: Bool = false) {
            self.file = file
            if append, let existing = try? String(contentsOf: file, encoding: .utf8) { lines = [existing] }
        }
        func append(_ line: String) {
            lines.append(line)
            try? lines.joined(separator: "\n").write(to: file, atomically: true, encoding: .utf8)
        }
    }

    private func typeSearch(_ app: XCUIApplication, _ text: String) {
        let field = app.textFields.firstMatch
        field.tap()
        if let current = field.value as? String, !current.isEmpty, current != "Search…" {
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count))
        }
        field.typeText(text)
    }

    /// Swipes the paged tab body (below the strip, above the tab bar) -- not the strip itself.
    private func swipeTabs(_ app: XCUIApplication, left: Bool) {
        let from = app.coordinate(withNormalizedOffset: CGVector(dx: left ? 0.85 : 0.15, dy: 0.7))
        let to = app.coordinate(withNormalizedOffset: CGVector(dx: left ? 0.15 : 0.85, dy: 0.7))
        from.press(forDuration: 0.05, thenDragTo: to)
    }

    /// Scrolls the report Form until `element` sits clear of the sheet's bar and its bottom edge
    /// (controlled ~200 pt drags, not flings: a fling at the top would collapse the sheet's detent).
    private func reveal(_ app: XCUIApplication, _ element: XCUIElement, missingIsBelow: Bool = true) {
        let window = app.windows.firstMatch.frame
        for _ in 0..<8 {
            settleFrame(element)
            guard element.exists else { drag(app, up: missingIsBelow); continue }
            let f = element.frame
            if f.minY < 140 { drag(app, up: false) } else if f.maxY > window.maxY - 24 { drag(app, up: true) } else { return }
        }
    }

    /// A tap right after a drag (scroll deceleration, a detent animation, the sheet's own
    /// presentation) lands where the row WAS. Poll until two consecutive frame reads agree.
    private func settleFrame(_ element: XCUIElement) {
        var previous: CGRect?
        let deadline = Date().addingTimeInterval(3)
        while Date() < deadline {
            let current = element.exists ? element.frame : .null
            if current == previous { return }
            previous = current
            Thread.sleep(forTimeInterval: 0.15)
        }
    }

    private func drag(_ app: XCUIApplication, up: Bool) {
        let from = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: up ? 0.7 : 0.45))
        let to = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: up ? 0.45 : 0.7))
        from.press(forDuration: 0.1, thenDragTo: to)
    }

    private func reasonRows(_ app: XCUIApplication) -> [XCUIElement] {
        app.switches.matching(NSPredicate(format: "identifier BEGINSWITH 'report.reason.'")).allElementsBoundByIndex
    }

    private func openReport(_ app: XCUIApplication) {
        app.buttons["detail.kebab.button"].tap()
        let report = app.buttons["detail.kebab.report"]
        XCTAssertTrue(report.waitForExistence(timeout: 10), "c6: kebab menu never opened")
        report.tap()
        XCTAssertTrue(app.buttons["report.submit"].waitForExistence(timeout: 10), "c6: report sheet never opened")
        // The sheet opens at `.medium`; nothing is dragged here -- `reveal` scrolls each row into
        // view on demand (a drag inside the Form expands the detent before it scrolls the content).
        _ = app.switches["report.reason.MUSIC"].waitForExistence(timeout: 5)
        settleFrame(app.switches["report.reason.MUSIC"])
    }

    private func popToDetail(_ app: XCUIApplication, anchor: String) {
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(any(app, anchor).waitForExistence(timeout: 10), "c6: Back did not return to the detail screen")
    }

    /// iPhone leg. Channel: header, five tabs, strip metrics, swipe, per-tab bodies, selection
    /// surviving rotation, search + `search_no_results`, Subscribe flip + the cap, autofill + Load
    /// more, the footer error, empty copy, degraded mode, 410. Playlist: hero, three cells (no
    /// Download), 1-based positions across page 2, search, Save/Saved, Play All / Shuffle / row
    /// tap into the player, kebab Share/Report, the report sheet's rules, 201 and 429. Then ar-dark,
    /// en-dark, ar-light and `.accessibility3`. Notes go to `c-task6-iphone-measurements.txt`.
    func testDetailCTask6IPhone() throws {
        let directory = try shotsDirectory()
        // Written on every append (not in a `defer`): an XCUITest "not hittable" failure aborts the
        // method through an ObjC exception, which skips Swift's defer -- run 1 lost every note.
        let notes = Notes(file: directory.appendingPathComponent("c-task6-iphone-measurements.txt"))
        let en = Self.locales[0]

        // -- A. Channel, en light: header, tabs, kebab, share, subscribe.
        XCUIDevice.shared.orientation = .portrait
        var app = launch(channelScreen(), locale: en, extraArguments: [])
        let title = app.staticTexts["channel.title"]
        XCTAssertTrue(title.waitForExistence(timeout: 20), "c6 channel: title never appeared")
        XCTAssertEqual(title.label, "Fixture Channel")
        XCTAssertEqual(app.staticTexts["channel.subscribers"].label, "1.2M subscribers")
        XCTAssertTrue(any(app, "channel.videos.row.video-0-0").waitForExistence(timeout: 10), "c6 channel: Videos never loaded")
        let window = app.windows.firstMatch.frame
        let tabs = ["videos", "live", "shorts", "playlists", "about"]
        for tab in tabs {
            let button = app.buttons["channel.tab.\(tab)"]
            XCTAssertTrue(button.exists, "c6 channel: tab \(tab) missing")
            measure(button, "iphone-en tab.\(tab)", into: notes)
        }
        XCTAssertTrue(app.buttons["channel.tab.videos"].isSelected)
        notes.append("iphone-en strip: about.maxX=\(app.buttons["channel.tab.about"].frame.maxX) window.width=\(window.width) (compact strip scrolls when about.maxX > width)")
        measure(app.buttons["channel.subscribe"], "iphone-en subscribe", into: notes)
        measure(app.buttons["detail.kebab.button"], "iphone-en kebab", into: notes, barItem: true)
        measure(app.navigationBars.buttons.firstMatch, "iphone-en back", into: notes, barItem: true)
        let firstRow = any(app, "channel.videos.row.video-0-0")
        notes.append("iphone-en videos row0 frame=\(firstRow.frame) label=\(firstRow.label.debugDescription)")
        XCTAssertGreaterThanOrEqual(firstRow.frame.height, 44)
        try write(named: "detail-c6-iphone-en-light-channel-videos", into: directory)

        app.buttons["detail.kebab.button"].tap()
        let share = app.buttons["detail.kebab.share"]
        XCTAssertTrue(share.waitForExistence(timeout: 10), "c6 channel: kebab menu never opened")
        XCTAssertTrue(app.buttons["detail.kebab.report"].exists)
        XCTAssertEqual(share.label, "Share"); XCTAssertEqual(app.buttons["detail.kebab.report"].label, "Report")
        try write(named: "detail-c6-iphone-en-light-channel-kebab", into: directory)
        share.tap()
        let shareSheet = app.otherElements["ActivityListView"]
        let shareShown = shareSheet.waitForExistence(timeout: 10)
        notes.append("iphone-en share sheet shown=\(shareShown) texts=\(app.staticTexts.allElementsBoundByIndex.prefix(6).map { $0.label })")
        XCTAssertTrue(shareShown, "c6 channel: Share did not open the share sheet")
        try write(named: "detail-c6-iphone-en-light-channel-share", into: directory)
        // iOS 26's compact share sheet has no Close button: tap the dimmed area beneath it.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.8)).tap()
        if !shareSheet.waitForNonExistence(timeout: 5) { app.swipeDown() }
        XCTAssertTrue(shareSheet.waitForNonExistence(timeout: 10), "c6 channel: share sheet did not dismiss")

        let subscribe = app.buttons["channel.subscribe"]
        XCTAssertEqual(subscribe.label, "Subscribe")
        subscribe.tap()
        XCTAssertTrue(app.buttons["channel.subscribe"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.buttons["channel.subscribe"].label, "Subscribed", "c6 subscribe: label must flip")
        XCTAssertTrue(app.buttons["channel.subscribe"].isSelected)
        try write(named: "detail-c6-iphone-en-light-channel-subscribed", into: directory)
        app.buttons["channel.subscribe"].tap()
        XCTAssertEqual(app.buttons["channel.subscribe"].label, "Subscribe", "c6 subscribe: label must flip back")

        // -- B. Tabs: tap Live / Shorts, swipe Shorts -> Playlists -> Shorts, Playlists, About, rotation.
        app.buttons["channel.tab.live"].tap()
        XCTAssertTrue(any(app, "channel.live.row.live-0-0").waitForExistence(timeout: 10), "c6 live: rows never appeared")
        try write(named: "detail-c6-iphone-en-light-channel-live", into: directory)
        app.buttons["channel.tab.shorts"].tap()
        let cell0 = app.buttons["channel.shorts.cell.shorts-0-0"], cell1 = app.buttons["channel.shorts.cell.shorts-0-1"]
        XCTAssertTrue(cell0.waitForExistence(timeout: 10), "c6 shorts: cells never appeared")
        notes.append("iphone-en shorts cell0=\(cell0.frame) cell1=\(cell1.frame)")
        XCTAssertEqual(cell0.frame.minY, cell1.frame.minY, accuracy: 2, "c6 shorts: two columns on iPhone")
        XCTAssertEqual(cell0.frame.height / cell0.frame.width, 16.0 / 9.0, accuracy: 0.05, "c6 shorts: cells are 9:16")
        try write(named: "detail-c6-iphone-en-light-channel-shorts", into: directory)
        swipeTabs(app, left: true)
        XCTAssertTrue(any(app, "channel.playlists.row.PL0").waitForExistence(timeout: 10), "c6 swipe: Shorts -> Playlists did not page")
        XCTAssertTrue(app.buttons["channel.tab.playlists"].isSelected, "c6 swipe: strip did not follow the page")
        notes.append("iphone-en swipe left: playlists selected=\(app.buttons["channel.tab.playlists"].isSelected)")
        try write(named: "detail-c6-iphone-en-light-channel-playlists", into: directory)
        swipeTabs(app, left: false)
        XCTAssertTrue(cell0.waitForExistence(timeout: 10), "c6 swipe: Playlists -> Shorts did not page back")
        XCTAssertTrue(app.buttons["channel.tab.shorts"].isSelected)
        app.buttons["channel.tab.about"].tap()
        let aboutSubs = app.staticTexts["channel.about.subscribers"]
        XCTAssertTrue(aboutSubs.waitForExistence(timeout: 10), "c6 about: rows never appeared")
        XCTAssertEqual(aboutSubs.label, "1.2M subscribers")
        XCTAssertTrue(app.staticTexts["channel.about.description"].exists)
        try write(named: "detail-c6-iphone-en-light-channel-about", into: directory)
        XCUIDevice.shared.orientation = .landscapeLeft
        settle(app, landscape: true)
        XCTAssertTrue(app.buttons["channel.tab.about"].isSelected, "c6 rotation: the selection must survive")
        XCTAssertTrue(aboutSubs.exists)
        try write(named: "detail-c6-iphone-en-light-channel-about-landscape", into: directory)
        XCUIDevice.shared.orientation = .portrait
        settle(app, landscape: false)

        // -- C. Search: per-tab filter, About ignores it, zero matches -> search_no_results.
        app.buttons["channel.tab.videos"].tap()
        XCTAssertTrue(any(app, "channel.videos.row.video-0-0").waitForExistence(timeout: 10))
        typeSearch(app, "Video 3")
        XCTAssertTrue(any(app, "channel.videos.row.video-0-3").waitForExistence(timeout: 10), "c6 search: the match never appeared")
        XCTAssertTrue(any(app, "channel.videos.row.video-0-0").waitForNonExistence(timeout: 5), "c6 search: non-matching rows must go")
        XCTAssertFalse(app.buttons["listFooter.loadMore"].exists, "c6 search: no Load more while filtered")
        notes.append("iphone-en search 'Video 3': rows=\(ids(app, prefix: "channel.videos.row.").map { $0.identifier })")
        try write(named: "detail-c6-iphone-en-light-channel-search-match", into: directory)
        typeSearch(app, "zzz")
        let noResults = app.staticTexts["No results found"]
        XCTAssertTrue(noResults.waitForExistence(timeout: 10), "c6 search: zero matches must show search_no_results")
        try write(named: "detail-c6-iphone-en-light-channel-search-none", into: directory)
        app.buttons["channel.tab.live"].tap()
        XCTAssertTrue(noResults.waitForExistence(timeout: 10), "c6 search: the filter is per tab (Live has no 'zzz' either)")
        app.buttons["channel.tab.about"].tap()
        XCTAssertTrue(aboutSubs.waitForExistence(timeout: 10), "c6 search: About must ignore the query")

        // -- D. Subscribe cap: 30 seeded, the 31st shows me_subscription_cap_reached.
        app = launch(channelScreen(["-fitrah-seed-subscriptions"]), locale: en, extraArguments: [])
        XCTAssertTrue(app.buttons["channel.subscribe"].waitForExistence(timeout: 20))
        app.buttons["channel.subscribe"].tap()
        let cap = app.staticTexts["You're following 30 channels (the limit). Unsubscribe one to follow this channel."]
        XCTAssertTrue(cap.waitForExistence(timeout: 5), "c6 subscribe cap: banner never appeared")
        XCTAssertEqual(app.buttons["channel.subscribe"].label, "Subscribe", "c6 subscribe cap: the 31st must not flip")
        try write(named: "detail-c6-iphone-en-light-channel-subscribe-cap", into: directory)

        // -- E. Pagination (ruling 10, compact): two-row pages -> one autofill, then Load more; tap renews.
        // One row per page: the pinned header leaves ~320 pt for the tab body on an iPhone 17, so
        // only a 1-row page still fits after its autofill (2 rows); a 2-row page would not, and the
        // near-end scroll trigger would then legitimately keep paging.
        app = launch(channelScreen(["-fitrah-fake-browse-pages", "4", "1"]), locale: en, extraArguments: [])
        let loadMore = app.buttons["listFooter.loadMore"]
        XCTAssertTrue(loadMore.waitForExistence(timeout: 20), "c6 pagination: Load more never appeared after the single autofill")
        var rows = ids(app, prefix: "channel.videos.row.")
        notes.append("iphone-en pagination before tap rows=\(rows.count) \(rows.map { $0.identifier })")
        XCTAssertEqual(rows.count, 2, "c6 pagination: one page + one autofill = 2 rows on compact")
        measure(loadMore, "iphone-en loadMore", into: notes)
        try write(named: "detail-c6-iphone-en-light-channel-loadmore", into: directory)
        loadMore.tap()
        XCTAssertTrue(any(app, "channel.videos.row.video-2-0").waitForExistence(timeout: 10), "c6 pagination: the tap must fetch page 3")
        rows = ids(app, prefix: "channel.videos.row.")
        notes.append("iphone-en pagination after tap rows=\(rows.count)")
        XCTAssertGreaterThanOrEqual(rows.count, 3)

        // Footer error: the append fails -> message + Retry, rows kept.
        app = launch(channelScreen(["-fitrah-fake-browse-pages", "4", "1", "-fitrah-fake-browse-fail-append"]), locale: en, extraArguments: [])
        let retry = app.buttons["listFooter.retry"]
        XCTAssertTrue(retry.waitForExistence(timeout: 20), "c6 footer: the append error never appeared")
        XCTAssertTrue(app.staticTexts["Failed to load more. Tap to retry."].exists)
        XCTAssertTrue(any(app, "channel.videos.row.video-0-0").exists, "c6 footer: rows must survive an append failure")
        measure(retry, "iphone-en footer.retry", into: notes)
        try write(named: "detail-c6-iphone-en-light-channel-footer-error", into: directory)

        // -- F. Empty copy per tab (perPage 0).
        app = launch(channelScreen(["-fitrah-fake-browse-pages", "1", "0"]), locale: en, extraArguments: [])
        XCTAssertTrue(app.staticTexts["This channel has no videos yet"].waitForExistence(timeout: 20), "c6 empty: Videos copy")
        try write(named: "detail-c6-iphone-en-light-channel-empty-videos", into: directory)
        app.buttons["channel.tab.live"].tap()
        XCTAssertTrue(app.staticTexts["No live or upcoming streams"].waitForExistence(timeout: 10), "c6 empty: Live copy")
        app.buttons["channel.tab.shorts"].tap()
        XCTAssertTrue(app.staticTexts["No Shorts available"].waitForExistence(timeout: 10), "c6 empty: Shorts copy")
        try write(named: "detail-c6-iphone-en-light-channel-empty-shorts", into: directory)

        // -- G. Degraded mode: notice + Videos rows, Live/Shorts/Playlists error + Retry, About renders.
        app = launch(channelScreen(["-fitrah-fake-browse-botcheck"]), locale: en, extraArguments: [])
        XCTAssertTrue(app.staticTexts["channel.degradedNotice"].waitForExistence(timeout: 20), "c6 degraded: notice never appeared")
        XCTAssertEqual(app.staticTexts["channel.degradedNotice"].label, "Showing recent uploads only")
        XCTAssertEqual(app.staticTexts["channel.subscribers"].label, "–", "c6 degraded: no subscriber text -> the placeholder")
        XCTAssertTrue(any(app, "channel.videos.row.video-0-0").waitForExistence(timeout: 10), "c6 degraded: Videos must still list")
        XCTAssertFalse(app.buttons["listFooter.loadMore"].exists, "c6 degraded: no footer on the feed")
        try write(named: "detail-c6-iphone-en-light-channel-degraded-videos", into: directory)
        for tab in ["live", "shorts", "playlists"] {
            app.buttons["channel.tab.\(tab)"].tap()
            XCTAssertTrue(any(app, "channel.\(tab).error").waitForExistence(timeout: 10), "c6 degraded: \(tab) must show the error state")
            XCTAssertTrue(app.buttons["Retry"].exists, "c6 degraded: \(tab) must offer Retry")
            XCTAssertTrue(app.staticTexts["Couldn't load this tab. Please check your connection and try again."].exists)
            try write(named: "detail-c6-iphone-en-light-channel-degraded-\(tab)", into: directory)
        }
        app.buttons["channel.tab.about"].tap()
        XCTAssertTrue(app.staticTexts["channel.about.subscribers"].waitForExistence(timeout: 10), "c6 degraded: About must render")

        // -- H. 410: content_unavailable, no Retry -- channel and playlist.
        app = launch(channelScreen(["-fitrah-fake-browse-unavailable"]), locale: en, extraArguments: [])
        XCTAssertTrue(any(app, "channel.unavailable").waitForExistence(timeout: 20), "c6 410 channel: state never appeared")
        XCTAssertTrue(app.staticTexts["Content not available"].exists)
        XCTAssertFalse(app.buttons["Retry"].exists, "c6 410 channel: no Retry (ruling 14)")
        try write(named: "detail-c6-iphone-en-light-channel-unavailable", into: directory)
        app = launch(playlistScreen(["-fitrah-fake-browse-unavailable"]), locale: en, extraArguments: [])
        XCTAssertTrue(any(app, "playlist.unavailable").waitForExistence(timeout: 20), "c6 410 playlist: state never appeared")
        XCTAssertFalse(app.buttons["Retry"].exists, "c6 410 playlist: no Retry (ruling 14)")
        XCTAssertFalse(app.buttons["playlist.playAll"].isEnabled, "c6 410 playlist: nothing to play")
        try write(named: "detail-c6-iphone-en-light-playlist-unavailable", into: directory)

    }

    /// iPhone leg, playlist + report: hero, three cells (no Download), 1-based positions across
    /// page 2, search, Save/Saved, Play All / Shuffle / row tap into the player, the kebab, the
    /// report sheet's rules, 201 and 429. Split from the channel leg so each run stays inside a
    /// foreground watchdog. Notes append to `c-task6-iphone-measurements.txt`.
    func testDetailCTask6IPhonePlaylist() throws {
        let directory = try shotsDirectory()
        let notes = Notes(file: directory.appendingPathComponent("c-task6-iphone-measurements.txt"), append: true)
        let en = Self.locales[0]
        var app: XCUIApplication
        XCUIDevice.shared.orientation = .portrait

        // -- I. Playlist, en light: hero, three cells, positions, Save, page 2, search.
        app = launch(playlistScreen(), locale: en, extraArguments: [])
        let pTitle = app.staticTexts["playlist.title"]
        XCTAssertTrue(pTitle.waitForExistence(timeout: 20), "c6 playlist: title never appeared")
        XCTAssertEqual(pTitle.label, "Fixture Playlist")
        XCTAssertTrue(any(app, "playlist.row.1").waitForExistence(timeout: 10), "c6 playlist: rows never appeared")
        notes.append("iphone-en playlist metadata=\(app.staticTexts["playlist.metadata"].exists ? app.staticTexts["playlist.metadata"].label.debugDescription : "-")")
        for id in ["playlist.playAll", "playlist.shuffle", "playlist.save"] { measure(app.buttons[id], "iphone-en \(id)", into: notes) }
        XCTAssertFalse(app.buttons["Download"].exists, "c6 playlist: no Download anywhere (rulings 28/56)")
        XCTAssertEqual(app.buttons.matching(NSPredicate(format: "identifier IN {'playlist.playAll','playlist.shuffle','playlist.save'}")).count, 3, "c6 playlist: exactly three action cells")
        XCTAssertEqual(app.buttons["playlist.playAll"].label, "Play all"); XCTAssertEqual(app.buttons["playlist.shuffle"].label, "Shuffle")
        XCTAssertEqual(app.buttons["playlist.save"].label, "Save")
        let row1 = any(app, "playlist.row.1")
        notes.append("iphone-en playlist row1 frame=\(row1.frame) label=\(row1.label.debugDescription)")
        XCTAssertTrue(row1.label.contains("Position 1"), "c6 playlist: the row label must carry its position")
        measure(app.buttons["detail.kebab.button"], "iphone-en playlist kebab", into: notes, barItem: true)
        try write(named: "detail-c6-iphone-en-light-playlist", into: directory)
        app.buttons["playlist.save"].tap()
        XCTAssertEqual(app.buttons["playlist.save"].label, "Saved", "c6 save: label must flip")
        XCTAssertTrue(app.buttons["playlist.save"].isSelected)
        try write(named: "detail-c6-iphone-en-light-playlist-saved", into: directory)
        app.buttons["playlist.save"].tap()
        XCTAssertEqual(app.buttons["playlist.save"].label, "Save")
        // Collapse: the inline title crosses into the bar.
        app.swipeUp(); app.swipeUp(); app.swipeUp()
        let barTitle = app.navigationBars.staticTexts["Fixture Playlist"]
        notes.append("iphone-en playlist collapsed: bar title=\(barTitle.exists)")
        XCTAssertTrue(barTitle.waitForExistence(timeout: 5), "c6 playlist: the title must crossfade into the bar once collapsed")
        try write(named: "detail-c6-iphone-en-light-playlist-collapsed", into: directory)
        let deadline = Date().addingTimeInterval(15)
        while !any(app, "playlist.row.13").exists && Date() < deadline { app.swipeUp() }
        XCTAssertTrue(any(app, "playlist.row.13").exists, "c6 playlist: page 2 must continue the 1-based positions")
        try write(named: "detail-c6-iphone-en-light-playlist-page2", into: directory)
        app.swipeDown(); app.swipeDown(); app.swipeDown(); app.swipeDown()
        typeSearch(app, "Item 3")
        XCTAssertTrue(any(app, "playlist.row.4").waitForExistence(timeout: 10), "c6 playlist search: Item 3 is position 4")
        XCTAssertFalse(any(app, "playlist.row.1").exists, "c6 playlist search: non-matching rows must go")
        try write(named: "detail-c6-iphone-en-light-playlist-search-match", into: directory)
        typeSearch(app, "zzz")
        XCTAssertTrue(app.staticTexts["No results found"].waitForExistence(timeout: 10), "c6 playlist search: zero matches -> search_no_results (ruling 46)")
        try write(named: "detail-c6-iphone-en-light-playlist-search-none", into: directory)
        app = launch(playlistScreen(["-fitrah-fake-browse-pages", "1", "0"]), locale: en, extraArguments: [])
        XCTAssertTrue(app.staticTexts["This playlist has no videos yet"].waitForExistence(timeout: 20), "c6 playlist: playlist_empty_state")
        XCTAssertFalse(app.buttons["playlist.playAll"].isEnabled, "c6 empty playlist: Play All disabled")
        try write(named: "detail-c6-iphone-en-light-playlist-empty", into: directory)

        // -- J. Play All / Shuffle / row tap open the player with the queue.
        app = launch(playlistScreen(), locale: en, extraArguments: [])
        XCTAssertTrue(any(app, "playlist.row.1").waitForExistence(timeout: 20))
        app.buttons["playlist.playAll"].tap()
        XCTAssertTrue(app.otherElements["player.videoBox"].waitForExistence(timeout: 20), "c6 Play All: player never opened")
        XCTAssertTrue(app.staticTexts["player.upNext.header"].waitForExistence(timeout: 10), "c6 Play All: queue never listed")
        let playAllTitle = app.staticTexts["player.metadata.title"].label
        let playAllRows = upNextRows(app).map { $0.identifier }
        notes.append("iphone-en playAll title=\(playAllTitle.debugDescription) upNext=\(playAllRows)")
        XCTAssertEqual(playAllTitle, "Item 0", "c6 Play All: starts at item 1")
        try write(named: "detail-c6-iphone-en-light-playlist-playall", into: directory)
        popToDetail(app, anchor: "playlist.row.1")
        app.buttons["playlist.shuffle"].tap()
        XCTAssertTrue(app.staticTexts["player.upNext.header"].waitForExistence(timeout: 20), "c6 Shuffle: queue never listed")
        let shuffledRows = upNextRows(app).map { $0.identifier }
        notes.append("iphone-en shuffle title=\(app.staticTexts["player.metadata.title"].label.debugDescription) upNext=\(shuffledRows)")
        XCTAssertNotEqual(shuffledRows, playAllRows, "c6 Shuffle: Up Next must be shuffled")
        try write(named: "detail-c6-iphone-en-light-playlist-shuffle", into: directory)
        popToDetail(app, anchor: "playlist.row.1")
        any(app, "playlist.row.3").tap()
        XCTAssertTrue(app.otherElements["player.videoBox"].waitForExistence(timeout: 20), "c6 row tap: player never opened")
        XCTAssertTrue(app.staticTexts["player.metadata.title"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["player.metadata.title"].label, "Item 2", "c6 row tap: starts on that row")
        try write(named: "detail-c6-iphone-en-light-playlist-rowtap", into: directory)
        popToDetail(app, anchor: "playlist.row.1")

        // -- K. Report sheet: 11 rows, the cap, Other, zero reasons (no network), cancel.
        openReport(app)
        notes.append("iphone-en report at rest: bar=\(app.buttons["report.submit"].frame) music=\(app.switches["report.reason.MUSIC"].frame) nudity=\(app.switches["report.reason.NUDITY"].frame) window=\(app.windows.firstMatch.frame)")
        // The Form is lazy: rows past the fold are not in the snapshot until scrolled to, so the
        // eleven are collected across the top and the bottom of the list.
        var seen = reasonRows(app).map { $0.identifier }
        let reasons = reasonRows(app)
        reveal(app, app.switches["report.reason.OTHER"])
        seen += reasonRows(app).map { $0.identifier }.filter { !seen.contains($0) }
        reveal(app, app.switches["report.reason.MUSIC"])
        notes.append("iphone-en report rows=\(seen)")
        XCTAssertEqual(seen.count, 11, "c6 report: 11 rows")
        for row in reasons.prefix(3) { measure(row, "iphone-en \(row.identifier)", into: notes) }
        measure(app.buttons["report.cancel"], "iphone-en report.cancel", into: notes, barItem: true)
        measure(app.buttons["report.submit"], "iphone-en report.submit", into: notes, barItem: true)
        app.buttons["report.submit"].tap()
        reveal(app, app.staticTexts["report.message"])
        XCTAssertTrue(app.staticTexts["report.message"].waitForExistence(timeout: 5), "c6 report: zero reasons must show the validation message")
        XCTAssertEqual(app.staticTexts["report.message"].label, "Please select at least one reason")
        try write(named: "detail-c6-iphone-en-light-report-zero", into: directory)
        app.buttons["report.cancel"].tap()
        XCTAssertTrue(app.buttons["report.submit"].waitForNonExistence(timeout: 5), "c6 report: Cancel must dismiss")

        // The cap, Other's field, 201 and 429 open the sheet with reasons pre-checked
        // (`-fitrah-report-preselect`, ReportSheet.swift): XCUITest taps on this Form's Toggle rows
        // flip the wrong row or none on the iOS 26 simulator (run 1-4 evidence in the notes), so the
        // rig seeds the state and proves what the sheet does with it.
        app = launch(playlistScreen(["-fitrah-report-preselect", "10"]), locale: en, extraArguments: [])
        XCTAssertTrue(any(app, "playlist.row.1").waitForExistence(timeout: 20))
        openReport(app)
        var checked = Set(reasonRows(app).filter { ($0.value as? String) == "1" }.map { $0.identifier })
        let other = app.switches["report.reason.OTHER"]
        reveal(app, other)
        checked.formUnion(reasonRows(app).filter { ($0.value as? String) == "1" }.map { $0.identifier })
        notes.append("iphone-en report preselect 10: checked=\(checked.count) other exists=\(other.exists) enabled=\(other.exists ? other.isEnabled : false) value=\((other.value as? String) ?? "?")")
        XCTAssertEqual(checked.count, 10, "c6 report: ten checked")
        XCTAssertTrue(other.exists, "c6 report: the Other row must be reachable")
        if other.exists { XCTAssertFalse(other.isEnabled, "c6 report: the 11th row must disable at 10 (report_reason_limit)") }
        try write(named: "detail-c6-iphone-en-light-report-capped", into: directory)
        app.buttons["report.cancel"].tap()
        XCTAssertTrue(app.buttons["report.submit"].waitForNonExistence(timeout: 5))

        // Other alone (a legal one-reason state; 11 checked is unreachable past the cap of 10) reveals the field.
        app = launch(playlistScreen(["-fitrah-report-preselect", "OTHER"]), locale: en, extraArguments: [])
        XCTAssertTrue(any(app, "playlist.row.1").waitForExistence(timeout: 20))
        openReport(app)
        let otherField = app.textViews["report.otherText"].exists ? app.textViews["report.otherText"] : app.textFields["report.otherText"]
        reveal(app, otherField)
        XCTAssertTrue(otherField.waitForExistence(timeout: 5), "c6 report: Other must reveal the field")
        if otherField.exists { otherField.tap(); otherField.typeText("Test note") }
        notes.append("iphone-en report other field=\(otherField.exists ? "\(otherField.frame)" : "missing") value=\((otherField.value as? String).debugDescription)")
        try write(named: "detail-c6-iphone-en-light-report-other", into: directory)
        app.buttons["report.cancel"].tap()
        XCTAssertTrue(app.buttons["report.submit"].waitForNonExistence(timeout: 5))

        // 201 -> dismiss + report_success banner; 429 -> the sheet and the checks stay.
        app = launch(playlistScreen(["-fitrah-fake-report", "201", "-fitrah-report-preselect", "1"]), locale: en, extraArguments: [])
        XCTAssertTrue(any(app, "playlist.row.1").waitForExistence(timeout: 20))
        openReport(app)
        XCTAssertEqual(app.switches["report.reason.MUSIC"].value as? String, "1")
        app.buttons["report.submit"].tap()
        let thanks = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Thank you'")).firstMatch
        let thanked = thanks.waitForExistence(timeout: 5)
        try write(named: "detail-c6-iphone-en-light-report-success", into: directory)
        notes.append("iphone-en report 201: banner=\(thanked) label=\(thanked ? thanks.label.debugDescription : "-") sheet gone=\(!app.buttons["report.submit"].exists)")
        XCTAssertTrue(thanked, "c6 report 201: report_success banner")
        XCTAssertTrue(app.buttons["report.submit"].waitForNonExistence(timeout: 10), "c6 report 201: the sheet must dismiss")
        app = launch(playlistScreen(["-fitrah-fake-report", "429", "-fitrah-report-preselect", "1"]), locale: en, extraArguments: [])
        XCTAssertTrue(any(app, "playlist.row.1").waitForExistence(timeout: 20))
        openReport(app)
        app.buttons["report.submit"].tap()
        reveal(app, app.staticTexts["report.message"])
        XCTAssertTrue(app.staticTexts["report.message"].waitForExistence(timeout: 10), "c6 report 429: inline message")
        XCTAssertEqual(app.staticTexts["report.message"].label, "You've sent too many reports recently. Please try again later.")
        XCTAssertTrue(app.buttons["report.submit"].exists, "c6 report 429: the sheet stays (ruling 72)")
        reveal(app, app.switches["report.reason.MUSIC"], missingIsBelow: false)
        XCTAssertEqual(app.switches["report.reason.MUSIC"].exists ? app.switches["report.reason.MUSIC"].value as? String : "missing", "1", "c6 report 429: the check stays")
        try write(named: "detail-c6-iphone-en-light-report-429", into: directory)
    }

    /// C T6 fix I1: a list that fits, whose Load-more tap appends past the fold. The new rows'
    /// `onAppear` fires before `onContentFits` reports the overflow, so a near-end check that reads
    /// `contentFits` on its own schedule skips them and the list stops with no button and no way to
    /// page. Six 1-row pages: 1 + autofill = 2 (fit, Load more), tap -> 3, renewed autofill -> 4
    /// (overflows), and the near-end trigger must carry the rest: every page appears.
    func testDetailCTask6PaginationPastTheFold() throws {
        let directory = try shotsDirectory()
        let notes = Notes(file: directory.appendingPathComponent("c-task6-iphone-measurements.txt"), append: true)
        XCUIDevice.shared.orientation = .portrait
        let app = launch(channelScreen(["-fitrah-fake-browse-pages", "6", "1"]), locale: Self.locales[0], extraArguments: [])
        let loadMore = app.buttons["listFooter.loadMore"]
        XCTAssertTrue(loadMore.waitForExistence(timeout: 20), "c6 fold: Load more never appeared after the single autofill")
        XCTAssertEqual(ids(app, prefix: "channel.videos.row.").count, 2, "c6 fold: one page + one autofill = 2 rows")
        loadMore.tap()
        // Scrolls (rows below the fold materialise on the way) and taps Load more if it ever shows.
        let rows = scrollCollecting(app, prefix: "channel.videos.row.", target: 6)
        notes.append("iphone-en fold after tap rows=\(rows.count) \(rows) loadMore=\(loadMore.exists)")
        try write(named: "detail-c6-iphone-en-light-channel-fold", into: directory)
        XCTAssertEqual(rows.count, 6, "c6 fold: the list stopped at \(rows.count) rows past the fold with no Load more (near-end trigger lost)")
    }

    /// iPhone leg, the other matrix cells: ar-dark, en-dark, ar-light and `.accessibility3` for
    /// both screens (plus the report sheet in ar).
    func testDetailCTask6IPhoneLocales() throws {
        let directory = try shotsDirectory()
        let notes = Notes(file: directory.appendingPathComponent("c-task6-iphone-measurements.txt"), append: true)
        let en = Self.locales[0], ar = Self.locales[1]
        var app: XCUIApplication
        XCUIDevice.shared.orientation = .portrait

        // -- L. ar dark: strip from the trailing edge, mirrored action bar, position column trailing.
        app = launch(channelScreen(), locale: ar, extraArguments: [])
        XCTAssertTrue(any(app, "channel.videos.row.video-0-0").waitForExistence(timeout: 20), "c6 ar channel: never loaded")
        let arVideos = app.buttons["channel.tab.videos"].frame, arAbout = app.buttons["channel.tab.about"].frame
        notes.append("iphone-ar strip videos=\(arVideos) about=\(arAbout) subscribe=\(app.buttons["channel.subscribe"].frame) subscribers=\(app.staticTexts["channel.subscribers"].label.debugDescription)")
        XCTAssertGreaterThan(arVideos.minX, arAbout.minX, "c6 ar: the strip must lead from the trailing (right) edge")
        try write(named: "detail-c6-iphone-ar-dark-channel-videos", into: directory)
        app.buttons["channel.tab.shorts"].tap()
        XCTAssertTrue(app.buttons["channel.shorts.cell.shorts-0-0"].waitForExistence(timeout: 10))
        try write(named: "detail-c6-iphone-ar-dark-channel-shorts", into: directory)
        app = launch(playlistScreen(), locale: ar, extraArguments: [])
        XCTAssertTrue(any(app, "playlist.row.1").waitForExistence(timeout: 20), "c6 ar playlist: never loaded")
        let arPlay = app.buttons["playlist.playAll"].frame, arSave = app.buttons["playlist.save"].frame
        notes.append("iphone-ar playlist playAll=\(arPlay) save=\(arSave) row1=\(any(app, "playlist.row.1").frame)")
        XCTAssertGreaterThan(arPlay.minX, arSave.minX, "c6 ar: the action bar must mirror")
        try write(named: "detail-c6-iphone-ar-dark-playlist", into: directory)
        openReport(app)
        try write(named: "detail-c6-iphone-ar-dark-report", into: directory)
        app.buttons["report.cancel"].tap()

        // -- M. The other two theme cells: en dark, ar light.
        app = launch(channelScreen(), locale: LocaleCase(key: "en", theme: "dark", arguments: []), extraArguments: [])
        XCTAssertTrue(any(app, "channel.videos.row.video-0-0").waitForExistence(timeout: 20))
        try write(named: "detail-c6-iphone-en-dark-channel-videos", into: directory)
        app = launch(playlistScreen(), locale: LocaleCase(key: "en", theme: "dark", arguments: []), extraArguments: [])
        XCTAssertTrue(any(app, "playlist.row.1").waitForExistence(timeout: 20))
        try write(named: "detail-c6-iphone-en-dark-playlist", into: directory)
        app = launch(channelScreen(), locale: LocaleCase(key: "ar", theme: "light", arguments: ar.arguments), extraArguments: [])
        XCTAssertTrue(any(app, "channel.videos.row.video-0-0").waitForExistence(timeout: 20))
        try write(named: "detail-c6-iphone-ar-light-channel-videos", into: directory)
        app = launch(playlistScreen(), locale: LocaleCase(key: "ar", theme: "light", arguments: ar.arguments), extraArguments: [])
        XCTAssertTrue(any(app, "playlist.row.1").waitForExistence(timeout: 20))
        try write(named: "detail-c6-iphone-ar-light-playlist", into: directory)

        // -- N. Dynamic Type .accessibility3: strip reachable, subscribe row + action bar wrap.
        app = launch(channelScreen(), locale: en, extraArguments: Self.accessibility3)
        XCTAssertTrue(any(app, "channel.videos.row.video-0-0").waitForExistence(timeout: 20), "c6 a11y3 channel: never loaded")
        let a11ySubs = app.staticTexts["channel.subscribers"].frame, a11ySubscribe = app.buttons["channel.subscribe"].frame
        notes.append("iphone-en-a11y3 subscribers=\(a11ySubs) subscribe=\(a11ySubscribe) tab.about=\(app.buttons["channel.tab.about"].frame) window=\(app.windows.firstMatch.frame)")
        XCTAssertGreaterThanOrEqual(a11ySubscribe.minY, a11ySubs.maxY - 1, "c6 a11y3: the subscribe row must go single column")
        XCTAssertTrue(app.buttons["channel.tab.videos"].isHittable, "c6 a11y3: the strip must stay reachable")
        try write(named: "detail-c6-iphone-en-light-channel-a11y3", into: directory)
        app = launch(playlistScreen(), locale: en, extraArguments: Self.accessibility3)
        XCTAssertTrue(app.buttons["playlist.playAll"].waitForExistence(timeout: 20), "c6 a11y3 playlist: never loaded")
        let a11yPlay = app.buttons["playlist.playAll"].frame, a11yShuffle = app.buttons["playlist.shuffle"].frame
        notes.append("iphone-en-a11y3 playAll=\(a11yPlay) shuffle=\(a11yShuffle) save=\(app.buttons["playlist.save"].frame)")
        XCTAssertEqual(a11yPlay.minX, a11yShuffle.minX, accuracy: 1, "c6 a11y3: the action bar must wrap to one column")
        XCTAssertGreaterThanOrEqual(a11yShuffle.minY, a11yPlay.maxY - 1)
        XCTAssertLessThanOrEqual(a11yPlay.maxX, app.windows.firstMatch.frame.maxX + 1, "c6 a11y3: cells must not clip")
        try write(named: "detail-c6-iphone-en-light-playlist-a11y3", into: directory)
    }

    // MARK: - Plan C Task 6 step 5: live YouTube + live backend (opt-in, C_LIVE=1)

    private static let cLiveChannelId = "UCmMcOjsVehVlEOteyrhjI2Q"
    private static let cLiveVideoId = "xc7keR2piUM"

    private func firstWithPrefix(_ app: XCUIApplication, _ prefix: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", prefix)).firstMatch
    }

    /// C T6 fix C1: with a REAL banner the header column grew to the image's covering width and was
    /// centred + clipped (avatar off the left edge, Subscribe cut at the right). The fakes carry no
    /// banner (`RemoteImage` is https-only), so only a live header can prove the bound holds.
    private func assertHeaderFits(_ app: XCUIApplication, _ tag: String, into notes: Notes) {
        let window = app.windows.firstMatch.frame
        let subscribe = app.buttons["channel.subscribe"]
        let avatar = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Channel avatar")).firstMatch
        notes.append("\(tag) header fit: subscribe=\(subscribe.frame) avatar exists=\(avatar.exists) frame=\(avatar.exists ? "\(avatar.frame)" : "-") window=\(window)")
        XCTAssertLessThanOrEqual(subscribe.frame.maxX, window.maxX + 1, "\(tag) (C1): the header overflowed the screen (banner width unbounded)")
        XCTAssertTrue(avatar.exists && avatar.frame.minX >= window.minX - 1, "\(tag) (C1): the avatar is off-screen or missing")
    }

    /// Scrolls a lazily-materialised list, accumulating every distinct row id seen, tapping Load
    /// more when it shows; stops at `target` ids, or after `idle` swipes with nothing new and no
    /// footer in sight. Returns the ids in first-seen order.
    private func scrollCollecting(_ app: XCUIApplication, prefix: String, target: Int, idle: Int = 6, maxSwipes: Int = 150) -> [String] {
        var seen: [String] = []
        var quiet = 0
        for _ in 0..<maxSwipes {
            var grew = false
            for id in ids(app, prefix: prefix).map({ $0.identifier }) where !seen.contains(id) { seen.append(id); grew = true }
            if seen.count >= target { break }
            if app.buttons["listFooter.loadMore"].exists { app.buttons["listFooter.loadMore"].tap(); quiet = 0; continue }
            let footer = app.otherElements["listFooter.loading"].exists || app.buttons["listFooter.retry"].exists
                || app.activityIndicators["listFooter.loading"].exists
            quiet = grew || footer ? 0 : quiet + 1
            if quiet >= idle { break }
            app.swipeUp()
        }
        return seen
    }

    /// Every line of the app's redirected stdout (`-fitrah-stdout`) matching `prefix`.
    private func logLines(_ path: String, prefix: String) -> [String] {
        ((try? String(contentsOfFile: path, encoding: .utf8)) ?? "").split(separator: "\n").map(String.init).filter { $0.hasPrefix(prefix) }
    }

    /// Step 5 items 1-8 + CF-C-2/3/13/15 against the real `LiveBrowseSource`, the real backend
    /// (`C_LIVE_API_BASE_URL`, default production) and a locally served remote config
    /// (`C_LIVE_CONFIG_URL`). Files exactly ONE real content report per run. Notes go to
    /// `c-task6-live-measurements.txt`; the app's DEBUG prints to `c-task6-live-app.log`.
    func testDetailCTask6Live() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["C_LIVE"] == "1", "live detail checks are opt-in: C_LIVE=1")
        let directory = try shotsDirectory()
        let notes = Notes(file: directory.appendingPathComponent("c-task6-live-measurements.txt"))
        let log = directory.appendingPathComponent("c-task6-live-app.log").path
        try? FileManager.default.removeItem(atPath: log)
        let base = ProcessInfo.processInfo.environment["C_LIVE_API_BASE_URL"] ?? "https://app.fitrahtube.com/"
        let en = Self.locales[0]
        func live(_ arguments: [String]) -> Screen {
            Screen(key: "c-live", arguments: ["-fitrah-api-base-url", base, "-fitrah-stdout", log] + arguments, anchor: .button("unused"))
        }
        func channel(_ extra: [String] = []) -> XCUIApplication {
            launch(live(["-fitrah-route", "channel", Self.cLiveChannelId, "-"] + extra), locale: en, extraArguments: [], fakeContainer: false)
        }
        func playlist(_ extra: [String] = []) -> XCUIApplication {
            launch(live(["-fitrah-route", "playlist", Self.livePlaylistId, "-"] + extra), locale: en, extraArguments: [], fakeContainer: false)
        }
        XCUIDevice.shared.orientation = .portrait

        // 1 + 4. Real channel; a fast second open repeats page 1's index push byte-for-byte -> 429.
        var app = channel()
        var title = app.staticTexts["channel.title"]
        XCTAssertTrue(title.waitForExistence(timeout: 60), "live 1: channel title never appeared")
        XCTAssertTrue(firstWithPrefix(app, "channel.videos.row.").waitForExistence(timeout: 60), "live 1: Videos never loaded")
        let firstOpenPush = logLines(log, prefix: "IndexClient:")
        app = channel()
        XCTAssertTrue(firstWithPrefix(app, "channel.videos.row.").waitForExistence(timeout: 60), "live 4: Videos never loaded on the second open")
        title = app.staticTexts["channel.title"]
        let deadline = Date().addingTimeInterval(20)
        while Date() < deadline, title.label == Self.cLiveChannelId { Thread.sleep(forTimeInterval: 0.5) }
        let subscribers = app.staticTexts["channel.subscribers"].label
        notes.append("live-1 header title=\(title.label.debugDescription) subscribers=\(subscribers.debugDescription) degraded=\(app.staticTexts["channel.degradedNotice"].exists) videos first=\(firstWithPrefix(app, "channel.videos.row.").identifier)")
        XCTAssertNotEqual(title.label, Self.cLiveChannelId, "live 1: the header never replaced the route's id")
        XCTAssertTrue(subscribers.lowercased().contains("subscriber"), "live 1: subscriber line not populated: \(subscribers)")
        XCTAssertFalse(app.staticTexts["channel.degradedNotice"].exists, "live 1: degraded on a fresh open (bot-check?)")
        assertHeaderFits(app, "live 1", into: notes)
        try write(named: "detail-c6-live-channel-videos", into: directory)
        Thread.sleep(forTimeInterval: 3)  // let the second open's push land before reading the log
        let secondOpenPush = Array(logLines(log, prefix: "IndexClient:").dropFirst(firstOpenPush.count))
        notes.append("live-4 index first open=\(firstOpenPush) second open=\(secondOpenPush)")
        XCTAssertFalse(firstOpenPush.isEmpty, "live 4: no index push logged on the first open")
        XCTAssertTrue(secondOpenPush.contains { $0.contains("status=429") }, "live 4: the fast second open did not 429 (30 s dedupe)")
        for line in firstOpenPush + secondOpenPush {
            let items = try XCTUnwrap(Int(line.split(separator: " ").last { $0.hasPrefix("items=") }?.dropFirst(6) ?? ""), "live 4: unparsable push line: \(line)")
            XCTAssertLessThanOrEqual(items, 50, "live 4: a batch over 50: \(line)")
        }

        // 1 (cont.) + CF-C-13: Live, Shorts, Playlists, About populate.
        app.buttons["channel.tab.live"].tap()
        let liveRow = firstWithPrefix(app, "channel.live.row.")
        let liveShown = liveRow.waitForExistence(timeout: 30)
        notes.append("live-1 live tab rows=\(liveShown ? ids(app, prefix: "channel.live.row.").count : 0) error=\(any(app, "channel.live.error").exists) badges(upcoming)=\(app.staticTexts["Upcoming"].exists) first label=\(liveShown ? liveRow.label.debugDescription : "-")")
        // A channel with no stream today legitimately shows the empty copy; an error state never is.
        XCTAssertTrue(liveShown || !any(app, "channel.live.error").exists, "live 1: the Live tab errored")
        try write(named: "detail-c6-live-channel-live", into: directory)
        app.buttons["channel.tab.shorts"].tap()
        XCTAssertTrue(firstWithPrefix(app, "channel.shorts.cell.").waitForExistence(timeout: 30), "live 1: Shorts empty against a channel that has them (stale fixtures?)")
        notes.append("live-1 shorts cells visible=\(ids(app, prefix: "channel.shorts.cell.").count)")
        try write(named: "detail-c6-live-channel-shorts", into: directory)
        app.buttons["channel.tab.playlists"].tap()
        XCTAssertTrue(firstWithPrefix(app, "channel.playlists.row.").waitForExistence(timeout: 30), "live 1: Playlists empty against a channel that has them (stale fixtures?)")
        try write(named: "detail-c6-live-channel-playlists", into: directory)
        // 9 / CF-C-15: the Playlists tab pages (30 per page live).
        let playlistIds = scrollCollecting(app, prefix: "channel.playlists.row.", target: 31)
        notes.append("live-9 playlists paged: distinct rows=\(playlistIds.count) last=\(playlistIds.last ?? "-")")
        XCTAssertGreaterThan(playlistIds.count, 30, "live 9: the Playlists tab did not page past its first 30")
        try write(named: "detail-c6-live-channel-playlists-page2", into: directory)
        app.buttons["channel.tab.about"].tap()
        XCTAssertTrue(app.staticTexts["channel.about.subscribers"].waitForExistence(timeout: 10), "live 1: About never rendered")
        notes.append("live-1 about subscribers=\(app.staticTexts["channel.about.subscribers"].label.debugDescription)")
        try write(named: "detail-c6-live-channel-about", into: directory)

        // 2. Deep pagination on Videos: past 200, where the `VLUU…` uploads playlist used to stop
        // (100 + 100, then no continuation); the Videos tab pages 30 at a time. A fresh open: the
        // compact strip has scrolled Videos out of reach after About.
        app = channel()
        XCTAssertTrue(firstWithPrefix(app, "channel.videos.row.").waitForExistence(timeout: 60))
        let t2 = Date()
        let videoIds = scrollCollecting(app, prefix: "channel.videos.row.", target: 250)
        let pushed = logLines(log, prefix: "IndexClient: CHANNEL").map { Int($0.split(separator: " ").last { $0.hasPrefix("items=") }?.dropFirst(6) ?? "") ?? 0 }
        notes.append("live-2 deep pagination: distinct rows seen=\(videoIds.count) in \(Int(Date().timeIntervalSince(t2)))s; index pushes (items per batch, all opens)=\(pushed); footer loadMore=\(app.buttons["listFooter.loadMore"].exists) loading=\(app.otherElements["listFooter.loading"].exists || app.activityIndicators["listFooter.loading"].exists) retry=\(app.buttons["listFooter.retry"].exists)")
        XCTAssertGreaterThan(videoIds.count, 200, "live 2: the Videos tab stopped at the old VLUU cap (\(videoIds.count) rows)")
        try write(named: "detail-c6-live-channel-videos-end", into: directory)

        // 3. Real playlist: opens, pages onto page 2, Play All reaches the player.
        app = playlist()
        let pTitle = app.staticTexts["playlist.title"]
        XCTAssertTrue(pTitle.waitForExistence(timeout: 60), "live 3: playlist title never appeared")
        XCTAssertTrue(any(app, "playlist.row.1").waitForExistence(timeout: 60), "live 3: rows never appeared")
        notes.append("live-3 playlist title=\(pTitle.label.debugDescription) metadata=\(app.staticTexts["playlist.metadata"].exists ? app.staticTexts["playlist.metadata"].label.debugDescription : "-")")
        // The route carries no title (CF-C-9): the backend's `Playlist` must replace the id.
        XCTAssertNotEqual(pTitle.label, Self.livePlaylistId, "live 3: the deep-link header fetch never replaced the id")
        let save = app.buttons["playlist.save"], window = app.windows.firstMatch.frame
        notes.append("live-3 hero fit: save=\(save.frame) window=\(window)")
        XCTAssertLessThanOrEqual(save.frame.maxX, window.maxX + 1, "live 3 (C1): the playlist header overflowed the screen (hero width unbounded)")
        try write(named: "detail-c6-live-playlist", into: directory)
        let positions = scrollCollecting(app, prefix: "playlist.row.", target: 101)
        notes.append("live-3 playlist paged: distinct rows=\(positions.count) last=\(positions.last ?? "-")")
        XCTAssertTrue(positions.contains("playlist.row.101"), "live 3: page 2 never appended (position 101)")
        try write(named: "detail-c6-live-playlist-page2", into: directory)
        app = playlist()
        XCTAssertTrue(any(app, "playlist.row.1").waitForExistence(timeout: 60))
        app.buttons["playlist.playAll"].tap()
        XCTAssertTrue(app.otherElements["player.videoBox"].waitForExistence(timeout: 60), "live 3: Play All never opened the player")
        let upNext = app.staticTexts["player.upNext.header"].waitForExistence(timeout: 60)
        notes.append("live-3 playAll title=\(app.staticTexts["player.metadata.title"].label.debugDescription) upNext=\(upNext) rows=\(upNextRows(app).count) state card=\(app.staticTexts["player.state.message"].exists ? app.staticTexts["player.state.message"].label.debugDescription : "-")")
        XCTAssertTrue(upNext, "live 3: Play All listed no queue")
        try write(named: "detail-c6-live-playlist-playall", into: directory)

        // 5. ONE real report (reason OTHER) -> 201 -> report_success. The 429 leg is NOT run here.
        // A marker file keeps a re-run of this method in the same output directory from filing a
        // second one (the script clears the directory per run).
        let filed = directory.appendingPathComponent("c-task6-live-report-filed").path
        if FileManager.default.fileExists(atPath: filed) {
            notes.append("live-5 report: SKIPPED, already filed by an earlier run into this directory")
        } else {
        app = playlist(["-fitrah-report-preselect", "OTHER"])
        XCTAssertTrue(any(app, "playlist.row.1").waitForExistence(timeout: 60))
        openReport(app)
        let otherField = app.textViews["report.otherText"].exists ? app.textViews["report.otherText"] : app.textFields["report.otherText"]
        reveal(app, otherField)
        XCTAssertTrue(otherField.waitForExistence(timeout: 5), "live 5: Other's field never showed")
        otherField.tap()
        otherField.typeText("iOS Plan C acceptance test - safe to dismiss")
        app.buttons["report.submit"].tap()
        let thanks = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Thank you'")).firstMatch
        let thanked = thanks.waitForExistence(timeout: 30)
        notes.append("live-5 report: banner=\(thanked) label=\(thanked ? thanks.label.debugDescription : "-") sheet gone=\(!app.buttons["report.submit"].exists) message=\(app.staticTexts["report.message"].exists ? app.staticTexts["report.message"].label.debugDescription : "-")")
        XCTAssertTrue(thanked, "live 5: the real backend did not answer 201 (see message in the notes)")
        if thanked { FileManager.default.createFile(atPath: filed, contents: nil) }
        try write(named: "detail-c6-live-report-success", into: directory)
        }

        // 7. Deep links through the same `Router.open(URL)` as `.onOpenURL` (`-fitrah-deeplink`).
        app = launch(live(["-fitrah-deeplink", "albunyaantube://channel/\(Self.cLiveChannelId)"]), locale: en, extraArguments: [], fakeContainer: false)
        XCTAssertTrue(app.staticTexts["channel.title"].waitForExistence(timeout: 60), "live 7: channel deep link did not land")
        XCTAssertTrue(firstWithPrefix(app, "channel.videos.row.").waitForExistence(timeout: 60))
        try write(named: "detail-c6-live-deeplink-channel", into: directory)
        app = launch(live(["-fitrah-deeplink", "albunyaantube://playlist/\(Self.livePlaylistId)"]), locale: en, extraArguments: [], fakeContainer: false)
        XCTAssertTrue(app.staticTexts["playlist.title"].waitForExistence(timeout: 60), "live 7: playlist deep link did not land")
        XCTAssertTrue(any(app, "playlist.row.1").waitForExistence(timeout: 60))
        notes.append("live-7 deep-linked playlist title=\(app.staticTexts["playlist.title"].label.debugDescription) (route carried no title: CF-C-9)")
        XCTAssertNotEqual(app.staticTexts["playlist.title"].label, Self.livePlaylistId, "live 7: the deep-link header fetch never replaced the id")
        try write(named: "detail-c6-live-deeplink-playlist", into: directory)
        app = launch(live(["-fitrah-deeplink", "albunyaantube://video/\(Self.cLiveVideoId)"]), locale: en, extraArguments: [], fakeContainer: false)
        XCTAssertTrue(app.otherElements["player.videoBox"].waitForExistence(timeout: 60), "live 7: video deep link did not land")
        try write(named: "detail-c6-live-deeplink-video", into: directory)

        // 8. refresh() reaches a real (locally served) document: the marker id proves the fetch.
        if let configURL = ProcessInfo.processInfo.environment["C_LIVE_CONFIG_URL"] {
            app = launch(live(["-fitrah-remote-config-url", configURL, "-fitrah-tab", "home"]), locale: en, extraArguments: [], fakeContainer: false)
            let until = Date().addingTimeInterval(30)
            var refreshed: [String] = []
            while Date() < until, refreshed.isEmpty { Thread.sleep(forTimeInterval: 1); refreshed = logLines(log, prefix: "RemoteConfig:") }
            notes.append("live-8 remote config via \(configURL): \(refreshed)")
            XCTAssertTrue(refreshed.contains { $0.contains("featuredCategoryId=c-task6-live-marker") }, "live 8: current() did not return the fetched document")
        } else {
            notes.append("live-8 remote config: NOT RUN (C_LIVE_CONFIG_URL unset)")
        }

        // 11 / CF-C-2: did the stale-token rotation ever fire?
        let botChecks = logLines(log, prefix: "BrowseClient: bot-check")
        notes.append("live-11 bot-check trips=\(botChecks.count) \(botChecks)")
    }

    /// iPad leg: the strip fills the width, two autofills then Load more (ruling 10), the
    /// selection survives rotation, playlist positions, ar mirroring, `.accessibility3`.
    func testDetailCTask6IPad() throws {
        let directory = try shotsDirectory()
        let notes = Notes(file: directory.appendingPathComponent("c-task6-ipad-measurements.txt"))
        let en = Self.locales[0], ar = Self.locales[1]

        XCUIDevice.shared.orientation = .portrait
        var app = launch(channelScreen(), locale: en, extraArguments: [])
        XCTAssertTrue(any(app, "channel.videos.row.video-0-0").waitForExistence(timeout: 20), "c6 ipad channel: never loaded")
        let window = app.windows.firstMatch.frame
        let tabs = ["videos", "live", "shorts", "playlists", "about"].map { app.buttons["channel.tab.\($0)"] }
        for tab in tabs { measure(tab, "ipad-en tab.\(tab.identifier)", into: notes) }
        XCTAssertEqual(tabs.last!.frame.maxX, window.maxX, accuracy: 2, "c6 ipad: the strip must fill the width")
        XCTAssertEqual(tabs[0].frame.width, tabs[4].frame.width, accuracy: 2, "c6 ipad: equal tab widths")
        measure(app.buttons["channel.subscribe"], "ipad-en subscribe", into: notes)
        measure(app.buttons["detail.kebab.button"], "ipad-en kebab", into: notes, barItem: true)
        try write(named: "detail-c6-ipad-en-light-channel-videos", into: directory)
        app.buttons["channel.tab.shorts"].tap()
        XCTAssertTrue(app.buttons["channel.shorts.cell.shorts-0-0"].waitForExistence(timeout: 10))
        let cells = ids(app, prefix: "channel.shorts.cell.")
        let firstRowCells = cells.filter { abs($0.frame.minY - cells[0].frame.minY) < 2 }.count
        notes.append("ipad-en shorts columns=\(firstRowCells)")
        XCTAssertEqual(firstRowCells, 5, "c6 ipad: Shorts grid is 5 columns at large width")
        try write(named: "detail-c6-ipad-en-light-channel-shorts", into: directory)
        XCUIDevice.shared.orientation = .landscapeLeft
        settle(app, landscape: true)
        XCTAssertTrue(app.buttons["channel.tab.shorts"].isSelected, "c6 ipad rotation: the selection must survive")
        try write(named: "detail-c6-ipad-en-light-channel-shorts-landscape", into: directory)
        XCUIDevice.shared.orientation = .portrait
        settle(app, landscape: false)

        // Ruling 10 on regular width: first page fits -> second automatically -> stop at two with Load
        // more. Two-row pages: 6 rows (~480 pt) still fit the ~800 pt tab body; 9 rows of 79 pt plus
        // the footer did not, and the near-end scroll trigger then legitimately paged on.
        app = launch(channelScreen(["-fitrah-fake-browse-pages", "6", "2"]), locale: en, extraArguments: [])
        let loadMore = app.buttons["listFooter.loadMore"]
        XCTAssertTrue(loadMore.waitForExistence(timeout: 20), "c6 ipad pagination: Load more never appeared after two autofills")
        var rows = ids(app, prefix: "channel.videos.row.")
        notes.append("ipad-en pagination rows=\(rows.count) \(rows.map { $0.identifier })")
        XCTAssertEqual(rows.count, 6, "c6 ipad pagination: one page + two autofills = 6 rows")
        measure(loadMore, "ipad-en loadMore", into: notes)
        try write(named: "detail-c6-ipad-en-light-channel-loadmore", into: directory)
        loadMore.tap()
        XCTAssertTrue(any(app, "channel.videos.row.video-3-0").waitForExistence(timeout: 10), "c6 ipad pagination: the tap renews the budget")
        rows = ids(app, prefix: "channel.videos.row.")
        notes.append("ipad-en pagination after tap rows=\(rows.count)")
        XCTAssertGreaterThanOrEqual(rows.count, 8)

        app = launch(playlistScreen(), locale: en, extraArguments: [])
        XCTAssertTrue(any(app, "playlist.row.1").waitForExistence(timeout: 20), "c6 ipad playlist: never loaded")
        for id in ["playlist.playAll", "playlist.shuffle", "playlist.save"] { measure(app.buttons[id], "ipad-en \(id)", into: notes) }
        XCTAssertFalse(app.buttons["Download"].exists)
        try write(named: "detail-c6-ipad-en-light-playlist", into: directory)
        openReport(app)
        XCTAssertTrue(app.switches["report.reason.MUSIC"].exists)
        try write(named: "detail-c6-ipad-en-light-report", into: directory)
        app.buttons["report.cancel"].tap()

        app = launch(channelScreen(), locale: ar, extraArguments: [])
        XCTAssertTrue(any(app, "channel.videos.row.video-0-0").waitForExistence(timeout: 20), "c6 ipad ar channel: never loaded")
        XCTAssertGreaterThan(app.buttons["channel.tab.videos"].frame.minX, app.buttons["channel.tab.about"].frame.minX, "c6 ipad ar: the strip must mirror")
        try write(named: "detail-c6-ipad-ar-dark-channel-videos", into: directory)
        app = launch(playlistScreen(), locale: ar, extraArguments: [])
        XCTAssertTrue(any(app, "playlist.row.1").waitForExistence(timeout: 20), "c6 ipad ar playlist: never loaded")
        XCTAssertGreaterThan(app.buttons["playlist.playAll"].frame.minX, app.buttons["playlist.save"].frame.minX, "c6 ipad ar: the action bar must mirror")
        try write(named: "detail-c6-ipad-ar-dark-playlist", into: directory)

        app = launch(channelScreen(), locale: en, extraArguments: Self.accessibility3)
        XCTAssertTrue(any(app, "channel.videos.row.video-0-0").waitForExistence(timeout: 20), "c6 ipad a11y3: never loaded")
        notes.append("ipad-en-a11y3 tab.about=\(app.buttons["channel.tab.about"].frame) subscribe=\(app.buttons["channel.subscribe"].frame)")
        XCTAssertTrue(app.buttons["channel.tab.about"].isHittable)
        try write(named: "detail-c6-ipad-en-light-channel-a11y3", into: directory)
        app = launch(playlistScreen(), locale: en, extraArguments: Self.accessibility3)
        XCTAssertTrue(app.buttons["playlist.playAll"].waitForExistence(timeout: 20))
        XCTAssertEqual(app.buttons["playlist.playAll"].frame.minX, app.buttons["playlist.shuffle"].frame.minX, accuracy: 1, "c6 ipad a11y3: one column")
        try write(named: "detail-c6-ipad-en-light-playlist-a11y3", into: directory)
    }

    // MARK: - Phase 3 Task 6: the Saved screen (plan 2026-09-01-ios-phase3-offline-cast)

    /// `-fitrah-seed-offline` inserts one row per `OfflineStatus`, so one screen shows the full
    /// action matrix, every status caption, the failed row's error copy and the storage footer.
    /// en + ar (RTL) portrait, on whichever device `screenshots.sh`'s phase3-saved block launches.
    func testSavedScreenPhase3() throws {
        let directory = try shotsDirectory()
        let screen = Screen(key: "phase3-saved",
                            arguments: ["-fitrah-seed-offline", "-fitrah-route", "offline"],
                            anchor: .element("unused"))
        for locale in Self.locales {
            XCUIDevice.shared.orientation = .portrait
            let app = launch(screen, locale: locale, extraArguments: [])
            // Seeded titles come from `seedDebugOfflineItemsIfRequested` and are
            // locale-independent, same idea as the FakeCatalogClient anchors.
            let firstRow = app.staticTexts
                .matching(NSPredicate(format: "label CONTAINS %@", "Seeded Lecture")).firstMatch
            XCTAssertTrue(firstRow.waitForExistence(timeout: 20),
                          "phase3-saved/\(locale.key): the seeded rows never appeared")
            try write(named: "phase3-saved-\(locale.key)-\(locale.theme)-portrait", into: directory)
        }
    }

    /// Phase 3 Task 8: the player toolbar with all FIVE slots — favorite, share, report, the Save
    /// slot and the cast slot. `seed-offline-3` is `-fitrah-seed-offline`'s `.completed` row
    /// (`OfflineStatus.allCases[3]`), so the Save slot renders its Open state without a gate hook;
    /// the cast slot needs no hook either — `AppDelegate`'s launch `setUp()` creates a real
    /// `GCKCastContext` on the simulator, which is all `castAvailable` asks for.
    func testPlayerSaveAndCastToolbarPhase3() throws {
        let directory = try shotsDirectory()
        let screen = Screen(key: "phase3-player-save-cast",
                            arguments: ["-fitrah-seed-offline", "-fitrah-fake-player-hls",
                                        "-fitrah-route", "player", "seed-offline-3"],
                            anchor: .button("unused"))
        for locale in Self.locales {
            XCUIDevice.shared.orientation = .portrait
            let app = launch(screen, locale: locale, extraArguments: [])
            let favorite = app.buttons["player.favoriteButton"]
            XCTAssertTrue(favorite.waitForExistence(timeout: 20),
                          "phase3-player-save-cast/\(locale.key): the toolbar never appeared")
            let save = app.buttons["player.saveButton"]
            XCTAssertTrue(save.waitForExistence(timeout: 10),
                          "phase3-player-save-cast/\(locale.key): the save slot never appeared")
            // `buttons`, not `otherElements` (fix round 1, review Important 3): the accessibility
            // element is now the `GCKUICastButton` itself, so it carries the button trait.
            let cast = app.buttons["player.castButton"]
            XCTAssertTrue(cast.waitForExistence(timeout: 10),
                          "phase3-player-save-cast/\(locale.key): the cast slot never appeared")
            try write(named: "phase3-player-save-cast-\(locale.key)-\(locale.theme)-portrait", into: directory)
        }
    }

    // MARK: - Phase 4 Task 19: the account screens
    // (docs/superpowers/plans/2026-09-02-ios-phase4-accounts.md)

    /// The Phase 4 screens `Self.screens` cannot hold. The first two are ROOT destinations
    /// `SplashRouter` picks from the fixture `/me`'s status, so they take no `-fitrah-route` at
    /// all; the last two are pushed routes. `-fitrah-fake-auth` is read at CONTAINER CONSTRUCTION
    /// (`AppContainer.sharedFake`), which is why the status rides a launch argument rather than a
    /// seed that runs in the scene's `.task`.
    ///
    /// `sign-in` is NOT here: with no `GoogleService-Info.plist` (git-ignored, USER-BLOCKED)
    /// `SignInCapabilities.current()` is all-false, so `SignInScreen` renders its F11 empty state
    /// instead of the form. Photographing the real screen needs a launch hook that feeds
    /// `AppContainer.fake(capabilities:googleSignIn:appleSignIn:)` from `sharedFake` — machinery
    /// that does not exist, so the row is dropped rather than invented (task-19-stage7-report.md).
    private static let phase4Screens: [Screen] = [
        Screen(key: "profile-bootstrap", arguments: ["-fitrah-fake-auth", "pendingProfile"],
               anchor: .buttonID("bootstrap.submit")),
        // The non-dismissible terminal alert. `RootView`'s `.onChange(of: outcome, initial: true)`
        // raises it on the FIRST pass, i.e. while `showSplash` is still true — so the captured
        // backdrop is the splash, not the guest shell `SplashRouter.outcome` also asks for. That is
        // what a blocked account actually sees at launch, and the alert is the whole subject.
        Screen(key: "account-blocked", arguments: ["-fitrah-fake-auth", "blocked"], anchor: .alert),
        Screen(key: "profile", arguments: ["-fitrah-fake-auth", "active", "-fitrah-route", "profile"],
               anchor: .buttonID("profile.save")),
        // `SettingsView.accountSection`, which renders only for a signed-in user. Its OWN row
        // rather than `-fitrah-fake-auth active` on the shared `settings` row: prepending the
        // Account section pushes the first Playback toggle out of the materialised hierarchy at
        // `.accessibility3` on a phone, and `testAccessibilityTextSizes`' `settings` capture —
        // green today — went red on `.firstSwitch` (task-19-stage7-report.md, commit 3).
        Screen(key: "settings-account",
               arguments: ["-fitrah-fake-auth", "active", "-fitrah-route", "settings"],
               anchor: .buttonID("settings.signOut")),
    ]

    /// Phase 4 Tasks 29-30. Its own table for `testAccountScreensPhase4`'s reason — and its own
    /// CASE, so a Part B re-run does not re-shoot Part A's eight account screens.
    private static let partBScreens: [Screen] = [
        // Fork F14's Pending tab. `-fitrah-seed-submissions` writes one AWAITING row per store,
        // which is what makes the tab bar exist at all (`MeViewModel.showsTabs`);
        // `-fitrah-me-pending` selects it, because the rig cannot tap a segmented control. The
        // anchor is the control's identifier — the section's only locale-independent text is a
        // seeded title, and identifiers read the same in en and ar.
        Screen(key: "me-pending-tab",
               arguments: ["-fitrah-fake-auth", "active", "-fitrah-seed-submissions",
                           "-fitrah-me-pending", "-fitrah-tab", "me"],
               anchor: .anyID("me.awaiting.row.UCmMcOjsVehVlEOteyrhjI2Q")),
        // Task 29's import review screen. `-fitrah-seed-import-review` is the ONE fixture state
        // that lets the import flow past `.authorizing`: it makes the fixture authorizer hand back
        // a synthetic token and points `youtubeImportSource` at three canned pages. Nothing in this
        // run addresses `googleapis.com`. This closes part of CF-A-22 — the screen was declared
        // unphotographable at Task 29 for exactly the machinery added here.
        Screen(key: "import-review",
               arguments: ["-fitrah-fake-auth", "active", "-fitrah-seed-import-review",
                           "-fitrah-route", "importFromYouTube"],
               anchor: .buttonID("import.confirm")),
    ]

    func testPartBScreensPhase4() throws {
        let directory = try shotsDirectory()
        for screen in Self.partBScreens {
            for locale in Self.locales {
                try capture(screen, locale: locale, extraArguments: [], suffix: "", into: directory)
            }
        }
    }

    /// Part B gate, stage 3 I-1: the ONE end-to-end tap through SwiftUI's own alert. The caution
    /// gate is presented off a custom `Binding`, and if SwiftUI wrote that binding `false` BEFORE
    /// running the tapped button's action, Continue would start nothing — a defect no unit test
    /// can see, because they all call `acceptCaution()` on the model directly. The fixture pipeline
    /// answers a canned 503, so a run that starts ends on the DONE arm ("0 of N imported"), and
    /// that arm's Done button is what proves the tap went through.
    func testImportCautionContinueStartsTheRun() throws {
        let screen = try XCTUnwrap(Self.partBScreens.first { $0.key == "import-review" })
        let app = launch(screen, locale: Self.locales[0], extraArguments: [])
        let confirm = app.buttons["import.confirm"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 30), "the review screen never loaded")
        confirm.tap()
        let alert = app.alerts.firstMatch
        XCTAssertTrue(alert.waitForExistence(timeout: 10), "the caution gate did not appear")
        let proceed = alert.buttons["Continue"]
        XCTAssertTrue(proceed.waitForExistence(timeout: 5), "no Continue on the caution gate")
        proceed.tap()
        let done = app.descendants(matching: .any).matching(
            NSPredicate(format: "identifier == %@", "import.done")).firstMatch
        XCTAssertTrue(done.waitForExistence(timeout: 30),
                      "Continue started nothing: the run never reached its DONE arm")
    }

    /// Its own case rather than rows on `Self.screens`: that table is captured by
    /// `testCatalogScreens`, which `screenshots.sh` runs on iPads only and which is far too long to
    /// re-run for one row. Same one-block-per-task shape as `testSavedScreenPhase3`.
    func testAccountScreensPhase4() throws {
        let directory = try shotsDirectory()
        for screen in Self.phase4Screens {
            for locale in Self.locales {
                try capture(screen, locale: locale, extraArguments: [], suffix: "", into: directory)
            }
        }
    }

    // MARK: - Helpers

    /// `fakeContainer: false` (Plan C Task 6's live leg) launches the LIVE container: real
    /// `LiveBrowseSource`, real index/report clients, the app's own defaults suite and store.
    private func launch(_ screen: Screen, locale: LocaleCase, extraArguments: [String], fakeContainer: Bool = true) -> XCUIApplication {
        let app = XCUIApplication()
        var arguments = (fakeContainer ? ["-fitrah-fake-container"] : []) + ["-theme", locale.theme]
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
        case .buttonID(let identifier):
            return app.buttons[identifier]
        case .anyID(let identifier):
            return app.descendants(matching: .any).matching(
                NSPredicate(format: "identifier == %@", identifier)).firstMatch
        case .alert:
            return app.alerts.firstMatch
        }
    }
}
