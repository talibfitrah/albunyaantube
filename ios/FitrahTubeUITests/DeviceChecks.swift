import XCTest

/// Real-device checks (background download, Chromecast, AirPlay) on a physical iPhone: the fixture
/// container with the DEBUG fake account (no credentials) serving the LIVE catalog (`-fitrah-api-base-url`, the
/// `testAppStoreScreenshots` configuration). Player, InnerTube extraction and the Cast SDK are the
/// real ones there; the offline engine is real only with `-fitrah-debug-offline-allowed` (check 1).
/// Opt-in, so the simulator gate never runs them:
///
///     TEST_RUNNER_FITRAH_DEVICE_CHECKS=1 xcodebuild test -project FitrahTube.xcodeproj -scheme FitrahTube \
///       -testPlan FitrahTubeUITests -only-testing:FitrahTubeUITests/DeviceChecks/testChromecast \
///       -destination 'id=<UDID>' -allowProvisioningUpdates -derivedDataPath DerivedData-Signed
///
/// Catalog lecture only: "Tafsir Surah al-Maun" (nhaGO__rxHQ), found by the "tafsir surah" search.
final class DeviceChecks: XCTestCase {
    private static let lectureTitle = "Tafsir Surah al-Maun"
    private static let lectureId = "nhaGO__rxHQ"
    private var environment: [String: String] { ProcessInfo.processInfo.environment }
    private var springboard: XCUIApplication { XCUIApplication(bundleIdentifier: "com.apple.springboard") }

    override func setUpWithError() throws {
        try XCTSkipUnless(environment["FITRAH_DEVICE_CHECKS"] == "1", "Real-device checks are opt-in: FITRAH_DEVICE_CHECKS=1")
        continueAfterFailure = false
        // Landscape on a phone is the fullscreen player, which hides the toolbar (Save, Cast).
        XCUIDevice.shared.orientation = .portrait
    }

    /// Background download: Save for offline on the ONE debug-allowed catalog lecture (the real
    /// engine and background `URLSession`), pause (mandatory: playing audio or PiP would keep the
    /// process alive), Home, and the app must be SUSPENDED while the save is still running, so only
    /// the background session can move the rest of the bytes. Stay out, come back: it reads Saved.
    /// The Mac lists the file's bytes while the app is away (`tmp/offline`: the fixture's rows are
    /// in memory, so its files go to the temporary directory).
    func testBackgroundDownload() throws {
        let app = openLecture(extraArguments: ["-fitrah-debug-offline-allowed", Self.lectureId])
        let progress = startSave(app)
        pausePlayback(app)
        XCTAssertTrue(progress.exists, "the save finished before Home: nothing left to prove in the background")
        XCUIDevice.shared.press(.home)
        let deadline = Date().addingTimeInterval(30)
        while Date() < deadline, !isAway(app) { Thread.sleep(forTimeInterval: 1) }
        XCTAssertTrue(isAway(app), "still state \(app.state.rawValue) 30 s after Home: the process was kept alive, so this is not a background transfer")
        print("DEVICECHECK BACKGROUNDED state=\(app.state.rawValue) at=\(Date())")
        Thread.sleep(forTimeInterval: Double(environment["FITRAH_BACKGROUND_SECONDS"] ?? "") ?? 300)
        print("DEVICECHECK RETURNING state=\(app.state.rawValue) at=\(Date())")
        app.activate()
        assertSaved(app)
        app.terminate()
    }

    /// The terminate-and-relaunch leg, the only one that exercises iOS relaunching the app for
    /// background-session events (`AppDelegate.application(_:handleEventsForBackgroundURLSession:
    /// completionHandler:)` -> `AppContainer.current.offlineManager.reattach()`). That relaunch
    /// carries no launch arguments, so it builds the LIVE container over the on-disk store: the save
    /// must start there too, which needs a real signed-in account. The Mac reads the store while
    /// the app is gone (`runcheck.sh`).
    func testBackgroundDownloadRelaunch() throws {
        guard let email = environment["FITRAH_LIVE_EMAIL"], !email.isEmpty,
              let password = environment["FITRAH_LIVE_PASSWORD"], !password.isEmpty else {
            throw XCTSkip("the relaunch leg runs on the LIVE container behind the real sign-in wall: set TEST_RUNNER_FITRAH_LIVE_EMAIL and TEST_RUNNER_FITRAH_LIVE_PASSWORD (an owner-supplied test account; never create one)")
        }
        try signInLive(email: email, password: password)
        let arguments = ["-fitrah-debug-offline-allowed", Self.lectureId]
        var app = openLecture(extraArguments: arguments, live: true)
        let progress = startSave(app)
        XCTAssertTrue(progress.exists, "the save finished before the terminate: nothing left for the relaunch path")
        app.terminate()
        XCTAssertEqual(app.state, .notRunning)
        print("DEVICECHECK TERMINATED at=\(Date())")
        Thread.sleep(forTimeInterval: Double(environment["FITRAH_BACKGROUND_SECONDS"] ?? "") ?? 600)
        print("DEVICECHECK RELAUNCHING at=\(Date())")
        app = openLecture(extraArguments: arguments, live: true)
        assertSaved(app)
        app.terminate()
    }

