import SwiftUI

/// Root of the app. Task 8 adds a real animated `SplashView` in front of this; for now
/// `SplashRouter.destination(onboardingCompleted:)` decides immediately between the onboarding
/// placeholder and the main shell.
struct RootView: View {
    @Environment(\.container) private var container
    @State private var widthClass: WidthClass = .compact

    var body: some View {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-fitrah-gallery") {
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
        let args = ProcessInfo.processInfo.arguments
        guard let flagIndex = args.firstIndex(of: "-fitrah-gallery-section"), args.indices.contains(flagIndex + 1),
              let raw = Int(args[flagIndex + 1]) else { return nil }
        return ComponentsGallery.Section(rawValue: raw)
    }
    #endif

    private var mainBody: some View {
        destinationView
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            // Measures the full-size container, not the content — see WidthClass.
            .onGeometryChange(for: WidthClass.self) { WidthClass(size: $0.size) } action: { widthClass = $0 }
            .environment(\.widthClass, widthClass)
    }

    @ViewBuilder
    private var destinationView: some View {
        switch SplashRouter.destination(onboardingCompleted: container.settings.onboardingCompleted) {
        case .onboarding: OnboardingPlaceholderView()
        case .main: MainShellView()
        }
    }
}

/// Task 8 replaces this with the real 3-page onboarding flow (`splash-onboarding.md` §3).
private struct OnboardingPlaceholderView: View {
    @Environment(\.container) private var container

    var body: some View {
        VStack(spacing: 16) {
            Text("Onboarding")
            Button("Get started") {
                container.settings.onboardingCompleted = true
            }
        }
    }
}

#Preview { RootView() }
