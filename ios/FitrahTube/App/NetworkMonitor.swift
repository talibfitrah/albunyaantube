import Foundation
import Network
import Observation

/// Android's `NetworkMonitor` (`android/app/src/main/java/com/albunyaan/tube/util/NetworkMonitor.kt:18-58`),
/// documented in `docs/superpowers/plans/2026-08-23-ios-phase1-research/shell-home.md:71-105`.
/// `NWPathMonitor` already reports aggregate reachability per callback (the bug Android's comment
/// describes -- flashing the offline banner during a Wi-Fi/cellular handover -- doesn't apply
/// here), so this is a thin wrapper: start the monitor, map `path.status`, de-dupe.
@MainActor @Observable final class NetworkMonitor {
    private(set) var isOnline = true
    /// Phase 3 Task 4: the offline cellular gate's input (`OfflineStateMachine.allowedToRun`).
    private(set) var isOnCellular = false

    private let monitor = NWPathMonitor()

    init(start: Bool = true) {
        #if DEBUG
        // Acceptance artefact hook (task 7): forces the offline banner on for a screenshot
        // without needing to actually disable the simulator's network.
        if LaunchArguments.debug.contains("-fitrah-offline") {
            isOnline = false
            return
        }
        #endif
        if start {
            // Delivered on the main queue and applied synchronously (gate A-M1). Each callback
            // used to spawn its own unstructured `Task { @MainActor in … }`, and unstructured
            // tasks have no FIFO guarantee onto an actor -- two path updates inside one hop (a
            // Wi-Fi drop immediately followed by a cellular attach) could be applied in reverse,
            // and the `isOnline != online` de-dupe cannot detect that. The offline banner then sat
            // there while the device was online until the next path change. Ordering is now
            // structural: `NWPathMonitor` serialises its callbacks onto the queue it is given.
            monitor.pathUpdateHandler = { [weak self] path in
                let online = Self.isOnline(for: path.status)
                let cellular = path.usesInterfaceType(.cellular)
                MainActor.assumeIsolated {
                    guard let self else { return }
                    if self.isOnCellular != cellular { self.isOnCellular = cellular }
                    guard self.isOnline != online else { return }
                    self.isOnline = online
                }
            }
            monitor.start(queue: .main)
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
