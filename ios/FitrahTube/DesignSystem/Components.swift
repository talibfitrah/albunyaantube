import SwiftUI
import UIKit

// MARK: - Localization helpers (Home meta line, plural/substitution xcstrings keys)

/// Same pattern as `LocalizationTests.string(_:locale:_:)`: look the key up in the `.lproj`
/// bundle for `locale` (not `Bundle.main`, which only answers for the device's own preferred
/// language) and let `String(format:)` resolve `.xcstrings` plural/substitution variants.
private func localizedFormat(_ key: String, locale: Locale, _ args: CVarArg...) -> String {
    let format = Format.localizedBundle(for: locale).localizedString(forKey: key, value: nil, table: nil)
    return String(format: format, locale: locale, arguments: args)
}

/// Video meta line: `views • timeAgo[ • category]`, `" • "`-joined, each segment omitted when its
/// underlying value is nil (RULINGS.md contradiction #3: null view count omits the segment,
/// correcting Android's "0 views"). `includeCategory` is false for the tabs' `VideoRow`/
/// `VideoGridCell` (category shown as a separate chip there) and true for the Home `MediaCard`
/// (content-lists.md §5.6; shell-home.md "Video meta string").
private func videoMeta(_ item: ContentItem, locale: Locale, includeCategory: Bool) -> String {
    var parts: [String] = []
    if let views = item.viewCount {
        parts.append(localizedFormat("video_views", locale: locale,
                                      Format.compactCount(views, locale: locale), Int64(Format.pluralQuantity(views))))
    }
    if let days = item.uploadedDaysAgo {
        parts.append(Format.timeAgo(days: days, locale: locale))
    }
    if includeCategory, let category = item.category, !category.isEmpty {
        parts.append(category)
    }
    return parts.joined(separator: " • ")
}

private func videoAccessibilityLabel(_ item: ContentItem, locale: Locale) -> String {
    let duration = item.durationSeconds.map(Format.duration) ?? ""
    let views = item.viewCount.map {
        localizedFormat("video_views", locale: locale, Format.compactCount($0, locale: locale), Int64(Format.pluralQuantity($0)))
    } ?? ""
    let uploaded = item.uploadedDaysAgo.map { Format.timeAgo(days: $0, locale: locale) } ?? ""
    return localizedFormat("a11y_video_item", locale: locale, item.title, duration, views, uploaded)
}

// MARK: - RemoteImage

/// No third-party image library (ponytail ladder: `URLSession` + `URLCache` already do this).
/// One shared session with a 50 MB memory / 200 MB disk `URLCache` for the HTTP response cache,
/// plus an `NSCache<NSURL, UIImage>` so a re-appearing cell (list scroll, carousel reuse) skips
/// JPEG/PNG decoding, not just the network round trip. Placeholder = `Color.skeleton` per the
/// task's design-system instruction (also RULINGS.md contradiction #8: one skeleton token
/// everywhere, not Android's per-screen `surface_variant`/`shimmer_background` mix).
struct RemoteImage: View {
    let url: URL?
    var contentMode: ContentMode = .fill

    @State private var image: UIImage?

    init(url: URL?, contentMode: ContentMode = .fill) {
        self.url = url
        self.contentMode = contentMode
    }

    private static let cache = NSCache<NSURL, UIImage>()
    private static let session: URLSession = {
        let configuration = URLSessionConfiguration.default
        configuration.urlCache = URLCache(memoryCapacity: 50 * 1024 * 1024, diskCapacity: 200 * 1024 * 1024)
        return URLSession(configuration: configuration)
    }()

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().aspectRatio(contentMode: contentMode)
            } else {
                Color.skeleton
            }
        }
        .clipped()
        .task(id: url) { await load() }
    }

    private func load() async {
        guard let url else { image = nil; return }
        if let cached = Self.cache.object(forKey: url as NSURL) {
            image = cached
            return
        }
        image = nil
        // ponytail: decode happens on the calling (main) actor -- fine at card/thumbnail sizes
        // (max ~320 pt here); move to a background decode if profiling shows main-thread jank.
        guard let (data, _) = try? await Self.session.data(from: url), let decoded = UIImage(data: data) else { return }
        Self.cache.setObject(decoded, forKey: url as NSURL)
        image = decoded
    }
}

// MARK: - Duration / count chips

/// Home/list duration overlay: `home_duration_chip_bg` #CC000000, white text, r4
/// (shell-home.md "Duration chip"; content-lists.md §5.3/5.4).
struct DurationChip: View {
    let seconds: Int

    init(seconds: Int) { self.seconds = seconds }

