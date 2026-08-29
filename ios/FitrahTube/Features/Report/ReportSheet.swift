import SwiftUI

/// The content-report sheet (`ContentReportBottomSheet.kt`, spec §10): 11 reason toggles, an
/// "Other" field, Cancel / Submit. Validation lives in `ReportPayload.make`; this view only maps
/// its result and the client's `ReportState` to copy. `.succeeded` dismisses and hands the
/// thank-you to the presenter via `onSubmitted` -- the banner belongs on the screen that stays.
struct ReportSheet: View {
    let context: ReportContext
    let onSubmitted: () -> Void

    @Environment(\.container) private var container
    @Environment(\.dismiss) private var dismiss
    @State private var selected: [ReportReason] = []
    @State private var otherText = ""
    @State private var state: ReportState = .idle
    @State private var validationKey: String?

    var body: some View {
        NavigationStack {
            Form {
                Section(String(localized: "report_subtitle")) {
                    ForEach(ReportReason.allCases, id: \.self, content: row)
                    if selected.contains(.other) {
                        TextField(String(localized: "report_other_hint"), text: $otherText, axis: .vertical)
                            .lineLimit(3...6)
                            .onChange(of: otherText) { _, new in
                                if new.count > ReportPayload.maxOtherLength { otherText = String(new.prefix(ReportPayload.maxOtherLength)) }
                            }
                            .accessibilityIdentifier("report.otherText")
                    }
                }
                if let messageKey = validationKey ?? inlineErrorKey {
                    Text(String(localized: String.LocalizationValue(messageKey)))
                        .foregroundStyle(Color.errorText)
                        .accessibilityIdentifier("report.message")
                }
            }
            .navigationTitle(String(localized: "report_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "cancel")) { dismiss() }
                        .accessibilityIdentifier("report.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "report_submit"), action: submit)
                        .disabled(state == .submitting)
                        .accessibilityIdentifier("report.submit")
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private var inlineErrorKey: String? {
        switch state {
        case .rateLimited: "report_rate_limited"
        case .failed(let messageKey): messageKey
        default: nil
        }
    }

    private func row(_ reason: ReportReason) -> some View {
        let isOn = Binding(
            get: { selected.contains(reason) },
            set: { on in
                selected.removeAll { $0 == reason }
                if on { selected.append(reason) }
                validationKey = nil
            })
        // The eleventh row goes disabled at the server's cap rather than eating its 400.
        let capped = !isOn.wrappedValue && selected.count >= ReportPayload.maxReasons
        return Toggle(String(localized: String.LocalizationValue(reason.messageKey)), isOn: isOn)
            .frame(minHeight: 44)
            .disabled(capped)
            .accessibilityHint(capped ? String(localized: "report_reason_limit") : "")
            .accessibilityIdentifier("report.reason.\(reason.rawValue)")
    }

    private func submit() {
        switch ReportPayload.make(context: context, reasons: selected, otherText: otherText) {
        case .failure(.noReasons(let key)), .failure(.tooManyReasons(let key)):
            validationKey = key
        case .success(let body):
            validationKey = nil
            state = .submitting
            Task {
                let result = (try? await container.report.submit(body)) ?? .idle
                state = result
                if result == .succeeded {
                    onSubmitted()
                    dismiss()
                }
            }
        }
    }
}
