import Foundation
import InnerTubeKit
import SwiftUI
import UIKit

/// The SwiftUI screen for `Route.player(PlayerArgs)` (spec §10). Phase-1 VM-creation pattern:
/// `@Environment(\.container)` read inside `.task`, `@State private var model: PlayerViewModel?`
/// held across body re-renders (`FavoritesView`/`SearchView`).
struct PlayerScreen: View {
    let args: PlayerArgs

    @Environment(\.container) private var container
    @Environment(\.widthClass) private var widthClass
    /// Task 10 (spec §6.11): compact vertical size class == iPhone landscape -- the signal that
    /// hides the metadata panel below the player. iPad landscape stays `.regular` (a much taller
    /// window even in landscape), so this never fires there.
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.router) private var router
    @State private var model: PlayerViewModel?
    /// B5 Task 3 (reconciliation note 3): the fullscreen exit control's latch. Suppresses the
    /// auto-enter until the device rotates out of the fullscreen orientation; no timers.
    @State private var userExitedFullscreen = false
    /// Same flag name Android uses (`PlayerFragment.kt:3473-3484`).
    @AppStorage("fullscreen_zoom_hint_shown") private var zoomHintShown = false
    /// Phase 3 Task 5 (reconciliation note 3): the per-open `offlineAllowed` answer — nil until
    /// the ONE fetch per player open lands, and the Save button stays hidden until then
    /// (fail-closed). Held here, not in the toolbar: a fullscreen toggle rebuilds the toolbar
    /// and must not refetch.
    @State private var saveGate: GateAnswer?
    /// The remote kill-switch (`RemoteConfig.isDownloadsEnabled`), read per open.
    @State private var saveEnabled = true

    var body: some View {
        let fullscreen = model.map(isFullscreen) ?? false
        Group {
            if let model {
                stateView(model.state, model: model, fullscreen: fullscreen)
            } else {
                ProgressView()
            }
        }
        .statusBarHidden(fullscreen)
        // `.tabBar` here, not (only) in the shell: a tab-bar hide takes effect on the PUSHED view
        // inside the tab's `NavigationStack`; the shell's `.toolbar(.hidden, for: .tabBar)` outside
        // the `TabView` is inert on this SDK (verified on iPhone 17 / iOS 26.3, B5 Task 3).
        .toolbar(fullscreen ? .hidden : .visible, for: .navigationBar, .tabBar)
        // Ruling 42/CF-B2-10: the shell reads this to hide the navigation rail (regular) --
        // `Router.swift:33-34`, `MainShellView.swift:62`. B5 is the plan its doc comment was
        // waiting for. `avKitFullscreen` is folded in so the iPad's stock
        // AVKit fullscreen also clears the rail.
        .onChange(of: fullscreen || model?.avKitFullscreen == true, initial: true) { _, isFS in
            router.isFullscreen = isFS
        }
        // Not optional: Android restores system UI unconditionally in `onDestroyView`
        // (`PlayerFragment.kt:822-829`); without this, popping the player while fullscreen leaves
        // the app with no tab bar.
        .onDisappear { router.isFullscreen = false }
        // Reconciliation note 3: rotating out of the fullscreen orientation re-arms the auto-enter.
        .onChange(of: verticalSizeClass) { _, new in if new != .compact { userExitedFullscreen = false } }
        .onChange(of: fullscreen, initial: true) { _, isFS in
            guard isFS, !zoomHintShown else { return }
            zoomHintShown = true
            model?.banner = BannerMessage(text: String(localized: "player_fullscreen_zoom_hint"))
        }
        // Task 8 (spec §10 Chromecast): `CastController` publishes, the MOUNTED screen reacts —
        // the controller holds no ViewModel, so a session that starts or ends with no player on
        // screen simply has no reader. `.onChange` (not `.task(id:)`) so only real transitions
        // fire: no spurious hand-back on mount, where there was never a session to come back from.
        .onChange(of: container.castController.isSessionActive) { _, active in
            guard let model else { return }
            if active {
                Task { await startCasting(model) }
            } else {
                model.resumeAfterCast(at: container.castController.lastStreamPosition)
            }
        }
        // Spec §10: "observe the load result and surface 'Couldn't play on {device}' on failure
        // (Android swallows it)". Consumed and cleared here so a second failure on the same device
        // still announces itself.
        .onChange(of: container.castController.lastLoadFailureDevice) { _, device in
            guard let device else { return }
            model?.banner = BannerMessage(
                text: String(format: String(localized: "cast_error_format"), device))
            container.castController.lastLoadFailureDevice = nil
        }
        .task {
            guard model == nil else { return }
            // Task 7 (reconciliation note 5): an offline open builds the SAME VM over
            // `OfflineResolver` — no network, no player fork — and passes NO queue source, so
            // Up Next/queue paging are disabled by construction (`loadQueue` guards on it).
            let vm: PlayerViewModel
            if let offlineItemId = args.offlineItemId {
                vm = PlayerViewModel(resolver: OfflineResolver(store: container.offlineStore,
                                                               itemId: offlineItemId,
                                                               base: container.offlineBase),
                                     settings: container.settings, args: args)
            } else {
                vm = PlayerViewModel(resolver: Self.resolver(container: container),
                                     settings: container.settings, args: args,
                                     queueSource: Self.queueSource(container: container))
            }
            model = vm
            await vm.open()
            #if DEBUG
            // Task 9 screenshot rig: jumps straight to `.recoveryExhausted` after a real fixture
            // resolve (see `PlayerViewModel.debugForceRecoveryExhausted`) -- the budget machine
            // itself is exhaustively unit-tested in `PlaybackRecoveryTests` and would otherwise need
            // a genuinely failing `AVPlayerItem` to drive for real.
            if LaunchArguments.debug.contains("-fitrah-fake-player-recovery-exhausted") {
                vm.debugForceRecoveryExhausted()
            }
            #endif
        }
        // Phase 3 Task 5: one gate fetch per player open, re-run when the queue advances to a new
        // video (`PlayerViewModel.swapArgs` mutates `args` in place — the `.task(id:)` lesson from
        // this file's favorite seed). Reset FIRST so the button is hidden while the answer for the
        // new video is in flight.
        .task(id: model?.args.videoId ?? args.videoId) {
            saveGate = nil
            // Task 7: an offline open performs NO backend fetch — the Save slot (and every other
            // save affordance) is hidden by the offline flag, so the answer would go unread.
            if args.offlineItemId != nil { return }
            // Task 5 review fold-in 1: `.task(id:)` cancels the old task on advance but does NOT
            // prevent its in-flight continuation from resuming — without these guards a stale
            // `.allowed` fetched for video A could land AFTER the reset for video B ran (the
            // gate fetch can take ~15 s against a slow backend), showing Save on a video the
            // gate never affirmed. Fail-closed means checking before every assignment.
            let enabled = await container.innerTube.remoteConfig.current().isDownloadsEnabled
            guard !Task.isCancelled else { return }
            saveEnabled = enabled
            let answer = await container.offlineGate.answer(model?.args.videoId ?? args.videoId)
            guard !Task.isCancelled else { return }
            saveGate = answer
        }
        // Task 9 + B3 task 4: the two announcements `PlayerStateView` can't make itself, since
        // neither `.rung2Progressive` nor `.embed` mounts it (Task 7's identity note keeps both
        // playable rungs in the switch case below; `.embed` has its own branch). ONE site for both
        // -- `EmbedRungView` deliberately posts nothing of its own. `.onChange` fires only on a real
        // transition, so entering `.embed` announces exactly once.
        .rungAnnouncements(state: model?.state, isOnline: container.network.isOnline)
    }

    /// Session start/resume (spec §10): a FRESH resolve, then load with the local position, then
    /// pause local. Order matters — the pause happens only once there is something to load, so a
    /// video that turns out to be uncastable keeps playing on the phone under its banner.
    private func startCasting(_ model: PlayerViewModel) async {
        let cast = container.castController
        guard let media = await model.castMedia() else {
            // Nothing castable: the embed rung (never castable — the no-hand-off directive) or a
            // resolve that did not come back. Same outcome for the user as a receiver refusing the
            // load, so it gets the same banner rather than copy of its own.
            cast.reportLoadFailure()
            return
        }
        model.pauseForCast()
        cast.load(media, at: model.currentTime)
    }

    /// What a transition INTO `state` says out loud, or nil for silence. Pure, so
    /// `PlayerScreenEmbedTests` can pin the offline case that `.onChange` can't be asked about.
    static func transitionAnnouncement(for state: StreamState?, isOnline: Bool) -> String? {
        switch state {
        case .rung2Progressive:
            // M1 (B1 final review): the announcement is a sentence about what just happened
            // ("Playing in standard quality", spec §6.6 Transitions row, verbatim); the PILL is a
            // standing label ("Standard quality (360p)"). Reading the pill's noun phrase out as an
            // event was the wrong register -- two different strings, deliberately.
            return String(localized: "player_announce_standard_quality")
        case .embed:
            // Spec §6.6 Transitions: a native->embed demotion is NEVER silent. The caption above the
            // frame is the visible half; this is the audible one, and it is the same sentence
            // ("Playing in YouTube's player") rather than a second string, because the caption
            // already IS a statement of what just happened.
            //
            // I2 (Task 4 review): ONLINE only. Offline, `.embed` never mounts `EmbedRungView` at all
            // -- the branch below routes it to the offline card -- so this announced a player that
            // is not on screen, over a card that says the opposite.
            return isOnline ? String(localized: "player_embed_caption") : nil
        default:
            return nil
        }
    }

    /// Ruling 42's rule with this screen's inputs (`PlayerFullscreen.isActive`). Compact HEIGHT is
    /// the iPhone-landscape signal (Task 10's existing rule); iPad landscape stays regular.
    private func isFullscreen(_ model: PlayerViewModel) -> Bool {
        PlayerFullscreen.isActive(widthClass: widthClass, deviceIsLandscape: verticalSizeClass == .compact,
                                  videoIsPortrait: model.videoIsPortrait, userExited: userExitedFullscreen)
    }

    @ViewBuilder
    private func stateView(_ state: StreamState, model: PlayerViewModel, fullscreen: Bool) -> some View {
        switch state {
        // Task 7: ONE branch for both playable rungs, deliberately. Two `case`s each building their
        // own `PlayerHostView` gave SwiftUI two different view identities, so a mid-play demotion
        // (`.ready` -> `.rung2Progressive`) dismantled the host and rebuilt it from scratch --
        // dropping the `AVPlayer` whose `currentTime()` is the only thing carrying the position
        // over (`PlayerHostView.player(for:replacing:)`). Sharing the branch keeps one host across
        // the demotion; the rung-specific chrome differs inside it.
        case .ready(let resolved), .rung2Progressive(let resolved):
            let isRung1 = Self.isRung1(state)
            let tracks = Self.captionTracks(state)
            // B5 Task 3 / ruling C: fullscreen is a MODIFIER change on this one tree, never a second
            // `PlayerHostView` placement and never a `.fullScreenCover` -- both would be a second
            // view identity, which drops the `AVPlayer` (Task 7's note above).
            // Task 8: metadata panel below the player, toolbar between the two (Android's
            // action-row placement) -- ONE branch still, per Task 7's identity note above: the
            // `PlayerHostView` call below is unconditional in both cases, so its view identity
            // (and the live `AVPlayer` it wraps) survives a `.ready` <-> `.rung2Progressive`
            // demotion exactly as before. Only the surrounding layout (full-bleed ZStack -> video
            // box + scrolling content below) changed.
            // B5 Task 3: the reader is a PERMANENT wrapper (no conditional tree), only there to hand
            // the fullscreen box the container's own aspect ratio -- `aspectRatio(nil)` would fit
            // the HOST's ideal ratio, not the screen's, and a `ScrollView` proposes no height.
            GeometryReader { geo in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    ZStack(alignment: .topTrailing) {
                        PlayerHostView(state: state, quality: model.selectedQuality,
                                       audioOnly: model.audioOnly, model: model, isFullscreen: fullscreen)
                        // I8 (B1 final review): a mid-play rung-2 demotion empties `tracks` and
                        // hides the captions menu -- but the selection survives (session-only
                        // state, deliberately), so the overlay used to keep rendering cues with no
                        // menu left to turn them off. Render only a selection the CURRENT menu
                        // still offers. I4: the resolve's own User-Agent goes on the cue fetch.
                        if let selected = Self.activeCaptionTrack(selected: model.selectedCaptionTrack, tracks: tracks) {
                            CaptionOverlay(model: model, track: selected, userAgent: resolved.userAgent)
                        }
                        VStack(alignment: .trailing, spacing: 8) {
                            // B5 Task 3: FIRST in this same column so it never overlaps the
                            // controls below, which stay available in fullscreen. Ruling 45's first
                            // step; forces no orientation (reconciliation note 3).
                            if fullscreen {
                                fullscreenExitButton
                            }
                            // While audio-only there is no video rendition to cap, no subtitle
                            // track and no alternate audible group on an m4a item -- every one of
                            // these controls would be inert, so none of them is shown. The
                            // audio-only button itself stays, so the user can get back out.
                            if model.audioOnly {
                                // Fix round 1, C1: a status PILL, stacked with the rest of this
                                // column, never a fill over the video surface. The surface is
                                // AVKit's own -- an opaque full-bleed status sat on top of the
                                // stock transport, so while audio-only there was no way to pause.
                                // `allowsHitTesting(false)`: chrome, not a control.
                                statusPill(String(localized: "player_status_audio_only"),
                                           identifier: "player.audioOnlyPill")
                                    .allowsHitTesting(false)
                            } else {
                                // Quality control on rung 1 only -- rung 2 (progressive, single
                                // rendition) hides it entirely per spec §10 ("Rung 2 hides the
                                // control") and shows the persistent pill instead.
                                // Task 7: neither offline -- a saved file has exactly one
                                // rendition, and the rung-2 pill is a statement about the
                                // extraction ladder, which an offline open never walked.
                                if !model.isOfflinePlayback {
                                    if isRung1 {
                                        qualityMenu(model)
                                    } else {
                                        rung2Pill
                                    }
                                }
                                AudioLanguageMenu(model: model)
                                CaptionsMenu(model: model, tracks: tracks)
                            }
                            if PlayerViewModel.audioOnlyAvailable(for: state) {
                                audioOnlyButton(model)
                            }
                        }
                        .padding()
                        // The reader below ignores the safe area in fullscreen, so this column
                        // (exit control first) re-applies the insets to clear the island/corners.
                        .padding(fullscreen ? geo.safeAreaInsets : EdgeInsets())
                    }
                    .aspectRatio(fullscreen && geo.size.height > 0 ? geo.size.width / geo.size.height : 16.0 / 9.0,
                                 contentMode: .fit)
                    .frame(maxWidth: .infinity)
                    .frame(height: fullscreen ? geo.size.height : nil)
                    .background(Color.black)
                    .overlay { seekFeedback(model) }
                    // Reduce Motion: an instant show/hide, not a fade (spec §14).
                    .animation(reduceMotion ? nil : .easeInOut(duration: 0.15), value: model.seekFeedback)
                    // `.contain` first: an identifier on a bare container is inherited by every child
                    // element and would overwrite `player.fullscreenExit` / the quality menu's.
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("player.videoBox")

                    if !fullscreen {

                    // Task 10 (spec §6.11): compact-height landscape (iPhone landscape) hides the
                    // METADATA panel only -- the toolbar (favorite/share/report) stays available,
                    // which is what the comment always claimed and I1 (B1 final review) found the
                    // code never did. Favorite is the one action with no other route in from the
                    // player, so hiding it in landscape lost it entirely.
                    // B5: `model.args`, never this screen's own `args` -- after an advance the
                    // screen's value is only the INITIAL video.
                    PlayerToolbar(args: model.args, saveGate: saveGate, saveEnabled: saveEnabled,
                                  isOfflinePlayback: model.isOfflinePlayback)
                    if verticalSizeClass != .compact {
                        PlayerMetadataView(args: model.args)
                    }
                    // Ruling 33: the whole section, header included, is absent when there is nothing
                    // queued. Android renders the header over nothing (`fragment_player.xml:608-625`,
                    // defect-adjacent). Below the toolbar, outside the metadata guard, so the
                    // non-fullscreen landscape column keeps it.
                    if !model.queue.upcoming.isEmpty {
                        Text(String(localized: "player_up_next_header"))
                            .font(TypeScale.sectionTitle).fontWeight(.bold)
                            .foregroundStyle(Color.textPrimary)
                            .padding(.horizontal, Spacing.md(widthClass)).padding(.top, Spacing.md(widthClass))
                            .accessibilityIdentifier("player.upNext.header")
                            #if DEBUG
                            // CF-B5-h: the VM's end-of-item / advance counters, readable by XCUITest.
                            .accessibilityValue("playToEnd=\(model.playToEndCalls) advance=\(model.advanceCalls)")
                            #endif
                        upNextList(model)
                    }
                    }   // !fullscreen
                }
                // Task 10 (`ios-app-design.md` §11 `content_max_width`): the ONE screen where a
                // tablet content column isn't full width -- nil below the sw600 threshold, 1200/
                // 1600 pt above it (`Size.playerMaxWidth`). The outer `.frame(maxWidth: .infinity)`
                // centers this narrower column within the full scroll width.
                .frame(maxWidth: fullscreen ? nil : Size.playerMaxWidth(widthClass))
                .frame(maxWidth: .infinity)
            }
            .scrollDisabled(fullscreen)
            .background(Color.background.ignoresSafeArea())
            }   // GeometryReader
            .ignoresSafeArea(edges: fullscreen ? .all : [])
            .transientBanner(Bindable(model).banner)
        // B3 task 4: rung 3 gets its OWN branch -- legitimately, because it is a different playback
        // surface with no `AVPlayer` to preserve across a transition, which is the only thing the
        // shared branch above exists to protect. It never mounts `PlayerHostView`.
        case .embed(let resolved):
            // Offline gate, same rule the `.idle`/`.loading`/`.error` states already follow (I2, B1
            // final review): a `WKWebView` pointed at `youtube-nocookie.com` with no network renders
            // a black frame under a caption claiming something is playing. One offline surface with
            // a Retry, not a lie plus a spinner. `PlayerStateCopy.map` answers the offline copy for
            // `.embed` and `preconditionFailure`s for it online, where this branch owns the screen.
            if container.network.isOnline {
                EmbedRungView(resolved: resolved, model: model, args: model.args)
            } else {
                PlayerStateView(state: state, isOnline: false, thumbnailURL: model.args.thumbnailURL) {
                    Task { await model.retry() }
                }
            }
        // Task 9: every non-playable state (`.idle`/`.loading`/`.error`/`.contentUnavailable`/
        // `.cooldown`/`.recoveryExhausted`) shares ONE `PlayerStateView` mount -- see that type's
        // doc comment for why one shared view identity (not a case per state) is what makes the
        // cross-dissolve animation and transition announcements work.
        default:
            // No secondary action and no confirmation dialog: owner directive 2026-08-27 bans every
            // redirect and hand-off to YouTube, so a terminal state offers Retry or nothing. Nothing
            // in this app calls `UIApplication.open` with a YouTube URL, in any Safe Mode setting.
            PlayerStateView(state: state, isOnline: container.network.isOnline,
                            thumbnailURL: model.args.thumbnailURL) {
                Task { await model.retry() }
            }
        }
    }

    /// Up Next (B5): one column on compact, two on regular/large (`PlayerFragment.kt:895-908`),
    /// collapsing to one at `.accessibility1+`. `VideoRow`/`VideoGridCell` unmodified -- they
    /// already carry `videoAccessibilityLabel`, `DurationChip` and `Format` (rulings 37/48).
    /// `subtitle: channelTitle` is the same override Favorites uses.
    @ViewBuilder
    private func upNextList(_ model: PlayerViewModel) -> some View {
        let columns = GridRules.columns(widthClass == .compact ? 1 : 2, dynamicTypeSize: dynamicTypeSize)
        // Keyed and tapped by absolute queue index, not `item.id` (Cubic P2, same class as
        // 23b3c325): a playlist can repeat a video id, which collapsed duplicate rows to one
        // SwiftUI identity and sent a tap on the later duplicate to the first occurrence. The
        // a11y identifier keeps the video id AFTER the unique index so XCUITest can still
        // address a row by video.
        if columns == 1 {
            ForEach(Array(zip(model.queue.upcoming.indices, model.queue.upcoming)), id: \.0) { index, item in
                VideoRow(item: item, subtitle: item.channelTitle) { Task { await model.play(at: index) } }
                    .accessibilityIdentifier("player.upNext.row.\(index).\(item.id)")
            }
        } else {
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: GridRules.cardGap(widthClass)),
                                     count: columns), spacing: GridRules.cardGap(widthClass)) {
                ForEach(Array(zip(model.queue.upcoming.indices, model.queue.upcoming)), id: \.0) { index, item in
                    VideoGridCell(item: item, subtitle: item.channelTitle) { Task { await model.play(at: index) } }
                        .accessibilityIdentifier("player.upNext.row.\(index).\(item.id)")
                }
            }
            .padding(.horizontal, Spacing.md(widthClass))
        }
    }

    /// The FitrahTube toolbar's own SwiftUI quality control -- NOT AVKit chrome. On this SDK
    /// (Xcode 26.3 / iOS 26.2) `AVPlayerViewController`'s stock transport exposes no separate
    /// accessibility elements for XCUITest to anchor on (task-3-report.md), so this button/menu is
    /// what `testPlayerQualityMenu` drives, via `player.qualityMenu.button` /
    /// `player.qualityOption.*` -- never an AVKit identifier.
    @ViewBuilder
    private func qualityMenu(_ model: PlayerViewModel) -> some View {
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
                    .accessibilityIdentifier("player.qualityOption.\(option)")
                }
            }
        } label: {
            Image(systemName: "slider.horizontal.3")
                .foregroundStyle(.white)
                .padding(10)
                .background(.black.opacity(0.55), in: Circle())
        }
        .accessibilityIdentifier("player.qualityMenu.button")
        // Task 10 (spec §6.11 "label + value on custom controls"): label = the control's role
        // ("Quality"), value = the live pick ("Auto") -- same split `CategoryPill` already uses
        // (`Components.swift`). `QualityOption.label` is deliberately unlocalized (its own doc
        // comment), matching the brief's own "Quality, Auto" example verbatim.
        .accessibilityLabel(String(localized: "player_quality_selector"))
        .accessibilityValue(model.selectedQuality.label)
    }

    /// The audio-only toggle (ruling 34, spec §10). Offered only while the resolved stream really
    /// carries an itag 140 rendition (`PlayerViewModel.audioOnlyAvailable`) -- rung 2's muxed
    /// progressive has no separate audio, and neither does `.embed`/live.
    /// `.accessibilityAddTraits(.isSelected)`, not an `accessibilityValue`: no existing string key
    /// carries generic on/off wording (the favorite button uses two DISTINCT labels because it
    /// changes meaning; this one does not), and B2 adds no new keys. The selected trait is what
    /// VoiceOver reads for a toggle button.
    private func audioOnlyButton(_ model: PlayerViewModel) -> some View {
        Button {
            model.audioOnly.toggle()
        } label: {
            Image(systemName: model.audioOnly ? "headphones.circle.fill" : "headphones")
                .foregroundStyle(.white)
                .padding(10)
                .background(.black.opacity(0.55), in: Circle())
        }
        .accessibilityIdentifier("player.audioOnly.button")
        .accessibilityLabel(String(localized: "player_audio_only_label"))
        .accessibilityAddTraits(model.audioOnly ? [.isSelected] : [])
    }

    /// Only `.hls` carries `captionTracks` (rung-2 progressive has none), so this doubles as the
    /// "hide the captions toggle on rung 2" rule. Fix round 1 F1: `captionTracks` mixes manual and
    /// auto-generated (`kind=asr`) tracks -- manual ones already ride AVKit's stock subtitle menu,
    /// so this menu (and its `tracks.first` auto-enable pick, `CaptionsMenu`) must only ever
    /// see the auto-generated ones. Not `private`: `CaptionsProviderTests` pins the filter
    /// directly, same pattern as `PlayerHostView.player(for:replacing:)`.
    static func captionTracks(_ state: StreamState) -> [CaptionTrack] {
        guard case .ready(let resolved) = state, case .hls(_, _, _, let tracks) = resolved.stream else { return [] }
        return tracks.filter(\.isAutoGenerated)
    }

    /// I8: the selected track, but only while the captions menu still lists it. A rung-2
    /// demotion (or any re-resolve to a stream with different tracks) empties/changes the list
    /// while `selectedCaptionTrack` -- session state that deliberately survives a re-resolve --
    /// still points at the old one; rendering that would leave cues on screen with no menu to
    /// dismiss them. Pure, so `PlayerScreenCaptionsTests` drives it directly.
    static func activeCaptionTrack(selected: CaptionTrack?, tracks: [CaptionTrack]) -> CaptionTrack? {
        guard let selected, tracks.contains(selected) else { return nil }
        return selected
    }

    private static func isRung1(_ state: StreamState) -> Bool {
        if case .ready = state { return true }
        return false
    }

    /// B5 Task 3: the fullscreen exit control -- ≥44 pt tap target on the same scrim chrome as
    /// `qualityMenu`, never on bare video (spec §14). Action: the latch only.
    private var fullscreenExitButton: some View {
        Button {
            userExitedFullscreen = true
        } label: {
            Image(systemName: "arrow.down.right.and.arrow.up.left")
                .foregroundStyle(.white)
                .frame(minWidth: 44, minHeight: 44)
                .background(.black.opacity(0.55), in: Circle())
        }
        .accessibilityIdentifier("player.fullscreenExit")
        .accessibilityLabel(String(localized: "player_action_fullscreen"))
    }

    /// B5 Task 3: the ±10 s flash. Chrome for a gesture VoiceOver users do not perform (they use
    /// AVKit's ±10 s buttons), so hidden from both hit-testing and accessibility. The SF Symbols
    /// carry the "10" and mirror themselves; the HStack is pinned LTR because the zones are spatial.
    @ViewBuilder
    private func seekFeedback(_ model: PlayerViewModel) -> some View {
        if let zone = model.seekFeedback {
            HStack {
                if zone == .forward { Spacer() }
                Image(systemName: zone == .back ? "gobackward.10" : "goforward.10")
                    .font(.system(size: 44))
                    .foregroundStyle(.white)
                    .padding(Spacing.lg(widthClass))
                if zone == .back { Spacer() }
            }
            .environment(\.layoutDirection, .leftToRight)
            .transition(.opacity)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
            .accessibilityIdentifier("player.seekFeedback")
        }
    }

    /// The persistent rung-2 badge (spec §10): rung 2 is a single 360p progressive rendition, so
    /// instead of a quality control the user gets a standing statement of what they're watching --
    /// which is also the visible half of "never silently swap a native stream" (the demotion is a
    /// distinct `StreamState`, and Task 9's state-view announcements read it out).
    private var rung2Pill: some View {
        statusPill(String(localized: "player_standard_quality"), identifier: "player.rung2Pill")
    }

    /// The shared status-pill chrome (rung-2 badge, audio-only status). Sized to its own text and
    /// only translucent, so whatever it sits over -- here, AVKit's stock transport -- stays visible
    /// and usable underneath (fix round 1, C1).
    private func statusPill(_ text: String, identifier: String) -> some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(.white)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(.black.opacity(0.55), in: Capsule())
            .accessibilityIdentifier(identifier)
    }

    /// `-fitrah-fake-player`: the UI-test/screenshot hook -- swaps the real InnerTubeKit-backed
    /// resolver for `FixturePlayerResolver` (below), which resolves to the bundled local fixture
    /// clip, so the screenshot rig never touches the network. Compiled out of Release with every
    /// other debug hook in this app (`FitrahTubeApp.swift`).
    /// Not private (B4): `ShortsScreen` walks the same ladder -- one copy, so a future fixture hook
    /// lands on both screens.
    static func resolver(container: AppContainer) -> any StreamResolving {
        #if DEBUG
        if LaunchArguments.debug.contains("-fitrah-fake-player-audio-only") {
            return FixtureAudioOnlyResolver()
        }
        if LaunchArguments.debug.contains("-fitrah-fake-player-hls") {
            return FixtureHLSPlayerResolver()
        }
        // Task 9 screenshot rig: each of these throws the `ExtractionError` `PlayerViewModel.map`
        // maps onto the state it's named for, so `ScreenshotTests` can capture every row of spec
        // §6.6's state table with no network access.
        if LaunchArguments.debug.contains("-fitrah-fake-player-error") {
            return FixtureErrorResolver()
        }
        if LaunchArguments.debug.contains("-fitrah-fake-player-unavailable") {
            return FixtureUnavailableResolver()
        }
        if LaunchArguments.debug.contains("-fitrah-fake-player-cooldown") {
            return FixtureCooldownResolver()
        }
        if LaunchArguments.debug.contains("-fitrah-fake-player-embed") {
            return FixtureEmbedResolver()
        }
        if LaunchArguments.debug.contains("-fitrah-fake-player") {
            return FixturePlayerResolver()
        }
        #endif
        // The `#if DEBUG` fixture resolvers above are returned UNWRAPPED -- the screenshot rig must
        // never be rate limited.
        return RateLimitedResolver(wrapping: LiveStreamResolver(resolver: container.resolver),
                                   rateLimiter: container.innerTube.rateLimiter,
                                   clock: container.innerTube.clock)
    }

    /// B5: the playlist queue behind Up Next -- same `#if DEBUG` ladder shape as `resolver(container:)`.
    /// `-fitrah-fake-player-queue` serves ~8 fixture items; `-dead` makes three of them unplayable
    /// under `FixturePlayerResolver`'s dead-id rule (the auto-skip capture).
    static func queueSource(container: AppContainer) -> any PlaylistQueueSource {
        #if DEBUG
        if LaunchArguments.debug.contains("-fitrah-fake-player-queue-dead") {
            return FixtureQueueSource(deadItems: true)
        }
        if LaunchArguments.debug.contains("-fitrah-fake-player-queue") {
            return FixtureQueueSource(deadItems: false)
        }
        #endif
        return LivePlaylistQueueSource(client: container.innerTube.browse)
    }
}

