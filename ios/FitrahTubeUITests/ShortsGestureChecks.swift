import XCTest

/// The Shorts screen's gestures and the edge swipe Back on any pushed screen, driven by XCTest's own touch synthesis:
/// axe's synthetic touches never begin UIKit's interactive pop on the iOS 26 simulator, not even on a screen with its
/// navigation bar showing (round 6, 2026-11-14), so the edge swipe can only be checked here. iPhone and iPad (whose rail
/// sits on that edge), English and Arabic. Fixture container, player and browse pages: no network. Opt-in:
///
///     TEST_RUNNER_FITRAH_SHORTS_CHECKS=1 xcodebuild test -project FitrahTube.xcodeproj -scheme FitrahTube \
///       -testPlan FitrahTubeUITests -only-testing:FitrahTubeUITests/ShortsGestureChecks \
///       -destination 'platform=iOS Simulator,name=iPhone 17' -derivedDataPath DerivedData
final class ShortsGestureChecks: XCTestCase {
    override func setUpWithError() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["FITRAH_SHORTS_CHECKS"] == "1",
                          "opt-in: FITRAH_SHORTS_CHECKS=1")
        continueAfterFailure = false
    }

    func testEdgeSwipePopsTheShortsScreen() {
        let app = openShort(language: "en", locale: "en_US")
        edgeSwipe(app, rtl: false)
        XCTAssertTrue(app.buttons["shorts.back"].waitForNonExistence(timeout: 5), "the edge swipe did not pop the Short")
        XCTAssertTrue(app.descendants(matching: .any)["channel.tab.shorts"].waitForExistence(timeout: 5))
    }

    /// The pop edge is the leading one, so the right in Arabic: UIKit mirrors the navigation stack.
    func testEdgeSwipePopsTheShortsScreenInArabic() {
        let app = openShort(language: "ar", locale: "ar")
        edgeSwipe(app, rtl: true)
        XCTAssertTrue(app.buttons["shorts.back"].waitForNonExistence(timeout: 5), "the edge swipe did not pop the Short")
        XCTAssertTrue(app.descendants(matching: .any)["channel.tab.shorts"].waitForExistence(timeout: 5))
    }

    /// Any pushed screen, its navigation bar showing: the channel screen, pushed on Home.
    func testEdgeSwipePopsAPushedScreen() { edgeSwipePopsTheChannel(language: "en", locale: "en_US") }

    func testEdgeSwipePopsAPushedScreenInArabic() { edgeSwipePopsTheChannel(language: "ar", locale: "ar") }

    /// iPad: the rail lies over the stacks now (the edge strip is the edge swipe's), and its items still switch tabs.
    func testTheRailStillSwitchesTabs() throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad, "the rail is the iPad's")
        let app = XCUIApplication()
        app.launchArguments = ["-fitrah-fake-container", "-fitrah-fake-auth", "active", "-onboarding_completed", "YES",
                               "-AppleLanguages", "(en)", "-AppleLocale", "en_US", "-fitrah-fake-browse-pages", "3", "6"]
        app.launch()
        let channels = app.buttons["Channels"]
        XCTAssertTrue(channels.waitForExistence(timeout: 30))
        XCTAssertFalse(channels.isSelected)
        channels.tap()
        XCTAssertTrue(channels.waitFor(selected: true), "the rail's Channels item did not take the tap")
    }

    /// iPad: what the shell itself shows outside the tabs' stacks (the cast strip, the offline banner, the transient
    /// banner) stays clear of the rail that lies over them, on the right in Arabic. `FITRAH_SHOT_DIR` saves a screenshot.
    func testTheShellsOwnViewsClearTheRail() throws { try shellViewsClearTheRail(language: "en", locale: "en_US") }

    func testTheShellsOwnViewsClearTheRailInArabic() throws { try shellViewsClearTheRail(language: "ar", locale: "ar") }

    func testAVerticalDragSlidesToTheNextShort() {
        let app = openShort(language: "en", locale: "en_US")
        let title = app.staticTexts["shorts.title"]
        XCTAssertEqual(title.label, "Shorts 1")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.7))
            .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.25)))
        XCTAssertTrue(title.waitFor(label: "Shorts 2"), "the slide did not move on: \(title.label)")
    }

    /// The thumb stays where it is dropped (round 6: a keyframe-tolerant seek moved it to the landed keyframe, 0 on this
    /// one-keyframe fixture). Paused first, so playback does not move it; dragged from the thumb itself, as a slider
    /// takes no drag that starts on its track.
    func testTheScrubberThumbStaysWhereItIsDropped() {
        let app = openShort(language: "en", locale: "en_US")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.4)).tap()
        let slider = app.sliders["shorts.scrubber"]
        let frame = slider.frame
        let thumb = app.coordinate(withNormalizedOffset: .zero)
            .withOffset(CGVector(dx: frame.minX + 16 + slider.normalizedSliderPosition * (frame.width - 32), dy: frame.midY))
        thumb.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: .zero)
            .withOffset(CGVector(dx: frame.minX + 16 + 0.8 * (frame.width - 32), dy: frame.midY)))
        sleep(2)   // the seek lands, then writes the landed time back
        XCTAssertEqual(slider.normalizedSliderPosition, 0.8, accuracy: 0.1)
    }

    /// From the screen's very edge: the system flags only a touch that starts there as an edge touch (one 4 pt in does
    /// not begin the pop, measured 2026-11-14). On iPad that edge is the rail's.
    private func edgeSwipe(_ app: XCUIApplication, rtl: Bool) {
        app.coordinate(withNormalizedOffset: CGVector(dx: rtl ? 0.995 : 0.005, dy: 0.5))
            .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: rtl ? 0.1 : 0.9, dy: 0.5)))
    }

    private func edgeSwipePopsTheChannel(language: String, locale: String) {
        let app = openChannel(language: language, locale: locale)
        let shortsTab = app.descendants(matching: .any)["channel.tab.shorts"]
        edgeSwipe(app, rtl: language == "ar")
        XCTAssertTrue(shortsTab.waitForNonExistence(timeout: 5), "the edge swipe did not pop the channel screen")
    }

    private func openChannel(language: String, locale: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-fitrah-fake-container", "-fitrah-fake-auth", "active", "-onboarding_completed", "YES",
                               "-AppleLanguages", "(\(language))", "-AppleLocale", locale,
                               "-fitrah-fake-player", "-fitrah-fake-browse-pages", "3", "6",
                               "-fitrah-route", "channel", "UCfixturechannel", "Fixture Channel"]
        app.launch()
        XCTAssertTrue(app.descendants(matching: .any)["channel.tab.shorts"].waitForExistence(timeout: 30),
                      "the channel never showed its Shorts tab")
        return app
    }

    private func shellViewsClearTheRail(language: String, locale: String) throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad, "the rail is the iPad's")
        let app = XCUIApplication()
        app.launchArguments = ["-fitrah-fake-container", "-fitrah-fake-auth", "active", "-onboarding_completed", "YES",
                               "-AppleLanguages", "(\(language))", "-AppleLocale", locale, "-fitrah-fake-browse-pages", "3", "6",
                               "-fitrah-fake-cast", "-fitrah-offline", "-fitrah-banner", "Fixture banner"]
        app.launch()
        let rail = app.descendants(matching: .any)["navigationRail"]
        XCTAssertTrue(rail.waitForExistence(timeout: 30))
        let rtl = language == "ar"
        continueAfterFailure = true                                  // every view's verdict, not only the first
        func clear(_ element: XCUIElement) -> Bool {
            rtl ? element.frame.maxX <= rail.frame.minX + 0.5 : element.frame.minX >= rail.frame.maxX - 0.5
        }
        // The transient banner leaves after 2.5 s (`TransientBanner`), so it is read first, and only while it is up.
        let banner = app.staticTexts["transientBanner.text"].firstMatch
        if banner.exists { XCTAssertTrue(clear(banner), "the banner \(banner.frame) lies under the rail \(rail.frame)") }
        for id in ["cast.miniControls", "offlineBanner.text"] {
            let element = app.descendants(matching: .any)[id]
            XCTAssertTrue(element.waitForExistence(timeout: 10), "\(id) never showed")
            XCTAssertTrue(clear(element), "\(id) \(element.frame) lies under the rail \(rail.frame)")
        }
        if let dir = ProcessInfo.processInfo.environment["FITRAH_SHOT_DIR"] {
            try? XCUIScreen.main.screenshot().pngRepresentation
                .write(to: URL(fileURLWithPath: "\(dir)/shell-chrome-ipad-\(language).png"))
        }
    }

    /// The fixture channel's Shorts tab, its second Short open.
    private func openShort(language: String, locale: String) -> XCUIApplication {
        let app = openChannel(language: language, locale: locale)
        app.descendants(matching: .any)["channel.tab.shorts"].tap()
        let cell = app.descendants(matching: .any)["channel.shorts.cell.shorts-0-1"]
        XCTAssertTrue(cell.waitForExistence(timeout: 20), "the Shorts tab never listed its second Short")
        cell.tap()
        XCTAssertTrue(app.staticTexts["shorts.title"].waitForExistence(timeout: 20), "the Short never played")
        return app
    }
}

private extension XCUIElement {
    func waitFor(selected: Bool, timeout: TimeInterval = 10) -> Bool {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isSelected == %@", NSNumber(value: selected)),
                                                    object: self)
        return XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
    }

    func waitFor(label: String, timeout: TimeInterval = 10) -> Bool {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", label), object: self)
        return XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
    }
}
