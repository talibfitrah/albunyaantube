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
}