    var body: some View {
        Text(Format.duration(seconds))
            .font(TypeScale.badge) // Tokens.swift: caption2 11 bold covers both Android's 10bold badge and 11bold duration sizes
            .foregroundStyle(.white)
            .padding(.horizontal, 6).padding(.vertical, 3) // home_duration_chip_padding_horizontal/vertical
            .background(Color.durationChip, in: RoundedRectangle(cornerRadius: Radius.chip))
    }
}

/// Playlist overlay chip: `home_video_count_chip_bg` (80% brand), `onBrand` text -- Android's
/// literal white fails AA on the dark-mode mint fill (shell-home.md "Playlist card"). Not a
/// brief-listed public type; a private detail of `MediaCard`'s playlist styling.
private struct PlaylistCountChip: View {
    let count: Int
    let locale: Locale

    var body: some View {
        Text(localizedFormat("video_count", locale: locale, Int64(count)))
            .font(TypeScale.badge)
            .foregroundStyle(Color.onBrand)
            .padding(.horizontal, 6).padding(.vertical, 3)
            .background(Color.videoCountChip, in: RoundedRectangle(cornerRadius: Radius.chip))
    }
}

/// Live/upcoming badge. `Badge(live/upcoming/duration)` in spec §7's component list is split into
/// this (live/upcoming only) plus the separate `DurationChip` type per the task brief's Interfaces
/// block, which is authoritative for the Swift API shape.
struct Badge: View {
    enum Kind { case live, upcoming }
    let kind: Kind

    init(_ kind: Kind) { self.kind = kind }

    var body: some View {
        Text(String(localized: String.LocalizationValue(kind == .live ? "live_badge" : "upcoming_badge")))
            .font(TypeScale.badge)
            .foregroundStyle(.white)
            .padding(.horizontal, 6).padding(.vertical, 3)
            .background(kind == .live ? Color.liveBadge : Color.upcomingBadge, in: RoundedRectangle(cornerRadius: Radius.chip))
    }
}

/// Inline category chip inside a row/cell: `surface_variant` bg, brand text, single line, not
/// interactive (content-lists.md §5.5).
struct CategoryChip: View {
    let text: String

    init(text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(TypeScale.caption)
            .foregroundStyle(Color.brand)
            .lineLimit(1)
            .padding(.horizontal, Spacing.sm).padding(.vertical, Spacing.xxs)
            .background(Color.surfaceVariant, in: RoundedRectangle(cornerRadius: Radius.chip))
    }
}

// MARK: - MediaCard (Home carousel)

/// Home carousel card for videos and playlists (r16, 16:9 thumb, fixed-height content block) --
/// spec §7 `MediaCard`; shell-home.md "Video card"/"Playlist card". Not used for channels, which
/// render as `HomeChannelItem` instead (not a card at all on Android).
struct MediaCard: View {
    let item: ContentItem
    let width: CGFloat
    let onTap: () -> Void
    @Environment(\.locale) private var locale

    init(item: ContentItem, width: CGFloat, onTap: @escaping () -> Void) {
        self.item = item
        self.width = width
        self.onTap = onTap
    }

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 0) {
                ZStack(alignment: item.type == .playlist ? .bottomLeading : .bottomTrailing) {
                    RemoteImage(url: item.thumbnailURL)
                    overlayChip.padding(Spacing.sm)
                }
                .frame(width: width, height: width * 9 / 16)
                .clipped()
                content
                    .padding(Spacing.sm)
                    .frame(width: width, height: 100, alignment: .topLeading) // home_card_content_height, every bucket
            }
            .background(Color.homeCard)
            .clipShape(RoundedRectangle(cornerRadius: Radius.card))
            .shadow(color: .black.opacity(0.12), radius: 2, x: 0, y: 1) // home_card_elevation 2dp -> spec "shadows sm 2"
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabel)
    }

    @ViewBuilder private var overlayChip: some View {
        switch item.type {
        case .video:
            if let seconds = item.durationSeconds { DurationChip(seconds: seconds) }
        case .playlist:
            if let count = item.itemCount { PlaylistCountChip(count: count, locale: locale) }
        case .channel:
            EmptyView()
        }
    }

    @ViewBuilder private var content: some View {
        switch item.type {
        case .video:
            VStack(alignment: .leading, spacing: Spacing.xs) {
                Text(item.title).font(TypeScale.itemTitle).foregroundStyle(Color.textPrimary).lineLimit(2)
                Text(videoMeta(item, locale: locale, includeCategory: true))
                    .font(TypeScale.itemMeta).foregroundStyle(Color.textSecondary).lineLimit(2)
            }
        case .playlist:
            VStack(alignment: .leading, spacing: Spacing.xs) {
                Text(item.title).font(TypeScale.itemTitle).foregroundStyle(Color.textPrimary).lineLimit(2)
                // RULINGS.md #17: channelName <- category bug fixed -- prefer channelTitle, fall
                // back to category only when channelTitle is nil.
                if let subtitle = item.channelTitle ?? item.category {
                    Text(subtitle).font(TypeScale.itemMeta).foregroundStyle(Color.textSecondary).lineLimit(1)
                }
            }
        case .channel:
            EmptyView()
        }
    }

    private var accessibilityLabel: String {
        switch item.type {
        case .video:
            videoAccessibilityLabel(item, locale: locale)
        case .playlist:
            item.itemCount.map { localizedFormat("a11y_playlist_item", locale: locale, item.title, Int64($0)) } ?? item.title
        case .channel:
            item.title
        }
    }
}

