import AVFoundation
import SwiftUI

/// The pure scrub-bar arithmetic (B4 task 3). A live/unknown duration is 0 or NaN on a real
/// `AVPlayerItem`; both clamp to 0 rather than divide. `time` clamps into `[0, duration]` so a
/// released thumb never seeks past the end.
enum ShortsScrub {
    static func progress(current: Double, duration: Double) -> Double {
        guard duration.isFinite, duration > 0, current.isFinite else { return 0 }
        return min(max(current / duration, 0), 1)
    }

    static func time(progress: Double, duration: Double) -> Double {
        guard duration.isFinite, duration > 0 else { return 0 }
        return min(max(progress, 0), 1) * duration
    }
}

/// The Shorts chrome over the Task 2 stage (Android `item_shorts_page.xml`, brief §9.2-§9.3): the
/// full-screen tap target, the play/pause flash, the bottom channel/title block, the rail
/// (Like / Share / audio language / captions) and the scrub bar. Back and the kebab live in
/// `ShortsScreen`, over every branch. Exactly ONE `AVPlayer` is ever touched here --
/// `model.currentPlayer`, handed off by `PlayerHostView` -- and it can be nil on the first render,
/// so every control no-ops on nil.
struct ShortsOverlay: View {
    let model: PlayerViewModel
    let args: PlayerArgs

    @Environment(\.container) private var container
    @Environment(\.router) private var router
    @Environment(\.widthClass) private var widthClass
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var observer = TimeObserver()
    @State private var progress = 0.0
    @State private var currentSeconds = 0.0
    @State private var editing = false
    /// `.pause`: playback is paused, the play glyph stays until resumed. `.play`: just resumed,
    /// the pause glyph fades after 600 ms (`ShortsPageViewHolder.kt:124-154`).
    @State private var indicator: Indicator?
    @State private var flashTask: Task<Void, Never>?
    @State private var isFavorite = false
    @State private var bannerMessage: BannerMessage?

    private enum Indicator { case play, pause }

    /// Android `ShortsPageViewHolder.kt:44-60`: no channel row for a blank/nil channel -- an "@"
    /// with nothing after it is worse than no row.
    static func showsChannelRow(channelName: String?) -> Bool {
        !(channelName ?? "").trimmingCharacters(in: .whitespaces).isEmpty
    }

    var body: some View {
        ZStack {
            // ABOVE the host (transport is off, nothing under it wants the tap), BELOW the chrome
            // (so a rail tap is not also a pause).
            Color.clear
                .contentShape(Rectangle())
                .onTapGesture(perform: togglePlayPause)
                .accessibilityLabel(String(localized: "player_action_play_pause"))
                .accessibilityAddTraits(.isButton)

            if let indicator {
                Image(systemName: indicator == .pause ? "play.fill" : "pause.fill")
                    .font(.system(size: 44))
                    .foregroundStyle(.white)
                    .frame(width: 112, height: 112)
                    .background(.black.opacity(0.5), in: Circle())
                    .transition(reduceMotion ? .identity : .opacity)
                    .allowsHitTesting(false)
                    .accessibilityIdentifier("shorts.playPauseIndicator")
            }

            VStack(spacing: Spacing.sm) {
                Spacer()
                HStack(alignment: .bottom, spacing: Spacing.md(widthClass)) {
                    channelAndTitle
                    Spacer(minLength: 0)
                    rail
                }
                scrubBar
            }
            .padding(Spacing.md(widthClass))
            // Reserve the top row for `ShortsScreen`'s Back / kebab.
            .padding(.top, 44)
        }
        .transientBanner($bannerMessage)
        .task { isFavorite = container.favorites.isFavorite(args.videoId) }
        // The periodic observer is an OWNER: (re)start on every player hand-off, cancel on
        // disappear -- the same start/store/cancel discipline `CaptionOverlay` uses.
        .onChange(of: model.currentPlayer, initial: true) { _, player in
            observer.stop()
            guard let player else { return }
            observer.start(player: player) { time in
                guard !editing else { return }
                currentSeconds = time.seconds
                progress = ShortsScrub.progress(current: time.seconds, duration: duration)
            }
        }
        .onDisappear {
            observer.stop()
            flashTask?.cancel()
        }
    }

    private var duration: Double {
        model.currentPlayer?.currentItem?.duration.seconds ?? 0
    }

