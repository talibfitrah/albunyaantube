import Foundation
import Testing
@testable import FitrahTube

struct LaunchArgumentsTests {
    /// Under DEBUG the accessor is the live process argument list; Release's `[]` branch is
    /// proven by the gate's Release simulator build compiling it.
    @Test func debugAccessorIsWiredToTheProcessArguments() {
        #expect(LaunchArguments.debug == ProcessInfo.processInfo.arguments)
        #expect(!LaunchArguments.debug.isEmpty)
    }
}
