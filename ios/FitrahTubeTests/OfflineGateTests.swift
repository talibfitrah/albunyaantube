import FitrahAPI
import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// Phase 3 Task 5, reconciliation note 3: the hand-written per-video `offlineAllowed` gate.
/// Save-time semantics are FAIL-CLOSED (anything but a 200-with-true hides the Save button);
/// sweep-time semantics ride the same `GateAnswer` (`OfflineSweep.decide`), where `.unreachable`
/// keeps and `.gone` deletes — which is why the 5xx/transport rows below pin `.unreachable` and
/// NEVER `.gone`: a mistaken `.gone` mass-deletes the library.
@Suite struct OfflineGateTests {

    // MARK: - OfflineGateClient status/decode mapping

    private struct Canned: HTTPTransport {
        var status = 200
        var body = Data()
        var fail = false
        func send(_ request: HTTPRequest) async throws -> HTTPResponse {
            if fail { throw URLError(.notConnectedToInternet) }
            #expect(request.headers["X-Device-Id"] == "device-1")
            #expect(request.url.path() == "/api/v1/videos/xc7keR2piUM")
            return HTTPResponse(status: status, headers: [:], body: body)
        }
    }

    private func client(status: Int = 200, json: String = "{}", fail: Bool = false) -> OfflineGateClient {
        OfflineGateClient(transport: Canned(status: status, body: Data(json.utf8), fail: fail),
                          baseURL: URL(string: "https://app.fitrahtube.com/")!, deviceId: DeviceId(value: "device-1"))
    }

    @Test func a200WithOfflineAllowedTrueIsAllowed() async {
        #expect(await client(json: #"{"offlineAllowed":true}"#).answer("xc7keR2piUM") == .allowed)
    }

    /// Contradiction 5: production returns the raw Firestore `Video` model, Timestamp objects
    /// included. The client decodes ONLY `{offlineAllowed}` and must ignore everything else.
    @Test func aProductionShapedBodyDecodesTheFlagAndIgnoresTimestamps() async {
        let body = """
            {"id":"abc","youtubeId":"xc7keR2piUM","title":"Lecture","categoryIds":[],"status":"APPROVED",
             "createdAt":{"seconds":1764112840,"nanos":608000000},"updatedAt":{"seconds":1785308468,"nanos":854000000},
             "offlineAllowed":true,"approved":true}
            """
        #expect(await client(json: body).answer("xc7keR2piUM") == .allowed)
    }

    @Test func a200WithTheFlagFalseIsNotAllowed() async {
        #expect(await client(json: #"{"offlineAllowed":false}"#).answer("xc7keR2piUM") == .notAllowed)
    }

    /// The ruling's default-false: a 200 without the field (a video registered before the flag
    /// existed) was never admin-flagged — not saveable.
    @Test func a200WithoutTheFlagIsNotAllowed() async {
        #expect(await client(json: "{}").answer("xc7keR2piUM") == .notAllowed)
    }

    @Test func a404IsGone() async {
        #expect(await client(status: 404).answer("xc7keR2piUM") == .gone)
    }

    @Test func a410IsGone() async {
        #expect(await client(status: 410).answer("xc7keR2piUM") == .gone)
    }

    /// THE mass-delete pin: a 5xx is the backend having a bad day, not the video leaving the
    /// catalog. `.gone` here would let the sweep delete the whole library during an outage.
    @Test func a5xxIsUnreachableNeverGone() async {
        for status in [500, 502, 503] {
            #expect(await client(status: status).answer("xc7keR2piUM") == .unreachable)
        }
    }

    @Test func aTransportErrorIsUnreachableNeverGone() async {
        #expect(await client(fail: true).answer("xc7keR2piUM") == .unreachable)
    }

    /// A 200 whose body cannot decode (captive portal, proxy junk) is no answer, not a "no" —
    /// `.notAllowed` would delete a library row at sweep time on garbage.
    @Test func anUndecodable200IsUnreachable() async {
        #expect(await client(json: "not json").answer("xc7keR2piUM") == .unreachable)
    }

    /// Other 4xx (401/403/429...) are neither a catalog removal nor an admin answer.
    @Test func anUnexpected4xxIsUnreachable() async {
        for status in [400, 401, 403, 429] {
            #expect(await client(status: status).answer("xc7keR2piUM") == .unreachable)
        }
    }

    // MARK: - Button-state table (gate × config × item-status → state)

    private static let allGates: [GateAnswer?] = [nil, .allowed, .notAllowed, .gone, .unreachable]
    private static let allStatuses: [OfflineStatus?] = [nil] + OfflineStatus.allCases.map { $0 }

    /// Fork D (Task 5 review fold-in): the kill-switch governs SAVING, not access to what is
    /// already saved — OFF hides only the `.save` state (silently). A running save stays
    /// visible/cancellable and a completed item stays openable.
    @Test func downloadsDisabledHidesOnlyTheSaveState() {
        for gate in Self.allGates {
            for status: OfflineStatus in [.queued, .running, .paused] {
                #expect(SaveAffordance.state(gate: gate, downloadsEnabled: false, itemStatus: status) == .progress)
            }
            #expect(SaveAffordance.state(gate: gate, downloadsEnabled: false, itemStatus: .completed) == .open)
            for status: OfflineStatus? in [nil, .failed, .cancelled] {
                #expect(SaveAffordance.state(gate: gate, downloadsEnabled: false, itemStatus: status) == .hidden)
            }
        }
    }

    /// An existing item's presence outranks the gate: its save was authorized at save time and
    /// the sweep owns revocation — a completed item must open OFFLINE, where the gate fetch
    /// never lands.
    @Test func anInFlightItemShowsProgressRegardlessOfGate() {
        for gate in Self.allGates {
            for status: OfflineStatus in [.queued, .running, .paused] {
                #expect(SaveAffordance.state(gate: gate, downloadsEnabled: true, itemStatus: status) == .progress)
            }
        }
    }

    @Test func aCompletedItemShowsOpenRegardlessOfGate() {
        for gate in Self.allGates {
            #expect(SaveAffordance.state(gate: gate, downloadsEnabled: true, itemStatus: .completed) == .open)
        }
    }

    /// Fail-closed: only an affirmative `.allowed` shows Save. Unknown (fetch not landed),
    /// not-allowed, gone and unreachable all render NOTHING — refusal says nothing at all here.
    @Test func noItemShowsSaveOnlyWhenTheGateAffirms() {
        #expect(SaveAffordance.state(gate: .allowed, downloadsEnabled: true, itemStatus: nil) == .save)
        for gate: GateAnswer? in [nil, .notAllowed, .gone, .unreachable] {
            #expect(SaveAffordance.state(gate: gate, downloadsEnabled: true, itemStatus: nil) == .hidden)
        }
    }

    /// failed/cancelled rows behave like "no item": a fresh save upserts over the old row
    /// (`OfflineManager.save` tears the old one down first), and the gate must re-affirm.
    @Test func aFailedOrCancelledItemBehavesLikeNoItem() {
        for status: OfflineStatus in [.failed, .cancelled] {
            #expect(SaveAffordance.state(gate: .allowed, downloadsEnabled: true, itemStatus: status) == .save)
            for gate: GateAnswer? in [nil, .notAllowed, .gone, .unreachable] {
                #expect(SaveAffordance.state(gate: gate, downloadsEnabled: true, itemStatus: status) == .hidden)
            }
        }
    }
}
