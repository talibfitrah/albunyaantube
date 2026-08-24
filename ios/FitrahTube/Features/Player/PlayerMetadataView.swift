import Foundation
import SwiftUI

/// The player's metadata panel (spec §10 Feature-parity bullets), wired below the player area in
/// `PlayerScreen`'s shared `.ready`/`.rung2Progressive` branch: title, real channel name, view
/// count, and an expand/collapse description.
struct PlayerMetadataView: View {
    let args: PlayerArgs

    @Environment(\.widthClass) private var widthClass
    @Environment(\.locale) private var locale
    @State private var isDescriptionExpanded = false

    // ponytail: fixed collapsed line count rather than measuring actual text-wrap truncation
    // (SwiftUI has no truncation callback) -- the toggle is shown whenever a description exists,
    // even one short enough that expanding changes nothing visible. Upgrade path if that reads as
    // broken in practice: a `ViewThatFits`-based "does this truncate at N lines" probe.
    private static let collapsedLineLimit = 3

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            if let title = args.title {
                Text(title)
                    .font(TypeScale.headline(widthClass))
                    .foregroundStyle(Color.textPrimary)
                    .lineLimit(2)
                    .accessibilityIdentifier("player.metadata.title")
            }

            // Ruling 39: `args.channelName` already resolved the real-title-over-category fallback
            // upstream (`PlayerArgs.init(item:)`, `Route.swift`) -- this view renders exactly what
            // it was given and never substitutes anything else when it's nil.
            if let channelName = args.channelName {
                Text(channelName)
                    .font(TypeScale.subtitle)
                    .foregroundStyle(Color.textSecondary)
                    .accessibilityIdentifier("player.metadata.channelName")
            }

            Text(Self.viewsText(viewCount: args.viewCount, locale: locale))
                .font(TypeScale.itemMeta)
                .foregroundStyle(Color.textSecondary)
                .accessibilityIdentifier("player.metadata.views")

            if let description = args.description, !description.isEmpty {
                descriptionSection(description)
            }
        }
        .padding(Spacing.md(widthClass))
    }

    @ViewBuilder
    private func descriptionSection(_ raw: String) -> some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Text(attributedDescription(raw))
                .font(TypeScale.body(widthClass))
                .foregroundStyle(Color.textPrimary)
                .lineLimit(isDescriptionExpanded ? nil : Self.collapsedLineLimit)
                .accessibilityIdentifier("player.metadata.description")

            Button {
                isDescriptionExpanded.toggle()
            } label: {
                Text(String(localized: isDescriptionExpanded ? "player_description_less" : "player_description_more"))
                    .font(TypeScale.caption)
                    .foregroundStyle(Color.brand)
            }
            .accessibilityIdentifier("player.metadata.descriptionToggle")
        }
    }

    /// Ruling 37 (ONE formatter): routed through the Phase-1 `Format`/`video_views` plural, same
    /// call shape as `Components.swift`'s `videoMeta`. `player_no_views` (reserved, unused until
    /// now) covers the nil case -- unlike a grid card, the player metadata panel always shows a
    /// views line rather than omitting the segment. `static`/`internal` (not embedded in `body`) so
    /// `PlayerToolbarTests` can pin the formatting without rendering the view.
    static func viewsText(viewCount: Int64?, locale: Locale) -> String {
        guard let viewCount else { return String(localized: "player_no_views") }
        return Format.localizedFormat(
            "video_views", locale: locale,
            Format.compactCount(viewCount, locale: locale), Int64(Format.pluralQuantity(viewCount))
        )
    }
}

/// Android's `PlayerDescriptions.kt` allow-list (`docs/library-guides` reference), ported to
/// iOS's plain-text `description` field (the FitrahTube backend serves plain text, not YouTube's
/// raw HTML, so there is no `HtmlCompat.fromHtml` step to port -- only the link-scheme filter).
/// `NSDataDetector` is the platform's own link finder (ponytail: no hand-rolled URL regex); a
/// detected match whose scheme isn't http/https is left as plain, non-tappable text -- the text
/// itself is never altered, only whether a `.link` attribute is attached to it. Free function
/// (not a method) so `PlayerToolbarTests` can call it directly with no view/environment needed.
func attributedDescription(_ raw: String) -> AttributedString {
    var attributed = AttributedString(raw)
    guard let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) else {
        return attributed
    }
    let nsRaw = raw as NSString
    for match in detector.matches(in: raw, range: NSRange(location: 0, length: nsRaw.length)) {
        guard let url = match.url, let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" else { continue }
        guard let stringRange = Range(match.range, in: raw),
              let start = AttributedString.Index(stringRange.lowerBound, within: attributed),
              let end = AttributedString.Index(stringRange.upperBound, within: attributed) else { continue }
        attributed[start..<end].link = url
    }
    return attributed
}

#if DEBUG
#Preview {
    PlayerMetadataView(args: PlayerArgs(
        videoId: "preview", title: "Understanding Tawakkul: Trusting Allah in Every Situation",
        channelName: "Sample Channel", description: "A short reminder with a link: https://example.com/read-more.",
        durationSeconds: 754, viewCount: 12_700_000
    ))
}
#endif
