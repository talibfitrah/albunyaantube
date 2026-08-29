import FitrahAPI
import Foundation
import InnerTubeKit
import Synchronization
import Testing
@testable import FitrahTube

/// Plan C Task 3: the pure report payload (target/parent/subtype/reason mapping) and the
/// hand-written `POST /api/v1/reports` client's status mapping.
struct ReportPayloadTests {
    private static let ctx = ReportContext(targetType: .video, targetId: "abc", parentType: nil,
                                           parentId: nil, contentSubType: nil)
    private static let channelSelfReport = ReportContext(targetType: .channel, targetId: "UC1", parentType: nil,
                                                         parentId: nil, contentSubType: nil)

    @Test func aVideoReportedFromAChannelTabCarriesTheChannelParent() throws {
        // RULING 66 + spec 10 ("Report (VIDEO, with parent PLAYLIST/CHANNEL and subtype)"). Android's
        // 5-arg newInstance (ContentReportBottomSheet.kt:178-184) supports exactly this and is never
        // called from anywhere (all three call sites use the 2-arg form) -- iOS ships the wire format
        // Android defined but never sent.
        let ctx = ReportContext(targetType: .video, targetId: "abc", parentType: .channel,
                                parentId: "UC123", contentSubType: nil)
        let body = try #require(try ReportPayload.make(context: ctx, reasons: [.music], otherText: nil).get())
        #expect(body.targetType == "VIDEO")
        #expect(body.targetId == "abc")
        #expect(body.parentType == "CHANNEL")
        #expect(body.parentId == "UC123")
        #expect(body.reasons == ["MUSIC"])
        // The three parent fields are exactly what the generated client CANNOT send (the OpenAPI
        // schema omits them, api-specification.yaml:341-363) -- which is why ReportClient is
        // hand-written. This test is the reason that decision exists.
    }

    @Test func nilParentFieldsAreOmittedFromTheJSONEntirely() throws {
        // Encoding them as explicit nulls is not the same as omitting them: the controller's record
        // binds absent and null identically today, but a `@Size(max = 128) String parentId` that
        // arrives as "" would be kept. Optional + default encoder = omitted; pin it on the bytes.
        let body = try #require(try ReportPayload.make(context: Self.channelSelfReport, reasons: [.other], otherText: "x").get())
        let json = String(decoding: try JSONEncoder().encode(body), as: UTF8.self)
        #expect(json.contains("parentType") == false)
        #expect(json.contains("parentId") == false)
        #expect(json.contains("contentSubType") == false)
        #expect(json.contains(#""otherDescription":"x""#))
    }

    @Test func aLiveRowCarriesTheLivestreamSubtype() throws {
        // ChannelLiveTabFragment.kt:62-69
        let ctx = ReportContext(targetType: .video, targetId: "abc", parentType: .channel,
                                parentId: "UC1", contentSubType: .livestream)
        let body = try #require(try ReportPayload.make(context: ctx, reasons: [.music], otherText: nil).get())
        #expect(body.contentSubType == "LIVESTREAM")
    }

    @Test func aShortCarriesTheShortSubtypeAndItsChannelParent() throws {
        // ChannelShortsTabFragment.kt:89-101
        let ctx = ReportContext(targetType: .video, targetId: "abc", parentType: .channel,
                                parentId: "UC1", contentSubType: .short)
        let body = try #require(try ReportPayload.make(context: ctx, reasons: [.music], otherText: nil).get())
        #expect(body.contentSubType == "SHORT")
        #expect(body.parentType == "CHANNEL")
    }

    @Test func aChannelReportingItselfCarriesNoParent() throws {
        // ChannelDetailFragment.kt:449-453
        let body = try #require(try ReportPayload.make(context: Self.channelSelfReport, reasons: [.shirk], otherText: nil).get())
        #expect(body.targetType == "CHANNEL")
        #expect(body.parentType == nil)
        #expect(body.parentId == nil)
    }

    @Test func aBlankParentIdIsCoercedToNil() throws {
        // ReportRepository.kt:41 -- the backend keeps parent context only when parentType is
        // CHANNEL/PLAYLIST *and* parentId is non-blank (ContentReportService.java:83-93); sending an
        // empty string just gets it dropped server-side, so drop it here where it can be tested.
        let ctx = ReportContext(targetType: .video, targetId: "abc", parentType: .playlist,
                                parentId: "  ", contentSubType: nil)
        let body = try #require(try ReportPayload.make(context: ctx, reasons: [.music], otherText: nil).get())
        #expect(body.parentId == nil)
        #expect(body.parentType == nil)
    }

