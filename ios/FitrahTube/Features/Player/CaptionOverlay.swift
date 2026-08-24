import AVFoundation
import InnerTubeKit
import SwiftUI

/// The auto-generated captions overlay (spec §10 Captions paragraph; plan §6.5): shows the cue
/// whose interval contains the current playback time for `track`, nothing otherwise. Manual
/// tracks (HLS `SUBTITLES` renditions) render through AVKit's own stock subtitle menu -- this view
/// only exists for the InnerTubeKit-surfaced auto-generated tracks `PlayerScreen`'s captions
/// toggle picks from.
struct CaptionOverlay: View {
    let model: PlayerViewModel
    let track: CaptionTrack

    @State private var observer = TimeObserver()
    @State private var activeCue: CaptionsProvider.Cue?

    var body: some View {
        VStack {
            Spacer()
            if let activeCue {
                Text(activeCue.text)
                    .font(.callout)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(.black.opacity(0.75), in: RoundedRectangle(cornerRadius: 6))
                    .padding(.bottom, 72) // above the transport area
                    .accessibilityIdentifier("player.captionOverlay.text")
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
        .allowsHitTesting(false)
        .task(id: track.url) { await load() }
        .onDisappear { observer.stop() }
    }

    @MainActor
    private func load() async {
        observer.stop()
        activeCue = nil
        let item = model.currentItem
        let fetched = (try? await CaptionsProvider().cues(for: track)) ?? []
        // Currentness guard (Task 5's known gap, task-6-brief.md): a re-resolve/item swap while
        // this `await` was in flight must not apply a stale track's cues to the new item.
        guard item === model.currentItem, let player = model.currentPlayer else { return }
        observer.start(player: player, cues: fetched) { activeCue = $0 }
    }
}

/// Same `MainActor.assumeIsolated` shape as `PlayerHostView.Coordinator` (`queue: .main` on
/// `addPeriodicTimeObserver` guarantees the callback fires on the main thread) -- a small
/// reference-type holder so `CaptionOverlay`'s `@State` keeps one stable observer across its own
/// body re-evaluations, and so the `@Sendable` observer closure never needs to capture the
/// (non-Sendable) `View` struct itself.
@MainActor
final class TimeObserver {
    private var cues: [CaptionsProvider.Cue] = []
    private var token: Any?
    private weak var player: AVPlayer?

    func start(player: AVPlayer, cues: [CaptionsProvider.Cue], onChange: @escaping (CaptionsProvider.Cue?) -> Void) {
        stop()
        self.player = player
        self.cues = cues
        let interval = CMTime(seconds: 0.25, preferredTimescale: 600)
        token = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
            MainActor.assumeIsolated {
                guard let self else { return }
                onChange(CaptionsProvider.activeCue(self.cues, at: time.seconds))
            }
        }
    }

    func stop() {
        if let token, let player { player.removeTimeObserver(token) }
        token = nil
        player = nil
    }
}
