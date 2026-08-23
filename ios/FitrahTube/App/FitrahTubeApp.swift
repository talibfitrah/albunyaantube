import Foundation
import SwiftUI

@main
struct FitrahTubeApp: App {
    // Release must always build the live container -- a Release binary should never be able to
    // serve fake data even if `-fitrah-fake-container` somehow ended up in its arguments.
    #if DEBUG
    @State private var container = ProcessInfo.processInfo.arguments.contains("-fitrah-fake-container")
        ? AppContainer.fake()
        : AppContainer.live()
    #else
    @State private var container = AppContainer.live()
    #endif

    // Owned at the app scope, not inside MainShellView, so a deep link that arrives before the
    // shell exists -- e.g. tapped while Onboarding is still showing -- has somewhere to land
    // (RULING 4: held in `pendingRoute`, applied once `Router.shellDidAppear()` runs).
    @State private var router = Router()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(\.container, container)
                .environment(\.router, router)
                .onOpenURL { router.open($0) }
                .task { openDebugDeepLinkIfRequested() }
        }
    }

    /// Debug-only launch hook (`-fitrah-deeplink <url>`, two argv tokens): exercises the exact
    /// same `Router.open(URL)` that `.onOpenURL` calls, so deep links are reproducible via
    /// `xcrun simctl launch` -- unlike `simctl openurl`, which routes through iOS's "Open in
    /// AppName?" system confirmation and needs a real tap to proceed.
    private func openDebugDeepLinkIfRequested() {
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        if let flagIndex = args.firstIndex(of: "-fitrah-deeplink"),
           args.indices.contains(flagIndex + 1),
           let url = URL(string: args[flagIndex + 1]) {
            router.open(url)
        }
        #endif
    }
}
