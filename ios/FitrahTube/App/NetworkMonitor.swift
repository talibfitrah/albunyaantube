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
    /// ponytail: starts `false`, so on a cellular launch with Wi-Fi-only ON a download scheduled
    /// before `NWPathMonitor`'s first (near-immediate, main-queue) callback can start on cellular
    /// once; `gateDidChange` pauses it as soon as the real path lands. Deferring the first
    /// scheduling pass on this signal would thread launch ordering through OfflineManager for a
    /// milliseconds-wide window — upgrade there if the window ever bites.
    private(set) var isOnCellular = false
    /// Stage 6 I-M2: which network the device is on (`identity(for:)`).
    private(set) var identity = ""

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
                let identity = Self.identity(for: path)
                MainActor.assumeIsolated {
                    guard let self else { return }
                    if self.isOnCellular != cellular { self.isOnCellular = cellular }
                    if self.identity != identity { self.identity = identity }
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

    /// The interfaces the path can use and its gateways: what changes when the device leaves home Wi-Fi for cellular or
    /// another Wi-Fi, so a googlevideo URL minted under one identity is IP-bound to another network than the current one.
    /// ponytail: not the public IP; two networks behind the same gateway address (a 192.168.1.1 router) read as one, and
    /// a refusal there pauses HD as it did before. Ask a public-IP echo if that ever matters.
    nonisolated static func identity(for path: NWPath) -> String {
        (path.availableInterfaces.map(\.name) + path.gateways.map { "\($0)" }).joined(separator: " ")
    }

    nonisolated static func isOnline(for status: NWPath.Status) -> Bool {
        switch status {
        case .satisfied, .requiresConnection: return true
        case .unsatisfied: return false
        @unknown default: return false
        }
    }
}
