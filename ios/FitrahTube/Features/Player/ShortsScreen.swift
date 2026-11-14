import SwiftUI

/// The Shorts playback surface (spec §10 "Shorts", plan §6.8; B4 Tasks 2-3). ONE `PlayerViewModel`,
/// ONE `PlayerHostView` in `.shorts` presentation. Rulings 50/51 ("no feed, no pager", plan B4
/// reconciliation 1) are REVERSED by the owner's request of 2026-11-14: a Short opened from a channel's
/// Shorts tab swipes through that tab, up to the next (older) Short and down to the previous, over the
/// player's own queue (`ChannelQueueSource`, `ShortsSwipe`). Still no global feed, and still one live
/// player: a swipe is `play(at:)` on the same model, never a second host. Everything the ladder already
/// does (recovery, Safe Mode, quality ceiling, audio session) arrives through the shared view model;
/// this file adds the 9:16 stage, the portrait lock, and -- because the navigation bar is hidden
/// (fork D) -- its own Back and kebab over every branch. The rest of the chrome is `ShortsOverlay`,
/// on the playable branch only.
struct ShortsScreen: View {
    let args: PlayerArgs

    @Environment(\.container) private var container
    @Environment(\.widthClass) private var widthClass
    @Environment(\.dismiss) private var dismiss
    @State private var model: PlayerViewModel?
    @State private var bannerMessage: BannerMessage?
    /// Captured when the sheet opens: the lock screen's or AirPods' next while it is up moves `model.args` on, and
    /// the report must stay on the Short the user was looking at (as `PlayerToolbar.saveSheetArgs`).
    @State private var report: ReportContext?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            PopGestureEnabler()
            if let model {
                stage(model.state, model: model)
                    // Over both branches: a Short that will not play can be swiped past. The whole area, letterbox
                    // included (iPad), takes the swipe; a tap still reaches the overlay's play/pause beneath.
                    .contentShape(Rectangle())
                    .gesture(DragGesture(minimumDistance: 20).onEnded { value in
                        guard let offset = ShortsSwipe.offset(value.predictedEndTranslation) else { return }
                        Self.swipe(model, by: offset)
                    })
            } else {
                ProgressView().tint(.white)
            }
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
        .sheet(item: $report) { context in
            ReportSheet(context: context) {
                bannerMessage = BannerMessage(text: String(localized: "report_success"))
            }
        }
        .statusBarHidden(true)                              // ruling 57
        .toolbar(.hidden, for: .navigationBar)              // fork D; Back is `backButton` below
        .onAppear { OrientationLock.lockPortrait(); model?.resumeUpgrades() }
        .onDisappear { OrientationLock.release(); model?.stopUpgrade() }
        .task {
            guard model == nil else { return }
            // The Shorts tab's queue; the VM ignores it without `queueChannelId` (a deep link: one Short, as before).
            let vm = PlayerViewModel(resolver: PlayerScreen.resolver(container: container),
                                     settings: container.settings, args: args,
                                     queueSource: ChannelQueueSource(browse: container.browse, channelTitle: args.channelName,
                                                                     shorts: true),
                                     hd: PlayerScreen.hdUpgrade(container: container))
            model = vm
            await vm.open()
        }
        .rungAnnouncements(model: model)
    }

    /// Which surface a state renders: the player for both playable rungs, the status card for the
    /// rest -- `StreamState.isPlayable`, the same two-way split as `PlayerScreen.stateView`.
    @ViewBuilder
    private func stage(_ state: StreamState, model: PlayerViewModel) -> some View {
        // ONE branch for both playable rungs -- Global Constraints, and the same identity rule
        // PlayerScreen.stateView carries. Rung-specific chrome differs INSIDE it.
        if state.isPlayable {
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
                    if let resolved = state.resolved, let track = CaptionsMenu.overlayTrack(model) {
                        CaptionOverlay(model: model, track: track, userAgent: resolved.userAgent, bottomClearance: 200)
                    }
                }
                // owner issue 1: the adopted title. One overlay per Short (owner 2026-11-14 swipe): its state -- the heart's
                // favorite, the play/pause glyph, the scrub -- is that Short's. A swipe shows `.loading`, which remounts
                // this branch anyway; the id is for the hops that do not: the lock screen's next (`skip(by:)`, no loading
                // card) and a resolve that lands before the card's first frame.
                .overlay { ShortsOverlay(model: model, args: model.args).id(model.args.videoId) }
                .frame(maxWidth: Size.playerMaxWidth(widthClass))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            // Task 4: on the black surface, with the dark palette's text -- the card used to paint
            // the app's light `Color.background` over the stage.
            PlayerStateView(state: state, isOnline: container.network.isOnline,
                            thumbnailURL: model.args.thumbnailURL, background: .black) { PlayerScreen.retryTapped(model) }
                .modifier(ShortsSwipeActions(model: model))
                .environment(\.colorScheme, .dark)
                .safeAreaPadding(.top, 44 + Spacing.md(widthClass))
        }
    }

    /// A swipe or its VoiceOver action: `PlayerViewModel.swipe`, which pages past the loaded end. A user's Play, as
    /// `PlayerScreen.playUpNext`, only when there is somewhere to go.
    static func swipe(_ model: PlayerViewModel, by offset: Int) {
        guard canSwipe(model, by: offset) else { return }
        BackgroundPlaybackController.userAskedToPlay()
        model.heldByOpen = false
        Task { await model.swipe(by: offset) }
    }

    static func canSwipe(_ model: PlayerViewModel, by offset: Int) -> Bool {
        model.queueNeighbour(offset) != nil || (offset > 0 && model.queue.cursor != nil)
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
            // Only while the stream has renditions to choose from (`qualityOptions`).
            if !model.qualityOptions.isEmpty {
                Section(String(localized: "player_quality_dialog_title")) {
                    ForEach(model.qualityOptions, id: \.self) { option in
                        Button {
                            model.selectedQuality = option
                        } label: {
                            if option == model.selectedQuality {
                                Label(option.label, systemImage: "checkmark")
                            } else {
                                Text(option.label)
                            }
                        }
                        .accessibilityIdentifier("shorts.qualityOption.\(option.id)")
                    }
                }
            }
            Button {
                // The MODEL's args: after a swipe this screen's own `args` are still the first Short's.
                report = model.args.shortsReportContext
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

extension PlayerArgs {
    /// B4 CF-B1-9 / Plan C task 3: `contentSubType = SHORT`, `parentType = CHANNEL` when the short
    /// carries its channel id (`ChannelShortsTabFragment.kt:89-101`).
    var shortsReportContext: ReportContext {
        ReportContext(targetType: .video, targetId: videoId,
                      parentType: channelId.map { _ in .channel }, parentId: channelId, contentSubType: .short)
    }
}

/// The swipe's VoiceOver path (owner 2026-11-14): a vertical swipe is VoiceOver's own gesture. On the overlay and on
/// the loading or error card, so a Short that will not play can be passed as with a swipe.
struct ShortsSwipeActions: ViewModifier {
    let model: PlayerViewModel

    func body(content: Content) -> some View {
        content.accessibilityActions {
            if ShortsScreen.canSwipe(model, by: 1) {
                Button(String(localized: "player_action_next")) { ShortsScreen.swipe(model, by: 1) }
            }
            if ShortsScreen.canSwipe(model, by: -1) {
                Button(String(localized: "player_action_previous")) { ShortsScreen.swipe(model, by: -1) }
            }
        }
    }
}

/// Owner 2026-11-14: which way a drag on the Shorts stage moves through the queue. Up is the next (older)
/// Short, down the previous; mostly sideways is nobody's swipe here (the scrubber's drag, Back's edge swipe).
enum ShortsSwipe {
    static func offset(_ translation: CGSize) -> Int? {
        // 80 pt: past a tap's jitter and a scrub's wobble, well short of a flick.
        guard abs(translation.height) >= 80, abs(translation.height) > abs(translation.width) else { return nil }
        return translation.height < 0 ? 1 : -1
    }
}
