import Foundation
import SwiftUI

@main
struct FitrahTubeApp: App {
    @State private var container = ProcessInfo.processInfo.arguments.contains("-fitrah-fake-container")
        ? AppContainer.fake()
        : AppContainer.live()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(\.container, container)
        }
    }
}
