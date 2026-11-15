import XCTest

/// The Shorts screen's gestures, driven by XCTest's own touch synthesis: axe's synthetic touches never begin UIKit's
/// interactive pop on the iOS 26 simulator, not even on a screen with its navigation bar showing (round 6, 2026-11-14),
/// so the edge swipe Back can only be checked here. Fixture container, player and browse pages: no network. Opt-in:
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

    func testEdgeSwipePopsTheShortsScreen() throws {
        try XCTSkipIf(UIDevice.current.userInterfaceIdiom == .pad, "iPad: the tab rail holds the leading edge")
        edgeSwipePops(language: "en", locale: "en_US")
    }

    /// The pop edge is the leading one, so the right in Arabic: UIKit mirrors the navigation stack.
    func testEdgeSwipePopsTheShortsScreenInArabic() throws {
        try XCTSkipIf(UIDevice.current.userInterfaceIdiom == .pad, "iPad: the tab rail holds the leading edge")
        edgeSwipePops(language: "ar", locale: "ar")
    }

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
    /// not begin the pop, measured). iPad is skipped: its tab rail holds the leading screen edge, so no pushed screen in
    /// the app pops by edge swipe there, the channel screen with its navigation bar included (measured 2026-11-14).
    private func edgeSwipePops(language: String, locale: String) {
        let app = openShort(language: language, locale: locale)
        let rtl = language == "ar"
        app.coordinate(withNormalizedOffset: CGVector(dx: rtl ? 0.995 : 0.005, dy: 0.5))
            .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: rtl ? 0.1 : 0.9, dy: 0.5)))
        XCTAssertTrue(app.buttons["shorts.back"].waitForNonExistence(timeout: 5), "the edge swipe did not pop the Short")
        XCTAssertTrue(app.descendants(matching: .any)["channel.tab.shorts"].waitForExistence(timeout: 5))
    }

    /// The fixture channel's Shorts tab, its second Short open.
    private func openShort(language: String, locale: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-fitrah-fake-container", "-fitrah-fake-auth", "active", "-onboarding_completed", "YES",
                               "-AppleLanguages", "(\(language))", "-AppleLocale", locale,
                               "-fitrah-fake-player", "-fitrah-fake-browse-pages", "3", "6",
                               "-fitrah-route", "channel", "UCfixturechannel", "Fixture Channel"]
        app.launch()
        let tab = app.descendants(matching: .any)["channel.tab.shorts"]
        XCTAssertTrue(tab.waitForExistence(timeout: 30), "the channel never showed its Shorts tab")
        tab.tap()
        let cell = app.descendants(matching: .any)["channel.shorts.cell.shorts-0-1"]
        XCTAssertTrue(cell.waitForExistence(timeout: 20), "the Shorts tab never listed its second Short")
        cell.tap()
        XCTAssertTrue(app.staticTexts["shorts.title"].waitForExistence(timeout: 20), "the Short never played")
        return app
    }
}

private extension XCUIElement {
    func waitFor(label: String, timeout: TimeInterval = 10) -> Bool {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", label), object: self)
        return XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
    }
}
