import Foundation

/// What the download layer reports back to `OfflineManager`, keyed by `OfflineItem.id`.
nonisolated enum OfflineDownloadEvent: Sendable {
    case progress(id: String, bytesWritten: Int64, totalBytes: Int64?)
    /// The bytes are at `<directory>/<id>.tmp`; the manager renames them to their final name.
    case finished(id: String)
    case failed(id: String, failure: OfflineDownloadFailure)
}

nonisolated enum OfflineDownloadFailure: Sendable {
    /// The partial is untouched, so `resumeData` continues the walk from it — a transient 5xx/416
    /// must not cost the whole download. (403 is the exception the manager makes: the URL itself
    /// is dead, so it re-resolves and restarts.)
    case http(status: Int, resumeData: Data?)
    /// Transport-level failure; `resumeData` is what the engine needs to continue later, if anything.
    case network(resumeData: Data?)
}

/// The per-rung engine seam (spec §11 "engine-per-rung"). One conformer ships: `ProgressiveEngine`
/// (itag 18 / itag 140 files). The HLS engine (`AVAssetDownloadTask` + `.movpkg`) is deliberately
/// NOT built — Task 1 was outcome B (fork G, first override: least dormant code); CF-D-1 re-opens
/// it on hardware, and it would be a second conformer here, nothing more.
nonisolated protocol OfflineEngine: Sendable {
    /// Every task this engine owns, including ones re-attached from a previous launch.
    var events: AsyncStream<OfflineDownloadEvent> { get }
    /// From zero: any partial `<id>.tmp` is discarded.
    func start(id: String, url: URL, userAgent: String, allowsCellular: Bool) async
    /// Continues with the opaque `resumeData` a `pause` or `.network` failure handed back.
    func resume(id: String, resumeData: Data, allowsCellular: Bool) async
    /// Stops the task and hands back what `resume` needs (nil when the task had nothing to give).
    func pause(id: String) async -> Data?
    func cancel(id: String) async
    /// Ids (`taskDescription`) of tasks still alive in the session — the relaunch re-attach key.
    func liveIds() async -> Set<String>
}

