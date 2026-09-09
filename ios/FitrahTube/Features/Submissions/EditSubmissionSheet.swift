import SwiftUI

/// `EditSubmissionBottomSheet.kt` — the submitter's "why I'm suggesting this" note, on a row that is
/// still theirs to change.
///
/// It owns no view model and no client. Android's sheet injects the repository and carries its own
/// copy of the success / already-reviewed / failed table; here the write is a closure the SCREEN
/// supplies, so both outcomes travel through `MySubmissionsViewModel.perform` — one table, and the
/// refresh a 409 needs happens without a `setFragmentResult` round trip to ask for it.
struct EditSubmissionSheet: View {
    let submission: Submission
    /// Returns the message the screen banners.
    let save: (String) async -> String
    let onFinish: (String) -> Void

    @Environment(\.widthClass) private var widthClass

    @State private var note: String = ""
    @State private var saving = false
    /// Seeded once. A re-render must not overwrite what the user has typed.
    @State private var seeded = false

    var body: some View {
        EditSheetScaffold(title: String(localized: "my_submissions_action_edit_note")) {
            LabelledField(key: "my_submissions_submitter_note_label") {
                TextField(String(localized: "my_submissions_submitter_note_label"), text: $note,
                          axis: .vertical)
                    .lineLimit(3...6)
                    .font(TypeScale.body(widthClass))
                    .padding(Spacing.md(widthClass))
                    .frame(minHeight: 44)
                    .background(Color.homeCard, in: RoundedRectangle(cornerRadius: Radius.card))
                    .accessibilityLabel(String(localized: "my_submissions_submitter_note_label"))
            }
            EditSheetAction(title: String(localized: "my_submissions_action_edit_note"),
                            isLoading: saving) {
                // At most one PATCH in flight, the same guard Android's `saving` flag is
                // (`EditSubmissionBottomSheet.kt:29-31`); `EditSheetAction` also disables itself.
                guard !saving else { return }
                saving = true
                let message = await save(note)
                saving = false
                onFinish(message)
            }
        }
        .task {
            guard !seeded else { return }
            seeded = true
            note = submission.submitterNote ?? ""
        }
    }
}

#if DEBUG
#Preview {
    EditSubmissionSheet(submission: Submission(id: "s1", type: .videos, title: "Lecture",
                                               thumbnailUrl: nil, status: .pending,
                                               submitterNote: "Please add this series",
                                               submittedAt: .now),
                        save: { _ in "" }, onFinish: { _ in })
}
#endif
