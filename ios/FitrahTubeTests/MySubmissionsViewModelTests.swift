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

    /// Anything else does NOT re-read: the write never happened, so nothing about the list changed
    /// and a refresh would spend a request to paint the same rows.
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

    // Part B gate (stage 1 B1 / CF-B-17): the three pagination pins went with the engine. This
    // list never pages — the all-statuses backend branch mints no cursor — so `limit` is its whole
    // reach, which `aPageWithRows…` and the query assertion below pin.

    // MARK: - Refresh

    /// **Fix round 1 / M6.** A refresh with rows already on screen keeps them: `.refreshable` spins
    /// its own control and both post-write refreshes come with a banner, so replacing the list with
    /// `SkeletonListView` underneath either is a second indicator for one event. The
    /// `AccountSession.fetch` precedent (`:323`). A first load still paints the skeleton — there it
    /// is the only indicator — which the `.loading` assertion in the four-arms test above pins.
    @Test func aRefreshKeepsTheRowsOnScreenWhileItIsInFlight() async {
        let gate = Gate()
        let transport = ScriptedTransport([.json(200, Self.page(["s1"])), .json(200, Self.page(["s2"]))],
                                          park: { index in if index == 2 { await gate.block() } })
        let model = makeModel(transport)
        await model.refresh()
        #expect(ids(model) == ["s1"])

        let second = Task { await model.refresh() }
        await gate.waitUntilBlocked()
        #expect(ids(model) == ["s1"], "the rows stay rendered while their own re-read is in flight")

        await gate.release()
        await second.value
        #expect(ids(model) == ["s2"])
    }

    /// **Re-review nit 1.** A TRANSIENT failure with rows on screen keeps them and hands back the
    /// message to banner — `AccountSession.fetch`'s `.network where … state.me != nil` precedent
    /// (`:369`, "a cached account beats an offline banner"). With nothing loaded it still fails to
    /// `.error`, because there the error card is the only thing on screen.
    @Test func aNetworkFailureKeepsTheRowsAndBannersInsteadOfBlankingTheList() async {
        let (model, transport) = self.model([.json(200, Self.page(["s1"])),
                                             .failing(URLError(.notConnectedToInternet))])
        await model.refresh()
        let message = await model.refresh()

        #expect(ids(model) == ["s1"], "a stall does not cost the user what they were reading")
        #expect(message == String(localized: "auth_error_network"))
        #expect(transport.sent.count == 2)
        // Fix round 1 / I1: 100, Android's reach (`MySubmissionsRepository.kt:23`) — with no
        // `status`, `limit` is the ENTIRE reach of this list, and no cursor is ever sent.
        #expect(transport.sent.map { $0.url.query() } == ["limit=100", "limit=100"])

        let (cold, _) = self.model([.failing(URLError(.notConnectedToInternet))])
        #expect(await cold.refresh() == nil, "nothing on screen: the error arm IS the message")
        #expect(cold.state == .error)
    }

    /// The other half of the discrimination: anything the SERVER decided still blanks to `.error`,
    /// because those are the cases where the rows on screen may be exactly what is wrong — a
    /// revoked moderator's 403 above all.
    @Test func aServerDecidedFailureWithRowsOnScreenStillGoesToTheErrorArm() async {
        for status in [403, 500] {
            let (model, _) = self.model([.json(200, Self.page(["s1"])), .json(status, "")])
            await model.refresh()
            let message = await model.refresh()

            #expect(model.state == .error, "\(status) is a verdict, not a stall")
            #expect(message == nil)
        }
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
