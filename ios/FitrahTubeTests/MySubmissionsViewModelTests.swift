import FitrahAPI
import Foundation
import InnerTubeKit
import SwiftUI
import Testing
@testable import FitrahTube

/// Ruling C4's role-gated screen: the four state arms, the two submitter-owned writes and their
/// shared 409 race, pagination, and — ruling C13 — the fact that the Error arm is a REAL screen.
@Suite(.perTest)
@MainActor
struct MySubmissionsViewModelTests {

    private static let base = URL(string: "https://api.fitrah.test/")!

    private func model(_ responses: [HTTPResponse]) -> (MySubmissionsViewModel, ScriptedTransport) {
        let transport = ScriptedTransport(responses)
        return (makeModel(transport), transport)
    }

    private func makeModel(_ transport: ScriptedTransport) -> MySubmissionsViewModel {
        MySubmissionsViewModel(client: ApprovalsClient(transport: transport, baseURL: Self.base,
                                                       deviceId: DeviceId(value: "dev-123")))
    }

    private static func row(id: String, status: String = "PENDING") -> String {
        """
        {"id":"\(id)","type":"VIDEO","entityId":"xc7keR2piUM","title":"Lecture \(id)",
         "submittedAt":{"seconds":1756800000,"nanos":0},"status":"\(status)","submitterNote":"why"}
        """
    }

    private static func page(_ ids: [String], nextCursor: String? = nil) -> String {
        let cursor = nextCursor.map { "\"\($0)\"" } ?? "null"
        return """
        {"data":[\(ids.map { row(id: $0) }.joined(separator: ","))],
         "pageInfo":{"nextCursor":\(cursor)}}
        """
    }

    private func loadedRows(_ model: MySubmissionsViewModel) -> [Submission] {
        if case .loaded(let items) = model.state { items } else { [] }
    }

    private func ids(_ model: MySubmissionsViewModel) -> [String] { loadedRows(model).map(\.id) }

    // MARK: - The four arms

    @Test func aPageWithRowsLoadsAnEmptyPageIsEmptyAndAFailureIsTheErrorArm() async {
        let (model, _) = self.model([.json(200, Self.page(["s1", "s2"])),
                                     .json(200, Self.page([])),
                                     .json(503, "")])
        #expect(model.state == .loading, "before the first round — and the skeleton's arm")

        await model.refresh()
        #expect(ids(model) == ["s1", "s2"])

        await model.refresh()
        #expect(model.state == .empty)

        await model.refresh()
        #expect(model.state == .error)
    }

    /// **Ruling C13.** Android's Error arm is `{ /* TODO snackbar in T12 */ }`
    /// (`MySubmissionsFragment.kt:70`) — a failed load paints a blank list with no message and no
    /// retry. It is NOT ported: every arm here resolves to a real view, and the error arm resolves
    /// to `ErrorStateView`, which carries the retry button. Walks the `@ViewBuilder` switch the
    /// same way `MainShellRoutingTests` walks `destination(for:)`.
    @Test func everyStateArmRendersARealViewAndTheErrorArmIsARealErrorState() {
        let screen = MySubmissionsScreen()
        #expect(leafTypeName(of: screen.stateView(.loading)) == "SkeletonListView")
        #expect(leafTypeName(of: screen.stateView(.empty)) == "EmptyStateView")
        #expect(leafTypeName(of: screen.stateView(.error)) == "ErrorStateView")
        #expect(leafTypeName(of: screen.stateView(.loaded([]))).hasPrefix("LazyVStack"))
    }

    // MARK: - Delete (Android's `MySubmissionsActionEvent`)

    /// The 409 race: the row was adjudicated between the list load and the tap. It is NOT removed
    /// optimistically — the local list is stale by definition at that point, so the screen says so
    /// and re-reads (`MySubmissionsViewModel.kt:76-83`).
    @Test func deletingAnAlreadyReviewedRowSaysSoAndRefreshesInsteadOfRemovingIt() async throws {
        let (model, transport) = self.model([.json(200, Self.page(["s1", "s2"])),
                                             .json(409, ""),
                                             .json(200, Self.page(["s1", "s2"]))])
        await model.refresh()
        let row = try #require(loadedRows(model).first)

        let message = await model.delete(row)

        #expect(message == String(localized: "my_submissions_already_reviewed"))
        #expect(ids(model) == ["s1", "s2"], "the row stays until the server says it is gone")
        #expect(transport.sent.map(\.method) == ["GET", "DELETE", "GET"], "the 409 is followed by a re-read")
    }

    @Test func aSuccessfulDeleteSaysSoAndRefreshes() async throws {
        let (model, transport) = self.model([.json(200, Self.page(["s1", "s2"])),
                                             .json(204, ""),
                                             .json(200, Self.page(["s2"]))])
        await model.refresh()
        let row = try #require(loadedRows(model).first)

        let message = await model.delete(row)

        #expect(message == String(localized: "my_submissions_delete_success"))
        #expect(ids(model) == ["s2"])
        #expect(transport.sent.map(\.method) == ["GET", "DELETE", "GET"])
        #expect(transport.sent[1].url.path() == "/api/admin/registry/videos/s1/submission")
    }