// MARK: - VideoRow (item_video_list.xml)

/// Full-width video row: 140 pt 16:9 thumbnail, 16 pt bold title (content-lists.md §5.3).
struct VideoRow: View {
    let item: ContentItem
    let onTap: () -> Void
    @Environment(\.widthClass) private var widthClass
    @Environment(\.locale) private var locale

    init(item: ContentItem, onTap: @escaping () -> Void) {
        self.item = item
        self.onTap = onTap
    }

    var body: some View {
        Button(action: onTap) {
            HStack(alignment: .top, spacing: 0) {
                ZStack(alignment: .bottomTrailing) {
                    RemoteImage(url: item.thumbnailURL)
                        .frame(width: 140, height: 140 * 9 / 16)
                    if let seconds = item.durationSeconds { DurationChip(seconds: seconds).padding(Spacing.xs) }
                }
                .frame(width: 140, height: 140 * 9 / 16)
                .clipShape(RoundedRectangle(cornerRadius: Radius.homeThumbnail))
                VStack(alignment: .leading, spacing: Spacing.xs) {
                    Text(item.title).font(TypeScale.subtitle).fontWeight(.bold)
                        .foregroundStyle(Color.textPrimary).lineLimit(2)
                    Text(videoMeta(item, locale: locale, includeCategory: false))
                        .font(TypeScale.itemMeta).foregroundStyle(Color.textSecondary).lineLimit(2)
                }
                .padding(.leading, Spacing.md(widthClass))
                Spacer(minLength: 0)
            }
            .padding(.horizontal, Spacing.md(widthClass))
            .padding(.vertical, Spacing.sm)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(videoAccessibilityLabel(item, locale: locale))
    }
}

// MARK: - VideoGridCell (item_video_grid.xml, the Videos tab)

/// Grid cell: full-width 16:9 thumbnail, fixed-height content block (title always reserves 2
/// lines' worth of space), category chip below the block (content-lists.md §5.4).
struct VideoGridCell: View {
    let item: ContentItem
    let onTap: () -> Void
    @Environment(\.widthClass) private var widthClass
    @Environment(\.locale) private var locale

    init(item: ContentItem, onTap: @escaping () -> Void) {
        self.item = item
        self.onTap = onTap
    }

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 0) {
                ZStack(alignment: .bottomTrailing) {
                    RemoteImage(url: item.thumbnailURL)
                    if let seconds = item.durationSeconds { DurationChip(seconds: seconds).padding(Spacing.xs) }
                }
                .aspectRatio(16.0 / 9.0, contentMode: .fit)
                .frame(maxWidth: .infinity)
                .clipShape(RoundedRectangle(cornerRadius: Radius.homeThumbnail))
                VStack(alignment: .leading, spacing: Spacing.xs) {
                    Text(item.title).font(TypeScale.itemTitle).fontWeight(.bold)
                        .foregroundStyle(Color.textPrimary).lineLimit(2, reservesSpace: true) // content-lists.md:429-430 -- minLines 2 and maxLines 2, always occupies two lines
                    Text(videoMeta(item, locale: locale, includeCategory: false))
                        .font(TypeScale.itemMeta).foregroundStyle(Color.textSecondary).lineLimit(2)
                }
                .frame(maxWidth: .infinity, alignment: .topLeading)
                .frame(height: 100, alignment: .topLeading) // home_card_content_height, fixed so grid rows align
                .padding(.top, Spacing.sm)
                if let category = item.category, !category.isEmpty {
                    CategoryChip(text: category).padding(.top, Spacing.xs)
                }
            }
            .padding(.horizontal, Spacing.sm)
            .padding(.top, Spacing.sm)
            .padding(.bottom, Spacing.md(widthClass))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(videoAccessibilityLabel(item, locale: locale))
    }
}

// MARK: - ChannelRow (item_channel.xml)

