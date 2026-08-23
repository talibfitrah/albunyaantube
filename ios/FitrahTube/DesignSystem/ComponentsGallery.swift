#if DEBUG
// Debug-only, and compiled out of Release entirely (gate B1-I8 / cso-F3): the gallery was
// reachable in a production build through About's 7-tap developer gesture -> DeveloperDialog ->
// "Components Gallery", shipping every design-system sample as dead weight in the binary and
// putting an untranslated developer surface one discoverable gesture away from a real user.
import SwiftUI

/// Debug-only screenshot rig for every design-system component (Task 6 acceptance artefact).
/// Reachable via the `-fitrah-gallery` launch argument (`RootView.swift`) and, later, from the
/// Developer dialog (Task 14). Not part of the app's real navigation graph.
///
/// `section` narrows the rig to one third of the components (fix round 1: a full-height gallery
/// screenshot on a phone only captured the top half). `nil` (the Xcode-preview default) renders
/// everything, matching the original behaviour.
struct ComponentsGallery: View {
    enum Section: Int { case headers = 1, cardsRows = 2, skeletonsStates = 3 }

    let section: Section?
    @State private var pillActive = false
    @State private var bannerMessage: BannerMessage?
    @Environment(\.widthClass) private var widthClass

    init(section: Section? = nil) { self.section = section }

    private let video = ContentItem(
        id: "gv1", type: .video, title: "How to Pray Witr Correctly — Full Explanation",
        category: "Fiqh", description: nil, thumbnailURL: nil, durationSeconds: 754,
        uploadedDaysAgo: 3, viewCount: 125_000, channelTitle: "Al-Huda Institute",
        subscribers: nil, videoCount: nil, itemCount: nil
    )
    private let videoNoViews = ContentItem(
        id: "gv2", type: .video, title: "New Upload With No View Count Yet",
        category: nil, description: nil, thumbnailURL: nil, durationSeconds: 42,
        uploadedDaysAgo: 0, viewCount: nil, channelTitle: nil, subscribers: nil, videoCount: nil, itemCount: nil
    )
    private let channel = ContentItem(
        id: "gc1", type: .channel, title: "Al-Huda Institute", category: "Lectures",
        description: nil, thumbnailURL: nil, durationSeconds: nil, uploadedDaysAgo: nil,
        viewCount: nil, channelTitle: nil, subscribers: 48_200, videoCount: nil, itemCount: nil
    )
    private let playlist = ContentItem(
        id: "gp1", type: .playlist, title: "Ramadan Reminders", category: "Ramadan",
        description: nil, thumbnailURL: nil, durationSeconds: nil, uploadedDaysAgo: nil,
        viewCount: nil, channelTitle: "Al-Huda Institute", subscribers: nil, videoCount: nil, itemCount: 24
    )

    var body: some View {
        ScrollView {
            // `.frame(maxWidth: .infinity, alignment: .leading)` below keeps this column
            // left-aligned when its content is narrower than the viewport (a vertical-only
            // ScrollView centers a content view that doesn't fill the cross axis). It does NOT
            // guard against an oversized child: any un-scrolled row of fixed-width views wider
            // than the viewport (see SkeletonCarousel's fix in Components.swift) forces the
            // whole column wider and gets centered regardless of this modifier -- the real fix
            // is giving every such row its own horizontal ScrollView.
            VStack(alignment: .leading, spacing: Spacing.lg(widthClass)) {
                if show(.headers) {
                    SectionHeader(emoji: "📖", title: "Qur'an Recitation", onSeeAll: {})

                    group("CategoryPill") {
                        HStack(spacing: Spacing.sm) {
                            CategoryPill(label: "Category", isActive: pillActive, onTap: { pillActive.toggle() }, onClear: { pillActive = false })
                            CategoryPill(label: "Fiqh", isActive: true, onTap: {}, onClear: {})
                        }
                    }

                    group("Badge / DurationChip / CategoryChip") {
                        HStack(spacing: Spacing.sm) {
                            Badge(.live)
                            Badge(.upcoming)
                            DurationChip(seconds: 754)
                            CategoryChip(text: "Fiqh")
                        }
                    }
                }

                if show(.cardsRows) {
                    group("MediaCard (video / playlist)") {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 12) {
                                MediaCard(item: video, width: 260) {}
                                MediaCard(item: playlist, width: 240) {}
                            }
                        }
                    }

                    group("HomeChannelItem") {
                        HStack(spacing: 12) { HomeChannelItem(item: channel) {} }
                    }

                    group("VideoRow") { VideoRow(item: video) {} }

                    group("VideoGridCell") {
                        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: Spacing.sm) {
                            VideoGridCell(item: video) {}
                            VideoGridCell(item: videoNoViews) {} // demonstrates the omitted-views segment
                        }
                    }

                    group("ChannelRow") { ChannelRow(item: channel) {} }
                    group("PlaylistRow") { PlaylistRow(item: playlist) {} }
                }

                if show(.skeletonsStates) {
                    group("SkeletonCarousel") { SkeletonCarousel(cards: 3) }
                    group("SkeletonGrid") {
                        SkeletonGrid(columns: 2, rows: 1).frame(height: 220)
                    }

                    group("EmptyStateView / ErrorStateView / SkeletonListView") {
                        VStack(spacing: Spacing.md(widthClass)) {
                            EmptyStateView(systemImage: "tray", title: "No downloads yet", message: "Downloaded videos will appear here")
                                .frame(height: 220)
                            ErrorStateView(message: "Couldn't load content.") {}
                                .frame(height: 220)
                            SkeletonListView(rows: 2)
                        }
                    }

                    group("TransientBanner") {
                        Button("Show banner") {
                            bannerMessage = BannerMessage(text: "Saved to favorites", actionTitle: "Undo", action: {})
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, Spacing.md(widthClass))
        }
        .background(Color.background)
        .transientBanner($bannerMessage)
    }

    private func show(_ s: Section) -> Bool { section == nil || section == s }

    @ViewBuilder
    private func group(_ title: String, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            Text(title).font(TypeScale.caption).foregroundStyle(Color.textMuted)
                .padding(.horizontal, Spacing.md(widthClass))
            content()
                .padding(.horizontal, Spacing.md(widthClass))
        }
    }
}

#Preview("Gallery - Light") { ComponentsGallery() }
#Preview("Gallery - Dark") { ComponentsGallery().preferredColorScheme(.dark) }
#Preview("Gallery - RTL") {
    ComponentsGallery()
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}
#endif