#if DEBUG
/// Same bundled fixture as `FixturePlayerResolver`, tagged `.hls` instead of `.progressive` so
/// `PlayerViewModel.map` resolves to `.ready`, not `.rung2Progressive` (where the quality control
/// is hidden by contract) -- lets `testPlayerQualityMenu` screenshot the menu with no real HLS
/// asset and no network. `AVPlayer` plays the local file identically either way; only this app's
/// own state-mapping tag differs.
private struct FixtureHLSPlayerResolver: StreamResolving {
    func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind,
                 sourceChannelId: String?, forceRefresh: Bool) async throws -> Resolved {
        guard let url = Bundle.main.url(forResource: "player-fixture", withExtension: "mp4") else {
            throw ExtractionError.transport("player-fixture.mp4 missing from the app bundle")
        }
        return Resolved(stream: .hls(url: url, isLive: false, audioOnlyURL: nil, captionTracks: []),
                         client: .visionos, userAgent: "FitrahTube/DebugFixture", resolvedAt: Date(), expiresAt: nil)
    }
}

/// B2 task 3 screenshot rig: the same bundled fixture as `FixtureHLSPlayerResolver`, but with a
/// non-nil `audioOnlyURL` (the same local clip -- AVPlayer plays it either way), which is what
/// `PlayerViewModel.audioOnlyAvailable` gates the audio-only toggle on. No real itag 140 stream
/// (and no network) is needed to capture the audio-only surface.
private struct FixtureAudioOnlyResolver: StreamResolving {
    func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind,
                 sourceChannelId: String?, forceRefresh: Bool) async throws -> Resolved {
        guard let url = Bundle.main.url(forResource: "player-fixture", withExtension: "mp4") else {
            throw ExtractionError.transport("player-fixture.mp4 missing from the app bundle")
        }
        return Resolved(stream: .hls(url: url, isLive: false, audioOnlyURL: url, captionTracks: []),
                         client: .visionos, userAgent: "FitrahTube/DebugFixture", resolvedAt: Date(), expiresAt: nil)
    }
}