/// Full-width channel row: circular avatar 56/64/72, bold name, brand-coloured subscriber count
/// (content-lists.md §5.1). Avatar size follows spec §7's `ChannelRow (circle 56/64/72)`, not
/// content-lists.md's own dimens.xml citation for the sw600/sw720 overrides (47/51 dp), which
/// look like a separate/inconsistent citation -- spec wins per the coding rules.
struct ChannelRow: View {
    let item: ContentItem
    let onTap: () -> Void
    @Environment(\.widthClass) private var widthClass
    @Environment(\.locale) private var locale

    init(item: ContentItem, onTap: @escaping () -> Void) {
        self.item = item
        self.onTap = onTap
    }

    private var avatarSize: CGFloat { widthClass.pick(56, 64, 72) }

    var body: some View {
        Button(action: onTap) {
            HStack(alignment: .top, spacing: 0) {
                RemoteImage(url: item.thumbnailURL)
                    .frame(width: avatarSize, height: avatarSize)
                    .clipShape(Circle())
                VStack(alignment: .leading, spacing: Spacing.xs) {
                    Text(item.title).font(TypeScale.subtitle).fontWeight(.bold)
                        .foregroundStyle(Color.textPrimary).lineLimit(2)
                    if let subscribers = item.subscribers {
                        Text(localizedFormat("channel_subscribers_format", locale: locale, Format.compactCount(subscribers, locale: locale)))
                            .font(TypeScale.body(widthClass)).foregroundStyle(Color.brand).lineLimit(1)
                    }
                    if let category = item.category, !category.isEmpty {
                        CategoryChip(text: category)
                    }
                }
                .padding(.leading, Spacing.md(widthClass))
                Spacer(minLength: 0)
            }
            .padding(Spacing.md(widthClass))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabel)
    }

    private var accessibilityLabel: String {
        let subscriberText = item.subscribers.map {
            localizedFormat("channel_subscribers_format", locale: locale, Format.compactCount($0, locale: locale))
        } ?? String(localized: "channel_subscribers_unknown")
        return localizedFormat("a11y_channel_item", locale: locale, item.title, subscriberText)
    }
}

// MARK: - HomeChannelItem (item_home_channel.xml, Home carousel)

/// Not a card: plain centred column, circular avatar 72/80/88, centred name (shell-home.md
/// "Channel card"; spec §7 `HomeChannelItem`).
struct HomeChannelItem: View {
    let item: ContentItem
    let onTap: () -> Void
    @Environment(\.widthClass) private var widthClass
    @Environment(\.locale) private var locale

    init(item: ContentItem, onTap: @escaping () -> Void) {
        self.item = item
        self.onTap = onTap
    }

    private var avatarSize: CGFloat { widthClass.pick(72, 80, 88) }

    var body: some View {
        Button(action: onTap) {
            VStack(spacing: 0) {
                RemoteImage(url: item.thumbnailURL)
                    .frame(width: avatarSize, height: avatarSize)
                    .clipShape(Circle())
                Text(item.title)
                    .font(TypeScale.itemMeta).fontWeight(.medium)
                    .foregroundStyle(Color.textPrimary)
                    .multilineTextAlignment(.center).lineLimit(2)
                    .padding(.top, Spacing.sm)
                if let subscribers = item.subscribers {
                    Text(localizedFormat("channel_subscribers_format", locale: locale, Format.compactCount(subscribers, locale: locale)))
                        .font(TypeScale.itemMeta).foregroundStyle(Color.textSecondary)
                        .multilineTextAlignment(.center)
                        .padding(.top, Spacing.xs)
                }
            }
            .padding(.vertical, Spacing.sm)
            .padding(.horizontal, Spacing.xs)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabel)
    }

    private var accessibilityLabel: String {
        let subscriberText = item.subscribers.map {
            localizedFormat("channel_subscribers_format", locale: locale, Format.compactCount($0, locale: locale))
        } ?? String(localized: "channel_subscribers_unknown")
        return localizedFormat("a11y_channel_item", locale: locale, item.title, subscriberText)
    }
}

// MARK: - PlaylistRow (item_playlist.xml)

/// Full-width playlist row: square thumbnail 80/100/120, bold title, plural item-count meta
/// (content-lists.md §5.2 -- "iOS must use the plural everywhere", fixing Android's hardcoded
/// `"\(count) items"`).
struct PlaylistRow: View {
    let item: ContentItem
    let onTap: () -> Void
    @Environment(\.widthClass) private var widthClass
    @Environment(\.locale) private var locale

    init(item: ContentItem, onTap: @escaping () -> Void) {
        self.item = item
        self.onTap = onTap
    }

