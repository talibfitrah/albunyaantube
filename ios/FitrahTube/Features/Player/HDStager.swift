import AVFoundation
import InnerTubeKit
import UIKit

/// Spec R43 (revision 4): the HD item buffering on a hidden, muted, paused player that follows the live position, and the
/// coordinator's whole per-tick decision (`step`). It never plays, never takes an external route, never auto-selects
/// captions (R19), and sign-out's `stopAllPlayback` reaches it.
@MainActor final class HDStager {
    /// The swap needs this much buffered past the live position (ruling (b)).
    nonisolated static let ahead: TimeInterval = 10
    /// Plan review I4: HD may compete for bandwidth only while the 360p holds this much ahead (or to its end).
    nonisolated static let standardAhead: TimeInterval = 15
    /// How far past its seek point the hidden item buffers (`preferredForwardBufferDuration`).
    nonisolated static let window: TimeInterval = 20
    /// ponytail: 30 s from creation, unmeasured -- a throttled HD (Gate 0's mint 2, 1 of 9) never fills; that video
    /// stays on 360p (R41).
    nonisolated static let deadline: TimeInterval = 30

    enum Poll: Equatable { case waiting, ready, failed }

    let masterURL: URL
    let item: AVPlayerItem
    private let hidden = AVPlayer()
    private let startedAt: Date
    private var seekedTo: TimeInterval = 0
    /// The host's item settings (the quality ceiling), applied to the hidden item here and to the visible one at the
    /// hand-over.
    private let configure: (AVPlayerItem) -> Void

    init(item: AVPlayerItem, masterURL: URL, position: TimeInterval, now: Date = Date(),
         configure: @escaping (AVPlayerItem) -> Void = { _ in }) {
        self.item = item
        self.masterURL = masterURL
        self.configure = configure
        startedAt = now
        configure(item)
        item.preferredForwardBufferDuration = Self.window
        hidden.isMuted = true
        hidden.allowsExternalPlayback = false
        hidden.appliesMediaSelectionCriteriaAutomatically = false
        hidden.replaceCurrentItem(with: item)
        PlayerHostView.builtPlayers.add(hidden)            // plan review M15
        seek(to: position)
    }

    /// The production item: the loader on the master, or the direct m4a when Audio only is on (M14, accepted). A `sidx`
    /// refused after its retry is reported through `onRefused` (R42).
    convenience init(upgrade: Resolved, masterURL: URL, audioOnly: Bool, position: TimeInterval,
                     onRefused: @escaping @Sendable () -> Void, configure: @escaping (AVPlayerItem) -> Void) {
        let url = PlayerHostView.streamURL(upgrade.stream, audioOnly: audioOnly)
        self.init(item: AVPlayerItem(asset: PlayerHostView.asset(url: url, userAgent: upgrade.userAgent,
                                                                 synthetic: upgrade.stream.synthetic, onRefused: onRefused)),
                  masterURL: masterURL, position: position, configure: configure)
    }

