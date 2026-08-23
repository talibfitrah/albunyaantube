import SwiftUI

/// Android empty_state.xml: 96 pt brand icon, 20 bold headline, body ≤ 300 pt, optional 56 pt button.
struct EmptyStateView: View {
    let systemImage: String
    let title: String
    let message: String
    var action: (title: String, run: () -> Void)? = nil
    @Environment(\.widthClass) private var widthClass

    var body: some View {
        VStack(spacing: Spacing.md(widthClass)) {
            VStack(spacing: Spacing.md(widthClass)) {
                Image(systemName: systemImage)
                    .font(.system(size: 96))
                    .foregroundStyle(Color.brand)
                    .accessibilityHidden(true)
                Text(title).font(TypeScale.headline).foregroundStyle(Color.textPrimary)
                StateMessage(text: message)
            }
            .accessibilityElement(children: .combine)
            if let action {
                StateButton(title: action.title, action: action.run)
            }
        }
        .padding(Spacing.lg(widthClass))
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// Android error_state.xml: red icon, message, 56 pt retry button.
struct ErrorStateView: View {
    let message: String
    let retry: () -> Void
    @Environment(\.widthClass) private var widthClass

    var body: some View {
        VStack(spacing: Spacing.md(widthClass)) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 64))
                .foregroundStyle(Color.accentRed)
                .accessibilityHidden(true)
            StateMessage(text: message)
            StateButton(title: String(localized: "Retry"), action: retry)
        }
        .padding(Spacing.lg(widthClass))
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct StateMessage: View {
    let text: String

    var body: some View {
        Text(text)
            .font(TypeScale.body)
            .foregroundStyle(Color.textSecondary)
            .multilineTextAlignment(.center)
            .frame(maxWidth: 300)
    }
}

private struct StateButton: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title).frame(minHeight: Size.button)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .tint(.brand)
        .foregroundStyle(Color.onBrand)
    }
}

/// Android skeleton_content_item.xml: 120×90 thumbnail block + two text bars, shimmering; static under Reduce Motion.
struct SkeletonListView: View {
    var rows: Int = 6
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.widthClass) private var widthClass
    @State private var shimmer = false

    var body: some View {
        VStack(spacing: Spacing.md(widthClass)) {
            ForEach(0..<rows, id: \.self) { _ in
                HStack(spacing: Spacing.sm) {
                    RoundedRectangle(cornerRadius: Radius.thumbnail)
                        .fill(fill)
                        .frame(width: 120, height: 90)
                    VStack(alignment: .leading, spacing: Spacing.sm) {
                        RoundedRectangle(cornerRadius: Radius.chip).fill(fill).frame(height: 16)
                        RoundedRectangle(cornerRadius: Radius.chip).fill(fill).frame(width: 140, height: 12)
                    }
                    Spacer(minLength: 0)
                }
            }
        }
        .padding(Spacing.md(widthClass))
        .accessibilityLabel(String(localized: "Loading"))
        .animation(reduceMotion ? nil : .easeInOut(duration: 1).repeatForever(autoreverses: true), value: shimmer)
        .onAppear { shimmer = true }
    }

    private var fill: Color { (shimmer && !reduceMotion) ? .skeletonShimmer : .skeleton }
}

#Preview("Empty") {
    EmptyStateView(systemImage: "tray", title: "No downloads yet", message: "Downloaded videos will appear here")
}

#Preview("Error") {
    ErrorStateView(message: "Couldn't load content.") {}
}

#Preview("Skeleton") {
    SkeletonListView()
}
