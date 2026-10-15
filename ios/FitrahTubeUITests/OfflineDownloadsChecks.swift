import XCTest

/// 1.0.3 live check (offline downloads, iOS fix brief R7): the LIVE container on the simulator,
/// signed in through the sign-in FORM, one catalog lecture saved for offline, then the dead-network
/// launches. The simulator shares the Mac's network, so "offline" is the app's DEBUG hooks:
/// `-fitrah-api-base-url` on a refused (or hanging) local port for the backend,
/// `-fitrah-firebase-unreachable` for Firebase, `-fitrah-remote-config-url` for the remote config and
/// `-fitrah-offline` for the reachability banner. Opt-in, so neither gate runs it:
///
///     security find-generic-password -s fitrahtube-test-account -w | tr -d '\n' | xcrun simctl pbcopy <sim>
///     TEST_RUNNER_FITRAH_OFFLINE_CHECKS=1 TEST_RUNNER_FITRAH_LIVE_EMAIL=<test account> \
///       TEST_RUNNER_FITRAH_HANG_PORT=<port> TEST_RUNNER_FITRAH_SHOTS_DIR=<dir> \
///       xcodebuild test-without-building … -only-testing:FitrahTubeUITests/OfflineDownloadsChecks
///
/// Quit Simulator.app first: its pasteboard sync would copy the password to the Mac's.
///
/// The build must be ad-hoc signed (`CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=-`), or Firebase
/// cannot keep the session in the simulator keychain. `HANG_PORT` is a local listener that accepts
/// and never answers (the dead Wi-Fi / stalled backend case). The password reaches the focused
/// SecureTextField by PASTE: the runner pipes it from the Keychain into `xcrun simctl pbcopy`, the
/// test pastes it and empties the pasteboard. Nothing here prints the tree once it is in.
final class OfflineDownloadsChecks: XCTestCase {
    /// Catalog lecture only, and a SHORT one so the save finishes: "Science of Judging Hadith"
    /// (One Message Foundation, 16 s), found by the "judging hadith" search. The same title also
    /// names a 73-minute lecture, so the row is matched on its duration too.
    private static let lectureTitle = "Science of Judging Hadith"
    private static let lectureDuration = "0:16"
    private static let lectureId = "NSzeOVP5YVQ"
    private static let liveAPI = "https://app.fitrahtube.com/"
    private static let refused = "http://127.0.0.1:9/"

    private struct Lang {
        let code: String, locale: String
        let saved: String, open: String, noInternet: String, home: String
    }
    private static let en = Lang(code: "en", locale: "en_US", saved: "Saved", open: "Open",
                                 noInternet: "No internet connection", home: "Home")
    private static let ar = Lang(code: "ar", locale: "ar_SA", saved: "المحفوظات", open: "فتح",
                                 noInternet: "لا يوجد اتصال بالإنترنت", home: "الرئيسية")

    private var environment: [String: String] { ProcessInfo.processInfo.environment }
    private var device: String { UIDevice.current.userInterfaceIdiom == .pad ? "ipad" : "iphone" }
    private var shotsDirectory: URL!
    private var notes: [String] = []

    override func setUpWithError() throws {
        try XCTSkipUnless(environment["FITRAH_OFFLINE_CHECKS"] == "1", "Live offline checks are opt-in: FITRAH_OFFLINE_CHECKS=1")
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        let dir = try XCTUnwrap(environment["FITRAH_SHOTS_DIR"], "set TEST_RUNNER_FITRAH_SHOTS_DIR")
        shotsDirectory = URL(fileURLWithPath: dir, isDirectory: true)
        try FileManager.default.createDirectory(at: shotsDirectory, withIntermediateDirectories: true)
    }

    override func tearDown() {
        try? notes.joined(separator: "\n").appending("\n")
            .write(to: shotsDirectory.appendingPathComponent("ios-fix-\(device)-notes.txt"), atomically: true, encoding: .utf8)
        super.tearDown()
    }

