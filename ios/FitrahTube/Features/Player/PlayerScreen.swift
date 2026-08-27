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
            let vm = PlayerViewModel(resolver: Self.resolver(container: container), catalog: container.catalog,
                                      favorites: container.favorites, settings: container.settings, args: args)
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
        // Task 9: the one announcement `PlayerStateView` can't make itself, since `.rung2Progressive`
        // never mounts it (Task 7's identity note keeps both playable rungs in the switch case
        // below). Fires on every transition INTO rung 2, including a later re-resolve while already
        // on rung 2 (a fresh stream swap is worth announcing again).
        .onChange(of: model?.state) { _, newValue in
            guard case .rung2Progressive = newValue else { return }
            AccessibilityNotification.Announcement(String(localized: "player_standard_quality")).post()
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
        case .ready, .rung2Progressive:
            let isRung1 = Self.isRung1(state)
            // Task 8: metadata panel below the player, toolbar between the two (Android's
            // action-row placement) -- ONE branch still, per Task 7's identity note above: the
            // `PlayerHostView` call below is unconditional in both cases, so its view identity
            // (and the live `AVPlayer` it wraps) survives a `.ready` <-> `.rung2Progressive`
            // demotion exactly as before. Only the surrounding layout (full-bleed ZStack -> video
            // box + scrolling content below) changed.
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    ZStack(alignment: .topTrailing) {
                        PlayerHostView(state: state, quality: model.selectedQuality, model: model)
                        if let selected = model.selectedCaptionTrack {
                            CaptionOverlay(model: model, track: selected)
                        }
                        VStack(alignment: .trailing, spacing: 8) {
                            // Quality control on rung 1 only -- rung 2 (progressive, single
                            // rendition) hides it entirely per spec §10 ("Rung 2 hides the
                            // control") and shows the persistent pill instead.
                            if isRung1 {
                                qualityMenu(model)
                            } else {
                                rung2Pill
                            }
                            AudioLanguageMenu(model: model)
                            captionsMenu(model, tracks: Self.captionTracks(state))
                        }
                        .padding()
                    }
                    .aspectRatio(16.0 / 9.0, contentMode: .fit)
                    .background(Color.black)

                    PlayerToolbar(args: args)
                    PlayerMetadataView(args: args)
                }
            }
            .background(Color.background.ignoresSafeArea())
        // Task 9: every non-playable state (`.idle`/`.loading`/`.error`/`.contentUnavailable`/
        // `.cooldown`/`.recoveryExhausted`) shares ONE `PlayerStateView` mount -- see that type's
        // doc comment for why one shared view identity (not a case per state) is what makes the
        // cross-dissolve animation and transition announcements work.
        default:
            PlayerStateView(state: state, isOnline: container.network.isOnline, thumbnailURL: args.thumbnailURL) {
                Task { await model.retry() }
            }
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
        .accessibilityLabel(String(localized: "player_quality_dialog_title"))
    }

    /// The captions toggle (spec §10 Captions paragraph; plan §6.5) -- own SwiftUI control, same
    /// reasoning as `qualityMenu`/`AudioLanguageMenu`. Lists `tracks` (InnerTubeKit's
    /// `captionTracks`, auto-generated ones only -- manual tracks ride AVKit's stock subtitle
    /// menu on their own) plus an Off option; hidden entirely when `tracks` is empty. Auto-enables
    /// the first track once, on this control's first appearance, when VoiceOver's closed-captioning
    /// setting is already on (`captionsAutoEnableApplied` guards it from re-firing after a manual
    /// "Off" pick on a later re-render/re-resolve within the same session).
    @ViewBuilder
    private func captionsMenu(_ model: PlayerViewModel, tracks: [CaptionTrack]) -> some View {
        if !tracks.isEmpty {
            Menu {
                Section(String(localized: "player_captions_dialog_title")) {
                    Button {
                        model.selectedCaptionTrack = nil
                    } label: {
                        if model.selectedCaptionTrack == nil {
                            Label(String(localized: "player_captions_off"), systemImage: "checkmark")
                        } else {
                            Text(String(localized: "player_captions_off"))
                        }
                    }
                    .accessibilityIdentifier("player.captionsOption.off")

                    ForEach(tracks, id: \.languageCode) { track in
                        Button {
                            model.selectedCaptionTrack = track
                        } label: {
                            if model.selectedCaptionTrack == track {
                                Label(Self.captionLabel(track), systemImage: "checkmark")
                            } else {
                                Text(Self.captionLabel(track))
                            }
                        }
                        .accessibilityIdentifier("player.captionsOption.\(track.languageCode)")
                    }
                }
            } label: {
                Image(systemName: "captions.bubble")
                    .foregroundStyle(.white)
                    .padding(10)
                    .background(.black.opacity(0.55), in: Circle())
            }
            .accessibilityIdentifier("player.captionsMenu.button")
            .accessibilityLabel(String(localized: "player_action_captions"))
            .task(id: tracks.map(\.languageCode)) {
                guard !model.captionsAutoEnableApplied else { return }
                model.captionsAutoEnableApplied = true
                if UIAccessibility.isClosedCaptioningEnabled, let first = tracks.first {
                    model.selectedCaptionTrack = first
                }
            }
        }
    }

    /// `"%1$@ (Auto-generated)"` for `kind=asr` tracks, else the bare language name.
    private static func captionLabel(_ track: CaptionTrack) -> String {
        track.isAutoGenerated
            ? String(format: String(localized: "player_captions_auto_generated"), track.languageName)
            : track.languageName
    }

    /// Only `.hls` carries `captionTracks` (rung-2 progressive has none), so this doubles as the
    /// "hide the captions toggle on rung 2" rule. Fix round 1 F1: `captionTracks` mixes manual and
    /// auto-generated (`kind=asr`) tracks -- manual ones already ride AVKit's stock subtitle menu,
    /// so this menu (and its `tracks.first` auto-enable pick, `captionsMenu` below) must only ever
    /// see the auto-generated ones. Not `private`: `CaptionsProviderTests` pins the filter
    /// directly, same pattern as `PlayerHostView.player(for:replacing:)`.
    static func captionTracks(_ state: StreamState) -> [CaptionTrack] {
        guard case .ready(let resolved) = state, case .hls(_, _, _, let tracks) = resolved.stream else { return [] }
        return tracks.filter(\.isAutoGenerated)
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
        Text(String(localized: "player_standard_quality"))
            .font(.caption)
            .foregroundStyle(.white)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(.black.opacity(0.55), in: Capsule())
            .accessibilityIdentifier("player.rung2Pill")
    }

    /// `-fitrah-fake-player`: the UI-test/screenshot hook -- swaps the real InnerTubeKit-backed
    /// resolver for `FixturePlayerResolver` (below), which resolves to the bundled local fixture
    /// clip, so the screenshot rig never touches the network. Compiled out of Release with every
    /// other debug hook in this app (`FitrahTubeApp.swift`).
    private static func resolver(container: AppContainer) -> any StreamResolving {
        #if DEBUG
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
        if ProcessInfo.processInfo.arguments.contains("-fitrah-fake-player") {
            return FixturePlayerResolver()
        }
        #endif
        return LiveStreamResolver(resolver: container.resolver)
    }
}

