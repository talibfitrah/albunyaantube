import SwiftUI

/// Android empty_state.xml: 96 pt brand icon, 20 bold headline, body ≤ 300 pt, optional 56 pt button.
struct EmptyStateView: View {
    let systemImage: String
    let title: String
    let message: String
    var action: (title: String, run: () -> Void)? = nil

    var body: some View {
        VStack(spacing: Spacing.md(.compact)) {
            VStack(spacing: Spacing.md(.compact)) {
                Image(systemName: systemImage)
                    .font(.system(size: 96))
                    .foregroundStyle(Color.brand)
                    .accessibilityHidden(true)
                Text(title).font(TypeScale.headline).foregroundStyle(Color.textPrimary)
                Text(message)
                    .font(TypeScale.body)
                    .foregroundStyle(Color.textSecondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 300)
            }
            .accessibilityElement(children: .combine)
            if let action {
                Button(action.title, action: action.run)
                    .buttonStyle(.borderedProminent)
                    .tint(.brand)
                    .foregroundStyle(Color.onBrand)
                    .frame(minHeight: Size.button)
            }
        }
        .padding(Spacing.lg(.compact))
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// Android error_state.xml: red icon, message, 56 pt retry button.
struct ErrorStateView: View {
    let message: String
    let retry: () -> Void

    var body: some View {
        VStack(spacing: Spacing.md(.compact)) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 64))
                .foregroundStyle(Color.accentRed)
                .accessibilityHidden(true)
            Text(message)
                .font(TypeScale.body)
                .foregroundStyle(Color.textSecondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 300)
            Button(String(localized: "Retry"), action: retry)
                .buttonStyle(.borderedProminent)
                .tint(.brand)
                .foregroundStyle(Color.onBrand)
                .frame(minHeight: Size.button)
        }
        .padding(Spacing.lg(.compact))
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// Android skeleton_content_item.xml: 120×90 thumbnail block + two text bars, shimmering; static under Reduce Motion.
struct SkeletonListView: View {
    var rows: Int = 6
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var shimmer = false

    var body: some View {
        VStack(spacing: Spacing.md(.compact)) {
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
        .padding(Spacing.md(.compact))
        .accessibilityLabel(String(localized: "Loading"))
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 1).repeatForever(autoreverses: true)) { shimmer = true }
        }
    }

    private var fill: Color { shimmer ? .skeletonShimmer : .skeleton }
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
