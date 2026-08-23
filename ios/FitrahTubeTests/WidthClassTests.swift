import Foundation
import SwiftUI
import Testing
@testable import FitrahTube

@Suite(.perTest)
struct WidthClassTests {
    @Test func phoneWidthsAreCompact() {
        #expect(WidthClass(width: 390) == .compact)
        #expect(WidthClass(width: 599) == .compact)
    }

    @Test func ipadPortraitIsRegular() {
        #expect(WidthClass(width: 600) == .regular)
        #expect(WidthClass(width: 834) == .regular)
        #expect(WidthClass(width: 999) == .regular)
    }

    @Test func largeIpadIsLarge() {
        #expect(WidthClass(width: 1000) == .large)
        #expect(WidthClass(width: 1376) == .large)
    }

    @Test func spacingScalesWithWidthClass() {
        #expect(Spacing.md(.compact) == 16)
        #expect(Spacing.md(.regular) == 20)
        #expect(Spacing.md(.large) == 24)
        #expect(Spacing.xxxl(.large) == 128)
    }

    /// task-14 R-D. Split View / Stage Manager cannot be driven from this sandbox, so the proof is
    /// structural: `RootView` derives the width class from *its own container's* geometry
    /// (`.onGeometryChange(for: WidthClass.self) { WidthClass(size: $0.size) }`, `RootView.swift`),
    /// and nothing in the app reads `UIScreen` at all. A 1/3-width Split View window on a 13" iPad
    /// is therefore measured as ~344 pt and takes the exact same `.compact` path a 390 pt iPhone
    /// does -- bottom tab bar, single-column lists.
    @Test func ipadSplitViewThirdWidthRendersThePhoneLayout() {
        let thirdOfIPadPro13 = CGSize(width: 1032 / 3, height: 1376)
        #expect(WidthClass(size: thirdOfIPadPro13) == .compact)
        #expect(ShellLayout(WidthClass(size: thirdOfIPadPro13)) == .bottomBar)
        #expect(GridRules.listColumns(WidthClass(size: thirdOfIPadPro13)) == 1)

        // Half-width (~516 pt) is still below the 600 pt sw600dp threshold; two-thirds (~688 pt)
        // clears it and gets the rail, same as a full-screen iPad mini.
        #expect(WidthClass(size: CGSize(width: 1032 / 2, height: 1376)) == .compact)
        #expect(WidthClass(size: CGSize(width: 1032 * 2 / 3, height: 1376)) == .regular)
    }

    @Test func bucketsOnSmallestWidthNotRotation() {
        // Landscape iPhone: width 956 > 600 but height 440 is the smaller dimension -- still compact.
        #expect(WidthClass(size: CGSize(width: 956, height: 440)) == .compact)
        // Landscape large iPad: smaller dimension 1032 still clears the 1000 large threshold.
        #expect(WidthClass(size: CGSize(width: 1376, height: 1032)) == .large)
    }
}
