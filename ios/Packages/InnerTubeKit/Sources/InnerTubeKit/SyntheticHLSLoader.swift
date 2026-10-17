import AVFoundation
import Foundation
import ObjectiveC

/// Serves `fitrah-hls://<uuid>/{master,v<i>,a<i>}.m3u8` to AVFoundation for a `tv_samsung` stream (spec §4.3, R12, R14,
/// R16, R42). Only playlists cross this delegate: every segment URL is an absolute `https` googlevideo URL, so media never
/// touches it. A media playlist costs one ranged GET of the rendition's `sidx`, kept for this loader's life. The
/// package's only `import AVFoundation`.
public final class SyntheticHLSLoader: NSObject, AVAssetResourceLoaderDelegate, @unchecked Sendable {
    // @unchecked Sendable: `playlists` is guarded by `lock`, `tasks` is touched only on `queue` (AVFoundation calls the
    // delegate there, and every finish hops back to it); every other stored property is immutable.
    public static let scheme = "fitrah-hls"
    /// One session for every loader's `sidx` GETs (each `URLSessionTransport` is its own `URLSession`).
    public static let sharedTransport: HTTPTransport = URLSessionTransport()
    nonisolated(unsafe) private static var retainKey: UInt8 = 0

    enum LoaderError: Error, Equatable {
        case unknownPlaylist(String)
        case http(Int)
    }

    private let model: SyntheticHLS
    private let userAgent: String
    private let transport: HTTPTransport
    private let retryDelay: Duration
    private let onRefused: (@Sendable () -> Void)?
    private let queue = DispatchQueue(label: "SyntheticHLSLoader")
    private let lock = NSLock()
    private var playlists: [String: String] = [:]
    /// In-flight answers by loading request; whoever removes an entry (the finish or `didCancel`) owns that request.
    private var tasks: [ObjectIdentifier: Task<Void, Never>] = [:]

    init(model: SyntheticHLS, userAgent: String, transport: HTTPTransport, retryDelay: Duration,
         onRefused: (@Sendable () -> Void)? = nil) {
        self.model = model
        self.userAgent = userAgent
        self.transport = transport
        self.retryDelay = retryDelay
        self.onRefused = onRefused
    }

    /// The asset for one synthetic stream. `url` is the `Resolved` stream's own `fitrah-hls` master URL, so the host's
    /// URL-identity reuse keeps working. `AVAssetResourceLoader` holds its delegate weakly, so the asset retains it.
    public static func asset(url: URL, model: SyntheticHLS, userAgent: String, options: [String: Any] = [:],
                             transport: HTTPTransport = sharedTransport, retryDelay: Duration = .seconds(1),
                             onRefused: (@Sendable () -> Void)? = nil) -> AVURLAsset {
        let asset = AVURLAsset(url: url, options: options)
        let loader = SyntheticHLSLoader(model: model, userAgent: userAgent, transport: transport, retryDelay: retryDelay,
                                        onRefused: onRefused)
        asset.resourceLoader.setDelegate(loader, queue: loader.queue)
        objc_setAssociatedObject(asset, &retainKey, loader, .OBJC_ASSOCIATION_RETAIN)
        return asset
    }

    public func resourceLoader(_ resourceLoader: AVAssetResourceLoader,
                               shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest) -> Bool {
        guard loadingRequest.request.url?.scheme == Self.scheme, let name = loadingRequest.request.url?.lastPathComponent else {
            return false
        }
        let pending = PendingRequest(loadingRequest)
        let id = ObjectIdentifier(loadingRequest)
        // Set before the task's finish can run: that hops onto `queue`, which this call is still occupying.
        tasks[id] = Task {
            let result: Result<Data, Error>
            do { result = .success(Data(try await answer(name).utf8)) } catch { result = .failure(error) }
            queue.async {
                guard self.tasks.removeValue(forKey: id) != nil else { return }   // cancelled: AVFoundation is done with it
                pending.finish(result)
            }
        }
        return true
    }

    /// No work after a cancel: the `sidx` GET (or the retry wait) stops, and the request is never finished.
    public func resourceLoader(_ resourceLoader: AVAssetResourceLoader, didCancel loadingRequest: AVAssetResourceLoadingRequest) {
        tasks.removeValue(forKey: ObjectIdentifier(loadingRequest))?.cancel()
    }

    /// The text of one playlist: `master.m3u8` from the model, `v<i>.m3u8` / `a<i>.m3u8` from one `sidx` GET each.
    func answer(_ name: String) async throws -> String {
        if name == "master.m3u8" { return model.masterPlaylist() }
        guard let rendition = rendition(named: name) else { throw LoaderError.unknownPlaylist(name) }
        if let kept = lock.withLock({ playlists[name] }) { return kept }
        let index = try await fetchIndex(rendition)
        let segments = try SIDX.parse(index, indexEnd: rendition.indexRange.upperBound, contentLength: rendition.contentLength)
        let text = SyntheticHLS.mediaPlaylist(rendition, segments: segments)
        lock.withLock { playlists[name] = text }
        return text
    }

    private func rendition(named name: String) -> SyntheticHLS.Rendition? {
        guard name.hasSuffix(".m3u8"), let index = Int(name.dropFirst().dropLast(".m3u8".count)) else { return nil }
        let list = name.hasPrefix("v") ? model.video : name.hasPrefix("a") ? model.audio : []
        return list.indices.contains(index) ? list[index] : nil
    }

    /// R42: a 403 is retried once, `retryDelay` later (googlevideo refuses the first seconds after `/player`). Any
    /// other answer than a 206 of exactly the index range (a 200 is the whole file), or a second 403, fails this
    /// playlist, which fails the item, and 360p keeps playing. A second 403 is also reported (revision 4): it is the
    /// signature of a wrong solve or a refused IP, and pauses `tv_samsung`.
    private func fetchIndex(_ rendition: SyntheticHLS.Rendition) async throws -> Data {
        let request = HTTPRequest(method: "GET", url: rendition.url, headers: [
            "User-Agent": userAgent,
            "Range": "bytes=\(rendition.indexRange.lowerBound)-\(rendition.indexRange.upperBound)",
        ], body: nil)
        var response = try await transport.send(request)
        if response.status == 403 {
            try await Task.sleep(for: retryDelay)
            response = try await transport.send(request)
            if response.status == 403 { onRefused?() }
        }
        guard response.status == 206, response.body.count == rendition.indexRange.count else {
            throw LoaderError.http(response.status)
        }
        return response.body
    }
}

/// The one AVFoundation object that crosses into the async fetch; touched only on the loader's queue.
private struct PendingRequest: @unchecked Sendable {
    let request: AVAssetResourceLoadingRequest

    init(_ request: AVAssetResourceLoadingRequest) { self.request = request }

    func finish(_ result: Result<Data, Error>) {
        guard !request.isCancelled else { return }   // cancelled, its `didCancel` still queued behind this finish
        switch result {
        case .success(let data):
            if let info = request.contentInformationRequest {
                info.contentType = "public.m3u-playlist"     // Gate 0: AVPlayer accepts this with no byte ranges
                info.contentLength = Int64(data.count)
                info.isByteRangeAccessSupported = false
            }
            request.dataRequest?.respond(with: data)
            request.finishLoading()
        case .failure(let error):
            request.finishLoading(with: error)
        }
    }
}
