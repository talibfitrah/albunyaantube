import Foundation

enum LaunchArguments {
    /// Test hooks (`-fitrah-*`). Compiled to an empty list in Release so no flag can reach a
    /// shipped binary -- every hook site reads this, never `ProcessInfo` directly.
    static var debug: [String] {
        #if DEBUG
        ProcessInfo.processInfo.arguments
        #else
        []
        #endif
    }
}
