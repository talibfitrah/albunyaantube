import SwiftUI
import Testing
@testable import FitrahTube

/// B5 Task 3: the two pure halves of fullscreen + the gesture overlay (`PlayerFullscreen.swift`).
struct PlayerFullscreenTests {
    @Test func iPhoneAutoFullscreensWhenTheDeviceAndTheVideoAgreeOnOrientation() {
        // Ruling 42 + spec §10: "9:16 sources fullscreen in portrait, else landscape; auto-enter
        // when opened in landscape on iPhone". The rule is `deviceIsLandscape != videoIsPortrait`.
        func active(_ landscape: Bool, _ portraitVideo: Bool) -> Bool {
            PlayerFullscreen.isActive(widthClass: .compact, deviceIsLandscape: landscape,
                                      videoIsPortrait: portraitVideo, userExited: false)
        }
        #expect(active(true, false))            // landscape device, landscape video -> yes
        #expect(active(false, true))            // portrait device,  9:16 video      -> yes
        #expect(active(true, true) == false)    // landscape device, 9:16 video      -> no
        #expect(active(false, false) == false)  // portrait device,  16:9 video      -> no
    }

    @Test func iPadNeverAutoFullscreens() {
        // Ruling 42: "iPad by button only". The only iPad fullscreen is AVKit's own stock button,
        // which costs this app nothing -- so the pure rule must simply never fire on a regular width.
        for landscape in [true, false] {
            #expect(PlayerFullscreen.isActive(widthClass: .regular, deviceIsLandscape: landscape,
                                              videoIsPortrait: false, userExited: false) == false)
            #expect(PlayerFullscreen.isActive(widthClass: .large, deviceIsLandscape: landscape,
                                              videoIsPortrait: false, userExited: false) == false)
        }
    }

    @Test func aDeliberateExitSuppressesTheAutoEnter() {
        // `PlayerFragment.kt:719-731` (`userDismissedFullscreen`, consumed after exactly one
        // suppressed auto-enter). Reconciliation note 3: the latch is cleared by rotating back,
        // which is one `.onChange`, not Android's two orientation-unlock timers.
        #expect(PlayerFullscreen.isActive(widthClass: .compact, deviceIsLandscape: true,
                                          videoIsPortrait: false, userExited: true) == false)
    }

    @Test func gestureZonesAreThirdsOfTheActualViewWidthNotTheScreen() {
        // `PlayerGestureDetector.kt:43-47`: split-screen / multi-window safe.
        #expect(PlayerGestures.zone(x: 10, width: 300) == .back)
        #expect(PlayerGestures.zone(x: 150, width: 300) == .centre)
        #expect(PlayerGestures.zone(x: 290, width: 300) == .forward)
        #expect(PlayerGestures.zone(x: 100, width: 300) == .back)     // boundary: < width/3
        #expect(PlayerGestures.zone(x: 200, width: 300) == .centre)   // boundary: <= 2*width/3
    }

    @Test func gestureZonesDoNotMirrorUnderRTL() {
        // Global Constraints: the zones are SPATIAL. AVKit mirrors its own scrubber under RTL, so
        // the leading third of the screen is still the earlier part of the timeline. Flipping the
        // zones would make the gesture disagree with the scrubber the user is looking at.
        #expect(PlayerGestures.zone(x: 10, width: 300, layoutDirection: .rightToLeft) == .back)
    }

    @Test func seekClampsAtZeroAndAtTheDuration() {
        // `PlayerGestureDetector.kt:66-79`: floor 0, cap duration, no-op when duration is unknown.
        #expect(PlayerGestures.seek(from: 3, zone: .back, duration: 100, step: 10) == 0)
        #expect(PlayerGestures.seek(from: 40, zone: .back, duration: 100, step: 10) == 30)
        #expect(PlayerGestures.seek(from: 95, zone: .forward, duration: 100, step: 10) == 100)
        #expect(PlayerGestures.seek(from: 40, zone: .forward, duration: 0, step: 10) == nil)
        #expect(PlayerGestures.seek(from: 40, zone: .centre, duration: 100, step: 10) == nil)
    }
}
