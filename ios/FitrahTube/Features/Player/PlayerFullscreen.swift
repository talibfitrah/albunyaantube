import Foundation
import SwiftUI

/// Ruling 42 / spec §10, as one pure function (B5 Task 3). iPhone auto-enters fullscreen when the
/// device and the video agree on orientation; iPad never auto-enters (AVKit's stock button is its
/// only fullscreen); a deliberate exit suppresses the auto-enter until the device rotates out of
/// the fullscreen orientation (reconciliation note 3 -- no orientation is ever forced).
///
/// | width   | deviceIsLandscape | videoIsPortrait | active |
/// |---------|-------------------|-----------------|--------|
/// | compact | true              | false           | yes    |
/// | compact | false             | true            | yes    |
/// | compact | true              | true            | no     |
/// | compact | false             | false           | no     |
/// | regular / large             | any             | no     |
nonisolated enum PlayerFullscreen {
    static func isActive(widthClass: WidthClass, deviceIsLandscape: Bool,
                         videoIsPortrait: Bool, userExited: Bool) -> Bool {
        widthClass == .compact && !userExited && (deviceIsLandscape != videoIsPortrait)
    }
}

/// The double-tap overlay's decisions (`PlayerGestureDetector.kt:43-79`), pure so the UIKit
/// recognizer in `PlayerHostView` is only glue.
nonisolated enum PlayerGestures {
    enum Zone: Equatable { case back, centre, forward }

    struct SeekFeedback: Equatable {
        let zone: Zone
        let seconds: TimeInterval
    }

    /// Thirds of the PASSED width (never `UIScreen`: split-screen / multi-window safe).
    /// `layoutDirection` is accepted only to document that it is deliberately ignored: the zones
    /// are spatial. AVKit mirrors its own scrubber under RTL, so the left third of the view is
    /// still the earlier part of the timeline the user is looking at.
    static func zone(x: CGFloat, width: CGFloat, layoutDirection: LayoutDirection = .leftToRight) -> Zone {
        if x <= width / 3 { return .back }
        if x <= width * 2 / 3 { return .centre }
        return .forward
    }

    /// Floor 0, cap `duration`; nil for the centre zone (not a seek) and for an unknown duration.
    static func seek(from position: TimeInterval, zone: Zone, duration: TimeInterval,
                     step: TimeInterval) -> TimeInterval? {
        guard duration > 0 else { return nil }
        switch zone {
        case .back: return max(0, position - step)
        case .forward: return min(duration, position + step)
        case .centre: return nil
        }
    }
}
