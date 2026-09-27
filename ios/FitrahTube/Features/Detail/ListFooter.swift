import InnerTubeKit
import SwiftUI

/// The one list footer both detail screens mount (ruling 47; `ListFooterAdapter.kt:40-49`,
/// `BaseChannelListTabFragment.kt:326-332`), driven only from `TabState`: appending → spinner,
/// a Load-more budget past its cap → button, an append failure → message + Retry, else nothing.
struct ListFooter<Item: Sendable & Equatable>: View {
    let state: TabState<Item>
    let loadMore: () -> Void
    let retry: () -> Void

    var body: some View {
        Group {
            if state.isAppending {
                ProgressView()
                    .tint(.brand)
                    .accessibilityLabel(String(localized: "loading_more"))
                    .accessibilityIdentifier("listFooter.loading")
            } else if case .errorAppend = state {
                VStack(spacing: Spacing.sm) {
                    Text(String(localized: "load_more_error"))
                        .font(.subheadline)
                        .foregroundStyle(Color.textSecondary)
                        .multilineTextAlignment(.center)
                    // C T6 measurement: a `.frame` OUTSIDE `.bordered` pads the button's slot, not
                    // its hit area (34 pt measured); the floor has to be on the label.
                    Button(action: retry) { Text(String(localized: "retry")).frame(minHeight: 44) }
                        .buttonStyle(.bordered)
                        .tint(.brand)
                        .accessibilityIdentifier("listFooter.retry")
                }
            } else if state.showsLoadMore {
                Button(action: loadMore) { Text(String(localized: "load_more")).frame(minHeight: 44) }
                    .buttonStyle(.bordered)
                    .tint(.brand)
                    .accessibilityIdentifier("listFooter.loadMore")
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, Spacing.sm)
    }
}