    private var thumbSize: CGFloat { widthClass.pick(80, 100, 120) }

    var body: some View {
        Button(action: onTap) {
            HStack(alignment: .top, spacing: 0) {
                RemoteImage(url: item.thumbnailURL)
                    .frame(width: thumbSize, height: thumbSize)
                    .clipShape(RoundedRectangle(cornerRadius: Radius.homeThumbnail))
                VStack(alignment: .leading, spacing: Spacing.xs) {
                    Text(item.title).font(TypeScale.subtitle).fontWeight(.bold)
                        .foregroundStyle(Color.textPrimary).lineLimit(2)
                    if let count = item.itemCount {
                        Text(localizedFormat("playlist_item_count", locale: locale, Int64(count)))
                            .font(TypeScale.itemMeta).foregroundStyle(Color.textSecondary).lineLimit(1)
                    }
                }
                .padding(.leading, Spacing.md(widthClass))
                Spacer(minLength: 0)
            }
            .padding(.horizontal, Spacing.md(widthClass))
            .padding(.vertical, Spacing.sm)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabel)
    }

    private var accessibilityLabel: String {
        item.itemCount.map { localizedFormat("a11y_playlist_item", locale: locale, item.title, Int64($0)) } ?? item.title
    }
}

// MARK: - SectionHeader (item_home_section.xml header row)

/// Emoji + title + optional "See all" chevron. RULINGS.md contradiction #2 drops Android's
/// 18-character hard truncation -- `lineLimit(1)` only (shell-home.md §B5).
struct SectionHeader: View {
    let emoji: String?
    let title: String
    let onSeeAll: (() -> Void)?
    /// VoiceOver override for the See-all button (shell-home.md:207: "See all content in
    /// {displayName}", `home_see_all_category`). `nil` leaves the button's default
    /// auto-combined label (its own "See all" text + chevron) untouched.
    let seeAllAccessibilityLabel: String?
    @Environment(\.widthClass) private var widthClass

    init(emoji: String?, title: String, onSeeAll: (() -> Void)?, seeAllAccessibilityLabel: String? = nil) {
        self.emoji = emoji
        self.title = title
        self.onSeeAll = onSeeAll
        self.seeAllAccessibilityLabel = seeAllAccessibilityLabel
    }

    var body: some View {
        HStack(spacing: 0) {
            if let emoji, !emoji.isEmpty {
                Text(emoji).font(.system(size: 20)).padding(.trailing, Spacing.md(widthClass))
            }
            Text(title)
                .font(TypeScale.headline(widthClass)) // Home section title is 20sp bold on every bucket, matches .headline here
                .foregroundStyle(Color.textPrimary)
                .lineLimit(1)
                .padding(.trailing, Spacing.sm)
            Spacer(minLength: 0)
            if let onSeeAll {
                let button = Button(action: onSeeAll) {
                    HStack(spacing: Spacing.xs) {
                        Text(String(localized: "see_all")).font(TypeScale.seeAll)
                        Image(systemName: "chevron.forward") // direction-sensitive SF Symbol -- auto-mirrors in RTL
                    }
                    .foregroundStyle(Color.brand)
                    .padding(Spacing.sm)
                }
                if let seeAllAccessibilityLabel {
                    button.accessibilityLabel(seeAllAccessibilityLabel)
                } else {
                    button
                }
            }
        }
        .frame(minHeight: widthClass == .large ? 56 : 48) // touch_target_min, 56 on sw720
        .padding(.horizontal, Spacing.homeHorizontalMargin(widthClass)) // home_horizontal_margin (shell-home.md:227)
    }
}

// MARK: - CategoryPill (Home category filter pill)

/// Always `categoryPill`-filled (brand text/icons); expand chevron and clear button are mutually
/// exclusive on `isActive` (shell-home.md §B4).
struct CategoryPill: View {
    let label: String
    let isActive: Bool
    let onTap: () -> Void
    let onClear: () -> Void

    init(label: String, isActive: Bool, onTap: @escaping () -> Void, onClear: @escaping () -> Void) {
        self.label = label
        self.isActive = isActive
        self.onTap = onTap
        self.onClear = onClear
    }

