import SwiftUI
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

    /// The sign-in provider buttons wear their brands' own published styles: Apple's HIG black
    /// (light) / white (dark) button, and Google's light (#FFFFFF, #747775 stroke, #1F1F1F text) and
    /// dark (#131314, #8E918F stroke, #E3E3E3 text) themes.
    @Test func theProviderButtonColorsAreTheBrandsOwn() {
        func rgb(_ color: Color, _ style: UIUserInterfaceStyle) -> UInt32 {
            var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0
            UIColor(color).resolvedColor(with: UITraitCollection(userInterfaceStyle: style))
                .getRed(&r, green: &g, blue: &b, alpha: nil)
            return UInt32((r * 255).rounded()) << 16 | UInt32((g * 255).rounded()) << 8 | UInt32((b * 255).rounded())
        }
        let expected: [(Color, UInt32, UInt32)] = [
            (.appleButtonFill, 0x000000, 0xFFFFFF), (.appleButtonText, 0xFFFFFF, 0x000000),
            (.googleButtonFill, 0xFFFFFF, 0x131314), (.googleButtonBorder, 0x747775, 0x8E918F),
            (.googleButtonText, 0x1F1F1F, 0xE3E3E3),
        ]
        for (index, (color, light, dark)) in expected.enumerated() {
            #expect(rgb(color, .light) == light, "row \(index) light")
            #expect(rgb(color, .dark) == dark, "row \(index) dark")
        }
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

    @Test func homeHorizontalMarginPicksByWidth() {
        #expect(Spacing.homeHorizontalMargin(.compact) == 16)
        #expect(Spacing.homeHorizontalMargin(.regular) == 24)
        #expect(Spacing.homeHorizontalMargin(.large) == 32)
    }
}
