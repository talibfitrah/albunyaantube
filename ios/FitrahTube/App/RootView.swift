import SwiftUI

/// Root of the app. `SplashView` gates entry (its own animation/work timeline, `splash-onboarding.md`
/// §1); once it completes, `SplashRouter.destination(onboardingCompleted:)` decides between
/// `OnboardingView` and the main shell.
struct RootView: View {
    @Environment(\.container) private var container
    @State private var widthClass: WidthClass = .compact
    @State private var showSplash = true

    var body: some View {
        #if DEBUG
        if LaunchArguments.debug.contains("-fitrah-gallery") {
            return AnyView(ComponentsGallery(section: gallerySection).environment(\.widthClass, widthClass))
        }
        #endif
        return AnyView(mainBody)
    }

    #if DEBUG
    /// `-fitrah-gallery-section <n>` narrows the Debug gallery rig to one third of the components,
    /// so a full-height screenshot on a phone captures every component (fix round 1). Absent or
    /// unparsable -> `nil`, which renders the whole gallery (original, pre-fix-round behaviour).
    private var gallerySection: ComponentsGallery.Section? {
        let args = LaunchArguments.debug
        guard let flagIndex = args.firstIndex(of: "-fitrah-gallery-section"), args.indices.contains(flagIndex + 1),
              let raw = Int(args[flagIndex + 1]) else { return nil }
        return ComponentsGallery.Section(rawValue: raw)
    }
    #endif

    private var mainBody: some View {
        destinationView
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            // Measures the *window*, not the safe-area-inset content (gate A-C2). Measuring the
            // content box made `WidthClass`'s "smallest width, never flips on rotation" invariant
            // false on the largest iPad: 13" is 1032×1376 pt, but landscape insets take 1032 down
            // to 980 — 20 pt under the 1000 pt `.large` threshold — so rotating the device flipped
            // the whole token table (4 columns → 3, Spacing.md 24 → 20, avatars 72 → 64,
            // NavigationRailMetrics.width 96 → 80). Lowering the threshold instead would only move
            // the same bug one device generation away.
            //
            // The insets are added back rather than reclaimed with `.ignoresSafeArea()`: measured
            // on an iPad Pro 13" in landscape, a `Color.clear.ignoresSafeArea()` background still
            // reported `size` 1376×980 with `safeAreaInsets` top 32 / bottom 20 — the ignore does
            // not expand what `onGeometryChange` sees, but the proxy does carry the insets, and
            // 980 + 32 + 20 is exactly the 1032 pt window.
            .onGeometryChange(for: WidthClass.self) { proxy in
                let insets = proxy.safeAreaInsets
                return WidthClass(size: CGSize(width: proxy.size.width + insets.leading + insets.trailing,
                                               height: proxy.size.height + insets.top + insets.bottom))
            } action: { widthClass = $0 }
            .environment(\.widthClass, widthClass)
            // task-13: Theme row (SettingsView) persists "system"/"light"/"dark"; applied here at
            // the root so it covers Onboarding and the main shell alike -- nil for "system" lets
            // the view inherit the environment's scheme, same as Android's FOLLOW_SYSTEM.
            .preferredColorScheme(container.settings.colorScheme)
    }

    @ViewBuilder
    private var destinationView: some View {
        if showSplash {
            SplashView { showSplash = false }
        } else {
            switch SplashRouter.destination(onboardingCompleted: container.settings.onboardingCompleted) {
            case .onboarding: OnboardingView()
            case .main: MainShellView()
            // Tasks 11/12 replace this arm with the real screen
            case .profileBootstrap: MainShellView()
            // Tasks 11/12 replace this arm with the real screen
            case .emailVerification: MainShellView()
            }
        }
    }
}

#Preview { RootView() }
