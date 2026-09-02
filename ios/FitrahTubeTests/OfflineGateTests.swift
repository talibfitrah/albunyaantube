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
        /// Nil means the response carries no `Content-Type` at all (an empty proxy error page).
        var contentType: String? = "application/json;charset=UTF-8"
        var fail = false
        func send(_ request: HTTPRequest) async throws -> HTTPResponse {
            if fail { throw URLError(.notConnectedToInternet) }
            #expect(request.headers["X-Device-Id"] == "device-1")
            #expect(request.url.path() == "/api/v1/videos/xc7keR2piUM")
            return HTTPResponse(status: status, headers: contentType.map { ["Content-Type": $0] } ?? [:], body: body)
        }
    }

    private func client(status: Int = 200, json: String = "{}",
                        contentType: String? = "application/json;charset=UTF-8",
                        fail: Bool = false) -> OfflineGateClient {
        OfflineGateClient(transport: Canned(status: status, body: Data(json.utf8), contentType: contentType, fail: fail),
                          baseURL: URL(string: "https://app.fitrahtube.com/")!, deviceId: DeviceId(value: "device-1"))
    }

    /// `GlobalExceptionHandler.handleResourceNotFoundException` verbatim (timestamp/status/error/
    /// message/path, `application/json`).
    private static let notFoundEnvelope = """
        {"timestamp":"2026-09-02T10:00:00.123","status":404,"error":"Not Found",
         "message":"Video not found with id: xc7keR2piUM","path":"/api/v1/videos/xc7keR2piUM"}
        """

    @Test func a200WithOfflineAllowedTrueIsAllowed() async {
        #expect(await client(json: #"{"youtubeId":"xc7keR2piUM","offlineAllowed":true}"#)
                    .answer("xc7keR2piUM") == .allowed)
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
        #expect(await client(json: #"{"youtubeId":"xc7keR2piUM","offlineAllowed":false}"#)
                    .answer("xc7keR2piUM") == .notAllowed)
    }

    /// The ruling's default-false: a 200 without the field (a video registered before the flag
    /// existed) was never admin-flagged — not saveable.
    @Test func a200WithoutTheFlagIsNotAllowed() async {
        #expect(await client(json: #"{"youtubeId":"xc7keR2piUM"}"#).answer("xc7keR2piUM") == .notAllowed)
    }

    /// Security r1 P0-1, the mass-delete pin on the OTHER leg: `VideoDTO` decodes ANY JSON object,
    /// so `{}`, an auth envelope, a WAF block page or a captive portal's 200 all read as "the flag
    /// is absent" → `.notAllowed` → `deleteGateRevoked` → the sweep erases the library. The 200 leg
    /// gets the 404 leg's discipline: the backend's own JSON content type AND an affirmative marker
    /// that the body is the Video model for the video we asked about. `youtubeId` is that marker —
    /// `PublicContentService.getVideoDetails` looks the row up BY it, while `id` is a Firestore
    /// auto-id (`VideoRepository.save` → `getCollection().document()`) that never equals the
    /// requested id.
    @Test func a200ThatIsNotThisVideosModelIsUnreachableNeverNotAllowed() async {
        // An empty object: decodes, marker absent.
        #expect(await client(json: "{}").answer("xc7keR2piUM") == .unreachable)
        // Somebody else's JSON (an auth envelope, a WAF block page).
        #expect(await client(json: #"{"message":"login required"}"#).answer("xc7keR2piUM") == .unreachable)
        // The Video model — for a DIFFERENT video (a proxy serving a cached or default document).
        #expect(await client(json: #"{"id":"abc","youtubeId":"otherVideo1","offlineAllowed":false}"#)
                    .answer("xc7keR2piUM") == .unreachable)
        // The right body served with the wrong content type: an edge echoing JSON is not the backend.
        #expect(await client(json: #"{"youtubeId":"xc7keR2piUM","offlineAllowed":false}"#,
                             contentType: "text/html").answer("xc7keR2piUM") == .unreachable)
        // A 200 with no content type at all.
        #expect(await client(json: #"{"youtubeId":"xc7keR2piUM"}"#, contentType: nil)
                    .answer("xc7keR2piUM") == .unreachable)
    }

    /// A 404 the BACKEND produced: the video left the catalog, and the sweep deletes the copy.
    @Test func aBackend404EnvelopeIsGone() async {
        #expect(await client(status: 404, json: Self.notFoundEnvelope).answer("xc7keR2piUM") == .gone)
    }

    /// Cubic R5-2, the second mass-delete pin: a reverse proxy, a CDN edge or a deploy briefly
    /// serving a default vhost answers 404 for `/api/v1/videos/*` too, and `sweep()` turns `.gone`
    /// into `deleteAll` — every saved file and row, irreversibly, on the next launch. Only the
    /// backend's own error envelope (JSON content type, `status` + `error`) may map to `.gone`.
    @Test func a404WithoutTheBackendEnvelopeIsUnreachableNeverGone() async {
        // A CDN/default-vhost error page.
        #expect(await client(status: 404, json: "<html><body>404 Not Found</body></html>",
                             contentType: "text/html").answer("xc7keR2piUM") == .unreachable)
        // A bare 404 with no body and no content type at all.
        #expect(await client(status: 404, json: "", contentType: nil).answer("xc7keR2piUM") == .unreachable)
        // JSON, but somebody else's JSON.
        #expect(await client(status: 404, json: #"{"error":"not found"}"#).answer("xc7keR2piUM") == .unreachable)
        // The envelope's shape served with a non-JSON content type — an edge echoing a body it
        // proxied is not the backend answering.
        #expect(await client(status: 404, json: Self.notFoundEnvelope, contentType: "text/plain")
                    .answer("xc7keR2piUM") == .unreachable)
    }

    /// 410 needs no envelope: `ContentGoneException` is the only thing in the world that answers
    /// Gone for a video URL — an edge that knows nothing about the resource answers 404.
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
