import SwiftUI
import UIKit

// MARK: - Localization helpers (Home meta line, plural/substitution xcstrings keys)

/// Video meta line: `views • timeAgo[ • category]`, `" • "`-joined, each segment omitted when its
/// underlying value is nil (RULINGS.md contradiction #3: null view count omits the segment,
/// correcting Android's "0 views"). `includeCategory` is false for the tabs' `VideoRow`/
/// `VideoGridCell` (category shown as a separate chip there) and true for the Home `MediaCard`
/// (content-lists.md §5.6; shell-home.md "Video meta string").
private func videoMeta(_ item: ContentItem, locale: Locale, includeCategory: Bool) -> String {
    var parts: [String] = []
    if let views = item.viewCount {
        parts.append(Format.localizedFormat("video_views", locale: locale,
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
        Format.localizedFormat("video_views", locale: locale, Format.compactCount($0, locale: locale), Int64(Format.pluralQuantity($0)))
    } ?? ""
    let uploaded = item.uploadedDaysAgo.map { Format.timeAgo(days: $0, locale: locale) } ?? ""
    return Format.localizedFormat("a11y_video_item", locale: locale, item.title, duration, views, uploaded)
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

    /// Bounded (gate cso-F4 / A-M6): with neither limit set, decoded bitmaps accumulated until the
    /// system raised memory pressure -- on an iPad grid of 16:9 thumbnails that is tens of MB
    /// before any eviction. `totalCostLimit` only means anything if every `setObject` supplies a
    /// cost, which `load()` below does.
    private static let cache: NSCache<NSURL, UIImage> = {
        let cache = NSCache<NSURL, UIImage>()
        cache.countLimit = 200
        cache.totalCostLimit = 50 << 20 // 50 MB of decoded pixels
        return cache
    }()

    /// MIN-9 (B2 final review): ephemeral with cookies fully disabled, exactly like
    /// `InnerTubeKit.URLSessionTransport`. Thumbnails come off `*.ggpht.com` / `*.ytimg.com`, so
    /// the default configuration was handing Google's image hosts a persistent, on-disk cookie jar
    /// that every later request replayed -- a per-install identifier the app has no use for and
    /// (`i.ytimg.com` shares a registrable domain with the InnerTube calls) one the resolver's own
    /// deliberately cookie-free session was trying not to create.
    ///
    /// The disk cache goes with it: an ephemeral session caches in RAM only. Thumbnails therefore
    /// re-download once per launch instead of persisting across launches; within a launch the
    /// decoded-image `NSCache` above already absorbs the repeats.
    private static let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieAcceptPolicy = .never
        configuration.httpShouldSetCookies = false
        configuration.urlCache = URLCache(memoryCapacity: 50 * 1024 * 1024, diskCapacity: 0)
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
        // The synchronous cache hit stays here so a re-appearing cell never flashes the skeleton.
        if let url, let cached = Self.cache.object(forKey: url as NSURL) {
            image = cached
            return
        }
        image = nil
        image = await Self.cachedImage(for: url)
    }

    /// The one image fetch in the app, shared with `BackgroundPlaybackController`'s Now Playing
    /// artwork (Task 4) -- same https guard, same 10 MB cap, same decoded-image cache, so the
    /// player's artwork URL is normally already resolved by the card the user tapped.
    ///
    /// https only (gate cso-F4). `URLSession` honours `file://` for data tasks, so a compromised
    /// or mis-configured backend could otherwise make the app read a local file and decode it as
    /// an image. Enforced here rather than at the `LiveCatalogClient` mapping boundary because
    /// every image fetch in the app funnels through this one call -- including the favorites
    /// store's own persisted thumbnail strings, which never pass through that mapper.
    static func cachedImage(for url: URL?) async -> UIImage? {
        guard let url, url.scheme?.lowercased() == "https" else { return nil }
        if let cached = cache.object(forKey: url as NSURL) { return cached }
        // ponytail: decode happens on the calling (main) actor -- fine at card/thumbnail sizes
        // (max ~320 pt here); move to a background decode if profiling shows main-thread jank.
        guard let data = try? await boundedImageData(from: url),
              let decoded = UIImage(data: data) else { return nil }
        let cost = decoded.cgImage.map { $0.bytesPerRow * $0.height } ?? data.count
        cache.setObject(decoded, forKey: url as NSURL, cost: cost)
        return decoded
    }

    private static let maxImageBytes = 10 << 20 // 10 MB

    /// The response body is remote, backend-supplied content (gate wave-4 V7 / cubic-r3 X1): a
    /// compromised or mis-configured thumbnail host could otherwise OOM the app with one multi-GB
    /// body. `data(from:)` only returns once the whole body is buffered, so a size check on its
    /// result runs after the allocation it was meant to prevent. `bytes(for:)` hands back the
    /// response -- and its `Content-Length`, when the server sends one -- before any body bytes
    /// are read, so a declared-oversize length aborts here with nothing downloaded. A chunked or
    /// absent length (`expectedContentLength == -1`) falls through to the loop, which enforces the
    /// same cap against the bytes actually received -- also catching a length header that
    /// understated the real body. The MIME check refuses non-images before either.
    private static func boundedImageData(from url: URL) async throws -> Data? {
        let (bytes, response) = try await session.bytes(for: URLRequest(url: url))
        guard (response as? HTTPURLResponse)?.mimeType?.hasPrefix("image/") == true else { return nil }
        guard response.expectedContentLength == -1 || response.expectedContentLength <= Int64(maxImageBytes) else { return nil }
        var data = Data()
        for try await byte in bytes {
            data.append(byte)
            if data.count > maxImageBytes { return nil }
        }
        return data
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
        Text(Format.localizedFormat("video_count", locale: locale, Int64(count)))
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
    /// task-14 (`screenshots/task-14/iphone-17/home-en-light-a11y3-portrait.png`): the flat 100 pt
    /// `home_card_content_height` clipped the title to one line and cut the meta line off entirely
    /// at `.accessibility3`. `@ScaledMetric` keeps the Android dimen at the default text size and
    /// grows it with Dynamic Type, so the block still aligns across a row of cards.
    @ScaledMetric(relativeTo: .subheadline) private var contentHeight: CGFloat = 100

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
                    .frame(width: width, height: contentHeight, alignment: .topLeading) // home_card_content_height, every bucket
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
            item.itemCount.map { Format.localizedFormat("a11y_playlist_item", locale: locale, item.title, Int64($0)) } ?? item.title
        case .channel:
            item.title
        }
    }
}