/// Resolves every video id to `player-fixture.mp4` (`ios/FitrahTube/Resources/`), a 2s local clip
/// generated with AVFoundation for exactly this purpose (no ffmpeg on the build machine, no network
/// dependency in the UI-test rig). `.progressive`, not `.hls`: the fixture is a plain local file,
/// which AVPlayer plays natively without an HLS manifest -- `PlayerScreen` routes `.rung2Progressive`
/// through the same `PlayerHostView` as `.ready`, so this still exercises the real playback path.
private struct FixturePlayerResolver: StreamResolving {
    func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind,
                 sourceChannelId: String?, forceRefresh: Bool) async throws -> Resolved {
        // B5: `-fitrah-fake-player-queue-dead`'s unplayable ids.
        if videoId.hasPrefix("dead-") { throw ExtractionError.unavailable(videoId: videoId) }
        guard let url = Bundle.main.url(forResource: "player-fixture", withExtension: "mp4") else {
            throw ExtractionError.transport("player-fixture.mp4 missing from the app bundle")
        }
        return Resolved(stream: .progressive(url: url, label: "360p"), client: .visionos,
                         userAgent: "FitrahTube/DebugFixture", resolvedAt: Date(), expiresAt: nil)
    }
}

/// Task 9 screenshot rig: `.transport` maps to `.error(messageKey: "player_error_message")` --
/// the real error path a network failure takes, exercised here with no network at all.
private struct FixtureErrorResolver: StreamResolving {
    func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind,
                 sourceChannelId: String?, forceRefresh: Bool) async throws -> Resolved {
        throw ExtractionError.transport("fixture error")
    }
}

