import Foundation
import SwiftUI

@main
struct FitrahTubeApp: App {
    @State private var container = ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
        ? AppContainer.fake()
        : AppContainer.live()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(\.container, container)
        }
    }
}
