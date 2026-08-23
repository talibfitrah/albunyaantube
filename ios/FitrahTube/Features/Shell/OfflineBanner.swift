import SwiftUI

/// Android's offline banner (`shell-home.md:A4`): pinned to the shell's top edge, overlaying
/// content without blocking it, start-aligned text (RTL-aware via `.leading`). RULING 9: uses
/// spec's `errorBackground`/`errorText` tokens, not Android's unstyled M3 error-container pair.
struct OfflineBanner: View {
    @Environment(\.widthClass) private var widthClass

    var body: some View {
        HStack(spacing: Spacing.sm) {
            Image(systemName: "wifi.slash")
                .font(.system(size: 20))
                .accessibilityHidden(true)
            Text(String(localized: "connectivity_offline_banner"))
                .font(TypeScale.itemTitle)
        }
        .foregroundStyle(Color.errorText)
        .padding(.horizontal, Spacing.md(widthClass))
        .padding(.vertical, Spacing.sm)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.errorBackground)
        // Gate cubic-r3 X2: no interactive content of its own, but `MainShellView` pins this at
        // the same top edge as each tab's `NavigationStack` navigation bar (`.overlay(alignment:
        // .top)`), so without this its full-width background silently swallowed taps meant for
        // the nav bar's title/trailing button and the pushed screens' Back button.
        .allowsHitTesting(false)
    }
}

#Preview {
    OfflineBanner()
}
