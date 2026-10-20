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

    /// Stage 6 I-M1: the player parks a failure only while the shell shows its offline banner (`MainShellView` reads this
    /// monitor), so a parked failure always has the existing offline surface on screen. `.requiresConnection` parked with
    /// no banner, and waited for a `.satisfied` that only a new connection (the parked recovery's own) could bring.
    @Test(arguments: [NWPath.Status.satisfied, .requiresConnection, .unsatisfied])
    func thePlayerParksAFailureExactlyWhenTheShellShowsTheOfflineBanner(_ status: NWPath.Status) {
        #expect(PlayerHostView.Coordinator.isOnline(status) == NetworkMonitor.isOnline(for: status))
    }
}
