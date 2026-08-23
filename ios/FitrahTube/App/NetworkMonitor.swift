import Network
import Observation

/// Android's `NetworkMonitor` (`android/app/src/main/java/com/albunyaan/tube/util/NetworkMonitor.kt:18-58`),
/// documented in `docs/superpowers/plans/2026-08-23-ios-phase1-research/shell-home.md:71-105`.
/// `NWPathMonitor` already reports aggregate reachability per callback (the bug Android's comment
/// describes -- flashing the offline banner during a Wi-Fi/cellular handover -- doesn't apply
/// here), so this is a thin wrapper: start the monitor, map `path.status`, de-dupe.
@MainActor @Observable final class NetworkMonitor {
    private(set) var isOnline = true

    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "com.albunyaan.tube.network-monitor")

    init(start: Bool = true) {
        if start {
            monitor.pathUpdateHandler = { [weak self] path in
                let online = Self.isOnline(for: path.status)
                Task { @MainActor in
                    guard let self, self.isOnline != online else { return }
                    self.isOnline = online
                }
            }
            monitor.start(queue: queue)
        }
    }

    deinit {
        monitor.cancel()
    }

    nonisolated static func isOnline(for status: NWPath.Status) -> Bool {
        switch status {
        case .satisfied, .requiresConnection: return true
        case .unsatisfied: return false
        @unknown default: return false
        }
    }
}