    /// Chromecast: in-app picker -> receiver -> 45 s hold (the Mac reads the receiver's media status
    /// twice via pychromecast) -> Stop Casting from the app. Under 2 minutes of TV time.
    func testChromecast() throws {
        let receiver = environment["FITRAH_CAST_DEVICE"] ?? "Family room TV 2"
        let app = openLecture()
        let cast = app.buttons["player.castButton"]
        XCTAssertTrue(cast.waitForExistence(timeout: 20), "no cast button: castAvailable is false")
        cast.tap()
        let device = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", receiver)).firstMatch
        XCTAssertTrue(waitAllowingLocalNetwork(for: device, timeout: 60), "\(receiver) never appeared in the picker\n\(app.debugDescription)")
        device.tap()
        XCTAssertTrue(wait(cast, valueIs: receiver, timeout: 45), "never connected: cast value \(String(describing: cast.value))")
        print("DEVICECHECK CAST-CONNECTED receiver=\(receiver) at=\(Date())")
        Thread.sleep(forTimeInterval: 45)
        cast.tap()
        let stop = app.buttons.matching(NSPredicate(format: "label ==[c] 'Stop Casting'")).firstMatch
        XCTAssertTrue(stop.waitForExistence(timeout: 15), "no Stop Casting in the dialog\n\(app.debugDescription)")
        stop.tap()
        XCTAssertTrue(wait(cast, valueIs: "", timeout: 30), "session never ended: cast value \(String(describing: cast.value))")
        print("DEVICECHECK CAST-ENDED at=\(Date())")
        app.terminate()
    }

    /// AirPlay: AVKit's own route button -> the TV -> the DEBUG `player.debugRoute` probe must show an
    /// `AirPlay:<tv>` output and `external=true`; then back to the iPhone and paused.
    func testAirPlay() throws {
        let tv = environment["FITRAH_AIRPLAY_TV"] ?? "TV-L65"
        let app = openLecture()
        let probe = app.descendants(matching: .any)["player.debugRoute"]
        print("DEVICECHECK ROUTE-BEFORE \(probe.value ?? "nil")")
        openRoutePicker(app)
        let target = pickerRow(app, containing: tv)
        XCTAssertTrue(target.waitForExistence(timeout: 30), "\(tv) not in the route picker\n\(app.debugDescription)\n\(springboard.debugDescription)")
        target.tap()
        let onTV = NSPredicate(format: "value CONTAINS %@ AND value CONTAINS 'external=true'", "AirPlay:\(tv)")
        let routed = expectation(for: onTV, evaluatedWith: probe)
        let result = XCTWaiter().wait(for: [routed], timeout: 45)
        print("DEVICECHECK ROUTE-ON-TV \(probe.value ?? "nil") at=\(Date())")
        if result != .completed { print(app.debugDescription); print(springboard.debugDescription) }
        XCTAssertEqual(result, .completed, "never routed to \(tv): \(probe.value ?? "nil")")
        Thread.sleep(forTimeInterval: 10)
        print("DEVICECHECK ROUTE-ON-TV+10s \(probe.value ?? "nil")")
        // Back to the phone, then pause.
        openRoutePicker(app)
        let phone = pickerRow(app, containing: "iPhone")
        XCTAssertTrue(phone.waitForExistence(timeout: 15), "no iPhone row\n\(app.debugDescription)")
        phone.tap()
        let local = NSPredicate(format: "value CONTAINS 'external=false' AND NOT (value CONTAINS 'AirPlay:')")
        XCTAssertEqual(XCTWaiter().wait(for: [expectation(for: local, evaluatedWith: probe)], timeout: 30), .completed,
                       "still external: \(probe.value ?? "nil")")
        print("DEVICECHECK ROUTE-BACK \(probe.value ?? "nil") at=\(Date())")
        dismissPickerIfShown(app)
        pausePlayback(app)
        app.terminate()
    }