// MARK: - VideoRow (item_video_list.xml)

/// Full-width video row: 140 pt 16:9 thumbnail, 16 pt bold title (content-lists.md §5.3).
struct VideoRow: View {
    let item: ContentItem
    /// Persistent subtitle overriding the computed views/upload-age `videoMeta` line -- Favorites
    /// rows pass the channel name here (`favorites-settings-about.md:90,94`: no view count/upload
    /// age snapshot exists for a favorite, but Android still shows a one-line channel-name caption).
    let subtitle: String?
    let onTap: () -> Void
    @Environment(\.widthClass) private var widthClass
    @Environment(\.locale) private var locale

    init(item: ContentItem, subtitle: String? = nil, onTap: @escaping () -> Void) {
        self.item = item
        self.subtitle = subtitle
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
                    if let subtitle {
                        Text(subtitle).font(TypeScale.itemMeta).foregroundStyle(Color.textSecondary).lineLimit(1)
                    } else {
                        Text(videoMeta(item, locale: locale, includeCategory: false))
                            .font(TypeScale.itemMeta).foregroundStyle(Color.textSecondary).lineLimit(2)
                    }
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
    /// Same task-14 fix as `MediaCard`
    /// (`screenshots/task-14/iphone-17/videos-en-light-a11y3-portrait.png`: the meta line was
    /// clipped to a single truncated "0 views • …").
    @ScaledMetric(relativeTo: .subheadline) private var contentHeight: CGFloat = 100

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
                .frame(height: contentHeight, alignment: .topLeading) // home_card_content_height, fixed so grid rows align
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
                        Text(Format.localizedFormat("channel_subscribers_format", locale: locale, Format.compactCount(subscribers, locale: locale)))
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
            Format.localizedFormat("channel_subscribers_format", locale: locale, Format.compactCount($0, locale: locale))
        } ?? String(localized: "channel_subscribers_unknown")
        return Format.localizedFormat("a11y_channel_item", locale: locale, item.title, subscriberText)
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
                    Text(Format.localizedFormat("channel_subscribers_format", locale: locale, Format.compactCount(subscribers, locale: locale)))
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
            Format.localizedFormat("channel_subscribers_format", locale: locale, Format.compactCount($0, locale: locale))
        } ?? String(localized: "channel_subscribers_unknown")
        return Format.localizedFormat("a11y_channel_item", locale: locale, item.title, subscriberText)
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
                        Text(Format.localizedFormat("playlist_item_count", locale: locale, Int64(count)))
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
        item.itemCount.map { Format.localizedFormat("a11y_playlist_item", locale: locale, item.title, Int64($0)) } ?? item.title
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