    private func seek(to position: TimeInterval) {
        seekedTo = position
        hidden.seek(to: CMTime(seconds: position, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
    }

    /// One look: re-aimed at the live position when it left the window, ready once enough is buffered past it.
    func poll(position: TimeInterval, loops: Bool, now: Date = Date()) -> Poll {
        if item.status == .failed { return .failed }
        let need = Self.need(position: position, duration: Self.duration(item), loops: loops)
        if Self.mustReseek(position: position, seekedTo: seekedTo, need: need ?? Self.ahead) { seek(to: position) }
        if item.status == .readyToPlay, let need, Self.isBuffered(Self.ranges(item), position: position, need: need) {
            return .ready
        }
        return now.timeIntervalSince(startedAt) > Self.deadline ? .failed : .waiting
    }

    /// Gate 1 ruling (2026-10-17): an `AVPlayerItem` can never leave its first `AVPlayer` (NSInvalidArgumentException, even
    /// after `replaceCurrentItem(with: nil)` and a wait), so `player` gets a NEW item on the SAME asset (Gate 1b B1: no new
    /// playlist or sidx request; the hidden item's buffer is fetched again, a short rebuffer). The hidden item only proved
    /// that HD buffers ahead. The fresh item gets the host's item settings here; the update pass the commit triggers then
    /// hands it to the media-selection menus and the observers, as for every item the host builds.
    /// Played at the LIVE position; the rate carries over: a paused player stays paused.
    func handOver(to player: AVPlayer) {
        hidden.replaceCurrentItem(with: nil)
        let fresh = AVPlayerItem(asset: item.asset)
        configure(fresh)
        let now = player.currentTime()
        player.replaceCurrentItem(with: fresh)
        player.seek(to: now, toleranceBefore: .zero, toleranceAfter: .zero)
        #if DEBUG
        print("HDStager: HD swapped in at \(now.seconds) s")   // read by the device leg (Task 23), deleted in Task 24
        #endif
    }

    // MARK: - the rules (pure)

    /// How much must be buffered past `position`: `ahead`, or what is left. A looping Short near its end waits for the
    /// loop (nil) instead of swapping onto a tail the loop throws away. A clip shorter than `ahead` takes what is left.
    nonisolated static func need(position: Double, duration: Double?, loops: Bool) -> Double? {
        guard let duration else { return ahead }
        let left = duration - position - 0.5
        if left >= ahead { return ahead }
        return loops && duration - 0.5 >= ahead ? nil : max(0, left)
    }

    nonisolated static func isBuffered(_ ranges: [(Double, Double)], position: Double, need: Double) -> Bool {
        ranges.contains { $0.0 <= position + 0.5 && $0.1 >= position + need }
    }

    /// Plan review I3: the live position left what the hidden item can fill -- a seek back (or a Short's loop) before its
    /// seek point, or past its window (a seek ahead, or a slow fill left behind). A seek inside the window needs nothing.
    nonisolated static func mustReseek(position: Double, seekedTo: Double, need: Double) -> Bool {
        position < seekedTo - 0.5 || position + need > seekedTo + window
    }

    nonisolated static func standardHasHeadroom(_ ranges: [(Double, Double)], position: Double, duration: Double?) -> Bool {
        isBuffered(ranges, position: position, need: duration.map { min(standardAhead, max(0, $0 - position - 0.5)) } ?? standardAhead)
    }

    /// M13: `.inactive` (Control Center, a notification) keeps the stager; the background, PiP and an external route drop it.
    nonisolated static func mayStage(appState: UIApplication.State, pictureInPicture: Bool, external: Bool) -> Bool {
        appState != .background && !pictureInPicture && !external
    }

    private static func ranges(_ item: AVPlayerItem) -> [(Double, Double)] {
        item.loadedTimeRanges.map { ($0.timeRangeValue.start.seconds, CMTimeRangeGetEnd($0.timeRangeValue).seconds) }
    }

    private static func duration(_ item: AVPlayerItem) -> Double? {
        item.duration.seconds.isFinite ? item.duration.seconds : nil
    }

    // MARK: - the coordinator's tick (plan review I5: one function, tested with fakes)

    /// Returns the stager to keep. nil drops it: nothing pending, not eligible (no penalty), handed over, failed or
    /// refused at the commit (both: no HD for that video). `make` builds a new stager; production passes the
    /// loader-backed one.
    static func step(_ stager: HDStager?, model: PlayerViewModel, player: AVPlayer, eligible: Bool, loops: Bool,
                     make: (Resolved, URL, TimeInterval) -> HDStager) -> HDStager? {
        guard eligible, let pending = model.pendingUpgrade, case .hls(let masterURL, _, _, _, _) = pending.stream,
              let visible = player.currentItem else { return nil }
        let position = player.currentTime().seconds
        guard let stager, stager.masterURL == masterURL else {
            guard standardHasHeadroom(ranges(visible), position: position, duration: duration(visible)) else { return nil }
            return make(pending, masterURL, position)
        }
        switch stager.poll(position: position, loops: loops) {
        case .waiting:
            return stager
        case .failed:
            model.upgradeFailed()
            return nil
        case .ready:
            if model.commitUpgrade(masterURL) { stager.handOver(to: player) }
            return nil
        }
    }
}