/// Background `URLSessionDownloadTask` engine, walking the file in 10 MB `Range` requests.
///
/// One request per file is NOT an option: googlevideo throttles a single long GET on an adaptive
/// format to roughly playback rate (measured 2026-09-01 on the itag-140 URL of `xc7keR2piUM`:
/// 31 KB/s plain vs 11.4 MB/s for a 10 MB `Range` — yt-dlp's `http_chunk_size` exists for the
/// same reason). Each chunk is its own background task with `taskDescription = item.id` (the
/// re-attach key); a finished chunk is appended to `<directory>/<id>.tmp` inside the delegate
/// callback (the temp location does not survive it) and the next chunk is issued from the
/// finished task's own request — so a relaunch resumes the walk with no state beyond the file.
/// The resume token is `{url, userAgent}`; the resume point is the `.tmp` size.
nonisolated final class ProgressiveEngine: NSObject, OfflineEngine, URLSessionDownloadDelegate, @unchecked Sendable {
    static let backgroundSessionIdentifier = "com.albunyaan.tube.offline"
    static let chunkSize: Int64 = 10 * 1024 * 1024

    private struct ResumeToken: Codable {
        var url: URL
        var userAgent: String
    }

    /// `AppDelegate` parks iOS's background-events completion handler here; the session that
    /// finishes its events pops and calls it. Keyed by identifier so a relaunch without the
    /// manager built yet still has somewhere to leave the handler.
    private static let handlerLock = NSLock()
    nonisolated(unsafe) private static var backgroundCompletionHandlers: [String: @MainActor @Sendable () -> Void] = [:]

    static func registerBackgroundCompletion(identifier: String, handler: @escaping @MainActor @Sendable () -> Void) {
        handlerLock.withLock { backgroundCompletionHandlers[identifier] = handler }
    }

    let events: AsyncStream<OfflineDownloadEvent>
    private let continuation: AsyncStream<OfflineDownloadEvent>.Continuation
    private let directory: URL
    private var session: URLSession!

    /// Guards `walks` + `stoppedIds` — the ONLY authority on whether a walk may continue.
    /// `task(id)` alone cannot be: at every ~10 MB chunk boundary the finished task is already
    /// `.completed` and the next task doesn't exist yet, so a cancel/pause landing there found
    /// nothing to stop and the delegate walked on.
    private let stateLock = NSLock()
    /// Per-walk resume token, registered by `start`/`resume` and re-registered by every
    /// `issueChunk` (a relaunch-re-attached walk enters only through the delegate's `issueChunk`)
    /// — `pause` reads it here rather than off a live task (nil at a boundary).
    /// Cleared on `.finished` and on `cancel`; kept across a transient failure so a racing pause
    /// still gets its token.
    private var walks: [String: ResumeToken] = [:]
    /// Ids whose walk is stopped (cancel or pause). The delegate consults this before touching a
    /// finished chunk or issuing the next one; `start`/`resume` clear it (a fresh attempt).
    /// ponytail: grows by one id per cancelled/paused item per session — trim on clear if that
    /// ever matters.
    private var stoppedIds: Set<String> = []
    /// The TASKS we cancelled, which is the identity `didCompleteWithError` needs: `stoppedIds` is
    /// keyed by ROW, and a `resume` clears the row's entry before the background daemon delivers
    /// the paused task's late `.cancelled` — which then read as a system cancel and failed the
    /// freshly resumed row while its new chunk kept downloading. Kept (never removed) so a
    /// re-delivery stays silent too; one Int per pause/cancel per session.
    private var stoppedTaskIds: Set<Int> = []

    init(directory: URL, configuration: URLSessionConfiguration) {
        let (stream, continuation) = AsyncStream.makeStream(of: OfflineDownloadEvent.self)
        events = stream
        self.continuation = continuation
        self.directory = directory
        super.init()
        // URLSession requires a SERIAL delegate queue ("an operation queue … with a maximum
        // concurrency of 1"); a fresh OperationQueue defaults to concurrent.
        let queue = OperationQueue()
        queue.maxConcurrentOperationCount = 1
        session = URLSession(configuration: configuration, delegate: self, delegateQueue: queue)
    }

    // MARK: - OfflineEngine

    func start(id: String, url: URL, userAgent: String, allowsCellular: Bool) async {
        stateLock.withLock {
            stoppedIds.remove(id)
            walks[id] = ResumeToken(url: url, userAgent: userAgent)
        }
        try? FileManager.default.removeItem(at: partialURL(id))
        issueChunk(id: id, url: url, userAgent: userAgent, allowsCellular: allowsCellular, offset: 0)
    }

    func resume(id: String, resumeData: Data, allowsCellular: Bool) async {
        guard let token = try? JSONDecoder().decode(ResumeToken.self, from: resumeData) else {
            continuation.yield(.failed(id: id, failure: .network(resumeData: nil)))
            return
        }
        stateLock.withLock {
            stoppedIds.remove(id)
            walks[id] = token
        }
        issueChunk(id: id, url: token.url, userAgent: token.userAgent, allowsCellular: allowsCellular,
                   offset: partialSize(id))
    }

    func pause(id: String) async -> Data? {
        let walk = stateLock.withLock { () -> ResumeToken? in
            stoppedIds.insert(id)   // authoritative: the delegate won't issue another chunk
            return walks[id]
        }
        let live = await task(id)
        stop(live)
        // Walk state first; the live task is the fallback for a relaunch-re-attached walk that
        // never registered one. At a chunk boundary both `live` and the old task-only path are
        // nil — the walk state is what keeps the token.
        return walk.flatMap { try? JSONEncoder().encode($0) } ?? live.flatMap(Self.token(for:))
    }

    func cancel(id: String) async {
        stateLock.withLock {
            stoppedIds.insert(id)   // authoritative even when no task is live (chunk boundary)
            walks[id] = nil
        }
        stop(await task(id))
    }

    /// Records the task as ours BEFORE cancelling it — the completion is delivered on the delegate
    /// queue, so the other order races it.
    private func stop(_ task: URLSessionTask?) {
        guard let task else { return }
        stateLock.withLock { _ = stoppedTaskIds.insert(task.taskIdentifier) }
        task.cancel()
    }

    func liveIds() async -> Set<String> {
        Set(await session.allTasks.filter { $0.state != .completed }.compactMap(\.taskDescription))
    }

    // MARK: - Chunk walk

    private func issueChunk(id: String, url: URL, userAgent: String, allowsCellular: Bool, offset: Int64) {
        let proceed = stateLock.withLock { () -> Bool in
            guard !stoppedIds.contains(id) else { return false }
            // Register on every chunk, not just in start/resume — a relaunch-re-attached
            // walk enters here from the delegate without ever passing either, and a boundary
            // `pause` without the token restarts the walk from zero (deleting the `.tmp`).
            walks[id] = ResumeToken(url: url, userAgent: userAgent)
            return true
        }
        guard proceed else { return }
        var request = URLRequest(url: url)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue(Self.rangeHeader(offset: offset), forHTTPHeaderField: "Range")
        request.allowsCellularAccess = allowsCellular
        let task = session.downloadTask(with: request)
        task.taskDescription = id
        task.resume()
    }

    nonisolated static func rangeHeader(offset: Int64) -> String {
        "bytes=\(offset)-\(offset + chunkSize - 1)"
    }

    /// `bytes 0-10485759/136852236` (or `bytes */136852236`) → 136852236.
    nonisolated static func total(fromContentRange header: String?) -> Int64? {
        guard let header, let slash = header.lastIndex(of: "/") else { return nil }
        return Int64(header[header.index(after: slash)...].trimmingCharacters(in: .whitespaces))
    }

    /// `bytes=10485760-20971519` → 10485760 (the chunk's own start, i.e. the `.tmp` size it appends to).
    nonisolated static func offset(fromRangeHeader header: String?) -> Int64 {
        guard let header, let equals = header.firstIndex(of: "="),
              let dash = header[header.index(after: equals)...].firstIndex(of: "-") else { return 0 }
        return Int64(header[header.index(after: equals)..<dash]) ?? 0
    }

    private func partialURL(_ id: String) -> URL { directory.appending(path: "\(id).tmp") }

    private func partialSize(_ id: String) -> Int64 {
        (try? FileManager.default.attributesOfItem(atPath: partialURL(id).path())[.size] as? Int64) ?? 0
    }

    private static func token(for task: URLSessionTask) -> Data? {
        guard let request = task.originalRequest, let url = request.url else { return nil }
        return try? JSONEncoder().encode(ResumeToken(url: url, userAgent: request.value(forHTTPHeaderField: "User-Agent") ?? ""))
    }

    private func task(_ id: String) async -> URLSessionTask? {
        await session.allTasks.first { $0.taskDescription == id && $0.state != .completed }
    }

    // MARK: - URLSessionDownloadDelegate

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let id = downloadTask.taskDescription, let request = downloadTask.originalRequest, let url = request.url else { return }
        // Kill switch first: a chunk finishing after a cancel/pause must neither append its bytes
        // (a cancel already deleted the tmp) nor issue the next chunk. Pause discards this chunk
        // too — resume re-fetches it from the tmp size, ≤10 MB of re-download for a simple rule.
        guard stateLock.withLock({ !stoppedIds.contains(id) }) else { return }
        let response = downloadTask.response as? HTTPURLResponse
        let status = response?.statusCode ?? 200
        guard (200..<300).contains(status) else {
            // The partial is untouched, so the token continues the walk from it (the manager
            // re-resolves from zero for a 403, where the URL itself is what died).
            continuation.yield(.failed(id: id, failure: .http(status: status, resumeData: Self.token(for: downloadTask))))
            return
        }
        let expectedOffset = Self.offset(fromRangeHeader: request.value(forHTTPHeaderField: "Range"))
        do {
            try OfflineManager.prepareDirectory(directory)
            let partial = partialURL(id)
            // The file and the chunk must agree on the offset in BOTH branches (a stale relaunch
            // task): `partialSize` is 0 with no partial, so a missing file demands a chunk that
            // starts at 0 — otherwise the walk wrote a file beginning mid-stream and completed it.
            guard status == 200 || partialSize(id) == expectedOffset else {
                throw CocoaError(.fileWriteUnknown)   // restart cleanly
            }
            // A 200 means the server ignored the Range and sent the whole body: it replaces.
            if status == 200 || !FileManager.default.fileExists(atPath: partial.path()) {
                try? FileManager.default.removeItem(at: partial)
                try FileManager.default.moveItem(at: location, to: partial)
            } else {
                let handle = try FileHandle(forWritingTo: partial)
                defer { try? handle.close() }
                try handle.seekToEnd()
                try handle.write(contentsOf: try Data(contentsOf: location))
            }
            let written = partialSize(id)
            let total = status == 200 ? written : Self.total(fromContentRange: response?.value(forHTTPHeaderField: "Content-Range"))
            continuation.yield(.progress(id: id, bytesWritten: written, totalBytes: total))
            // No parseable total (missing `Content-Range`, or `bytes 0-x/*` from a proxy or CDN
            // edge that strips the length): the walk cannot know it is done, and calling a 10 MB
            // partial `.finished` renames it to the final file and shows the row as saved. Fail
            // with the resume token instead — the `.tmp` stays, so a retry continues from it.
            guard let total else {
                continuation.yield(.failed(id: id, failure: .network(resumeData: Self.token(for: downloadTask))))
                return
            }
            if written < total {
                issueChunk(id: id, url: url, userAgent: request.value(forHTTPHeaderField: "User-Agent") ?? "",
                           allowsCellular: request.allowsCellularAccess, offset: written)
            } else {
                stateLock.withLock { walks[id] = nil }
                continuation.yield(.finished(id: id))
            }
        } catch {
            try? FileManager.default.removeItem(at: partialURL(id))
            continuation.yield(.failed(id: id, failure: .network(resumeData: Self.token(for: downloadTask))))
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let id = downloadTask.taskDescription else { return }
        let offset = Self.offset(fromRangeHeader: downloadTask.originalRequest?.value(forHTTPHeaderField: "Range"))
        let total = Self.total(fromContentRange: (downloadTask.response as? HTTPURLResponse)?.value(forHTTPHeaderField: "Content-Range"))
        continuation.yield(.progress(id: id, bytesWritten: offset + totalBytesWritten, totalBytes: total))
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error, let id = task.taskDescription else { return }
        // Our own pause/cancel: `pause` already returned its token. But iOS cancels background
        // tasks WE never stopped too (force-quit, background-session disconnect), and swallowing
        // those left the row at "Saving…" with no task — the manager's `active` claim was never
        // released, so `schedule()` refused every other queued row for the session. The engine's
        // own stop sets are what make a cancellation ours: the TASK we stopped (a resume clears
        // the row long before this arrives), or the ROW, for a stop issued at a chunk boundary
        // where there was no task to name.
        if (error as? URLError)?.code == .cancelled,
           stateLock.withLock({ stoppedTaskIds.contains(task.taskIdentifier) || stoppedIds.contains(id) }) { return }
        continuation.yield(.failed(id: id, failure: .network(resumeData: Self.token(for: task))))
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        guard let identifier = session.configuration.identifier,
              let handler = Self.handlerLock.withLock({ Self.backgroundCompletionHandlers.removeValue(forKey: identifier) })
        else { return }
        Task { @MainActor in handler() }
    }
}
