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
    @State private var showReport = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            PopGestureEnabler()
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
        // B4 CF-B1-9 / Plan C task 3: `contentSubType = SHORT`, `parentType = CHANNEL` when the
        // short carries its channel id (`ChannelShortsTabFragment.kt:89-101`).
        .sheet(isPresented: $showReport) {
            ReportSheet(context: ReportContext(
                targetType: .video, targetId: args.videoId,
                parentType: args.channelId.map { _ in .channel }, parentId: args.channelId, contentSubType: .short)) {
                bannerMessage = BannerMessage(text: String(localized: "report_success"))
            }
        }
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
                // Both overlays sit INSIDE the 9:16 column (Task 4, iPad): attached after either
                // `maxWidth` frame below (both are flexible frames that take the proposal, not the
                // child's size) they spanned the whole content area, so the rail floated on the
                // letterbox 100 pt from the video. On iPhone the column is the screen.
                // Task 4 live check 4: the CC pick did nothing here -- only `PlayerScreen` mounted the
                // overlay. Same I8 gate; the clearance is Android's `shorts_caption_bottom_clearance`
                // (200 dp) so cues sit above the channel/title/scrub block, not under it.
                .overlay {
                    if let resolved = state.resolved,
                       let track = PlayerScreen.activeCaptionTrack(selected: model.selectedCaptionTrack,
                                                                   tracks: PlayerScreen.captionTracks(state)) {
                        CaptionOverlay(model: model, track: track, userAgent: resolved.userAgent, bottomClearance: 200)
                    }
                }
                .overlay { ShortsOverlay(model: model, args: args) }
                .frame(maxWidth: Size.playerMaxWidth(widthClass))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        // Rung 3, mirroring PlayerScreen: its own surface, no AVPlayer. CF-B3-1: 9:16 is the ONE
        // parameter that differs. `safeAreaPadding` keeps the caption clear of the Back/kebab row.
        case .embed:
            if case .embed(let resolved) = state {
                EmbedRungView(resolved: resolved, model: model, args: args, aspectRatio: 9.0 / 16.0)
                    .safeAreaPadding(.top, 44 + Spacing.md(widthClass))
            }
        case .status:
            // Task 4: on the black surface, with the dark palette's text -- the card used to paint
            // the app's light `Color.background` over the stage.
            PlayerStateView(state: state, isOnline: container.network.isOnline,
                            thumbnailURL: args.thumbnailURL, background: .black) { Task { await model.retry() } }
                .environment(\.colorScheme, .dark)
                .safeAreaPadding(.top, 44 + Spacing.md(widthClass))
        }
    }

    private var backButton: some View {
        Button { dismiss() } label: {
            Image(systemName: "chevron.backward").shortsGlyph()
        }
        .accessibilityIdentifier("shorts.back")
        .accessibilityLabel(String(localized: "back"))
        // M8: Back before the kebab WITHIN this row. Relative to the stage the order is unverified --
        // sort priority does not cross ZStack layers and XCUITest enumeration ignores it (CF-B4-10).
        .accessibilitySortPriority(2)
    }

    /// `menu_shorts_kebab.xml` (ruling 53): Quality -- the ONE ladder, `QualityOption` (CF-B1-5)
    /// -- and Report: the one `ReportSheet` (Plan C task 3), as a SHORT with its channel parent.
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
                showReport = true
            } label: {
                Label(String(localized: "report_content"), systemImage: "flag")
            }
            .accessibilityIdentifier("shorts.kebab.report")
        } label: {
            Image(systemName: "ellipsis").shortsGlyph()
        }
        .accessibilityIdentifier("shorts.kebab.button")
        .accessibilityLabel(String(localized: "shorts_more_options_cd"))
        .accessibilitySortPriority(1)
    }
}

/// Fork D hides the navigation bar, and UIKit disables `interactivePopGestureRecognizer` whenever
/// the bar is hidden -- Task 4 measured the edge swipe doing nothing. Re-enable it for THIS screen
/// only (installed on appear, restored on disappear), begin only when there is something to pop.
/// Scoped on purpose: a global `UINavigationController` override would touch every stack for the
/// one screen that hides its bar.
private struct PopGestureEnabler: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> Controller { Controller() }
    func updateUIViewController(_ controller: Controller, context: Context) {}

    final class Controller: UIViewController, UIGestureRecognizerDelegate {
        private weak var recognizer: UIGestureRecognizer?
        private weak var originalDelegate: UIGestureRecognizerDelegate?

        override func viewWillAppear(_ animated: Bool) {
            super.viewWillAppear(animated)
            guard recognizer == nil, let pop = navigationController?.interactivePopGestureRecognizer else { return }
            recognizer = pop
            originalDelegate = pop.delegate
            pop.delegate = self
            pop.isEnabled = true
        }

        override func viewDidDisappear(_ animated: Bool) {
            super.viewDidDisappear(animated)
            restore()
        }

        /// M8 (final review): a controller torn down without `viewDidDisappear` (a stack reset)
        /// must not leave the pop gesture delegated to a dead object.
        deinit { MainActor.assumeIsolated { restore() } }

        private func restore() {
            recognizer?.delegate = originalDelegate
            recognizer = nil
        }

        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            (navigationController?.viewControllers.count ?? 0) > 1
        }
    }
}
