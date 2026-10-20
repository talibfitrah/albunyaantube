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

    /// The value following `flag` in `debug`, or nil when the flag is absent or last.
    static func value(after flag: String) -> String? {
        let args = debug
        guard let i = args.firstIndex(of: flag), args.indices.contains(i + 1) else { return nil }
        return args[i + 1]
    }
}