    var body: some View {
        HStack(spacing: 0) {
            Button(action: onTap) {
                HStack(spacing: 0) {
                    Image(systemName: "square.grid.2x2").font(.system(size: 20))
                    Text(label).font(TypeScale.seeAll).padding(.leading, Spacing.sm)
                    if !isActive {
                        Image(systemName: "chevron.down").font(.system(size: 20)).padding(.leading, Spacing.xs)
                    }
                }
                .foregroundStyle(Color.brand)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(String(localized: "home_select_category"))
            if isActive {
                Button(action: onClear) {
                    Image(systemName: "xmark").font(.system(size: 20))
                        .foregroundStyle(Color.brand)
                        // ponytail: Android's touch target overflows its 40dp pill (48dp
                        // TouchDelegate expansion that doesn't affect sibling layout); SwiftUI's
                        // `.frame(minHeight:)` instead grows the *laid-out* size, which fought the
                        // pill's own `.frame(height: 40)` and corrupted the whole row's width
                        // negotiation. Capped at the pill's own 40 pt height -- revisit with a
                        // custom hit-test area (`.contentShape` at a larger, non-participating
                        // rect) if the 8 pt shortfall from the 48 pt HIG target matters.
                        .frame(width: 40, height: 40)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(localized: "clear_filters"))
            }
        }
        .padding(.horizontal, 16) // home_category_pill_padding_horizontal -- flat across buckets
        .frame(height: 40) // home_category_pill_height
        .background(Color.categoryPill)
        .clipShape(RoundedRectangle(cornerRadius: Radius.pill))
    }
}

// MARK: - TransientBanner (RULINGS.md #24: one component for every toast/snackbar)

struct BannerMessage: Equatable {
    let text: String
    var actionTitle: String?
    var action: (() -> Void)?

    static func == (lhs: BannerMessage, rhs: BannerMessage) -> Bool {
        lhs.text == rhs.text && lhs.actionTitle == rhs.actionTitle
    }
}

/// Bottom banner, 2.5 s auto-dismiss, optional action, VoiceOver announcement.
struct TransientBanner: ViewModifier {
    @Binding var message: BannerMessage?
    @Environment(\.widthClass) private var widthClass

    init(message: Binding<BannerMessage?>) { self._message = message }

    func body(content: Content) -> some View {
        content.overlay(alignment: .bottom) {
            if let message {
                bannerView(message)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .task(id: message.text) {
                        AccessibilityNotification.Announcement(message.text).post()
                        try? await Task.sleep(for: .seconds(2.5))
                        if !Task.isCancelled { self.message = nil }
                    }
            }
        }
        .animation(.default, value: message)
    }

    @ViewBuilder
    private func bannerView(_ message: BannerMessage) -> some View {
        HStack(spacing: Spacing.sm) {
            Text(message.text).foregroundStyle(.white).lineLimit(2)
            Spacer(minLength: 0)
            if let title = message.actionTitle, let action = message.action {
                Button(title) { action(); self.message = nil }
                    .foregroundStyle(Color.accent)
            }
        }
        .font(TypeScale.body(widthClass))
        .padding(Spacing.md(widthClass))
        .background(Color.black.opacity(0.85), in: RoundedRectangle(cornerRadius: Radius.dialog))
        .padding(.horizontal, Spacing.md(widthClass))
        .padding(.bottom, Spacing.md(widthClass))
    }
}

extension View {
    func transientBanner(_ message: Binding<BannerMessage?>) -> some View {
        modifier(TransientBanner(message: message))
    }
}

// MARK: - Skeletons (RULINGS.md #16 shimmer + mirror layout; contradiction #8 one skeleton token)

/// Shared shimmer driver for the grid/carousel skeletons -- same phase/reduce-motion pattern as
/// `StateViews.SkeletonListView`, factored here instead of touching that file (out of this task's
/// scope) so `SkeletonGrid`/`SkeletonCarousel` don't duplicate it against each other.
private struct Shimmer<Content: View>: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var start = Date()
    @ViewBuilder let content: (Color) -> Content

    var body: some View {
        if reduceMotion {
            content(.skeleton)
        } else {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                let phase = Int(context.date.timeIntervalSince(start)) % 2 == 0
                content(phase ? .skeletonShimmer : .skeleton)
                    .animation(.easeInOut(duration: 1), value: phase)
            }
        }
    }
}

/// Mirrors a `VideoGridCell` grid shape -- `columns` × `rows` placeholder cells.
struct SkeletonGrid: View {
    let columns: Int
    let rows: Int
    @Environment(\.widthClass) private var widthClass

    init(columns: Int, rows: Int) {
        self.columns = columns
        self.rows = rows
    }

    var body: some View {
        Shimmer { fill in
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: Spacing.sm), count: max(1, columns)),
                      spacing: Spacing.md(widthClass)) {
                ForEach(0..<max(0, columns * rows), id: \.self) { _ in
                    VStack(alignment: .leading, spacing: Spacing.xs) {
                        RoundedRectangle(cornerRadius: Radius.homeThumbnail).fill(fill).aspectRatio(16.0 / 9.0, contentMode: .fit)
                        RoundedRectangle(cornerRadius: Radius.chip).fill(fill).frame(height: 14)
                        RoundedRectangle(cornerRadius: Radius.chip).fill(fill).frame(width: 100, height: 12)
                    }
                }
            }
            .padding(Spacing.md(widthClass))
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String(localized: "loading"))
    }
}

