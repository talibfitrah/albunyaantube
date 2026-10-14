import Foundation
import SwiftUI

/// The player's metadata panel (spec §10 Feature-parity bullets), wired below the player area in
/// `PlayerScreen`'s shared `.ready`/`.rung2Progressive` branch: title, real channel name, view
/// count, and an expand/collapse description.
struct PlayerMetadataView: View {
    let args: PlayerArgs

    @Environment(\.widthClass) private var widthClass
    @Environment(\.locale) private var locale
    // Task 10 (spec §6.11 "no clipped title" at `.accessibility3`): the fixed `.lineLimit(2)`
    // below (Task 8's `text_section_title` "max 2 lines" port) reduced a real title down to
    // "Understanding Tawakkul: Trusti…" at that size -- same `isAccessibilitySize` escape hatch
    // `GridRules.columns` already uses (`Layout.swift`), applied here instead of a second token.
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var isDescriptionExpanded = false
    /// CF-B1-10: the collapsed and the full text heights, measured; `DescriptionTruncation`
    /// decides whether the toggle is shown from the two.
    @State private var collapsedHeight: CGFloat = 0
    @State private var fullHeight: CGFloat = 0

    private static let collapsedLineLimit = 3

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            if let title = args.title {
                Text(title)
                    .font(TypeScale.headline(widthClass))
                    .foregroundStyle(Color.textPrimary)
                    .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 2)
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
                .onGeometryChange(for: CGFloat.self, of: \.size.height) { collapsedHeight = $0 }
                // The same text laid out unlimited at the same width, hidden: its height against
                // the visible one says whether the line limit actually cut anything.
                .background {
                    Text(attributedDescription(raw))
                        .font(TypeScale.body(widthClass))
                        .fixedSize(horizontal: false, vertical: true)
                        .hidden()
                        .onGeometryChange(for: CGFloat.self, of: \.size.height) { fullHeight = $0 }
                }

            if DescriptionTruncation.needsToggle(fits: fullHeight <= collapsedHeight + 1, isExpanded: isDescriptionExpanded) {
                descriptionToggle
            }
        }
    }

    private var descriptionToggle: some View {
        Button {
            isDescriptionExpanded.toggle()
        } label: {
            Text(String(localized: isDescriptionExpanded ? "player_description_less" : "player_description_more"))
                .font(TypeScale.caption)
                .foregroundStyle(Color.brand)
        }
        .accessibilityIdentifier("player.metadata.descriptionToggle")
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

/// CF-B1-10: the toggle is offered when the collapsed text is actually cut, or is expanded (so
/// "Show less" remains). Pure so `PlayerMetadataViewTests` pins it without the geometry.
nonisolated enum DescriptionTruncation {
    static func needsToggle(fits: Bool, isExpanded: Bool) -> Bool {
        !fits || isExpanded
    }
}

/// Android's `PlayerDescriptions.kt` allow-list (`docs/library-guides` reference), ported to
/// iOS's plain-text `description` field (the FitrahTube backend serves plain text, not YouTube's
/// raw HTML, so there is no `HtmlCompat.fromHtml` step to port -- only the link filter).
/// `NSDataDetector` is the platform's own link finder (ponytail: no hand-rolled URL regex). A match
/// is tappable only when `isOpenableLink` passes BOTH the URL the tap opens (`match.url`, which the
/// detector normalises: `http://` added to a bare domain, the host decoded and IDNA-mapped) AND the
/// text as written, so a normalisation cannot launder a link the text itself fails. Anything else
/// stays plain, non-tappable text -- the text itself is never altered. Free function (not a
/// method) so `PlayerToolbarTests` can call it directly with no view/environment needed.
func attributedDescription(_ raw: String) -> AttributedString {
    var attributed = AttributedString(raw)
    guard let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) else {
        return attributed
    }
    let nsRaw = raw as NSString
    for match in detector.matches(in: raw, range: NSRange(location: 0, length: nsRaw.length)) {
        // The tap opens a lower-case scheme (Android normalises it too); Foundation keeps `Https` as written.
        guard let detected = match.url, let scheme = detected.scheme,
              let url = URL(string: scheme.lowercased() + detected.absoluteString.dropFirst(scheme.count)),
              let stringRange = Range(match.range, in: raw),
              isOpenableLink(url.absoluteString), isOpenableLink(asWritten(raw[stringRange])),
              let start = AttributedString.Index(stringRange.lowerBound, within: attributed),
              let end = AttributedString.Index(stringRange.upperBound, within: attributed) else { continue }
        attributed[start..<end].link = url
    }
    return attributed
}

/// The match's text as written, with `http://` in front of a bare domain (`example.org/b`), the
/// same scheme the detector adds, so the rule still judges the written host.
private func asWritten(_ text: Substring) -> String {
    text.contains("://") ? String(text) : "http://" + text
}

/// The ONE link rule, the same as Android's `PlayerDescriptions.isOpenableLink` (keep the two in
/// step). Owner directives 2026-08-27 and
/// 2026-10-14: the app never hands users to YouTube. A link that fails stays visible as text:
/// 1. no `\`, whitespace (anywhere: no trim), control character or soft hyphen;
/// 2. scheme `http` or `https`;
/// 3. a host that is ASCII as Foundation gives it. `URL(string:)` converts a non-ASCII host with
///    IDNA (UTS 46, as a browser does: `ｙｏｕｔｕｂｅ.com` is `youtube.com`, `bücher.de` is
///    `xn--bcher-kva.de`) and refuses an unassigned code point (nil). The host is read
///    percent-decoded (`%79outube.com` is youtube.com); a decoded host that is not plain ASCII
///    fails closed;
/// 4. not `yt.be` or under it, no label starting with `youtube`, no label equal to `youtu` or
///    `withyoutube`: every YouTube domain, country domain, subdomain and redirecting marketing
///    domain. The accepted cost: an unrelated `youtuber.com` is text too.
func isOpenableLink(_ link: String) -> Bool {
    let refused: (Unicode.Scalar) -> Bool = {
        $0 == "\\" || $0 == "\u{AD}" || $0.properties.isWhitespace || $0.properties.generalCategory == .control
    }
    guard !link.unicodeScalars.contains(where: refused),
          let url = URL(string: link),
          let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https",
          let host = url.host(percentEncoded: false),
          host.unicodeScalars.allSatisfy({ $0.isASCII && !refused($0) })
    else { return false }
    let labels = host.lowercased().split(separator: ".")   // drops the empty label a trailing dot leaves
    let domain = labels.joined(separator: ".")
    guard !labels.isEmpty, domain != "yt.be", !domain.hasSuffix(".yt.be") else { return false }
    return !labels.contains { $0.hasPrefix("youtube") || $0 == "youtu" || $0 == "withyoutube" }
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
