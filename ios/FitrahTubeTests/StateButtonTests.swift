import Testing
import SwiftUI
import UIKit
import CoreImage
@testable import FitrahTube

/// Regression guard for StateViews.swift's StateButton: `.foregroundStyle(Color.onBrand)` is
/// applied to the `Text` inside the button's label (not chained after `.buttonStyle`, which
/// SwiftUI doesn't guarantee reaches the label) — this renders the retry button in both
/// appearances and samples its label ink to prove it's the correct onBrand color in each.
@Suite(.perTest)
struct StateButtonTests {
    @Test("Retry label is dark onBrand in dark mode (label frame located via hierarchy walk; falls back to a fixed box if no UILabel/UIButton is found)")
    func retryLabelIsDarkOnBrandInDarkMode() throws {
        let sample = try #require(Self.renderAndSampleLabel(dark: true))
        // Must be lighter than both candidate label colors (onBrand dark r≈0.04, brand fill
        // r≈0.21) so background pixels around the button never masquerade as the darkest pixel.
        #expect(
            sample.r < 0.2,
            "expected dark onBrand label (r<0.2), sampled r=\(sample.r) g=\(sample.g) b=\(sample.b)"
        )
    }

    @Test("Retry label is light onBrand in light mode (label frame located via hierarchy walk; falls back to a fixed box if no UILabel/UIButton is found)")
    func retryLabelIsLightOnBrandInLightMode() throws {
        let sample = try #require(Self.renderAndSampleLabel(dark: false))
        // Must be darker than both candidate label colors (onBrand light r=1.0, brand fill
        // r≈0.15) so background pixels around the button never masquerade as the lightest pixel.
        #expect(
            sample.r > 0.9,
            "expected light onBrand label (r>0.9), sampled r=\(sample.r) g=\(sample.g) b=\(sample.b)"
        )
    }

    /// Renders `ErrorStateView`'s retry button in the given appearance and samples the most
    /// extreme (darkest in dark mode, lightest in light mode) pixel inside its label area. The
    /// background is set to whichever color can never be mistaken for the label ink, so
    /// background pixels that leak into the sampled region can't skew the result.
    private static func renderAndSampleLabel(dark: Bool) -> (r: CGFloat, g: CGFloat, b: CGFloat)? {
        let controller = UIHostingController(rootView: ErrorStateView(message: "x") {})
        let style: UIUserInterfaceStyle = dark ? .dark : .light
        controller.overrideUserInterfaceStyle = style
        // Tall enough that ErrorStateView's icon (up to Size.iconXL == 128pt) + message + button
        // are never clipped out of the captured bitmap below — a too-short canvas silently drops
        // the button from the render entirely, which no sampling strategy can then recover from.
        let size = CGSize(width: 390, height: 500)
        controller.view.frame = CGRect(origin: .zero, size: size)
        controller.view.backgroundColor = dark ? .white : .black

        // A detached UIHostingController view doesn't reliably composite its SwiftUI content via
        // drawHierarchy — it needs an actual window/run-loop pass to render.
        let window = UIWindow(frame: CGRect(origin: .zero, size: size))
        window.overrideUserInterfaceStyle = style
        window.rootViewController = controller
        window.makeKeyAndVisible()
        window.layoutIfNeeded()
        RunLoop.current.run(until: Date())

        let (point, box) = Self.labelSampleRegion(in: controller.view, size: size)
        return Self.extremePixel(in: controller.view, size: size, around: point, box: box, wantDarkest: dark)
    }

    /// Locates the retry button's label by walking the hosted view hierarchy for the first
    /// `UILabel`/`UIButton` and returns its center point and size, converted into `root`'s
    /// coordinate space. Falls back to scanning the whole rendered view if no such view is found
    /// (SwiftUI's `.borderedProminent` button style doesn't back onto a classic UIKit
    /// UILabel/UIButton, so this fallback is what actually runs in practice) — safe because the
    /// background is deliberately set to whichever color can never be mistaken for the label ink.
    private static func labelSampleRegion(in root: UIView, size: CGSize) -> (point: CGPoint, box: CGSize) {
        if let frame = Self.firstLabelOrButtonFrame(in: root) {
            return (CGPoint(x: frame.midX, y: frame.midY), frame.size)
        }
        return (CGPoint(x: size.width / 2, y: size.height / 2), size)
    }

    private static func firstLabelOrButtonFrame(in root: UIView) -> CGRect? {
        var queue = Array(root.subviews)
        while !queue.isEmpty {
            let view = queue.removeFirst()
            if view is UILabel || view is UIButton {
                return view.convert(view.bounds, to: root)
            }
            queue.append(contentsOf: view.subviews)
        }
        return nil
    }

    private static func extremePixel(
        in view: UIView,
        size: CGSize,
        around point: CGPoint,
        box: CGSize,
        wantDarkest: Bool
    ) -> (r: CGFloat, g: CGFloat, b: CGFloat)? {
        let format = UIGraphicsImageRendererFormat()
        format.opaque = true
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: size, format: format)
        let image = renderer.image { _ in
            view.drawHierarchy(in: CGRect(origin: .zero, size: size), afterScreenUpdates: true)
        }

        guard let cgImage = image.cgImage else {
            Issue.record("render failed")
            return nil
        }
        let w = cgImage.width, h = cgImage.height

        let minX = max(0, Int(point.x - box.width / 2))
        let maxX = min(w - 1, Int(point.x + box.width / 2))
        let minY = max(0, Int(point.y - box.height / 2))
        let maxY = min(h - 1, Int(point.y + box.height / 2))
        guard minX <= maxX, minY <= maxY else {
            Issue.record("render failed")
            return nil
        }
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

        // Aggregate (min/max, order-independent) so the row direction of the returned buffer
        // doesn't matter here — only that `ciRect` covers the intended source region.
        var extreme: (r: CGFloat, g: CGFloat, b: CGFloat) = wantDarkest ? (1, 1, 1) : (0, 0, 0)
        for i in stride(from: 0, to: buffer.count, by: 4) {
            let r = CGFloat(buffer[i]) / 255
            let isMoreExtreme = wantDarkest ? (r < extreme.r) : (r > extreme.r)
            if isMoreExtreme {
                extreme = (r, CGFloat(buffer[i + 1]) / 255, CGFloat(buffer[i + 2]) / 255)
            }
        }
        return extreme
    }
}