    // MARK: - Helpers

    /// `live: false` is the fixture container with the fake account; `live: true` the LIVE container,
    /// which needs `signInLive` first (Firebase keeps the session in the keychain across launches).
    private func openLecture(extraArguments: [String] = [], live: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = (live ? [] : ["-fitrah-fake-container", "-fitrah-fake-auth", "active"])
            + ["-theme", "light", "-onboarding_completed", "YES", "-AppleLanguages", "(en)", "-AppleLocale", "en_US",
               "-fitrah-api-base-url", "https://app.fitrahtube.com/", "-fitrah-route-probe", "YES",
               "-fitrah-route", "search", "-fitrah-search-query", "tafsir surah"] + extraArguments
        app.launch()
        let row = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", Self.lectureTitle)).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 60), "lecture never appeared in search\n\(app.debugDescription)\n\(springboard.debugDescription)")
        if app.keyboards.firstMatch.exists { app.typeText("\n") }
        row.tap()
        // The probe lives in the player's playable branch only: it existing means a stream resolved.
        XCTAssertTrue(app.descendants(matching: .any)["player.debugRoute"].waitForExistence(timeout: 60),
                      "stream never resolved\n\(app.debugDescription)")
        Thread.sleep(forTimeInterval: 5)   // let playback actually start
        return app
    }