#if DEBUG
/// Same bundled fixture as `FixturePlayerResolver`, tagged `.hls` instead of `.progressive` so
/// `PlayerViewModel.map` resolves to `.ready`, not `.rung2Progressive` (where the quality control
/// is hidden by contract) -- lets `testPlayerQualityMenu` screenshot the menu with no real HLS
/// asset and no network. `AVPlayer` plays the local file identically either way; only this app's
/// own state-mapping tag differs.
private struct FixtureHLSPlayerResolver: StreamResolving {
    func resolve(_ videoId: String, purpose: Purpose, sourceChannelId: String?, forceRefresh: Bool) async throws -> Resolved {
        guard let url = Bundle.main.url(forResource: "player-fixture", withExtension: "mp4") else {
            throw ExtractionError.transport("player-fixture.mp4 missing from the app bundle")
        }
        return Resolved(stream: .hls(url: url, isLive: false, audioOnlyURL: nil, captionTracks: []),
                         client: .visionos, userAgent: "FitrahTube/DebugFixture", resolvedAt: Date(), expiresAt: nil)
    }
}

/// Resolves every video id to `player-fixture.mp4` (`ios/FitrahTube/Resources/`), a 2s local clip
/// generated with AVFoundation for exactly this purpose (no ffmpeg on the build machine, no network
/// dependency in the UI-test rig). `.progressive`, not `.hls`: the fixture is a plain local file,
/// which AVPlayer plays natively without an HLS manifest -- `PlayerScreen` routes `.rung2Progressive`
/// through the same `PlayerHostView` as `.ready`, so this still exercises the real playback path.
private struct FixturePlayerResolver: StreamResolving {
    func resolve(_ videoId: String, purpose: Purpose, sourceChannelId: String?, forceRefresh: Bool) async throws -> Resolved {
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
    func resolve(_ videoId: String, purpose: Purpose, sourceChannelId: String?, forceRefresh: Bool) async throws -> Resolved {
        throw ExtractionError.transport("fixture error")
    }
}

/// Task 9 screenshot rig: `.unavailable` maps to `.contentUnavailable` (ruling 14: one
/// non-retryable "not playable" surface for every terminal not-available reason).
private struct FixtureUnavailableResolver: StreamResolving {
    func resolve(_ videoId: String, purpose: Purpose, sourceChannelId: String?, forceRefresh: Bool) async throws -> Resolved {
        throw ExtractionError.unavailable(videoId: videoId)
    }
}

/// Task 9 screenshot rig: `.cooldown(until:)` maps straight through to `StreamState.cooldown`.
/// 45s out so the captured frame always shows a non-trivial countdown.
private struct FixtureCooldownResolver: StreamResolving {
    func resolve(_ videoId: String, purpose: Purpose, sourceChannelId: String?, forceRefresh: Bool) async throws -> Resolved {
        throw ExtractionError.cooldown(until: Date().addingTimeInterval(45))
    }
}

#Preview {
    PlayerScreen(args: PlayerArgs(videoId: "preview"))
        .environment(\.container, .sharedFake)
}
#endif
