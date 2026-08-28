import SwiftUI

/// The Shorts playback surface (spec §10 "Shorts", plan §6.8; B4 Tasks 2-3). ONE short, ONE
/// `PlayerViewModel`, ONE `PlayerHostView` in `.shorts` presentation -- no feed, no pager (plan B4
/// reconciliation 1 / ruling 51). Everything the ladder already does (recovery, Safe Mode, quality
/// ceiling, audio session) arrives through the shared view model; this file adds the 9:16 stage,
/// the portrait lock, and -- because the navigation bar is hidden (fork D) -- its own Back and kebab
/// over every branch. The rest of the chrome is `ShortsOverlay`, on the playable branch only.
struct ShortsScreen: View {
    let args: PlayerArgs

    @Environment(\.container) private var container
    @Environment(\.widthClass) private var widthClass
    @Environment(\.dismiss) private var dismiss
    @State private var model: PlayerViewModel?
    @State private var bannerMessage: BannerMessage?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let model { stage(model.state, model: model) } else { ProgressView().tint(.white) }
            VStack {
                HStack {
                    backButton
                    Spacer()
                    if let model { kebab(model) }
                }
                Spacer()
            }
            .padding(Spacing.md(widthClass))
        }
        .transientBanner($bannerMessage)
        .statusBarHidden(true)                              // ruling 57
        .toolbar(.hidden, for: .navigationBar)              // fork D; Back is `backButton` below
        .onAppear { OrientationLock.lockPortrait() }
        .onDisappear { OrientationLock.release() }
        .task {
            guard model == nil else { return }
            let vm = PlayerViewModel(resolver: PlayerScreen.resolver(container: container),
                                     settings: container.settings, args: args)
            model = vm
            await vm.open()
        }
        .rungAnnouncements(state: model?.state, isOnline: container.network.isOnline)
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
        // PlayerScreen.stateView carries. Rung-specific chrome differs INSIDE it.
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
                .overlay { ShortsOverlay(model: model, args: args) }
        // Rung 3, mirroring PlayerScreen: its own surface, no AVPlayer. CF-B3-1: 9:16 is the ONE
        // parameter that differs. `safeAreaPadding` keeps the caption clear of the Back/kebab row.
        case .embed:
            if case .embed(let resolved) = state {
                EmbedRungView(resolved: resolved, model: model, args: args, aspectRatio: 9.0 / 16.0)
                    .safeAreaPadding(.top, 44 + Spacing.md(widthClass))
            }
        case .status:
            PlayerStateView(state: state, isOnline: container.network.isOnline,
                            thumbnailURL: args.thumbnailURL) { Task { await model.retry() } }
                .safeAreaPadding(.top, 44 + Spacing.md(widthClass))
        }
    }

    private var backButton: some View {
        Button { dismiss() } label: {
            Image(systemName: "chevron.backward")
                .foregroundStyle(.white)
                .frame(width: 44, height: 44)
                .background(.black.opacity(0.55), in: Circle())
        }
        .accessibilityIdentifier("shorts.back")
        .accessibilityLabel(String(localized: "back"))
    }

    /// `menu_shorts_kebab.xml` (ruling 53): Quality -- the ONE ladder, `QualityOption` (CF-B1-5)
    /// -- and Report, which shows the same coming-soon banner `PlayerToolbar.reportButton` shows
    /// (CF-B1-9: Plan C wires one report flow, not two).
    private func kebab(_ model: PlayerViewModel) -> some View {
        Menu {
            Section(String(localized: "player_quality_dialog_title")) {
                ForEach(QualityOption.allCases, id: \.self) { option in
                    Button {
                        model.selectedQuality = option
                    } label: {
                        if option == model.selectedQuality {
                            Label(option.label, systemImage: "checkmark")
                        } else {
                            Text(option.label)
                        }
                    }
                    .accessibilityIdentifier("shorts.qualityOption.\(option)")
                }
            }
            Button {
                bannerMessage = BannerMessage(text: String(localized: "player_report_coming_soon"))
            } label: {
                Label(String(localized: "report_content"), systemImage: "flag")
            }
            .accessibilityIdentifier("shorts.kebab.report")
        } label: {
            Image(systemName: "ellipsis")
                .foregroundStyle(.white)
                .frame(width: 44, height: 44)
                .background(.black.opacity(0.55), in: Circle())
        }
        .accessibilityIdentifier("shorts.kebab.button")
        .accessibilityLabel(String(localized: "shorts_more_options_cd"))
    }
}