    /// Anything else does NOT re-read: nothing about the list changed, and a refresh here would
    /// replace the rows the user is looking at with a skeleton for no reason.
    @Test func aFailedDeleteSaysSoAndLeavesTheListAlone() async throws {
        let (model, transport) = self.model([.json(200, Self.page(["s1"])), .json(500, "")])
        await model.refresh()
        let row = try #require(loadedRows(model).first)

        #expect(await model.delete(row) == String(localized: "my_submissions_action_failed"))
        #expect(ids(model) == ["s1"])
        #expect(transport.sent.map(\.method) == ["GET", "DELETE"])
    }

    // MARK: - Edit note

    /// The same three outcomes, through the same table: a PATCH is a submitter-owned write exactly
    /// as the DELETE is, and answers the same 409 for the same reason.
    @Test func savingANoteConfirmsItAndTheSameRaceIsReportedTheSameWay() async throws {
        let (model, transport) = self.model([.json(200, Self.page(["s1"])), .json(204, ""),
                                             .json(200, Self.page(["s1"])), .json(409, ""),
                                             .json(200, Self.page(["s1"]))])
        await model.refresh()
        let row = try #require(loadedRows(model).first)

        #expect(await model.updateNote(row, note: "please look again")
            == String(localized: "my_submissions_edit_success"))
        #expect(transport.sent[1].method == "PATCH")
        #expect(transport.sent[1].url.path() == "/api/admin/registry/videos/s1/submitter-note")
        #expect(transport.sent[2].method == "GET")

        #expect(await model.updateNote(row, note: "again") == String(localized: "my_submissions_already_reviewed"))
        #expect(transport.sent.count == 5, "the 409 re-reads too")
    }

    // MARK: - Pagination

    /// CLAUDE.md's rule: a page whose rows already fit the viewport never scrolls, so the six
    /// `PaginationGuard` checks run instead of a scroll listener. Drives the same loop
    /// `MySubmissionsScreen.triggerAutoFill` runs, with `contentFits: true` throughout.
    @Test func theLoadedRowsFittingTheScreenKeepPagingUntilTheCursorRunsOut() async {
        let (model, transport) = self.model([.json(200, Self.page(["s1"], nextCursor: "c2")),
                                             .json(200, Self.page(["s2"], nextCursor: "c3")),
                                             .json(200, Self.page(["s3"]))])
        await model.refresh()
        #expect(model.hasMore)

        var paginationGuard = PaginationGuard()
        var rounds = 0
        while model.hasMore, rounds < 10 {
            rounds += 1
            var attempt = paginationGuard
            guard attempt.shouldAutoLoad(widthClass: .regular, hasMore: model.hasMore,
                                         paginationError: model.paginationError, contentFits: true,
                                         itemCount: ids(model).count) else {
                paginationGuard = attempt
                break
            }
            _ = await model.loadMore()
            paginationGuard = attempt
        }

        #expect(ids(model) == ["s1", "s2", "s3"])
        #expect(!model.hasMore)
        #expect(transport.sent.map { $0.url.query() }
            == ["limit=50", "cursor=c2&limit=50", "cursor=c3&limit=50"])
    }

    /// A failed page keeps the rows it has and latches `paginationError`, which is
    /// `PaginationGuard`'s guard 3 — otherwise a fits-on-screen page would re-fire the same failing
    /// request forever.
    @Test func aFailedPageKeepsTheRowsAndStopsTheAutofill() async {
        let (model, _) = self.model([.json(200, Self.page(["s1"], nextCursor: "c2")), .json(503, "")])
        await model.refresh()

        _ = await model.loadMore()

        #expect(ids(model) == ["s1"], "a failed page never replaces what is on screen")
        #expect(model.paginationError)
        var attempt = PaginationGuard()
        let refused = attempt.shouldAutoLoad(widthClass: .regular, hasMore: model.hasMore,
                                             paginationError: model.paginationError, contentFits: true,
                                             itemCount: 1)
        #expect(!refused, "guard 3 refuses the retry storm a fits-on-screen page would otherwise start")
    }

    /// Android's single-flight `refreshJob` (`MySubmissionsViewModel.kt:44-47`): a delete's refresh
    /// and a pull-to-refresh both call this, and the OLDER answer landing last would re-introduce a
    /// row that was just removed. The generation guard refuses the superseded round's write.
    @Test func aSupersededRefreshNeverWritesOverTheNewerOne() async {
        let gate = Gate()
        let transport = ScriptedTransport([.json(200, Self.page(["stale"])), .json(200, Self.page(["fresh"]))],
                                          park: { index in if index == 1 { await gate.block() } })
        let model = makeModel(transport)
        let first = Task { await model.refresh() }
        await gate.waitUntilBlocked()

        await model.refresh()
        #expect(ids(model) == ["fresh"])

        await gate.release()
        await first.value
        #expect(ids(model) == ["fresh"], "the superseded round must not paint its stale page")
    }
}
