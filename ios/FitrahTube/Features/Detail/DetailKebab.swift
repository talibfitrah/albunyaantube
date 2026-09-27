import SwiftUI

/// The detail screens' kebab (`menu_detail_kebab.xml`, ruling 53): Share and Report, nothing else.
/// One component for both `ChannelDetailScreen` and `PlaylistDetailScreen`. The presenting screen
/// owns `banner` so the thank-you shows on the screen that stays, not in the dismissed sheet.
struct DetailKebab: View {
    let share: ShareLinks.Target
    let title: String
    let report: ReportContext
    @Binding var banner: BannerMessage?

    @Environment(\.locale) private var locale
    @State private var showReport = false

    var body: some View {
        Menu {
            ShareLink(item: ShareLinks.url(for: share), subject: Text(title),
                      message: Text(ShareLinks.message(for: share, title: title, locale: locale))) {
                Label(String(localized: "action_share"), systemImage: "square.and.arrow.up")
            }
            .accessibilityIdentifier("detail.kebab.share")
            Button { showReport = true } label: {
                Label(String(localized: "report_content"), systemImage: "flag")
            }
            .accessibilityIdentifier("detail.kebab.report")
        } label: {
            Image(systemName: "ellipsis")
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .accessibilityIdentifier("detail.kebab.button")
        .accessibilityLabel(String(localized: "shorts_more_options_cd"))
        .sheet(isPresented: $showReport) {
            ReportSheet(context: report) { banner = BannerMessage(text: String(localized: "report_success")) }
        }
    }
}
