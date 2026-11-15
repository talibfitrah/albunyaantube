import XCTest

/// Up next's Retry footer (owner 2026-11-15), on the fixture channel whose every next page fails
/// (`-fitrah-fake-browse-fail-append`): a video opened from its Videos tab queues the tab's rows, its next page fails,
/// and the footer offers Retry. No network. `FITRAH_SHOT_DIR` (optional) saves a screenshot of the footer. Opt-in:
///
///     TEST_RUNNER_FITRAH_UPNEXT_CHECKS=1 xcodebuild test -project FitrahTube.xcodeproj -scheme FitrahTube \
///       -testPlan FitrahTubeUITests -only-testing:FitrahTubeUITests/UpNextFooterChecks \
///       -destination 'platform=iOS Simulator,name=iPhone 17' -derivedDataPath DerivedData
final class UpNextFooterChecks: XCTestCase {
    override func setUpWithError() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["FITRAH_UPNEXT_CHECKS"] == "1",
                          "opt-in: FITRAH_UPNEXT_CHECKS=1")
        continueAfterFailure = false
    }

    func testAFailedUpNextPageOffersRetry() { failedPageOffersRetry(language: "en", locale: "en_US") }

    func testAFailedUpNextPageOffersRetryInArabic() { failedPageOffersRetry(language: "ar", locale: "ar") }

    /// The last loaded row: its page fails, nothing is upcoming, and Retry is still there (the only way to page again).
    func testAFailedPageAfterTheLastVideoOffersRetry() {
        let app = launch(language: "en", locale: "en_US")
        let row = app.descendants(matching: .any)["channel.videos.row.video-0-5"]
        XCTAssertTrue(row.waitForExistence(timeout: 30), "the channel never listed its last video")
        for _ in 0..<6 where !row.isHittable { app.swipeUp() }
        row.tap()
        let retry = app.buttons["listFooter.retry"]
        XCTAssertTrue(retry.waitForExistence(timeout: 20), "a failed page after the last video showed no Retry footer")
        XCTAssertTrue(app.descendants(matching: .any)["player.upNext.header"].exists, "Retry showed without its header")
        for _ in 0..<8 where !retry.isHittable { app.swipeUp() }
        XCTAssertTrue(retry.isHittable)
        if let dir = ProcessInfo.processInfo.environment["FITRAH_SHOT_DIR"] {
            let idiom = UIDevice.current.userInterfaceIdiom == .pad ? "ipad" : "iphone"
            try? XCUIScreen.main.screenshot().pngRepresentation
                .write(to: URL(fileURLWithPath: "\(dir)/upnext-last-\(idiom)-en.png"))
        }
    }

    private func launch(language: String, locale: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-fitrah-fake-container", "-fitrah-fake-auth", "active", "-onboarding_completed", "YES",
                               "-AppleLanguages", "(\(language))", "-AppleLocale", locale,
                               "-fitrah-fake-player", "-fitrah-fake-browse-pages", "3", "6",
                               "-fitrah-fake-browse-fail-append",
                               "-fitrah-route", "channel", "UCfixturechannel", "Fixture Channel"]
        app.launch()
        return app
    }

    private func failedPageOffersRetry(language: String, locale: String) {
        let app = launch(language: language, locale: locale)
        let row = app.descendants(matching: .any)["channel.videos.row.video-0-0"]
        XCTAssertTrue(row.waitForExistence(timeout: 30), "the channel never listed its first video")
        row.tap()
        XCTAssertTrue(app.descendants(matching: .any)["player.upNext.header"].waitForExistence(timeout: 20),
                      "the player never showed Up next")

        // A button VoiceOver reaches: in the accessibility tree, labelled, and hittable once scrolled to.
        let retry = app.buttons["listFooter.retry"]
        XCTAssertTrue(retry.waitForExistence(timeout: 20), "a failed page showed no Retry footer")
        for _ in 0..<8 where !retry.isHittable { app.swipeUp() }
        XCTAssertTrue(retry.isHittable)
        XCTAssertFalse(retry.label.isEmpty)
        app.swipeUp()                                                // the whole footer on screen, not its top edge
        if let dir = ProcessInfo.processInfo.environment["FITRAH_SHOT_DIR"] {
            let idiom = UIDevice.current.userInterfaceIdiom == .pad ? "ipad" : "iphone"
            try? XCUIScreen.main.screenshot().pngRepresentation
                .write(to: URL(fileURLWithPath: "\(dir)/upnext-footer-\(idiom)-\(language).png"))
        }

        retry.tap()                                                  // fails again on this fixture: Retry comes back
        XCTAssertTrue(retry.waitForExistence(timeout: 10), "the footer did not come back after a failed retry")
    }
}