    func testOfflineDownloads() throws {
        let email = try XCTUnwrap(environment["FITRAH_LIVE_EMAIL"], "set TEST_RUNNER_FITRAH_LIVE_EMAIL")
        let hangPort = try XCTUnwrap(environment["FITRAH_HANG_PORT"], "set TEST_RUNNER_FITRAH_HANG_PORT")
        try signInThroughTheForm(email: email)
        try saveTheLecture()
        for lang in [Self.en, Self.ar] { try offlineColdLaunchPlaysTheSavedLecture(lang) }
        for lang in [Self.en, Self.ar] { try hangingBackendRoutesWithinTheBound(lang, port: hangPort) }
        try backOnlineKeepsAccountAndLibrary()
        try signOutOnline()
        for lang in [Self.en, Self.ar] { try signedOutOfflineShowsTheWallAndSaysNoInternet(lang, email: email) }
    }

    // MARK: - Legs

    /// Leg 0, online: the form, never the temp-file hook. The password is typed only after the
    /// SecureTextField itself reports keyboard focus.
    private func signInThroughTheForm(email: String) throws {
        let app = launch(Self.en, api: Self.liveAPI)
        let submit = app.buttons["signIn.submit"]
        XCTAssertTrue(submit.waitForExistence(timeout: 30), "not on the sign-in wall\n\(app.debugDescription)")
        let emailField = app.textFields.firstMatch
        emailField.tap()
        emailField.typeText(email)
        let passwordField = app.secureTextFields.firstMatch
        XCTAssertTrue(passwordField.exists, "no password field\n\(app.debugDescription)")
        passwordField.tap()
        let focus = expectation(for: NSPredicate(format: "hasKeyboardFocus == true"), evaluatedWith: passwordField)
        guard XCTWaiter().wait(for: [focus], timeout: 5) == .completed,
              passwordField.elementType == .secureTextField else {
            XCTFail("the password field never took focus: the password is NOT typed")
            return
        }
        // PASTED, never typed: `typeText` records the first characters of whatever it types in the
        // activity log, a SecureTextField included (measured 2026-10-14 on Xcode 26.3, against
        // `LiveSignInFile`'s note). The runner put the password on the simulator's pasteboard
        // straight from the Keychain; only the paste keystroke is recorded, and the pasteboard is
        // emptied right after.
        passwordField.typeKey("v", modifierFlags: .command)
        let filled = NSPredicate(format: "value BEGINSWITH %@", "•")
        if XCTWaiter().wait(for: [expectation(for: filled, evaluatedWith: passwordField)], timeout: 5) != .completed {
            passwordField.press(forDuration: 1.2)
            let paste = app.menuItems["Paste"].firstMatch
            if paste.waitForExistence(timeout: 5) { paste.tap() }
        }
        UIPasteboard.general.items = []
        XCTAssertEqual(XCTWaiter().wait(for: [expectation(for: filled, evaluatedWith: passwordField)], timeout: 5), .completed,
                       "the password never reached the field (paste)")
        submit.tap()
        // From here on, never `debugDescription`: the tree would carry the field's value.
        XCTAssertTrue(shell(app, Self.en).waitForExistence(timeout: 60), "sign-in never reached the shell")
        note("leg0 signed-in through the form")
        app.terminate()
    }