/// Mirrors a `MediaCard` carousel -- `cards` placeholder cards in a horizontal row.
struct SkeletonCarousel: View {
    let cards: Int
    @Environment(\.widthClass) private var widthClass

    init(cards: Int) { self.cards = cards }

    var body: some View {
        Shimmer { fill in
            // The real carousel's fixed-width cards are always wrapped in a horizontal
            // ScrollView by their caller (mirror the real layout -- RULINGS.md #16); without one
            // here, this row of un-shrinkable 260 pt cards is wider than the viewport, and the
            // outer (vertical-only) ScrollView centers the oversized content instead of clipping
            // it, corrupting every sibling row's horizontal position too.
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: Spacing.sm) {
                    ForEach(0..<max(0, cards), id: \.self) { _ in
                        RoundedRectangle(cornerRadius: Radius.card).fill(fill).frame(width: 260, height: 180)
                    }
                }
                .padding(.horizontal, Spacing.md(widthClass))
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String(localized: "loading"))
    }
}

// MARK: - Preview sample data

private enum PreviewData {
    static let video = ContentItem(
        id: "v1", type: .video, title: "How to Pray Witr Correctly — Full Explanation",
        category: "Fiqh", description: nil, thumbnailURL: nil, durationSeconds: 754,
        uploadedDaysAgo: 3, viewCount: 125_000, channelTitle: "Al-Huda Institute",
        subscribers: nil, videoCount: nil, itemCount: nil
    )
    static let videoNoViews = ContentItem(
        id: "v2", type: .video, title: "New Upload With No View Count Yet",
        category: nil, description: nil, thumbnailURL: nil, durationSeconds: 42,
        uploadedDaysAgo: 0, viewCount: nil, channelTitle: nil, subscribers: nil, videoCount: nil, itemCount: nil
    )
    static let channel = ContentItem(
        id: "c1", type: .channel, title: "Al-Huda Institute",
        category: "Lectures", description: nil, thumbnailURL: nil, durationSeconds: nil,
        uploadedDaysAgo: nil, viewCount: nil, channelTitle: nil, subscribers: 48_200, videoCount: nil, itemCount: nil
    )
    static let playlist = ContentItem(
        id: "p1", type: .playlist, title: "Ramadan Reminders",
        category: "Ramadan", description: nil, thumbnailURL: nil, durationSeconds: nil,
        uploadedDaysAgo: nil, viewCount: nil, channelTitle: "Al-Huda Institute", subscribers: nil, videoCount: nil, itemCount: 24
    )
}

// MARK: - Previews

#Preview("RemoteImage placeholder") {
    RemoteImage(url: nil).frame(width: 200, height: 112)
}

#Preview("DurationChip") { DurationChip(seconds: 754).padding() }
#Preview("DurationChip - Dark") { DurationChip(seconds: 754).padding().preferredColorScheme(.dark) }

#Preview("Badge - Live") { Badge(.live).padding() }
#Preview("Badge - Upcoming") { Badge(.upcoming).padding() }
#Preview("Badge - RTL") {
    Badge(.live).padding()
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}

#Preview("CategoryChip") { CategoryChip(text: "Fiqh").padding() }
#Preview("CategoryChip - Dark") { CategoryChip(text: "Fiqh").padding().preferredColorScheme(.dark) }

#Preview("MediaCard - Video Light") {
    MediaCard(item: PreviewData.video, width: 260) {}.padding()
}
#Preview("MediaCard - Video Dark") {
    MediaCard(item: PreviewData.video, width: 260) {}.padding().preferredColorScheme(.dark)
}
#Preview("MediaCard - Playlist Regular") {
    MediaCard(item: PreviewData.playlist, width: 240) {}.padding()
        .environment(\.widthClass, .regular)
}
#Preview("MediaCard - RTL") {
    MediaCard(item: PreviewData.video, width: 260) {}.padding()
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}

#Preview("VideoRow - Light") { VideoRow(item: PreviewData.video) {}.padding() }
#Preview("VideoRow - Dark") { VideoRow(item: PreviewData.video) {}.padding().preferredColorScheme(.dark) }
#Preview("VideoRow - Regular") { VideoRow(item: PreviewData.video) {}.padding().environment(\.widthClass, .regular) }
#Preview("VideoRow - RTL") {
    VideoRow(item: PreviewData.video) {}.padding()
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}

