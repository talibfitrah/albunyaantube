import SwiftUI
import Testing
import UIKit
@testable import FitrahTube

/// The sign-in flow's one layout, `CentredFormColumn`: centred vertically in the height it is given
/// whenever its content fits, starting at its top, scrolling, whenever it does not. Measured on the
/// rendered window at two heights per case: only the DIFFERENCE is asserted, so padding and font
/// metrics cancel out.
@MainActor
@Suite(.perTest)
struct SignInLayoutTests {
    /// 400 pt fits at both heights: centred means it moves down by HALF the 100 pt the screen grew.
    /// Top-aligned it would not move at all; bottom-aligned, by 100.
    @Test func aColumnThatFitsIsVerticallyCentred() async throws {
        let short = try await contentTop(of: CentredFormColumn { LateBlock(height: 400) }, height: 700)
        let tall = try await contentTop(of: CentredFormColumn { LateBlock(height: 400) }, height: 800)
        #expect(abs((tall - short) - 50) <= 1, "content top: \(short) at 700 pt, \(tall) at 800 pt")
    }

    /// 600 pt overflows at both heights (keyboard up, landscape, large text): the column opens at its
    /// top, so the content sits at the same visible spot whatever the height, below its own padding.
    /// Opened centred it would sit 50 pt apart, or fill the window from row 0, scrolled past its top.
    @Test func aColumnThatOverflowsStartsAtItsTop() async throws {
        let short = try await contentTop(of: CentredFormColumn { LateBlock(height: 600) }, height: 300)
        let tall = try await contentTop(of: CentredFormColumn { LateBlock(height: 600) }, height: 400)
        #expect(abs(tall - short) <= 1, "content top: \(short) at 300 pt, \(tall) at 400 pt")
        #expect(short > 0, "the content's top is scrolled out of view")
    }

    /// The flow's other screens wear the same column — verification's form fits a phone, so centred.
    @Test func emailVerificationIsVerticallyCentred() async throws {
        let short = try await contentTop(of: Self.emailVerification(), height: 700)
        let tall = try await contentTop(of: Self.emailVerification(), height: 800)
        #expect(abs((tall - short) - 50) <= 1, "content top: \(short) at 700 pt, \(tall) at 800 pt")
    }

    private static func emailVerification() -> some View {
        let user = AuthUser(uid: "layout", email: "student@fitrah.test", isEmailVerified: false,
                            providerIDs: ["password"])
        // Its own defaults: the screen writes its auto-send latch there.
        let defaults = UserDefaults(suiteName: "SignInLayoutTests.\(UUID().uuidString)") ?? .standard
        return EmailVerificationScreen()
            .environment(\.container, .fake(defaults: defaults, auth: FakeAuthClient(state: .signedIn(user))))
    }

    /// The first rendered row that differs from the top-left pixel (always padding), in window
    /// points, with `view` hosted `height` points tall. No safe area, so the view is the whole window.
    private func contentTop(of view: some View, height: CGFloat) async throws -> CGFloat {
        let controller = UIHostingController(rootView: view.dynamicTypeSize(.large))
        controller.safeAreaRegions = []
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        let window = scene.map(UIWindow.init(windowScene:)) ?? UIWindow()
        window.frame = CGRect(x: 0, y: 0, width: 390, height: height)
        window.rootViewController = controller
        window.isHidden = false
        defer { window.isHidden = true }

        // Content arrives after the first frame (`.task`); read until its position settles.
        var last: CGFloat?
        for _ in 0..<100 {
            try await Task.sleep(for: .milliseconds(30))
            let top = Self.firstContentRow(in: window)
            if let top, top == last { return top }
            last = top
        }
        Issue.record("the content never settled at height \(height)")
        return .nan
    }

    private static func firstContentRow(in window: UIWindow) -> CGFloat? {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.preferredRange = .standard
        let image = UIGraphicsImageRenderer(bounds: window.bounds, format: format).image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
        guard let cg = image.cgImage, let data = cg.dataProvider?.data,
              let bytes = CFDataGetBytePtr(data) else { return nil }
        let pixel = cg.bitsPerPixel / 8
        let background = Array(UnsafeBufferPointer(start: bytes, count: pixel))
        for y in 0..<cg.height {
            for x in 0..<cg.width {
                let start = bytes + y * cg.bytesPerRow + x * pixel
                if Array(UnsafeBufferPointer(start: start, count: pixel)) != background { return CGFloat(y) }
            }
        }
        return nil
    }
}

/// A block that appears after the first frame, as every flow screen's form does (each builds its
/// view model in `.task`) — the case a scroll anchor gets wrong.
private struct LateBlock: View {
    let height: CGFloat
    @State private var shown = false

    var body: some View {
        VStack { if shown { Color.red.frame(height: height) } }
            .task { shown = true }
    }
}