/// Task 9 screenshot rig: `.unavailable` maps to `.contentUnavailable` (ruling 14: one
/// non-retryable "not playable" surface for every terminal not-available reason).
private struct FixtureUnavailableResolver: StreamResolving {
    func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind,
                 sourceChannelId: String?, forceRefresh: Bool) async throws -> Resolved {
        throw ExtractionError.unavailable(videoId: videoId)
    }
}

/// Task 9 screenshot rig: `.cooldown(until:)` maps straight through to `StreamState.cooldown`.
/// 45s out so the captured frame always shows a non-trivial countdown.
private struct FixtureCooldownResolver: StreamResolving {
    func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind,
                 sourceChannelId: String?, forceRefresh: Bool) async throws -> Resolved {
        throw ExtractionError.cooldown(until: Date().addingTimeInterval(45))
    }
}

/// B3 task 2 screenshot rig: rung 3, the ladder's floor. `.embed` needs no network and no bundled
/// asset -- the video id is the whole stream.
private struct FixtureEmbedResolver: StreamResolving {
    func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind,
                 sourceChannelId: String?, forceRefresh: Bool) async throws -> Resolved {
        Resolved(stream: .embed(videoId: videoId), client: .web,
                 userAgent: "FitrahTube/DebugFixture", resolvedAt: Date(), expiresAt: nil)
    }
}

/// B5 screenshot rig: one page of eight fixture items. With `deadItems`, three ids carry the
/// `dead-` prefix that `FixturePlayerResolver` refuses, so the walk skips them.
private struct FixtureQueueSource: PlaylistQueueSource {
    let deadItems: Bool

    func page(playlistId: String, continuation: String?) async throws
        -> (items: [ContentItem], continuation: String?) {
        let ids = deadItems
            ? ["fixture-1", "dead-2", "dead-3", "dead-4", "fixture-5", "fixture-6", "fixture-7", "fixture-8"]
            : (1...8).map { "fixture-\($0)" }
        return (ids.enumerated().map { i, id in
            ContentItem(video: VideoItem(id: id, title: "Lecture \(i + 1): Tafsir of Surah Al-Kahf",
                                         channelName: "Fixture Channel", durationSeconds: 600 + i * 90,
                                         thumbnailURL: nil))
        }, nil)
    }
}

#Preview {
    PlayerScreen(args: PlayerArgs(videoId: "preview"))
        .environment(\.container, .sharedFake)
}
#endif
