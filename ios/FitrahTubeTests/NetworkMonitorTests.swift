import Network
import Testing
@testable import FitrahTube

@Suite(.perTest)
struct NetworkMonitorTests {
    @Test func startsOnlineBeforeAnyPathUpdateWhenNotStarted() {
        // start: false avoids depending on the real NWPathMonitor callback (and the sandbox's
        // network entitlements) for a deterministic assertion on the documented initial value.
        let monitor = NetworkMonitor(start: false)
        #expect(monitor.isOnline == true)
    }

    @Test func pathStatusMapsToOnlineOffline() {
        #expect(NetworkMonitor.isOnline(for: .satisfied) == true)
        #expect(NetworkMonitor.isOnline(for: .requiresConnection) == true)
        #expect(NetworkMonitor.isOnline(for: .unsatisfied) == false)
    }
}