    /// Leg 0, online: one catalog lecture saved for offline (the debug flag lets the gate allow it).
    private func saveTheLecture() throws {
        let app = launch(Self.en, api: Self.liveAPI,
                         extra: ["-fitrah-route", "search", "-fitrah-search-query", "judging hadith"])
        let row = app.buttons.matching(NSPredicate(format: "label CONTAINS %@ AND label CONTAINS %@",
                                                   Self.lectureTitle, Self.lectureDuration)).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 60), "lecture never appeared in search\n\(app.debugDescription)")
        if app.keyboards.firstMatch.exists { app.typeText("\n") }
        row.tap()
        // The clip is portrait, which opens fullscreen and hides the toolbar (Save included).
        let exitFullscreen = app.buttons["player.fullscreenExit"]
        if exitFullscreen.waitForExistence(timeout: 20) { exitFullscreen.tap() }
        let save = app.buttons["player.saveButton"]
        XCTAssertTrue(save.waitForExistence(timeout: 60) && save.isHittable, "no Save button\n\(app.debugDescription)")
        if save.value as? String != "Saved" {
            save.tap()
            let confirm = app.buttons.matching(NSPredicate(format: "label == 'Save for offline' AND identifier != 'player.saveButton'")).firstMatch
            XCTAssertTrue(confirm.waitForExistence(timeout: 10), "no confirm in the quality sheet\n\(app.debugDescription)")
            confirm.tap()
        }
        let saved = app.buttons.matching(NSPredicate(format: "identifier == 'player.saveButton' AND value == 'Saved'")).firstMatch
        XCTAssertTrue(saved.waitForExistence(timeout: 180), "the save never completed\n\(app.debugDescription)")
        note("leg0 saved \(Self.lectureId)")
        app.terminate()
    }

    /// Requirement 1: signed in, dead network, cold launch: the shell (never the wall), Saved from
    /// the Me tab, and the saved lecture PLAYS (the player's clock advances).
    private func offlineColdLaunchPlaysTheSavedLecture(_ lang: Lang) throws {
        let app = launch(lang, api: Self.refused, offline: true, extra: ["-fitrah-tab", "me"])
        XCTAssertTrue(shell(app, lang).waitForExistence(timeout: 10), "\(lang.code): no shell on an offline launch\n\(app.debugDescription)")
        XCTAssertFalse(app.buttons["signIn.submit"].exists, "\(lang.code): the offline launch showed the wall")
        shot("1-offline-launch-shell", lang)
        let savedLink = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", lang.saved)).firstMatch
        XCTAssertTrue(savedLink.waitForExistence(timeout: 10), "\(lang.code): no Saved link on Me\n\(app.debugDescription)")
        savedLink.tap()
        let open = app.buttons.matching(NSPredicate(format: "label == %@", lang.open)).firstMatch
        XCTAssertTrue(open.waitForExistence(timeout: 10), "\(lang.code): no Open on the saved row\n\(app.debugDescription)")
        shot("1-offline-saved", lang)
        open.tap()
        let probe = app.descendants(matching: .any)["player.debugRoute"]
        XCTAssertTrue(probe.waitForExistence(timeout: 20), "\(lang.code): the saved file never reached a playable state\n\(app.debugDescription)")
        let start = playerTime(probe)
        let advanced = expectation(for: NSPredicate { _, _ in self.playerTime(probe) >= start + 3 }, evaluatedWith: probe)
        XCTAssertEqual(XCTWaiter().wait(for: [advanced], timeout: 20), .completed,
                       "\(lang.code): playback never advanced: \(probe.value ?? "nil")")
        shot("1-offline-playing", lang)
        note("leg1 \(lang.code): shell, Saved, playing time \(start)s -> \(playerTime(probe))s")
        app.terminate()
    }

    /// Requirement 1 on dead Wi-Fi (R1's 3 s bound): `/me` reaches a listener that never answers.
    /// The launch must land in the shell on the saved record well inside the transport's 15 s.
    private func hangingBackendRoutesWithinTheBound(_ lang: Lang, port: String) throws {
        let started = Date()
        let app = launch(lang, api: "http://127.0.0.1:\(port)/", offline: false, extra: ["-fitrah-tab", "home"])
        XCTAssertTrue(shell(app, lang).waitForExistence(timeout: 12), "\(lang.code): still no shell 12 s into a hanging /me\n\(app.debugDescription)")
        let elapsed = Date().timeIntervalSince(started)
        XCTAssertFalse(app.buttons["signIn.submit"].exists)
        shot("2-hanging-me-shell", lang)
        note(String(format: "leg2 %@: shell %.1f s after launch() began, /me still unanswered (hang port %@)", lang.code, elapsed, port))
        XCTAssertLessThan(elapsed, 8, "\(lang.code): the launch waited \(elapsed) s on a hanging /me")
        app.terminate()
    }

    /// Requirement 4: back online, the account and the library are both still there.
    private func backOnlineKeepsAccountAndLibrary() throws {
        let app = launch(Self.en, api: Self.liveAPI, extra: ["-fitrah-tab", "me"])
        XCTAssertTrue(shell(app, Self.en).waitForExistence(timeout: 30), "back online: no shell\n\(app.debugDescription)")
        let savedLink = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", Self.en.saved)).firstMatch
        XCTAssertTrue(savedLink.waitForExistence(timeout: 10))
        savedLink.tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label == %@", Self.en.open)).firstMatch.waitForExistence(timeout: 10),
                      "back online: the saved row is gone\n\(app.debugDescription)")
        shot("4-back-online-saved", Self.en)
        note("leg4 back online: shell + saved row")
        app.terminate()
    }

    private func signOutOnline() throws {
        let app = launch(Self.en, api: Self.liveAPI, extra: ["-fitrah-route", "settings"])
        let signOut = app.buttons["settings.signOut"]
        XCTAssertTrue(signOut.waitForExistence(timeout: 30), "no Sign out in Settings\n\(app.debugDescription)")
        signOut.tap()
        let confirm = app.alerts.firstMatch.buttons["Sign out"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 10), "no sign-out confirmation\n\(app.debugDescription)")
        confirm.tap()
        XCTAssertTrue(app.buttons["signIn.submit"].waitForExistence(timeout: 20), "sign-out never reached the wall")
        note("leg3 signed out online")
        app.terminate()
    }

    /// Requirement 3: signed out + offline: the wall, and a sign-in attempt says "No internet
    /// connection". A throwaway password: Firebase is unreachable, so it is never checked.
    private func signedOutOfflineShowsTheWallAndSaysNoInternet(_ lang: Lang, email: String) throws {
        let app = launch(lang, api: Self.refused, offline: true)
        let submit = app.buttons["signIn.submit"]
        XCTAssertTrue(submit.waitForExistence(timeout: 10), "\(lang.code): no wall when signed out offline\n\(app.debugDescription)")
        XCTAssertFalse(shell(app, lang).exists)
        app.textFields.firstMatch.tap()
        app.textFields.firstMatch.typeText(email)
        app.secureTextFields.firstMatch.tap()
        app.secureTextFields.firstMatch.typeText("offline-probe-only")
        submit.tap()
        let banner = app.staticTexts["transientBanner.text"]
        XCTAssertTrue(banner.waitForExistence(timeout: 15), "\(lang.code): no banner after an offline sign-in attempt")
        XCTAssertEqual(banner.label, lang.noInternet)
        shot("3-signed-out-offline-wall", lang)
        note("leg3 \(lang.code): wall, banner '\(banner.label)'")
        app.terminate()
    }

    // MARK: - Helpers

    private func launch(_ lang: Lang, api: String, offline: Bool = false, extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        // The debug allowance on EVERY launch: the launch sweep asks the per-video gate about the
        // saved row, and an online launch without it would ask the real backend about a lecture
        // whose `offlineAllowed` this check does not control. Offline it changes nothing (an
        // unanswered gate keeps the row too).
        var arguments = ["-onboarding_completed", "YES", "-theme", "light",
                         "-AppleLanguages", "(\(lang.code))", "-AppleLocale", lang.locale,
                         "-fitrah-api-base-url", api, "-fitrah-route-probe", "YES",
                         "-fitrah-debug-offline-allowed", Self.lectureId]
        if api != Self.liveAPI {
            arguments += ["-fitrah-firebase-unreachable", "-fitrah-remote-config-url", Self.refused + "rc.json"]
        }
        if offline { arguments.append("-fitrah-offline") }
        app.launchArguments = arguments + extra
        app.launch()
        return app
    }

    /// The shell's Home destination: the bottom tab bar's on a phone, the leading rail's on an iPad
    /// (`NavigationRailView`, which XCUITest does not report as a tab bar). Never on the wall.
    private func shell(_ app: XCUIApplication, _ lang: Lang) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label == %@", lang.home)).firstMatch
    }

    private func playerTime(_ probe: XCUIElement) -> Int {
        let value = probe.value as? String ?? ""
        guard let field = value.split(separator: ";").first(where: { $0.hasPrefix("time=") }) else { return -1 }
        return Int(field.dropFirst(5)) ?? -1
    }

    private func shot(_ name: String, _ lang: Lang) {
        let file = shotsDirectory.appendingPathComponent("ios-fix-\(device)-\(lang.code)-\(name).png")
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: file)
    }

    private func note(_ line: String) {
        notes.append("\(ISO8601DateFormatter().string(from: Date())) \(line)")
        print("OFFLINECHECK \(line)")
    }
}
