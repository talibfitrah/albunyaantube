import FitrahAPI
import Foundation
import InnerTubeKit

/// Hand-written `POST /api/v1/reports` over the shared `HTTPTransport` (plan C reconciliation:
/// the generated `submitContentReport` cannot send `parentType`/`parentId`/`contentSubType`).
/// Same shape as `IndexClient`, but awaited -- the sheet shows the outcome.
struct ReportClient: Sendable {
    private let transport: HTTPTransport
    private let baseURL: URL
    private let deviceId: DeviceId

    init(transport: HTTPTransport = URLSessionTransport(), baseURL: URL, deviceId: DeviceId) {
        self.transport = transport
        self.baseURL = baseURL
        self.deviceId = deviceId
    }

    /// 201 → `.succeeded`; 429 → `.rateLimited` (ruling 72: the sheet stays open); anything else
    /// → `.failed("report_error")`. Cancellation is rethrown, never reported as a failure
    /// (`ReportRepository.kt:50-51`).
    func submit(_ body: ReportBody) async throws -> ReportState {
        let request = HTTPRequest(
            method: "POST", url: baseURL.appending(path: "api/v1/reports"),
            // ContentReportController.java:48-57: 400 without X-Device-Id.
            headers: ["Content-Type": "application/json", "X-Device-Id": deviceId.value],
            body: try JSONEncoder().encode(body))
        do {
            let response = try await transport.send(request)
            switch response.status {
            case 201: return .succeeded
            case 429: return .rateLimited
            default: return .failed(messageKey: "report_error")
            }
        } catch is CancellationError {
            throw CancellationError()
        } catch let e as URLError where e.code == .cancelled {
            throw CancellationError()
        } catch {
            return .failed(messageKey: "report_error")
        }
    }
}