    @Test func zeroReasonsIsRefusedWithoutANetworkCall() {
        // ReportViewModel.kt:34-37, but with the localized key Android leaves unused (RULING 70).
        #expect(ReportPayload.make(context: Self.ctx, reasons: [], otherText: nil)
            == .failure(.noReasons(messageKey: "report_select_reason")))
    }

    @Test func elevenReasonsCannotBeConstructed() {
        // ContentReportController.java:161 validates @Size(max = 10) against an 11-value enum. Android
        // lets the user check all 11 and eats a 400. The cap is here so the UI can render the eleventh
        // row disabled rather than discovering the limit from a server error.
        #expect(ReportReason.allCases.count == 11)
        #expect(ReportPayload.maxReasons == 10)
        #expect(ReportPayload.make(context: Self.ctx, reasons: ReportReason.allCases, otherText: nil)
            == .failure(.tooManyReasons(messageKey: "report_reason_limit")))
    }

    @Test func otherDescriptionIsTrimmedAndCappedAtFiveHundred() throws {
        // bottom_sheet_content_report.xml:124-142 (maxLength 500) and the server's @Size(max = 500).
        let long = "  " + String(repeating: "x", count: 600) + "  "
        let body = try #require(try ReportPayload.make(context: Self.ctx, reasons: [.other], otherText: long).get())
        #expect(body.otherDescription?.count == 500)
        #expect(body.otherDescription?.hasPrefix("x") == true)
        let blank = try #require(try ReportPayload.make(context: Self.ctx, reasons: [.other], otherText: "   ").get())
        #expect(blank.otherDescription == nil)
    }

    @Test func otherDescriptionIsDroppedWhenOtherIsNotSelected() throws {
        // ContentReportBottomSheet.kt:76 clears the field when the box is unchecked.
        let body = try #require(try ReportPayload.make(context: Self.ctx, reasons: [.music], otherText: "typed then unchecked").get())
        #expect(body.otherDescription == nil)
    }

    @Test func everyReasonHasACatalogKey() {
        // RULING 70. All 11 report_reason_* keys already exist; this pins the mapping so a renamed
        // enum case cannot silently render a raw key on screen.
        for reason in ReportReason.allCases {
            #expect(Bundle.main.localizedString(forKey: reason.messageKey, value: nil, table: nil) != reason.messageKey,
                    "\(reason) -> \(reason.messageKey)")
        }
        #expect(ReportReason.allCases.map(\.rawValue) == [
            "MUSIC", "NUDITY", "BAD_LANGUAGE", "FLIRTING", "ROMANCE", "AWRAH", "SHIRK", "BIDAH",
            "VIOLENCE", "MISINFORMATION", "OTHER",
        ])
    }

    // MARK: - ReportClient

    private struct StubTransport: HTTPTransport {
        var status = 201
        var error: Error?
        let onSend: @Sendable (HTTPRequest) -> Void
        func send(_ request: HTTPRequest) async throws -> HTTPResponse {
            onSend(request)
            if let error { throw error }
            return HTTPResponse(status: status, headers: [:], body: Data())
        }
    }

    private static func body() throws -> ReportBody {
        try #require(try ReportPayload.make(context: ctx, reasons: [.music], otherText: nil).get())
    }

    private static func client(_ transport: StubTransport) -> ReportClient {
        ReportClient(transport: transport, baseURL: URL(string: "https://app.fitrahtube.com/")!, deviceId: DeviceId(value: "device-123"))
    }

    @Test func submitPostsToReportsWithDeviceIdAndMapsStatuses() async throws {
        // ContentReportController.java:48-57: 400 without X-Device-Id. 201 -> succeeded, 429 ->
        // rateLimited (ruling 72: the sheet stays open), anything else -> failed(report_error).
        let sent = Mutex<[HTTPRequest]>([])
        let transport = StubTransport { request in sent.withLock { $0.append(request) } }
        #expect(try await Self.client(transport).submit(Self.body()) == .succeeded)
        let request = try #require(sent.withLock { $0.first })
        #expect(request.method == "POST")
        #expect(request.url.absoluteString == "https://app.fitrahtube.com/api/v1/reports")
        #expect(request.headers["X-Device-Id"] == "device-123")
        #expect(request.headers["Content-Type"] == "application/json")
        #expect(try await Self.client(StubTransport(status: 429) { _ in }).submit(Self.body()) == .rateLimited)
        #expect(try await Self.client(StubTransport(status: 500) { _ in }).submit(Self.body()) == .failed(messageKey: "report_error"))
        #expect(try await Self.client(StubTransport(error: URLError(.notConnectedToInternet)) { _ in }).submit(Self.body())
            == .failed(messageKey: "report_error"))
    }

    @Test func cancellationIsRethrownNotSwallowed() async throws {
        // ReportRepository.kt:50-51: a cancelled submit must not surface as "Failed to submit".
        await #expect(throws: CancellationError.self) {
            try await Self.client(StubTransport(error: CancellationError()) { _ in }).submit(Self.body())
        }
    }
}
