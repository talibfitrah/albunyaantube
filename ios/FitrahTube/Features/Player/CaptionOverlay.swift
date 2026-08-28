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
    /// I4 (B1 final review): the resolve's own `Resolved.userAgent`, threaded through to the
    /// `timedtext` fetch so it doesn't go out under CFNetwork's default (which names the app build
    /// and the iOS version).
    let userAgent: String
    /// Distance from the bottom of the stage to the cue. 72 clears AVKit's transport on the main
    /// player; `ShortsScreen` passes Android's 200 (`shorts_caption_bottom_clearance`) to clear its
    /// own channel/title/scrub block.
    var bottomClearance: CGFloat = 72

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
                    .padding(.bottom, bottomClearance)
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
        let fetched = (try? await CaptionsProvider().cues(for: track, userAgent: userAgent)) ?? []
        // Currentness guard (Task 5's known gap, task-6-brief.md): a re-resolve/item swap while
        // this `await` was in flight must not apply a stale track's cues to the new item. Fix
        // round 1 F2: also re-check `selectedCaptionTrack == track` -- a rapid A->B track switch
        // keeps the same item/player, so the item check alone let A's stale load win the race and
        // overwrite B's overlay (`try?` swallows `CancellationError`, so `.task(id:)`'s
        // cancellation of the old task isn't a reliable stop on its own).
        guard item === model.currentItem, model.selectedCaptionTrack == track,
              let player = model.currentPlayer else { return }
        observer.start(player: player) { activeCue = CaptionsProvider.activeCue(fetched, at: $0.seconds) }
    }
}

/// Same `MainActor.assumeIsolated` shape as `PlayerHostView.Coordinator` (`queue: .main` on
/// `addPeriodicTimeObserver` guarantees the callback fires on the main thread) -- a small
/// reference-type holder so a view's `@State` keeps one stable observer across its own body
/// re-evaluations, and so the `@Sendable` observer closure never needs to capture the
/// (non-Sendable) `View` struct itself. Start/store/cancel: ONE owner per player.
@MainActor
final class TimeObserver {
    private var token: Any?
    private weak var player: AVPlayer?

    /// 250 ms ticks (Android's Shorts ticker rate too, `ShortsPageViewHolder.kt:857-879`). B4
    /// task 3 made this cue-agnostic so `ShortsOverlay`'s scrubber shares the one owner.
    func start(player: AVPlayer, onTick: @escaping (CMTime) -> Void) {
        stop()
        self.player = player
        let interval = CMTime(seconds: 0.25, preferredTimescale: 600)
        token = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { time in
            MainActor.assumeIsolated { onTick(time) }
        }
    }

    func stop() {
        if let token, let player { player.removeTimeObserver(token) }
        token = nil
        player = nil
    }
}
