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

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(\.container, container)
        }
    }
}