// MARK: - HomeSectionRow (item_home_section.xml -- header + horizontal card rail)

/// One carousel section: header, then a horizontal rail of cards. One copy (gate wave-2 W9):
/// `HomeView.sectionRow` and `FeaturedView.sectionRow` were ~60 identical lines each, down to the
/// card-width maths, the section-title fallback and the item→route switch.
///
/// `onSeeAll` stays a caller closure because the two screens genuinely differ there: Home pushes
/// the section's raw `name`, Featured pushes the localized title.
struct HomeSectionRow: View {
    let section: HomeSection
    /// The measured width of the screen the rail sits in -- carousel card widths derive from it.
    let containerWidth: CGFloat
    let onSeeAll: () -> Void

    @Environment(\.router) private var router
    @Environment(\.widthClass) private var widthClass
    @Environment(\.locale) private var locale

    init(section: HomeSection, containerWidth: CGFloat, onSeeAll: @escaping () -> Void) {
        self.section = section
        self.containerWidth = containerWidth
        self.onSeeAll = onSeeAll
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            SectionHeader(
                emoji: section.icon,
                title: Format.sectionDisplayName(section, locale: locale),
                onSeeAll: onSeeAll,
                seeAllAccessibilityLabel: Format.sectionSeeAllLabel(section, locale: locale)
            )
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: GridRules.cardGap(widthClass)) {
                    ForEach(section.items) { item in
                        itemView(item)
                    }
                }
                .padding(.horizontal, Spacing.homeHorizontalMargin(widthClass)) // home_horizontal_margin (shell-home.md:227)
                .padding(.bottom, Spacing.sm)
            }
        }
        .padding(.top, Spacing.lg(widthClass)) // home_vertical_section_spacing 24/32/40 -- matches Spacing.lg exactly
    }

    @ViewBuilder
    private func itemView(_ item: ContentItem) -> some View {
        switch item.type {
        case .video, .playlist:
            MediaCard(item: item, width: cardWidth(for: item.type)) { router.push(Route(item: item)) }
        case .channel:
            HomeChannelItem(item: item) { router.push(Route(item: item)) }
        }
    }

    private func cardWidth(for type: ContentType) -> CGFloat {
        guard containerWidth > 0 else { return 0 }
        let visible = GridRules.carouselVisible(type, widthClass)
        return max(0, GridRules.carouselCardWidth(
            container: containerWidth, margin: Spacing.homeHorizontalMargin(widthClass), gap: GridRules.cardGap(widthClass), visible: visible
        ))
    }
}

// MARK: - SearchField (the pill-shaped query field, shared by Search and the list tabs)

/// Magnifier + text field + clear button in a `surfaceVariant` pill. One copy (gate wave-2 W9):
/// `SearchView.searchField` and `ContentListView.searchBar` were near-identical, differing only in
/// focus/submit behaviour and the field's VoiceOver label -- so the next styling change would have
/// had to be made twice, correctly, to keep the two screens looking like one app.
struct SearchField: View {
    @Binding var text: String
    let accessibilityLabel: String
    /// Set by `SearchView` (auto-focus on appear); `nil` on the list tabs, whose field is just one
    /// element of a header.
    var focus: FocusState<Bool>.Binding?
    /// Set by `SearchView`, which submits on the keyboard's Search key (bypassing its debounce);
    /// `nil` on the list tabs, which only ever fetch on the debounced `query` change.
    var onSubmit: (() -> Void)?

    @Environment(\.widthClass) private var widthClass

