import XCTest

/// A video opened while another plays must leave exactly ONE player playing (double-audio report,
/// 2026-11-03). Live catalog lectures through the real extraction on the fixture container with the
/// DEBUG fake account, like `DeviceChecks`; the second open is a real `.onOpenURL` (`app.open`).
/// `player.debugRoute`'s `playing=` counts every player in the process with rate > 0. Opt-in:
///
///     TEST_RUNNER_FITRAH_PLAYER_CHECKS=1 xcodebuild test -project FitrahTube.xcodeproj -scheme FitrahTube \
///       -testPlan FitrahTubeUITests -only-testing:FitrahTubeUITests/SinglePlayerChecks \
///       -destination 'platform=iOS Simulator,name=iPhone 17' -derivedDataPath DerivedData
final class SinglePlayerChecks: XCTestCase {
    private static let first = "86BEvUxr1LE"
    private static let second = "nhaGO__rxHQ"   // "Tafsir Surah al-Maun"

    override func setUpWithError() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["FITRAH_PLAYER_CHECKS"] == "1",
                          "live-network check, opt-in: FITRAH_PLAYER_CHECKS=1")
        continueAfterFailure = false
    }

    /// What happens between the first video playing and the second link.
    private enum Between { case nothing, background, otherTab }

    /// Both opens are real deep links: no debug route anywhere.
    func testDeepLinkWhilePlayingLeavesOnePlayerPlaying() throws {
        try deepLinkLeavesOnePlayerPlaying(firstViaDebugRoute: false, between: .nothing)
    }

    /// The share link tapped in another app while ours plays on in the background.
    func testDeepLinkFromBackgroundLeavesOnePlayerPlaying() throws {
        try deepLinkLeavesOnePlayerPlaying(firstViaDebugRoute: false, between: .background)
    }

    /// The lecture plays on while the user browses another tab; the link pushes onto THAT tab, so the
    /// first player screen stays mounted (and playing) in its own.
    func testDeepLinkFromAnotherTabLeavesOnePlayerPlaying() throws {
        try deepLinkLeavesOnePlayerPlaying(firstViaDebugRoute: false, between: .otherTab)
    }

    /// The reported sequence: `-fitrah-route player`, then a deep link.
    func testDeepLinkAfterDebugRouteLeavesOnePlayerPlaying() throws {
        try deepLinkLeavesOnePlayerPlaying(firstViaDebugRoute: true, between: .nothing)
    }

    private func deepLinkLeavesOnePlayerPlaying(firstViaDebugRoute: Bool, between: Between) throws {
        let app = XCUIApplication()
        app.launchArguments = ["-fitrah-fake-container", "-fitrah-fake-auth", "active", "-theme", "light",
                               "-onboarding_completed", "YES", "-AppleLanguages", "(en)", "-AppleLocale", "en_US",
                               "-fitrah-api-base-url", "https://app.fitrahtube.com/", "-fitrah-route-probe", "YES"]
            + (firstViaDebugRoute ? ["-fitrah-route", "player", Self.first] : [])
        app.launch()
        if !firstViaDebugRoute {
            // `Router.open` holds a link that lands before the shell and pushes it once the shell is up.
            app.open(URL(string: "albunyaantube://video/\(Self.first)")!)
        }
        XCTAssertTrue(waitPlaying(app, video: Self.first), "first lecture never played\n\(probes(app))")

        print("SINGLEPLAYER before second open: " + probes(app).joined(separator: " | "))
        switch between {
        case .nothing: break
        case .background:
            XCUIDevice.shared.press(.home)
            Thread.sleep(forTimeInterval: 3)
        case .otherTab:
            let channels = app.buttons["Channels"].firstMatch   // the tab bar and the iPad rail alike
            XCTAssertTrue(channels.waitForExistence(timeout: 10), "no Channels tab\n\(app.debugDescription)")
            channels.tap()
            Thread.sleep(forTimeInterval: 3)
        }
        app.open(URL(string: "albunyaantube://video/\(Self.second)")!)
        // PRESENT first: the second lecture really plays, so a zero below cannot pass vacuously.
        XCTAssertTrue(waitPlaying(app, video: Self.second), "second lecture never played\n\(probes(app))")

        var samples: [String] = []
        for _ in 0..<10 {
            samples.append(probes(app).joined(separator: " | "))
            Thread.sleep(forTimeInterval: 1)
        }
        print("SINGLEPLAYER between=\(between) samples:\n" + samples.joined(separator: "\n"))
        XCTAssertTrue(samples.allSatisfy { Self.field("playing", in: $0) == 1 },
                      "not exactly one player playing:\n" + samples.joined(separator: "\n"))
        app.terminate()
    }

    private static func field(_ name: String, in probe: String) -> Int? {
        probe.components(separatedBy: CharacterSet(charactersIn: ";|"))
            .first { $0.trimmingCharacters(in: .whitespaces).hasPrefix(name + "=") }
            .flatMap { Int($0.trimmingCharacters(in: .whitespaces).dropFirst(name.count + 1)) }
    }

    private func probes(_ app: XCUIApplication) -> [String] {
        app.descendants(matching: .any).matching(identifier: "player.debugRoute").allElementsBoundByIndex
            .compactMap { $0.value as? String }
    }

    /// The probe for `video` reports a clock of at least 3 s that is still moving.
    private func waitPlaying(_ app: XCUIApplication, video: String, timeout: TimeInterval = 60) -> Bool {
        func time() -> Int? {
            probes(app).first { $0.hasPrefix("video=\(video);") }.flatMap { Self.field("time", in: $0) }
        }
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let t0 = time(), t0 >= 3 {
                Thread.sleep(forTimeInterval: 2)
                if let t1 = time(), t1 > t0 { return true }
            }
            Thread.sleep(forTimeInterval: 1)
        }
        return false
    }
}