    /// `ScreenshotTests.signInLive`'s channel: a 0600 file the app reads and deletes
    /// (`-fitrah-live-signin-file`), never the form. A Firebase session already in the keychain
    /// needs no file. NOT YET SEEN ON A DEVICE: the file sits in the runner's own container, which
    /// iOS may not let the app read; then the wall shows its generic error and this fails saying so.
    private func signInLive(email: String, password: String) throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("live-signin-\(UUID().uuidString).json")
        let body = try JSONSerialization.data(withJSONObject: ["email": email, "password": password])
        FileManager.default.createFile(atPath: file.path, contents: body, attributes: [.posixPermissions: 0o600])
        defer { try? FileManager.default.removeItem(at: file) }
        let app = XCUIApplication()
        app.launchArguments = ["-onboarding_completed", "YES", "-AppleLanguages", "(en)", "-AppleLocale", "en_US",
                               "-fitrah-api-base-url", "https://app.fitrahtube.com/",
                               "-fitrah-live-signin-file", file.path, "-fitrah-tab", "home"]
        app.launch()
        let shell = app.tabBars.firstMatch, banner = app.staticTexts["transientBanner.text"]
        let deadline = Date().addingTimeInterval(60)
        while Date() < deadline, !shell.exists {
            if banner.exists, app.buttons["signIn.submit"].exists {
                XCTFail("live sign-in failed at the wall: \(banner.label). On a device the app may be unable to read the runner's file; sign in once by hand on the phone with the test account and re-run.")
                return
            }
            Thread.sleep(forTimeInterval: 0.2)
        }
        XCTAssertTrue(shell.exists, "live sign-in: the shell never appeared")
        app.terminate()
    }

    /// Save for offline -> the quality sheet's confirm -> the toolbar's progress slot leaves Waiting.
    private func startSave(_ app: XCUIApplication) -> XCUIElement {
        let save = app.buttons["player.saveButton"]
        XCTAssertTrue(save.waitForExistence(timeout: 30), "no Save button: the gate never affirmed\n\(app.debugDescription)")
        save.tap()
        let confirm = app.buttons.matching(NSPredicate(format: "label == 'Save for offline' AND identifier != 'player.saveButton'")).firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 10), "no confirm in the quality sheet\n\(app.debugDescription)")
        confirm.tap()
        let progress = app.descendants(matching: .any)["player.saveProgress"]
        XCTAssertTrue(progress.waitForExistence(timeout: 30), "the save never started\n\(app.debugDescription)")
        let running = expectation(for: NSPredicate(format: "value != 'Waiting'"), evaluatedWith: progress)
        XCTAssertEqual(XCTWaiter().wait(for: [running], timeout: 60), .completed, "still Waiting: \(progress.value ?? "nil")")
        print("DEVICECHECK DOWNLOAD-RUNNING value=\(progress.value ?? "nil") at=\(Date())")
        return progress
    }

    /// Mandatory: AVKit's Pause must be there, and its tap must turn it into Play.
    private func pausePlayback(_ app: XCUIApplication) {
        let pause = control(app, labeled: "Pause")
        XCTAssertTrue(pause.exists, "no Pause control: playback never started\n\(app.debugDescription)")
        pause.tap()
        XCTAssertTrue(control(app, labeled: "Play").exists, "Pause did not take: no Play control after the tap")
        print("DEVICECHECK PAUSED at=\(Date())")
    }

    private func assertSaved(_ app: XCUIApplication) {
        let saved = app.buttons.matching(NSPredicate(format: "identifier == 'player.saveButton' AND value == 'Saved'")).firstMatch
        let progress = app.descendants(matching: .any)["player.saveProgress"]
        XCTAssertTrue(saved.waitForExistence(timeout: 5), "not Saved: \(progress.exists ? String(describing: progress.value) : "no progress slot")\n\(app.debugDescription)")
        print("DEVICECHECK SAVED value=\(saved.value ?? "nil") at=\(Date())")
    }

    private func isAway(_ app: XCUIApplication) -> Bool {
        app.state == .runningBackgroundSuspended || app.state == .notRunning
    }

    /// An AVKit transport button, revealing the auto-hidden controls once if it is not on screen.
    private func control(_ app: XCUIApplication, labeled label: String) -> XCUIElement {
        let button = app.buttons.matching(NSPredicate(format: "label ==[c] %@", label)).firstMatch
        if !(button.waitForExistence(timeout: 3) && button.isHittable) {
            showControls(app)
            _ = button.waitForExistence(timeout: 3)
        }
        return button
    }

    /// First cast-button tap starts discovery, which raises iOS's local-network prompt once.
    private func waitAllowingLocalNetwork(for element: XCUIElement, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if element.exists { return true }
            let alert = springboard.alerts.firstMatch
            if alert.exists {
                let allow = alert.buttons.matching(NSPredicate(format: "label IN %@", ["Allow", "Sta toe", "Toestaan", "OK"])).firstMatch
                print("DEVICECHECK springboard alert: \(alert.label) -> tapping \(allow.exists ? allow.label : "button 1")")
                (allow.exists ? allow : alert.buttons.element(boundBy: 1)).tap()
            }
            Thread.sleep(forTimeInterval: 1)
        }
        return element.exists
    }

    private func wait(_ element: XCUIElement, valueIs value: String, timeout: TimeInterval) -> Bool {
        let predicate = NSPredicate(format: "value == %@", value)
        return XCTWaiter().wait(for: [expectation(for: predicate, evaluatedWith: element)], timeout: timeout) == .completed
    }

    private func showControls(_ app: XCUIApplication) {
        // Upper middle: the centre is AVKit's own play/pause once the controls are showing.
        app.descendants(matching: .any)["player.videoBox"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3)).tap()
    }

    /// AVKit's stock AirPlay button in the player's transport controls.
    private func openRoutePicker(_ app: XCUIApplication) {
        showControls(app)
        let route = app.buttons.matching(NSPredicate(format: "label ==[c] 'AirPlay' OR identifier CONTAINS[c] 'route' OR label CONTAINS[c] 'route'")).firstMatch
        if !route.waitForExistence(timeout: 5) {
            showControls(app)
            _ = route.waitForExistence(timeout: 5)
        }
        XCTAssertTrue(route.exists, "no AirPlay button in the player controls\n\(app.debugDescription)")
        route.tap()
    }

    /// The system route picker may render in-process or out of process: look in both trees.
    private func pickerRow(_ app: XCUIApplication, containing name: String) -> XCUIElement {
        for tree in [app, springboard] {
            for query in [tree.buttons, tree.cells, tree.staticTexts] {
                let match = query.matching(NSPredicate(format: "label CONTAINS %@", name)).firstMatch
                if match.waitForExistence(timeout: 3) { return match }
            }
        }
        return app.buttons.matching(NSPredicate(format: "label CONTAINS %@", name)).firstMatch
    }

    private func dismissPickerIfShown(_ app: XCUIApplication) {
        let done = app.buttons.matching(NSPredicate(format: "label IN %@", ["Done", "Close", "Gereed", "Sluit"])).firstMatch
        if done.exists { done.tap() }
    }
}
