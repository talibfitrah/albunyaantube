import SwiftUI

/// The Shorts playback surface (spec §10 "Shorts", plan §6.8; B4 Task 2). ONE short, ONE
/// `PlayerViewModel`, ONE `PlayerHostView` in `.shorts` presentation -- no feed, no pager (plan B4
/// reconciliation 1 / ruling 51). Everything the ladder already does (recovery, Safe Mode, quality
/// ceiling, audio session) arrives through the shared view model; this file adds the 9:16 stage and
/// the portrait lock. Chrome (tap indicator, scrub bar, rail, kebab, Back) is Task 3.
struct ShortsScreen: View {
    let args: PlayerArgs

    @Environment(\.container) private var container
    @Environment(\.widthClass) private var widthClass
    @State private var model: PlayerViewModel?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let model { stage(model.state, model: model) } else { ProgressView().tint(.white) }
        }
        .statusBarHidden(true)                              // ruling 57
        .toolbar(.hidden, for: .navigationBar)              // fork D; the Back control is Task 3's
        .onAppear { OrientationLock.lockPortrait() }
        .onDisappear { OrientationLock.release() }
        .task {
            guard model == nil else { return }
            let vm = PlayerViewModel(resolver: PlayerScreen.resolver(container: container),
                                     settings: container.settings, args: args)
            model = vm
            await vm.open()
        }
    }

    /// Which surface a state renders. Pure so the routing is testable without a live view: an online
    /// `.embed` MUST reach `EmbedRungView` -- `PlayerStateCopy.map` `preconditionFailure`s on it
    /// (C1, fix round 2). Same three-way split as `PlayerScreen.stateView`.
    enum Branch: Equatable { case player, embed, status }
    static func branch(for state: StreamState, isOnline: Bool) -> Branch {
        switch state {
        case .ready, .rung2Progressive: .player
        case .embed where isOnline: .embed
        default: .status
        }
    }

    @ViewBuilder
    private func stage(_ state: StreamState, model: PlayerViewModel) -> some View {
        switch Self.branch(for: state, isOnline: container.network.isOnline) {
        // ONE branch for both playable rungs -- Global Constraints, and the same identity rule
        // PlayerScreen.stateView carries. Rung-specific chrome differs INSIDE it (Task 3).
        case .player:
            // `.aspectRatio(.fit)` letterboxes the 9:16 box (iPad: spec §14); `videoGravity =
            // .resizeAspectFill` crops a non-9:16 source INSIDE it (Android resize_mode="zoom").
            // Two different levers, both needed.
            PlayerHostView(state: state, quality: model.selectedQuality, audioOnly: model.audioOnly,
                           model: model, presentation: .shorts)
                .accessibilityIdentifier("shorts.stage")
                .aspectRatio(9.0 / 16.0, contentMode: .fit)
                .frame(maxWidth: Size.playerMaxWidth(widthClass))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        // Rung 3, mirroring PlayerScreen: its own surface, no AVPlayer. Still 16:9 here -- the
        // 9:16 aspect parameter is Task 3 (CF-B3-1).
        case .embed:
            if case .embed(let resolved) = state {
                EmbedRungView(resolved: resolved, model: model, args: args)
            }
        case .status:
            PlayerStateView(state: state, isOnline: container.network.isOnline,
                            thumbnailURL: args.thumbnailURL) { Task { await model.retry() } }
        }
    }
}
