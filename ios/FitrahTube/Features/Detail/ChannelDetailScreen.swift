import SwiftUI

/// Android's `ChannelDetailFragment` (`fragment_channel_detail.xml`): banner + avatar header,
/// subscriber line, Subscribe, in-header search, then `ChannelTabsView`. Ruling F: the header
/// PINS above the paged tabs (a per-tab scroll inside an outer scroll never collapses); the strip
/// and the swipe are the non-negotiable half of ruling 9.
struct ChannelDetailScreen: View {
    let id: String
    let name: String?
    let avatarURL: URL?

    @Environment(\.container) private var container
    @Environment(\.widthClass) private var widthClass
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.verticalSizeClass) private var verticalSizeClass

    @State private var viewModel: ChannelDetailViewModel?
    @State private var banner: BannerMessage?

    var body: some View {
        VStack(spacing: 0) {
            if let viewModel {
                if viewModel.isUnavailable {
                    // RULING 14/15: terminal, no Retry.
                    EmptyStateView(systemImage: "exclamationmark.triangle.fill", iconColor: .accentRed,
                                   title: String(localized: "content_unavailable_title"),
                                   message: String(localized: "content_unavailable_message"))
                        .padding(.top, Spacing.lg(widthClass))
                        .accessibilityIdentifier("channel.unavailable")
                    Spacer()
                } else {
                    header(viewModel)
                    ChannelTabsView(viewModel: viewModel, banner: $banner)
                }
            } else {
                SkeletonListView().padding(Spacing.md(widthClass))
                Spacer()
            }
        }
        .background(Color.background.ignoresSafeArea())
        .navigationTitle(viewModel?.header.name ?? name ?? id)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                DetailKebab(share: .channel(id), title: viewModel?.header.name ?? name ?? id,
                            report: ReportContext(targetType: .channel, targetId: id, parentType: nil, parentId: nil, contentSubType: nil),
                            banner: $banner)
            }
        }
        .transientBanner($banner)
        .task {
            if viewModel == nil {
                viewModel = ChannelDetailViewModel(channelId: id, name: name, avatarURL: avatarURL,
                                                   browse: container.browse, subscriptions: container.subscriptions)
                await viewModel?.load()
            }
        }
    }

    // MARK: - Header (fragment_channel_detail.xml banner / avatar / name / subscribe)

    private var avatarSize: CGFloat { widthClass.pick(72, 88, 96) }

    private func header(_ viewModel: ChannelDetailViewModel) -> some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            // C T6 finding: the header pins (ruling F), so at compact height (iPhone landscape) the
            // banner + avatar block alone pushed the strip under the tab bar and left the tab body
            // with no height at all. Drop that block there; name, Subscribe, search and the strip stay.
            if verticalSizeClass != .compact {
                ZStack(alignment: .bottomLeading) {
                    ZStack {
                        RemoteImage(url: viewModel.header.bannerURL)
                        // C T6 finding: no banner -> the placeholder alone, no gradient over it.
                        if viewModel.header.bannerURL != nil {
                            LinearGradient(colors: [.clear, Color.heroOverlay], startPoint: .top, endPoint: .bottom)
                        }
                    }
                    .frame(height: widthClass.pick(96, 140, 160))
                    .clipped()
                    .accessibilityHidden(true)
                    RemoteImage(url: viewModel.header.avatarURL)
                        .frame(width: avatarSize, height: avatarSize)
                        .clipShape(Circle())
                        .overlay(Circle().stroke(Color.background, lineWidth: 3))
                        .padding(.leading, Spacing.md(widthClass))
                        .offset(y: avatarSize / 2)
                        .accessibilityLabel(String(localized: "cd_channel_avatar"))
                }
                .padding(.bottom, avatarSize / 2)
            }

            Text(viewModel.header.name)
                .font(TypeScale.headline(widthClass)).foregroundStyle(Color.textPrimary)
                .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 2)
                .padding(.horizontal, Spacing.md(widthClass))
                .accessibilityIdentifier("channel.title")

            subscribeRow(viewModel)

            if viewModel.isDegraded {
                Text(String(localized: "browse_degraded_notice"))
                    .font(TypeScale.caption).foregroundStyle(Color.errorText)
                    .padding(.horizontal, Spacing.sm).padding(.vertical, Spacing.xs)
                    .background(Color.errorBackground, in: RoundedRectangle(cornerRadius: Radius.chip))
                    .padding(.horizontal, Spacing.md(widthClass))
                    .accessibilityIdentifier("channel.degradedNotice")
            }

            SearchField(text: Binding(get: { viewModel.query }, set: { viewModel.query = $0 }),
                        accessibilityLabel: String(localized: "cd_search_icon"))
        }
        .padding(.bottom, Spacing.sm)
    }

    /// CF-C-9: subscriber line + Subscribe side by side, single column at accessibility sizes.
    private func subscribeRow(_ viewModel: ChannelDetailViewModel) -> some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: Spacing.sm))
            : AnyLayout(HStackLayout(spacing: Spacing.sm))
        let subscribed = viewModel.isSubscribed
        return layout {
            Text(viewModel.subscriberLine(for: viewModel.header.subscriberText))
                .font(TypeScale.itemMeta).foregroundStyle(Color.brand)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier("channel.subscribers")
            let button = Button {
                if let key = viewModel.toggleSubscribed() {
                    banner = BannerMessage(text: String(localized: String.LocalizationValue(key)))
                }
            } label: {
                Label(String(localized: subscribed ? "channel_unsubscribe" : "channel_subscribe"),
                      systemImage: subscribed ? "checkmark" : "plus")
                    .font(TypeScale.subtitle).fontWeight(.bold)
                    .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                    .frame(minHeight: 44)
                    .padding(.horizontal, Spacing.sm)
            }
            // Filled brand while unsubscribed, outlined once subscribed -- the system styles, not a
            // hand-painted background over `.bordered`.
            Group {
                if subscribed {
                    button.buttonStyle(.bordered)
                } else {
                    button.buttonStyle(.borderedProminent)
                }
            }
            .tint(.brand)
            .accessibilityValue(String(localized: subscribed ? "channel_unsubscribe" : "channel_subscribe"))
            .accessibilityAddTraits(subscribed ? [.isSelected] : [])
            .accessibilityIdentifier("channel.subscribe")
        }
        .padding(.horizontal, Spacing.md(widthClass))
    }
}

#if DEBUG
#Preview {
    NavigationStack {
        ChannelDetailScreen(id: "UCfixturechannel", name: "Fixture Channel", avatarURL: nil)
    }
    .environment(\.container, .sharedFake)
}
#endif
