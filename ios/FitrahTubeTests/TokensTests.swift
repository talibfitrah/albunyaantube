import Testing
import UIKit
@testable import FitrahTube

@Suite(.perTest)
struct TokensTests {
    @Test func argbWithAlphaByteYieldsFractionalAlpha() {
        var alpha: CGFloat = 0
        UIColor(argb: 0xCC000000).getRed(nil, green: nil, blue: nil, alpha: &alpha)
        #expect(abs(alpha - 0.8) < 0.01)
    }

    @Test func rgbWithoutAlphaByteIsOpaque() {
        var alpha: CGFloat = 0
        UIColor(argb: 0xFFFFFF).getRed(nil, green: nil, blue: nil, alpha: &alpha)
        #expect(alpha == 1)
    }

    @Test func headlineScalesUpOnLargeWidth() {
        #expect(TypeScale.headline(.large) != TypeScale.headline(.compact))
    }

    @Test func bodyScalesUpOnLargeWidth() {
        #expect(TypeScale.body(.large) != TypeScale.body(.compact))
    }

    @Test func widthClassPickReturnsMatchingValue() {
        #expect(WidthClass.compact.pick(1, 2, 3) == 1)
        #expect(WidthClass.regular.pick(1, 2, 3) == 2)
        #expect(WidthClass.large.pick(1, 2, 3) == 3)
    }
}
