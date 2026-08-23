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
    }
}

#Preview {
    OfflineBanner()
}
