import FitrahAPI
import Foundation
import InnerTubeKit

/// Phase 3 Task 5, reconciliation note 3: the per-video `offlineAllowed` gate, read at save time
/// with ONE `GET /api/v1/videos/{id}` per player open. Hand-written over `HTTPTransport` (the
/// `PublicHeaders` shape, `X-Device-Id` included) because the generated client cannot decode the
/// raw Firestore `Video` model that endpoint returns (contradiction 5, Timestamp objects) — this
/// decodes ONLY `{youtubeId, offlineAllowed}` and ignores everything else.
///
/// Mapping (save-time fail-closed; sweep-time semantics live in `OfflineSweep.decide`):
/// a 200 that IS this video's Video model (JSON content type + a matching `youtubeId`) reads its
/// flag — true → `.allowed`, false or ABSENT → `.notAllowed` (the ruling's default-false —
/// channel-sourced videos were never admin-flagged); 410, and a 404 carrying the BACKEND'S OWN
/// error envelope, → `.gone` (left the catalog); anything else — 5xx, other 4xx, a 404 from anyone
/// but the backend, transport error, a 200 that is not this video's model → `.unreachable`,
/// NEVER `.gone`: a mistaken `.gone` mass-deletes the library at sweep time.
nonisolated struct OfflineGateClient: Sendable {
    private let transport: HTTPTransport
    private let baseURL: URL
    private let deviceId: DeviceId

    init(transport: HTTPTransport = URLSessionTransport(), baseURL: URL, deviceId: DeviceId) {
        self.transport = transport
        self.baseURL = baseURL
        self.deviceId = deviceId
    }

    /// Only the two fields the gate reads; everything else in the Video model is ignored.
    /// `youtubeId` is the MARKER, not data: it is the field
    /// `PublicContentService.getVideoDetails` looks the row up by, so the backend's own answer for
    /// this path always carries it and it always equals the id we asked for. (`Video.id` is the
    /// Firestore document id — `VideoRepository.save` takes it from `getCollection().document()` —
    /// and never equals the requested YouTube id.)
    private struct VideoDTO: Decodable {
        var youtubeId: String?
        var offlineAllowed: Bool?
    }

    /// The backend's error envelope, as `GlobalExceptionHandler` writes it for every mapped
    /// exception: `{timestamp, status, error, message, path}`.
    private struct ErrorEnvelope: Decodable {
        var status: Int?
        var error: String?
    }

    /// Cubic R5-2: a 404 is a catalog removal ONLY when the backend itself said so. A reverse
    /// proxy, a CDN edge, or a deploy briefly serving a default vhost answers 404 for
    /// `/api/v1/videos/*` too — and `sweep()` turns `.gone` into `deleteAll`: every saved file and
    /// row, irreversibly, on the next launch or foreground. `ResourceNotFoundException` always
    /// comes back as JSON carrying its own `status`/`error`; an HTML error page, an empty body, or
    /// somebody else's JSON does not, and reads as `.unreachable` (keep, and retry next sweep).
    private static func isBackendNotFound(_ response: HTTPResponse) -> Bool {
        guard isJSON(response),
              let envelope = try? JSONDecoder().decode(ErrorEnvelope.self, from: response.body) else { return false }
        return envelope.status == 404 && envelope.error != nil
    }

    /// Both legs' first question, shared: did whoever answered claim to be speaking JSON at all.
    private static func isJSON(_ response: HTTPResponse) -> Bool {
        response.headers.contains {
            $0.key.lowercased() == "content-type" && $0.value.lowercased().contains("application/json")
        }
    }

    func answer(_ videoId: String) async -> GateAnswer {
        let request = HTTPRequest(method: "GET", url: baseURL.appending(path: "api/v1/videos/\(videoId)"),
                                  headers: ["X-Device-Id": deviceId.value], body: nil)
        guard let response = try? await transport.send(request) else { return .unreachable }
        switch response.status {
        case 200:
            // Security r1 P0-1: `VideoDTO` decodes ANY JSON object, so `{}`, an auth envelope, a WAF
            // block page or a captive portal's 200 all read as "the flag is absent" → `.notAllowed`
            // → `deleteGateRevoked` → the sweep erases the library. The 404 leg's discipline applies
            // here too: the backend's own content type AND an affirmative marker that this body is
            // the Video model FOR THE VIDEO WE ASKED ABOUT. Only then does an absent/false flag mean
            // "no" — anything else is no answer at all.
            guard Self.isJSON(response),
                  let dto = try? JSONDecoder().decode(VideoDTO.self, from: response.body),
                  dto.youtubeId == videoId else { return .unreachable }
            return dto.offlineAllowed == true ? .allowed : .notAllowed
        case 404:
            return Self.isBackendNotFound(response) ? .gone : .unreachable
        case 410:
            // No envelope check: `ContentGoneException` is the only thing that answers Gone for a
            // video URL — an edge that knows nothing about the resource answers 404, not 410.
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
/// - An existing item outranks everything: a completed item must open OFFLINE, where the gate fetch
///   never lands. Revocation is re-checked in two places behind this view, neither of them here —
///   `OfflineManager.begin` re-consults before every start and PARKS a refused one at "Waiting"
///   (review I2: it never deletes, because telling backend drift from a real revocation needs the
///   sweep's whole-library evidence), and the belted sweep is what actually removes the row and its
///   bytes. So a `.progress` row can go back to "Waiting" while this button still reads `.progress`;
///   it disappears when the sweep spends the verdict.
/// - `downloadsEnabled == false` (the remote kill-switch) hides ONLY the `.save` state, silently
///   (fork D): the switch governs saving, not access to what's already
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