    var body: some View {
        HStack(spacing: Spacing.sm) {
            Image(systemName: "magnifyingglass").foregroundStyle(Color.textSecondary)
            field
            if !text.isEmpty {
                Button { text = "" } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(Color.textSecondary)
                }
                .accessibilityLabel(String(localized: "search_clear"))
            }
        }
        .padding(.horizontal, Spacing.md(widthClass))
        .padding(.vertical, Spacing.sm)
        .background(Color.surfaceVariant, in: RoundedRectangle(cornerRadius: Radius.pill))
        .padding(.horizontal, Spacing.md(widthClass))
        .padding(.top, Spacing.sm)
    }

    @ViewBuilder
    private var field: some View {
        let base = TextField(String(localized: "search_hint"), text: $text)
            .textFieldStyle(.plain)
            .accessibilityLabel(accessibilityLabel)
        if let focus, let onSubmit {
            base.focused(focus).submitLabel(.search).onSubmit(onSubmit)
        } else {
            base
        }
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
    /// task-14 (`screenshots/task-14/iphone-17/home-en-light-a11y3-portrait.png`): at
    /// `.accessibility3` the label's descenders were sliced off by the flat 40 pt
    /// `home_category_pill_height` (the `.clipShape` below clips content, not just the fill).
    @ScaledMetric(relativeTo: .subheadline) private var height: CGFloat = 40

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
            // Label = role, value = the data (gate B1-I7). The label alone ("Select content
            // category") replaced the button's own text, which *is* the active category name when
            // a filter is applied -- so on Home too, the filtering category was invisible to
            // VoiceOver.
            .accessibilityLabel(String(localized: "home_select_category"))
            .accessibilityValue(label)
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
                        .frame(width: height, height: height)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(localized: "clear_filters"))
            }
        }
        .padding(.horizontal, 16) // home_category_pill_padding_horizontal -- flat across buckets
        .frame(height: height) // home_category_pill_height
        .background(Color.categoryPill)
        .clipShape(RoundedRectangle(cornerRadius: Radius.pill))
    }
}

// MARK: - TransientBanner (RULINGS.md #24: one component for every toast/snackbar)

struct BannerMessage: Equatable {
    let text: String
    var actionTitle: String?
    var action: (() -> Void)?

    /// Identity, not content (gate A-M7 / B1-minor-5). Equality and the dismissal timer both used
    /// to key on `text`/`actionTitle` alone, so: applying the same filter twice inside 2.5 s
    /// reused the in-flight timer and the second banner inherited the first's remaining time; and
    /// in `ContentListView` the pagination banner and the terminal-error banner share both strings
    /// (`list_error_title` + `retry`), so replacing one with the other mid-display kept the old
    /// timer, skipped the VoiceOver re-announcement, and silently swapped which action Retry ran.
    private let id = UUID()

    static func == (lhs: BannerMessage, rhs: BannerMessage) -> Bool { lhs.id == rhs.id }

    var identity: UUID { id }
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
                    .task(id: message.identity) {
                        AccessibilityNotification.Announcement(message.text).post()
                        // An actionable banner stays up under VoiceOver / Switch Control (gate
                        // B1-minor-4): 2.5 s is far less than the time it takes to navigate to the
                        // Retry button, so the one control the banner exists for was unreachable
                        // for exactly the users who need it most. It is dismissed by the action
                        // itself, or replaced by the next banner.
                        guard message.action == nil
                                || !(UIAccessibility.isVoiceOverRunning || UIAccessibility.isSwitchControlRunning) else { return }
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
                // Gate wave-2 W5: pinning an actionable banner for VoiceOver/Switch Control (above)
                // is right, but it left no way out except performing the action -- an offer of
                // "Retry" a user who has decided not to retry cannot decline. A real control, plus
                // the rotor action below so it is reachable without hunting for the glyph.
                Button { self.message = nil } label: {
                    Image(systemName: "xmark")
                        .foregroundStyle(.white)
                        // A bare glyph is ~13 pt of tappable area; HIG's floor is 44 (wave-3 D6).
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel(String(localized: "banner_dismiss"))
            }
        }
        .accessibilityAction(named: Text(String(localized: "banner_dismiss"))) { self.message = nil }
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

/// The one shimmer driver: TimelineView tick, two-phase fill, static under Reduce Motion. Used by
/// `SkeletonGrid`/`SkeletonCarousel` here and by `SkeletonListView` in `StateViews.swift`, which
/// hand-rolled its own copy until gate wave-2 W9 -- the two had already drifted (an extra
/// `!reduceMotion` guard in one `fill`), so a timing or token change had to be made twice.
struct Shimmer<Content: View>: View {
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