#Preview("VideoGridCell - Light") {
    VideoGridCell(item: PreviewData.video) {}.frame(width: 180).padding()
}
#Preview("VideoGridCell - Dark") {
    VideoGridCell(item: PreviewData.video) {}.frame(width: 180).padding().preferredColorScheme(.dark)
}
#Preview("VideoGridCell - Regular") {
    VideoGridCell(item: PreviewData.video) {}.frame(width: 220).padding().environment(\.widthClass, .regular)
}
#Preview("VideoGridCell - RTL") {
    VideoGridCell(item: PreviewData.video) {}.frame(width: 180).padding()
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}

#Preview("ChannelRow - Light") { ChannelRow(item: PreviewData.channel) {}.padding() }
#Preview("ChannelRow - Dark") { ChannelRow(item: PreviewData.channel) {}.padding().preferredColorScheme(.dark) }
#Preview("ChannelRow - Regular") { ChannelRow(item: PreviewData.channel) {}.padding().environment(\.widthClass, .regular) }
#Preview("ChannelRow - RTL") {
    ChannelRow(item: PreviewData.channel) {}.padding()
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}

#Preview("HomeChannelItem - Light") { HomeChannelItem(item: PreviewData.channel) {}.padding() }
#Preview("HomeChannelItem - Dark") { HomeChannelItem(item: PreviewData.channel) {}.padding().preferredColorScheme(.dark) }
#Preview("HomeChannelItem - Regular") { HomeChannelItem(item: PreviewData.channel) {}.padding().environment(\.widthClass, .regular) }
#Preview("HomeChannelItem - RTL") {
    HomeChannelItem(item: PreviewData.channel) {}.padding()
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}

#Preview("PlaylistRow - Light") { PlaylistRow(item: PreviewData.playlist) {}.padding() }
#Preview("PlaylistRow - Dark") { PlaylistRow(item: PreviewData.playlist) {}.padding().preferredColorScheme(.dark) }
#Preview("PlaylistRow - Regular") { PlaylistRow(item: PreviewData.playlist) {}.padding().environment(\.widthClass, .regular) }
#Preview("PlaylistRow - RTL") {
    PlaylistRow(item: PreviewData.playlist) {}.padding()
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}

#Preview("SectionHeader - Light") { SectionHeader(emoji: "📖", title: "Qur'an Recitation", onSeeAll: {}) }
#Preview("SectionHeader - Dark") { SectionHeader(emoji: "📖", title: "Qur'an Recitation", onSeeAll: {}).preferredColorScheme(.dark) }
#Preview("SectionHeader - Regular") { SectionHeader(emoji: "📖", title: "Qur'an Recitation", onSeeAll: {}).environment(\.widthClass, .regular) }
#Preview("SectionHeader - RTL") {
    SectionHeader(emoji: "📖", title: "تلاوة القرآن", onSeeAll: {})
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}

#Preview("CategoryPill - Inactive") { CategoryPill(label: "Category", isActive: false, onTap: {}, onClear: {}).padding() }
#Preview("CategoryPill - Active") { CategoryPill(label: "Fiqh", isActive: true, onTap: {}, onClear: {}).padding() }
#Preview("CategoryPill - Dark") {
    CategoryPill(label: "Fiqh", isActive: true, onTap: {}, onClear: {}).padding().preferredColorScheme(.dark)
}
#Preview("CategoryPill - RTL") {
    CategoryPill(label: "الفقه", isActive: true, onTap: {}, onClear: {}).padding()
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}

#Preview("TransientBanner") {
    Color.background.transientBanner(.constant(BannerMessage(text: "Saved to favorites", actionTitle: "Undo", action: {})))
}
#Preview("TransientBanner - Dark") {
    Color.background.transientBanner(.constant(BannerMessage(text: "Saved to favorites", actionTitle: "Undo", action: {})))
        .preferredColorScheme(.dark)
}

#Preview("SkeletonGrid - Light") { SkeletonGrid(columns: 2, rows: 2) }
#Preview("SkeletonGrid - Dark") { SkeletonGrid(columns: 2, rows: 2).preferredColorScheme(.dark) }
#Preview("SkeletonGrid - Regular") { SkeletonGrid(columns: 3, rows: 2).environment(\.widthClass, .regular) }
#Preview("SkeletonGrid - RTL") {
    SkeletonGrid(columns: 2, rows: 2)
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}

#Preview("SkeletonCarousel - Light") { SkeletonCarousel(cards: 3) }
#Preview("SkeletonCarousel - Dark") { SkeletonCarousel(cards: 3).preferredColorScheme(.dark) }
#Preview("SkeletonCarousel - RTL") {
    SkeletonCarousel(cards: 3)
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}
