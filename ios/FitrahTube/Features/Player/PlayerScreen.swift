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
    @State private var model: PlayerViewModel?

    var body: some View {
        Group {
            if let model {
                stateView(model.state, model: model)
            } else {
                ProgressView()
            }
        }
        .task {
            guard model == nil else { return }
            let vm = PlayerViewModel(resolver: Self.resolver(container: container),
                                      settings: container.settings, args: args,
                                      queueSource: Self.queueSource(container: container))
            model = vm
            await vm.open()
            #if DEBUG
            // Task 9 screenshot rig: jumps straight to `.recoveryExhausted` after a real fixture
            // resolve (see `PlayerViewModel.debugForceRecoveryExhausted`) -- the budget machine
            // itself is exhaustively unit-tested in `PlaybackRecoveryTests` and would otherwise need
            // a genuinely failing `AVPlayerItem` to drive for real.
            if ProcessInfo.processInfo.arguments.contains("-fitrah-fake-player-recovery-exhausted") {
                vm.debugForceRecoveryExhausted()
            }
            #endif
        }
        // Task 9 + B3 task 4: the two announcements `PlayerStateView` can't make itself, since
        // neither `.rung2Progressive` nor `.embed` mounts it (Task 7's identity note keeps both
        // playable rungs in the switch case below; `.embed` has its own branch). ONE site for both
        // -- `EmbedRungView` deliberately posts nothing of its own. `.onChange` fires only on a real
        // transition, so entering `.embed` announces exactly once.
        .rungAnnouncements(state: model?.state, isOnline: container.network.isOnline)
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

    @ViewBuilder
    private func stateView(_ state: StreamState, model: PlayerViewModel) -> some View {
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
            // Task 8: metadata panel below the player, toolbar between the two (Android's
            // action-row placement) -- ONE branch still, per Task 7's identity note above: the
            // `PlayerHostView` call below is unconditional in both cases, so its view identity
            // (and the live `AVPlayer` it wraps) survives a `.ready` <-> `.rung2Progressive`
            // demotion exactly as before. Only the surrounding layout (full-bleed ZStack -> video
            // box + scrolling content below) changed.
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    ZStack(alignment: .topTrailing) {
                        PlayerHostView(state: state, quality: model.selectedQuality,
                                       audioOnly: model.audioOnly, model: model)
                        // I8 (B1 final review): a mid-play rung-2 demotion empties `tracks` and
                        // hides the captions menu -- but the selection survives (session-only
                        // state, deliberately), so the overlay used to keep rendering cues with no
                        // menu left to turn them off. Render only a selection the CURRENT menu
                        // still offers. I4: the resolve's own User-Agent goes on the cue fetch.
                        if let selected = Self.activeCaptionTrack(selected: model.selectedCaptionTrack, tracks: tracks) {
                            CaptionOverlay(model: model, track: selected, userAgent: resolved.userAgent)
                        }
                        VStack(alignment: .trailing, spacing: 8) {
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
                                if isRung1 {
                                    qualityMenu(model)
                                } else {
                                    rung2Pill
                                }
                                AudioLanguageMenu(model: model)
                                CaptionsMenu(model: model, tracks: tracks)
                            }
                            if PlayerViewModel.audioOnlyAvailable(for: state) {
                                audioOnlyButton(model)
                            }
                        }
                        .padding()
                    }
                    .aspectRatio(16.0 / 9.0, contentMode: .fit)
                    .background(Color.black)

                    // Task 10 (spec §6.11): compact-height landscape (iPhone landscape) hides the
                    // METADATA panel only -- the toolbar (favorite/share/report) stays available,
                    // which is what the comment always claimed and I1 (B1 final review) found the
                    // code never did. Favorite is the one action with no other route in from the
                    // player, so hiding it in landscape lost it entirely.
                    // B5: `model.args`, never this screen's own `args` -- after an advance the
                    // screen's value is only the INITIAL video.
                    PlayerToolbar(args: model.args)
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
                        upNextList(model)
                    }
                }
                // Task 10 (`ios-app-design.md` §11 `content_max_width`): the ONE screen where a
                // tablet content column isn't full width -- nil below the sw600 threshold, 1200/
                // 1600 pt above it (`Size.playerMaxWidth`). The outer `.frame(maxWidth: .infinity)`
                // centers this narrower column within the full scroll width.
                .frame(maxWidth: Size.playerMaxWidth(widthClass))
                .frame(maxWidth: .infinity)
            }
            .background(Color.background.ignoresSafeArea())
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
        if columns == 1 {
            ForEach(Array(model.queue.upcoming), id: \.id) { item in
                VideoRow(item: item, subtitle: item.channelTitle) { Task { await model.play(id: item.id) } }
                    .accessibilityIdentifier("player.upNext.row.\(item.id)")
            }
        } else {
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: GridRules.cardGap(widthClass)),
                                     count: columns), spacing: GridRules.cardGap(widthClass)) {
                ForEach(Array(model.queue.upcoming), id: \.id) { item in
                    VideoGridCell(item: item, subtitle: item.channelTitle) { Task { await model.play(id: item.id) } }
                        .accessibilityIdentifier("player.upNext.row.\(item.id)")
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
        if ProcessInfo.processInfo.arguments.contains("-fitrah-fake-player-audio-only") {
            return FixtureAudioOnlyResolver()
        }
        if ProcessInfo.processInfo.arguments.contains("-fitrah-fake-player-hls") {
            return FixtureHLSPlayerResolver()
        }
        // Task 9 screenshot rig: each of these throws the `ExtractionError` `PlayerViewModel.map`
        // maps onto the state it's named for, so `ScreenshotTests` can capture every row of spec
        // §6.6's state table with no network access.
        if ProcessInfo.processInfo.arguments.contains("-fitrah-fake-player-error") {
            return FixtureErrorResolver()
        }
        if ProcessInfo.processInfo.arguments.contains("-fitrah-fake-player-unavailable") {
            return FixtureUnavailableResolver()
        }
        if ProcessInfo.processInfo.arguments.contains("-fitrah-fake-player-cooldown") {
            return FixtureCooldownResolver()
        }
        if ProcessInfo.processInfo.arguments.contains("-fitrah-fake-player-embed") {
            return FixtureEmbedResolver()
        }
        if ProcessInfo.processInfo.arguments.contains("-fitrah-fake-player") {
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
        if ProcessInfo.processInfo.arguments.contains("-fitrah-fake-player-queue-dead") {
            return FixtureQueueSource(deadItems: true)
        }
        if ProcessInfo.processInfo.arguments.contains("-fitrah-fake-player-queue") {
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
