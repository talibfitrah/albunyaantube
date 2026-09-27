import FitrahAPI
import Foundation
import InnerTubeKit

/// Phase 3 Task 5, reconciliation note 3: the per-video `offlineAllowed` gate — ONE
/// `GET /api/v1/videos/{id}`, plus playback's own `HEAD /api/v1/channels/{id}` when a channel is
/// known, run together. Hand-written over `HTTPTransport` (the `PublicHeaders` shape, `X-Device-Id`
/// included) because the generated client cannot decode the raw Firestore `Video` model that
/// endpoint returns (contradiction 5, Timestamp objects) — this decodes ONLY
/// `{youtubeId, offlineAllowed}` and ignores everything else.
///
/// The ruling (2026-09-27): offline MIRRORS playback — whatever playback's check lets play
/// (`BackendAvailabilityGate`: only a 410 refuses) may be saved, and an admin's explicit
/// `offlineAllowed=false` is the one extra block. So a backend 404 → `.allowed` (playback plays it:
/// a video in the catalog only through an approved channel or playlist, or no public registry row);
/// admins block by rejecting or archiving, which answers 410 → `.gone`, for the video or its channel.
/// A 200 that IS this video's Video model (JSON content type + a matching `youtubeId`) reads its
/// flag: an explicit false → `.notAllowed`; true, null or absent → `.allowed` (the backend serves
/// the same effective value, `Video.allowsOffline()`). Unlike playback this is fail-CLOSED at save
/// time: 5xx, other 4xx, a 404 from anyone but the backend, a transport error, a 200 that is not
/// this video's model, or a channel probe that fails → `.unreachable`, NEVER `.gone` — the sweep
/// keeps on `.unreachable`, and a mistaken `.gone` mass-deletes the library.
nonisolated struct OfflineGateClient: Sendable {
    private let transport: HTTPTransport
    private let baseURL: URL
    private let deviceId: DeviceId
    #if DEBUG
    /// Real-device check only (`AppContainer.offlineAllowedVideoId`): this ONE video answers
    /// `.allowed` without a request. Nothing else changes.
    var offlineAllowedVideoId: String?
    #endif

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

    /// Cubic R5-2: a 404 is an answer ONLY when the backend itself said so. A reverse proxy, a CDN
    /// edge, or a deploy briefly serving a default vhost answers 404 for `/api/v1/videos/*` too.
    /// `ResourceNotFoundException` always comes back as JSON carrying its own `status`/`error`; an
    /// HTML error page, an empty body, or somebody else's JSON does not, and reads as
    /// `.unreachable` (keep, and retry next sweep).
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

    /// `channelId`: the channel playback's own check asks about for this video (`PlayerArgs.channelId`,
    /// stamped on the row as `OfflineItem.channelId`), nil when playback asks only about the video.
    /// No default on purpose (review P1): every caller has to say which channel it means.
    func answer(_ videoId: String, channelId: String?) async -> GateAnswer {
        #if DEBUG
        if videoId == offlineAllowedVideoId { return .allowed }
        #endif
        // Both legs at once (review P3). Either one's 410 refuses; an explicit false blocks; a
        // channel nobody could vouch for is no answer at all.
        async let channel = channelAnswer(channelId)
        async let video = videoAnswer(videoId)
        let (channelVerdict, videoVerdict) = await (channel, video)
        if channelVerdict == .gone || videoVerdict == .gone { return .gone }
        if videoVerdict == .notAllowed { return .notAllowed }
        return channelVerdict == .unreachable ? .unreachable : videoVerdict
    }

    /// Playback's own channel probe (`BackendAvailabilityGate`: HEAD, only a 410 refuses) — but
    /// fail-CLOSED (review P2). Playback plays through a probe that errors, stalls or 5xxs; a Save
    /// must not, so anything but 2xx/404/410 is `.unreachable`: Save hidden, and the sweep keeps.
    private func channelAnswer(_ channelId: String?) async -> GateAnswer {
        guard let channelId else { return .allowed }
        let request = HTTPRequest(method: "HEAD", url: baseURL.appending(path: "api/v1/channels/\(channelId)"),
                                  headers: ["X-Device-Id": deviceId.value], body: nil)
        guard let response = try? await transport.send(request) else { return .unreachable }
        switch response.status {
        case 410: return .gone
        case 200..<300, 404: return .allowed
        default: return .unreachable
        }
    }

    private func videoAnswer(_ videoId: String) async -> GateAnswer {
        let request = HTTPRequest(method: "GET", url: baseURL.appending(path: "api/v1/videos/\(videoId)"),
                                  headers: ["X-Device-Id": deviceId.value], body: nil)
        guard let response = try? await transport.send(request) else { return .unreachable }
        switch response.status {
        case 200:
            // Security r1 P0-1: `VideoDTO` decodes ANY JSON object, so `{}`, an auth envelope, a WAF
            // block page or a captive portal's 200 would all read as a verdict — `.allowed` on no
            // answer at all, or, carrying another video's `false`, `.notAllowed` →
            // `deleteGateRevoked` → the sweep erases the library. The 404 leg's discipline applies
            // here too: the backend's own content type AND an affirmative marker that this body is
            // the Video model FOR THE VIDEO WE ASKED ABOUT. Only then does an explicit false mean
            // "no" — anything else is no answer at all.
            guard Self.isJSON(response),
                  let dto = try? JSONDecoder().decode(VideoDTO.self, from: response.body),
                  dto.youtubeId == videoId else { return .unreachable }
            return dto.offlineAllowed == false ? .notAllowed : .allowed
        case 404:
            return Self.isBackendNotFound(response) ? .allowed : .unreachable
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
///   never lands. Revocation is re-checked in two places behind this view, neither of them here.
///   `OfflineManager.begin` re-consults before every start, and its two refusals part company:
///   a per-video `.notAllowed`/`.gone` FAILS the row with `.notSaveable` (review I2: it never
///   deletes, because telling backend drift from a real revocation needs the sweep's whole-library
///   evidence; review NI1: it never parks either, because a per-video verdict is not a wall and a
///   parked head row starves every younger one), while an `.unreachable` transport answer — no
///   answer at all — parks the row at "Waiting" behind a timer. The belted sweep is what actually
///   removes the row and its bytes. So a `.progress` row can go back to "Waiting", or read as
///   failed, while this button still says `.progress`; it disappears when the sweep spends the
///   verdict, or when the user's own Retry does.
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
