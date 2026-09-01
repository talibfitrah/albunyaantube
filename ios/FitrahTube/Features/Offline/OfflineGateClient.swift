import FitrahAPI
import Foundation
import InnerTubeKit

/// Phase 3 Task 5, reconciliation note 3: the per-video `offlineAllowed` gate, read at save time
/// with ONE `GET /api/v1/videos/{id}` per player open. Hand-written over `HTTPTransport` (the
/// `PublicHeaders` shape, `X-Device-Id` included) because the generated client cannot decode the
/// raw Firestore `Video` model that endpoint returns (contradiction 5, Timestamp objects) — this
/// decodes ONLY `{offlineAllowed}` and ignores everything else.
///
/// Mapping (save-time fail-closed; sweep-time semantics live in `OfflineSweep.decide`):
/// 200 + `offlineAllowed: true` → `.allowed`; 200 with the flag false or ABSENT → `.notAllowed`
/// (the ruling's default-false — channel-sourced videos were never admin-flagged); 404/410 →
/// `.gone` (left the catalog); anything else — 5xx, other 4xx, transport error, undecodable 200 —
/// → `.unreachable`, NEVER `.gone`: a mistaken `.gone` mass-deletes the library at sweep time.
nonisolated struct OfflineGateClient: Sendable {
    private let transport: HTTPTransport
    private let baseURL: URL
    private let deviceId: DeviceId

    init(transport: HTTPTransport = URLSessionTransport(), baseURL: URL, deviceId: DeviceId) {
        self.transport = transport
        self.baseURL = baseURL
        self.deviceId = deviceId
    }

    /// Only the field the gate reads; everything else in the Video model is ignored.
    private struct VideoDTO: Decodable {
        var offlineAllowed: Bool?
    }

    func answer(_ videoId: String) async -> GateAnswer {
        let request = HTTPRequest(method: "GET", url: baseURL.appending(path: "api/v1/videos/\(videoId)"),
                                  headers: ["X-Device-Id": deviceId.value], body: nil)
        guard let response = try? await transport.send(request) else { return .unreachable }
        switch response.status {
        case 200:
            guard let dto = try? JSONDecoder().decode(VideoDTO.self, from: response.body) else {
                // A 200 that can't decode (captive portal, proxy junk) is no answer, not a "no".
                return .unreachable
            }
            return dto.offlineAllowed == true ? .allowed : .notAllowed
        case 404, 410:
            return .gone
        default:
            return .unreachable
        }
    }
}

/// The Save button's rendered state — pure, pinned by `OfflineGateTests`' table.
nonisolated enum SaveButtonState: Equatable, Sendable {
    case hidden, save, progress, open
}

/// (gate × config × item-status) → button state.
/// - An existing item outranks everything: its save was authorized at save time and the sweep owns
///   revocation — a completed item must open OFFLINE, where the gate fetch never lands.
/// - `downloadsEnabled == false` (the remote kill-switch) hides ONLY the `.save` state, silently
///   (fork D, Task 5 review fold-in): the switch governs saving, not access to what's already
///   saved — a running save stays visible/cancellable, a completed item stays openable.
/// - With no item (or a failed/cancelled row, which a fresh save upserts over), only an
///   affirmative `.allowed` under an enabled switch shows Save; everything else renders nothing.
nonisolated enum SaveAffordance {
    static func state(gate: GateAnswer?, downloadsEnabled: Bool, itemStatus: OfflineStatus?) -> SaveButtonState {
        switch itemStatus {
        case .queued, .running, .paused: return .progress
        case .completed: return .open
        case .failed, .cancelled, nil: return downloadsEnabled && gate == .allowed ? .save : .hidden
        }
    }
}