    private func togglePlayPause() {
        guard let player = model.currentPlayer else { return }
        flashTask?.cancel()
        if player.rate > 0 {
            player.pause()
            withAnimation(reduceMotion ? nil : .easeOut(duration: 0.25)) { indicator = .pause }
        } else {
            player.play()
            withAnimation(reduceMotion ? nil : .easeOut(duration: 0.25)) { indicator = .play }
            flashTask = Task {
                try? await Task.sleep(for: .milliseconds(600))
                guard !Task.isCancelled else { return }
                withAnimation(reduceMotion ? nil : .easeOut(duration: 0.25)) { indicator = nil }
            }
        }
    }

    // MARK: - Bottom block

    @ViewBuilder
    private var channelAndTitle: some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            if Self.showsChannelRow(channelName: args.channelName), let channelName = args.channelName {
                Button(action: openChannel) {
                    HStack(spacing: Spacing.sm) {
                        RemoteImage(url: args.channelAvatarURL)
                            .frame(width: 36, height: 36)
                            .clipShape(Circle())
                        Text(String(format: String(localized: "shorts_channel_handle"), channelName))
                            .font(TypeScale.itemMeta.weight(.semibold))
                            .foregroundStyle(.white)
                    }
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("shorts.channelHandle")
            }
            if let title = args.title {
                Text(title)
                    .font(TypeScale.itemMeta)
                    .foregroundStyle(.white)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                    .accessibilityIdentifier("shorts.title")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Spacing.sm)
        // Plan §6.11: overlay text on a scrim, never on bare video.
        .background(
            LinearGradient(colors: [.clear, .black.opacity(0.6)], startPoint: .top, endPoint: .bottom),
            in: RoundedRectangle(cornerRadius: Radius.thumbnail)
        )
    }

    /// Android `:631-642`: a tap goes to the channel only when the short carries an id.
    private func openChannel() {
        guard let channelId = args.channelId, !channelId.isEmpty else { return }
        router.push(.channel(id: channelId, name: args.channelName, avatarURL: args.channelAvatarURL))
    }

    // MARK: - Rail

    private var rail: some View {
        VStack(spacing: Spacing.md(widthClass)) {
            Button(action: toggleFavorite) {
                railGlyph(isFavorite ? "heart.fill" : "heart")
            }
            .accessibilityIdentifier("shorts.likeButton")
            .accessibilityLabel(String(localized: "shorts_like_cd"))
            .accessibilityValue(String(localized: isFavorite ? "player_action_favorited" : "player_action_not_favorited"))
            .accessibilityAddTraits(isFavorite ? [.isSelected] : [])

            ShareLink(item: args.shareURL, subject: Text(args.title ?? args.videoId)) {
                railGlyph("square.and.arrow.up")
            }
            .accessibilityIdentifier("shorts.shareButton")
            .accessibilityLabel(String(localized: "shorts_share_cd"))

            // Hides itself at <=1 option (ruling 52) -- nothing here writes its visibility.
            AudioLanguageMenu(model: model)
            CaptionsMenu(model: model, tracks: PlayerScreen.captionTracks(model.state))
        }
    }

    private func railGlyph(_ systemImage: String) -> some View {
        Image(systemName: systemImage)
            .foregroundStyle(.white)
            .frame(width: 44, height: 44)
            .background(.black.opacity(0.55), in: Circle())
    }

    private func toggleFavorite() {
        let result = FavoriteToggle.perform(item: args.contentItem, wasFavorite: isFavorite, store: container.favorites)
        isFavorite = result.isFavorite
        bannerMessage = result.banner
    }

    // MARK: - Scrub bar

    private var scrubBar: some View {
        Slider(value: $progress, in: 0...1) { isEditing in
            editing = isEditing
            // Seek only on release; while dragging the observer stops writing `progress`.
            guard !isEditing, let player = model.currentPlayer else { return }
            let seconds = ShortsScrub.time(progress: progress, duration: duration)
            player.seek(to: CMTime(seconds: seconds, preferredTimescale: 600))
        }
        .tint(.white)
        .accessibilityIdentifier("shorts.scrubber")
        .accessibilityLabel(String(localized: "shorts_seek_cd"))
        .accessibilityValue(String(format: String(localized: "player_duration_minutes_seconds"),
                                   Int64(currentSeconds) / 60, Int64(currentSeconds) % 60))
    }
}
