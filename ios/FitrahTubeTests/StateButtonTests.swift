import Testing
import SwiftUI
import UIKit
import CoreImage
@testable import FitrahTube

/// Regression guard for StateViews.swift's StateButton: `.foregroundStyle(Color.onBrand)` is
/// applied after `.buttonStyle(.borderedProminent)`, which is not guaranteed by SwiftUI to reach
/// the label — this renders the retry button in dark mode and samples its label ink, proving it's
/// dark onBrand (#0A1F18, r≈0.04) rather than a light system default (r≈1.0, near white).
@Suite(.perTest)
struct StateButtonTests {
    @Test func retryLabelIsDarkOnBrandInDarkMode() {
        let controller = UIHostingController(rootView: ErrorStateView(message: "x") {})
        controller.overrideUserInterfaceStyle = .dark
        let size = CGSize(width: 390, height: 200)
        controller.view.frame = CGRect(origin: .zero, size: size)
        // Must be lighter than both candidate label colors (onBrand dark r≈0.04, brand fill
        // r≈0.21) so background pixels around the button never masquerade as the darkest pixel.
        controller.view.backgroundColor = .white

        // A detached UIHostingController view doesn't reliably composite its SwiftUI content via
        // drawHierarchy — it needs an actual window/run-loop pass to render.
        let window = UIWindow(frame: CGRect(origin: .zero, size: size))
        window.overrideUserInterfaceStyle = .dark
        window.rootViewController = controller
        window.makeKeyAndVisible()
        window.layoutIfNeeded()
        RunLoop.current.run(until: Date())

        // Button sits in the bottom third (VStack: icon + message + button, centered, overflows
        // the 200pt height). Scan a box there and take the darkest red channel found: the brand
        // fill (~0.21 red) is always lighter than a correct dark onBrand label (~0.04 red), so
        // the minimum reliably lands on label ink even without locating the exact glyph pixel.
        let sample = Self.darkestPixel(
            in: controller.view,
            size: size,
            around: CGPoint(x: size.width / 2, y: size.height * 5 / 6),
            box: CGSize(width: 100, height: 44)
        )

        #expect(
            sample.r < 0.2,
            "expected dark onBrand label (r<0.2), sampled r=\(sample.r) g=\(sample.g) b=\(sample.b)"
        )
    }

    private static func darkestPixel(
        in view: UIView,
        size: CGSize,
        around point: CGPoint,
        box: CGSize
    ) -> (r: CGFloat, g: CGFloat, b: CGFloat) {
        let format = UIGraphicsImageRendererFormat()
        format.opaque = true
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: size, format: format)
        let image = renderer.image { _ in
            view.drawHierarchy(in: CGRect(origin: .zero, size: size), afterScreenUpdates: true)
        }

        guard let cgImage = image.cgImage else { return (1, 1, 1) }
        let w = cgImage.width, h = cgImage.height

        let minX = max(0, Int(point.x - box.width / 2))
        let maxX = min(w - 1, Int(point.x + box.width / 2))
        let minY = max(0, Int(point.y - box.height / 2))
        let maxY = min(h - 1, Int(point.y + box.height / 2))
        guard minX <= maxX, minY <= maxY else { return (1, 1, 1) }
        let boxW = maxX - minX + 1
        let boxH = maxY - minY + 1

        // CIContext.render(toBitmap:format:) hands back an explicit, unambiguous RGBA8 byte
        // layout — unlike CGImage.bitmapInfo (4 possible alpha-position/byte-order combinations),
        // which is easy to hand-decode wrong.
        let ciImage = CIImage(cgImage: cgImage)
        let ciContext = CIContext(options: [.workingColorSpace: NSNull()])
        // CIImage coordinates are bottom-left origin, y-up; (minX...maxX, minY...maxY) above are
        // top-left origin, y-down (UIKit point space) — flip the y-range.
        let ciRect = CGRect(x: minX, y: h - maxY - 1, width: boxW, height: boxH)
        var buffer = [UInt8](repeating: 0, count: boxW * boxH * 4)
        ciContext.render(
            ciImage,
            toBitmap: &buffer,
            rowBytes: boxW * 4,
            bounds: ciRect,
            format: .RGBA8,
            colorSpace: CGColorSpaceCreateDeviceRGB()
        )

        // Aggregate (minimum) is order-independent, so the row direction of the returned buffer
        // doesn't matter here — only that `ciRect` covers the intended source region.
        var darkest: (r: CGFloat, g: CGFloat, b: CGFloat) = (1, 1, 1)
        for i in stride(from: 0, to: buffer.count, by: 4) {
            let r = CGFloat(buffer[i]) / 255
            if r < darkest.r {
                darkest = (r, CGFloat(buffer[i + 1]) / 255, CGFloat(buffer[i + 2]) / 255)
            }
        }
        return darkest
    }
}
