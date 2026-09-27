import SwiftUI
import Testing
@testable import FitrahTube

/// `ShellLayout` is the pure rail-vs-bottom-bar decision `MainShellView` branches on (task-7b);
/// `NavigationRailMetrics` mirrors Android's `layout-sw600dp/`/`layout-sw720dp/` rail dimens.
@Suite(.perTest)
struct ShellLayoutTests {
    @Test func compactWidthUsesBottomBar() {
        #expect(ShellLayout(.compact) == .bottomBar)
    }

    @Test func regularWidthUsesRail() {
        #expect(ShellLayout(.regular) == .rail)
    }

    @Test func largeWidthUsesRail() {
        #expect(ShellLayout(.large) == .rail)
    }

    @Test func railWidthMatchesAndroidDimens() {
        // navigation_rail_width: values-sw600dp 80dp, values-sw720dp 96dp.
        #expect(NavigationRailMetrics.width(.regular) == 80)
        #expect(NavigationRailMetrics.width(.large) == 96)
    }

    @Test func railIconSizeMatchesAndroidDimens() {
        // navigation_rail_icon_size: values-sw600dp 28dp, values-sw720dp 32dp.
        #expect(NavigationRailMetrics.iconSize(.regular) == 28)
        #expect(NavigationRailMetrics.iconSize(.large) == 32)
    }

    @Test func railLabelFontMatchesAndroidDimensNearestDynamicTypeStyle() {
        // navigation_rail_label_size: default Material rail ~12sp (sw600, unset) -> .caption (12pt);
        // values-sw720dp 14sp -> nearest built-in style .footnote (no exact-14pt system style exists).
        #expect(NavigationRailMetrics.label(.regular) == Font.system(.caption))
        #expect(NavigationRailMetrics.label(.large) == Font.system(.footnote))
    }
}
