import FitrahAPI
import Foundation
import InnerTubeKit

/// Fire-and-forget push of browsed streams into the backend's search index
/// (`POST /api/v1/index/streams`; Android `IndexRepository.kt`). Hand-written over InnerTubeKit's
/// `HTTPTransport` rather than generated, because the endpoint is absent from
/// `api-specification.yaml` (plan reconciliation note 6). The response is ignored: a 429 is the
/// server's 30 s byte-identical-repeat dedupe (`IndexController.java:74-77`) and a retry would
/// only re-trip it; every other failure is logged in DEBUG and dropped, as on Android.
///
/// Callers `Task { await index.push(…) }` and never await it on a path the user is waiting on.
struct IndexClient: Sendable {
    enum SourceType: String, Sendable {
        case channel = "CHANNEL"
        case playlist = "PLAYLIST"
    }

    /// `IndexController.java:67-70` silently truncates above 50 while `IndexStreamsRequest.java:12`
    /// rejects above 60 outright -- 50 is the only batch size that is neither truncated nor 400'd.
    static let batchSize = 50

    private let transport: HTTPTransport
    private let baseURL: URL
    private let deviceId: DeviceId

    init(transport: HTTPTransport = URLSessionTransport(), baseURL: URL, deviceId: DeviceId) {
        self.transport = transport
        self.baseURL = baseURL
        self.deviceId = deviceId
    }

    /// Field-for-field `IndexStreamsRequest.kt:7-23`. `viewCount` is always nil: `VideoItem`
    /// carries YouTube's localized view *text*, not a number.
    private struct Request: Encodable {
        struct Item: Encodable {
            var id: String
            var name: String
            var thumbnailUrl: String?
            var uploaderName: String?
            var channelId: String?
            var duration: Int?
            var viewCount: Int64?
            var streamType: String
        }
        var sourceType: String
        var sourceId: String
        var items: [Item]
    }

    func push(sourceType: SourceType, sourceId: String, items: [VideoItem]) async {
        guard !items.isEmpty else { return }
        let url = baseURL.appending(path: "api/v1/index/streams")
        for start in stride(from: 0, to: items.count, by: Self.batchSize) {
            let batch = items[start..<min(start + Self.batchSize, items.count)].map { item in
                // streamType is flat "VIDEO" for every row, as Android's IndexRepository sends it; the
                // backend accepts it and nothing reads a SHORT distinction. `channelTab` has the tab
                // in scope if the index ever wants one.
                Request.Item(id: item.id, name: item.title, thumbnailUrl: item.thumbnailURL?.absoluteString,
                             uploaderName: item.channelName, channelId: item.channelId,
                             duration: item.durationSeconds, viewCount: nil, streamType: "VIDEO")
            }
            guard let body = try? JSONEncoder().encode(Request(sourceType: sourceType.rawValue, sourceId: sourceId, items: batch))
            else { continue }
            let request = HTTPRequest(
                method: "POST", url: url,
                headers: ["Content-Type": "application/json", "X-Device-Id": deviceId.value], body: body)
            do {
                let response = try await transport.send(request)
                #if DEBUG
                // Every status, not just failures: the live rig (`-fitrah-stdout`) reads the batch
                // size and the 30 s-repeat 429 off this line.
                print("IndexClient: \(sourceType.rawValue) \(sourceId) status=\(response.status) items=\(batch.count)")
                #endif
            } catch {
                #if DEBUG
                print("IndexClient: \(sourceType.rawValue) \(sourceId) failed: \(error)")
                #endif
            }
        }
    }
}
